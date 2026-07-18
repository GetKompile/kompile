package ai.kompile.chat.local.android.model

import android.content.Context
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.Message
import org.eclipse.deeplearning4j.tokenizers.NativeTokenizer
import org.nd4j.dsp.runtime.SdxRuntime
import org.nd4j.dsp.runtime.SdxTextSession
import java.io.File
import java.util.function.Consumer

/**
 * Shared SDX lifecycle for device-only Android providers.
 *
 * JavaCPP is only the transport seam. Tokenization, KV state, sampling, graph
 * lowering, capture, and replay remain inside the selected native SDX runtime.
 */
internal object SdxPlatformChatSession {

    fun open(
        context: Context,
        modelPath: String,
        options: SdxRuntime.ModelOptions,
        routeName: String,
        modelIdPrefix: String
    ): PlatformLocalChatSession {
        val resolvedModel = MobileModelArtifactResolver.resolve(context, modelPath)
        if (options.backend == SdxRuntime.SDX_BACKEND_NNAPI &&
            options.device_compilation_cache_directory.isNullOrBlank()
        ) {
            val cacheDirectory = File(context.codeCacheDir, "sdx-nnapi")
            check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) {
                "Unable to create NNAPI compilation cache: ${cacheDirectory.absolutePath}"
            }
            options.deviceCompilationCacheDirectory(cacheDirectory.absolutePath)
        }
        val runtime = SdxRuntime.create()
        var model: SdxRuntime.SdxModel? = null
        var tokenizer: NativeTokenizer? = null
        var textSession: SdxTextSession? = null
        try {
            check(runtime.abiVersion() == 1) {
                "Unsupported SDX runtime ABI ${runtime.abiVersion()}"
            }
            model = runtime.loadModel(resolvedModel.runtimeModelPath().toString(), options)
            val tokenizerPath = model.tokenizerPath()
                ?: error("SDX bundle has no tokenizerPath metadata")
            tokenizer = NativeTokenizer.fromFile(tokenizerPath)
            textSession = model.createTextSession()
            return Session(
                runtime = runtime,
                model = model,
                tokenizer = tokenizer,
                textSession = textSession,
                routeName = routeName,
                modelId = "$modelIdPrefix:${File(modelPath).name}"
            )
        } catch (failure: Throwable) {
            runCatching { textSession?.close() }
            runCatching { tokenizer?.close() }
            runCatching { model?.close() }
            runCatching { runtime.close() }
            throw failure
        }
    }

    private class Session(
        private val runtime: SdxRuntime,
        private val model: SdxRuntime.SdxModel,
        private val tokenizer: NativeTokenizer,
        private val textSession: SdxTextSession,
        override val routeName: String,
        override val modelId: String
    ) : PlatformLocalChatSession {

        override fun generate(
            messages: List<Message>,
            opts: GenOptions,
            onChunk: Consumer<String>?
        ): String {
            val prompt = MobilePromptRenderer.chatMl(messages)
            val promptIds = tokenizer.encodeLong(prompt, true)
            require(promptIds.isNotEmpty()) { "Tokenizer produced an empty prompt" }

            val generationOptions = SdxTextSession.GenerationOptions(opts.maxTokens())
                .temperature(opts.temperature())
                .topK(opts.topK())
                .topP(opts.topP())
            if (opts.seed() >= 0) {
                generationOptions.seed(opts.seed())
            }

            textSession.reset()
            val result = if (onChunk == null) {
                textSession.generate(promptIds, generationOptions, null, null)
            } else {
                tokenizer.newDecodeStream(true).use { decoder ->
                    textSession.generate(
                        promptIds,
                        generationOptions,
                        { tokenId ->
                            decoder.step(tokenId)
                                .takeIf(String::isNotEmpty)
                                ?.let(onChunk::accept)
                        },
                        null
                    )
                }
            }
            return tokenizer.decode(result.tokenIds(), true).trim()
        }

        override fun cancel() {
            textSession.cancel()
        }

        override fun close() {
            var firstFailure: Throwable? = null
            listOf<() -> Unit>(
                { textSession.close() },
                { tokenizer.close() },
                { model.close() },
                { runtime.close() }
            ).forEach { closeStep ->
                try {
                    closeStep()
                } catch (failure: Throwable) {
                    if (firstFailure == null) firstFailure = failure
                }
            }
            firstFailure?.let { throw it }
        }
    }
}
