package ai.kompile.chat.local.android.model

import android.content.Context
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.GraphChatPrompt
import ai.kompile.chat.local.Message
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
        maxTokens: Int
    ): PlatformLocalChatSession {
        val resolvedModel = MobileModelArtifactResolver.resolve(context, modelPath)
        val dispatchDirectory = context.applicationInfo.nativeLibraryDir
            ?: error("Android native library directory is unavailable")
        val session = SdxLiteRtLmChatSession.builder(
            resolvedModel.runtimeModelPath(),
            Paths.get(dispatchDirectory)
        )
            .cacheDirectory(context.cacheDir.toPath())
            .systemMessage(GraphChatPrompt.systemPrompt())
            .maxOutputTokens(maxTokens)
            .sampler(40, 0.9f, temperature, 0)
            .enableBenchmark(true)
            .build()
        return TensorG5Session(session, File(modelPath).name)
    }
}

private class TensorG5Session(
    private val session: SdxLiteRtLmChatSession,
    modelName: String
) : PlatformLocalChatSession {

    override val routeName: String = "LOCAL_TENSOR_G5"
    override val modelId: String = "sdx-tensor-g5:$modelName"

    override fun generate(
        messages: List<Message>,
        opts: GenOptions,
        onChunk: Consumer<String>?
    ): String {
        val nextTurn = MobilePromptRenderer.latestTurn(messages)
        return try {
            session.sendMessageStreaming(nextTurn, onChunk ?: Consumer { }).get().trim()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ChatException("Tensor G5 generation interrupted", interrupted)
        } catch (failed: ExecutionException) {
            throw ChatException(
                "Tensor G5 generation failed",
                failed.cause ?: failed
            )
        }
    }

    override fun cancel() {
        session.cancel()
    }

    override fun close() {
        session.close()
    }
}
