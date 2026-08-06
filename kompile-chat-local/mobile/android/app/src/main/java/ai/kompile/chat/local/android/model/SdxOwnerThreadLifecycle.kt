package ai.kompile.chat.local.android.model

import ai.kompile.chat.local.ChatException
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Serializes SDX work and guarantees that native cleanup remains queued on the runtime owner
 * after close callers stop waiting.
 */
internal class SdxOwnerThreadLifecycle(
    private val executor: ExecutorService,
    private val ownerThread: AtomicReference<Thread>,
    private val closeTimeoutMillis: Long,
    private val cleanup: () -> Unit
) {
    internal enum class State { OPEN, CLOSING, CLOSED }

    private val lock = Any()

    @Volatile
    internal var state: State = State.OPEN
        private set

    private var cleanupCompletion: CompletableFuture<Unit>? = null

    fun <T> execute(action: String, block: () -> T): T {
        val future = synchronized(lock) {
            requireOpen()
            if (Thread.currentThread() === ownerThread.get()) return block()
            executor.submit(Callable { block() })
        }
        return try {
            future.get()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ChatException("Interrupted while waiting for SDX to " + action, failure)
        } catch (failure: ExecutionException) {
            val cause = failure.cause ?: failure
            if (cause is ChatException) throw cause
            throw ChatException(
                "SDX " + action + " failed: " + (cause.message ?: cause.javaClass.simpleName),
                cause
            )
        }
    }

    fun close() {
        var runCleanupInline = false
        val completion = synchronized(lock) {
            cleanupCompletion?.let { return@synchronized it }

            state = State.CLOSING
            val created = CompletableFuture<Unit>()
            cleanupCompletion = created
            if (Thread.currentThread() === ownerThread.get()) {
                runCleanupInline = true
            } else {
                executor.execute { runCleanup(created) }
            }
            executor.shutdown()
            created
        }

        if (runCleanupInline) runCleanup(completion)

        try {
            completion.get(closeTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (failure: TimeoutException) {
            throw ChatException(
                "Timed out waiting for the SDX runtime owner thread to release native state",
                failure
            )
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ChatException("Interrupted while closing the SDX model session", failure)
        } catch (failure: ExecutionException) {
            val cause = failure.cause ?: failure
            if (cause is ChatException) throw cause
            throw ChatException("SDX model cleanup failed", cause)
        }
    }

    private fun requireOpen() {
        if (state != State.OPEN) {
            throw ChatException("SDX model session is closing or closed")
        }
    }

    private fun runCleanup(completion: CompletableFuture<Unit>) {
        val failure = try {
            cleanup()
            null
        } catch (caught: Throwable) {
            caught
        }
        state = State.CLOSED
        if (failure == null) {
            completion.complete(Unit)
        } else {
            completion.completeExceptionally(failure)
        }
    }
}
