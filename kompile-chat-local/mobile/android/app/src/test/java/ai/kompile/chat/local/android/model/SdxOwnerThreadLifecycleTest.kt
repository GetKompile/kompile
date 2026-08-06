package ai.kompile.chat.local.android.model

import ai.kompile.chat.local.ChatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SdxOwnerThreadLifecycleTest {

    @Test
    fun closeDuringGenerationCleansUpExactlyOnceOnOwnerThread() {
        val fixture = Fixture()
        val generation = fixture.call {
            fixture.generationStarted.countDown()
            fixture.releaseGeneration.await()
            "done"
        }
        assertTrue(fixture.generationStarted.await(1, TimeUnit.SECONDS))

        val close = fixture.callClose()
        fixture.releaseGeneration.countDown()

        assertEquals("done", generation.get(1, TimeUnit.SECONDS))
        close.get(1, TimeUnit.SECONDS)
        assertTrue(fixture.cleanupFinished.await(1, TimeUnit.SECONDS))
        assertEquals(1, fixture.cleanupCount.get())
        assertSame(fixture.ownerThread.get(), fixture.cleanupThread.get())
    }

    @Test
    fun closeTimeoutLeavesOwnerThreadCleanupQueuedUntilGenerationReturns() {
        val fixture = Fixture(closeTimeoutMillis = 25)
        val generation = fixture.call {
            fixture.generationStarted.countDown()
            fixture.releaseGeneration.await()
        }
        assertTrue(fixture.generationStarted.await(1, TimeUnit.SECONDS))

        assertThrows(ChatException::class.java) { fixture.lifecycle.close() }
        assertEquals(0, fixture.cleanupCount.get())

        fixture.releaseGeneration.countDown()
        generation.get(1, TimeUnit.SECONDS)
        assertTrue(fixture.cleanupFinished.await(1, TimeUnit.SECONDS))
        assertEquals(1, fixture.cleanupCount.get())
        assertSame(fixture.ownerThread.get(), fixture.cleanupThread.get())
    }

    @Test
    fun cleanupEventuallyCompletesAfterTimedOutCloseCallerReturns() {
        val fixture = Fixture(closeTimeoutMillis = 25)
        fixture.call {
            fixture.generationStarted.countDown()
            fixture.releaseGeneration.await()
        }
        assertTrue(fixture.generationStarted.await(1, TimeUnit.SECONDS))

        assertThrows(ChatException::class.java) { fixture.lifecycle.close() }
        fixture.releaseGeneration.countDown()

        assertTrue(fixture.cleanupFinished.await(1, TimeUnit.SECONDS))
        fixture.lifecycle.close()
        assertEquals(SdxOwnerThreadLifecycle.State.CLOSED, fixture.lifecycle.state)
    }

    @Test
    fun closeIsIdempotent() {
        val fixture = Fixture()

        fixture.lifecycle.close()
        fixture.lifecycle.close()
        fixture.lifecycle.close()

        assertEquals(1, fixture.cleanupCount.get())
        assertEquals(SdxOwnerThreadLifecycle.State.CLOSED, fixture.lifecycle.state)
    }

    @Test
    fun generationIsRejectedAsSoonAsCloseBegins() {
        val fixture = Fixture()
        fixture.call {
            fixture.generationStarted.countDown()
            fixture.releaseGeneration.await()
        }
        assertTrue(fixture.generationStarted.await(1, TimeUnit.SECONDS))

        val close = fixture.callClose()
        fixture.awaitClosing()

        assertThrows(ChatException::class.java) {
            fixture.lifecycle.execute("generate") { "must not run" }
        }

        fixture.releaseGeneration.countDown()
        close.get(1, TimeUnit.SECONDS)
        assertEquals(1, fixture.cleanupCount.get())
    }

    private class Fixture(closeTimeoutMillis: Long = 1_000) {
        val ownerThread = AtomicReference<Thread>()
        val generationStarted = CountDownLatch(1)
        val releaseGeneration = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        val cleanupCount = AtomicInteger()
        val cleanupThread = AtomicReference<Thread>()
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sdx-owner-test").apply { ownerThread.set(this) }
        }
        val lifecycle = SdxOwnerThreadLifecycle(
            executor = executor,
            ownerThread = ownerThread,
            closeTimeoutMillis = closeTimeoutMillis
        ) {
            cleanupThread.set(Thread.currentThread())
            cleanupCount.incrementAndGet()
            cleanupFinished.countDown()
        }

        fun <T> call(block: () -> T): CompletableFuture<T> {
            val result = CompletableFuture<T>()
            Thread({
                try {
                    result.complete(lifecycle.execute("generate", block))
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }, "sdx-generation-caller").apply {
                isDaemon = true
                start()
            }
            return result
        }

        fun callClose(): CompletableFuture<Unit> {
            val result = CompletableFuture<Unit>()
            Thread({
                try {
                    lifecycle.close()
                    result.complete(Unit)
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }, "sdx-close-caller").apply {
                isDaemon = true
                start()
            }
            return result
        }

        fun awaitClosing() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (lifecycle.state == SdxOwnerThreadLifecycle.State.OPEN &&
                System.nanoTime() < deadline
            ) {
                Thread.yield()
            }
            assertEquals(SdxOwnerThreadLifecycle.State.CLOSING, lifecycle.state)
        }
    }
}
