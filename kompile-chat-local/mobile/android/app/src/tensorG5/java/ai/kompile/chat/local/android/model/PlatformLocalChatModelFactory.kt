package ai.kompile.chat.local.android.model

import android.content.Context
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.GraphChatPrompt
import ai.kompile.chat.local.Message
import ai.kompile.chat.local.android.diagnostics.NativeOperationCheckpoint
import ai.kompile.chat.local.android.diagnostics.NativeOperationJournal
import ai.kompile.chat.local.android.diagnostics.NativeOperationKind
import ai.kompile.chat.local.android.diagnostics.NativeOperationTransaction
import org.nd4j.dsp.runtime.litertlm.SdxLiteRtLmChatSession
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ExecutionException
import java.util.function.Consumer

/** Google Tensor G5 direct-NPU implementation; generic NNAPI is never selected. */
internal object PlatformLocalChatModelFactory {

    fun open(
        context: Context,
        modelPath: String,
        temperature: Float,
        maxTokens: Int,
        diagnosticModelPath: String = modelPath
    ): PlatformLocalChatSession {
        val applicationContext = context.applicationContext
        val operation = NativeOperationJournal(applicationContext).begin(
            modelPath = diagnosticModelPath,
            operation = NativeOperationKind.SDX_MODEL_LOAD,
            checkpoint = NativeOperationCheckpoint.RESOLVE_MODEL_ASSETS
        )
        try {
            val resolvedModel = MobileModelArtifactResolver.resolve(
                applicationContext,
                modelPath,
                operation
            )
            val dispatchDirectory = applicationContext.applicationInfo.nativeLibraryDir
                ?: error("Android native library directory is unavailable")
            operation.checkpoint(NativeOperationCheckpoint.LOAD_LITERT_RUNTIME)
            val builder = SdxLiteRtLmChatSession.builder(
                resolvedModel.runtimeModelPath(),
                Paths.get(dispatchDirectory)
            )
                .cacheDirectory(applicationContext.cacheDir.toPath())
                .systemMessage(GraphChatPrompt.systemPrompt())
                .maxOutputTokens(maxTokens)
                .sampler(40, 0.9f, temperature, 0)
                .enableBenchmark(true)
            operation.checkpoint(NativeOperationCheckpoint.CREATE_LITERT_SESSION)
            val session = builder.build()
            val wrapped = TensorG5Session(
                applicationContext,
                diagnosticModelPath,
                session,
                File(modelPath).name
            )
            operation.complete()
            return wrapped
        } catch (failure: Throwable) {
            finishFailed(operation, failure)
            throw failure
        }
    }

    private fun finishFailed(operation: NativeOperationTransaction, failure: Throwable) {
        try {
            operation.failAndPersist(failure)
        } catch (journalFailure: Throwable) {
            failure.addSuppressed(journalFailure)
        }
    }
}

private class TensorG5Session(
    context: Context,
    private val diagnosticModelPath: String,
    private val session: SdxLiteRtLmChatSession,
    modelName: String
) : PlatformLocalChatSession {
    private val applicationContext = context.applicationContext

    override val routeName: String = "LOCAL_TENSOR_G5"
    override val modelId: String = "sdx-tensor-g5:$modelName"

    override fun generate(
        messages: List<Message>,
        opts: GenOptions,
        onChunk: Consumer<String>?
    ): String {
        val nextTurn = MobilePromptRenderer.latestTurn(messages)
        val operation = NativeOperationJournal(applicationContext).begin(
            modelPath = diagnosticModelPath,
            operation = NativeOperationKind.SDX_MODEL_EXECUTION,
            checkpoint = NativeOperationCheckpoint.EXECUTE_LITERT_GENERATION
        )
        try {
            val result = session.sendMessageStreaming(nextTurn, onChunk ?: Consumer { }).get().trim()
            operation.complete()
            return result
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            val failure = ChatException("Tensor G5 generation interrupted", interrupted)
            finishFailed(operation, failure)
            throw failure
        } catch (failed: ExecutionException) {
            val failure = ChatException(
                "Tensor G5 generation failed",
                failed.cause ?: failed
            )
            finishFailed(operation, failure)
            throw failure
        } catch (failure: Throwable) {
            finishFailed(operation, failure)
            throw failure
        }
    }

    override fun cancel() {
        val operation = NativeOperationJournal(applicationContext).begin(
            modelPath = diagnosticModelPath,
            operation = NativeOperationKind.SDX_MODEL_CANCELLATION,
            checkpoint = NativeOperationCheckpoint.CANCEL_LITERT_GENERATION
        )
        try {
            session.cancel()
            operation.complete()
        } catch (failure: Throwable) {
            finishFailed(operation, failure)
            throw failure
        }
    }

    override fun close() {
        val operation = NativeOperationJournal(applicationContext).begin(
            modelPath = diagnosticModelPath,
            operation = NativeOperationKind.SDX_MODEL_TEARDOWN,
            checkpoint = NativeOperationCheckpoint.CLOSE_LITERT_SESSION
        )
        try {
            session.close()
            operation.complete()
        } catch (failure: Throwable) {
            finishFailed(operation, failure)
            throw failure
        }
    }

    private fun finishFailed(operation: NativeOperationTransaction, failure: Throwable) {
        try {
            operation.failAndPersist(failure)
        } catch (journalFailure: Throwable) {
            failure.addSuppressed(journalFailure)
        }
    }
}
