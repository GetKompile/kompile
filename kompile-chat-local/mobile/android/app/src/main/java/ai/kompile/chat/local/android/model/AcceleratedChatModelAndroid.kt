package ai.kompile.chat.local.android.model

import android.content.Context
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.ChatModel
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.Message
import java.util.function.Consumer

/**
 * Android's only local-model seam. Every session opens canonical SDZ through the
 * flavor-specific [PlatformLocalChatModelFactory]. GGUF/GGML is only an ingestion
 * format: [SdxGgufModelImporter] commits canonical SDZ, exits its disposable process,
 * and never participates in runtime execution.
 */
internal class AcceleratedChatModelAndroid(
    context: Context,
    modelPath: String,
    temperature: Float,
    maxTokens: Int,
    verifiedSourceSha256: String? = null,
    verifiedSourceBytes: Long? = null,
    preparationOptions: ModelPreparationOptions = ModelPreparationOptions(),
    onPreparationStage: (PreparationStage) -> Unit = {},
    preparedModelInfo: PreparedModelInfo? = null
) : ChatModel, AutoCloseable {

    val preparationInfo: PreparedModelInfo? =
        preparedModelInfo ?: if (SdxGgufModelImporter.supports(modelPath)) {
            SdxGgufModelImporter.prepare(
                context.applicationContext,
                modelPath,
                verifiedSourceSha256,
                verifiedSourceBytes,
                preparationOptions,
                onPreparationStage
            )
        } else {
            null
        }

    val resolvedModelPath: String = preparationInfo?.canonicalSdzPath ?: modelPath

    private var session: PlatformLocalChatSession? = run {
        onPreparationStage(PreparationStage.LOAD_ACCELERATOR)
        PlatformLocalChatModelFactory.open(
            context.applicationContext,
            resolvedModelPath,
            temperature,
            maxTokens,
            diagnosticModelPath = modelPath,
            diagnosticMode = preparationOptions.diagnosticMode,
        )
    }

    val routeName: String
        get() = session?.routeName ?: "NONE"

    override fun generate(messages: List<Message>, opts: GenOptions): String =
        requireSession().generate(messages, opts, null)

    override fun generateStreaming(
        messages: List<Message>,
        opts: GenOptions,
        tokenConsumer: Consumer<String>
    ) {
        requireSession().generate(messages, opts, tokenConsumer)
    }

    override fun isAvailable(): Boolean = session != null

    override fun modelId(): String =
        session?.modelId ?: "sdx-mobile:unavailable"

    fun cancel() {
        session?.cancel()
    }

    override fun close() {
        val current = session ?: return
        session = null
        current.close()
    }

    private fun requireSession(): PlatformLocalChatSession =
        session ?: throw ChatException(
            "Local SDX runtime session is closed."
        )
}

/** Provider contract implemented once per accelerator source set. */
internal interface PlatformLocalChatSession : AutoCloseable {
    val routeName: String
    val modelId: String

    fun generate(
        messages: List<Message>,
        opts: GenOptions,
        onChunk: Consumer<String>?
    ): String

    fun cancel()
}

/**
 * History helpers every accelerator shares. Chat-template rendering lives with the SDX
 * source set instead: it runs through the tokenizer runtime, which the LiteRT-LM
 * provider does not ship because that engine applies the template inside its own
 * pipeline.
 */
internal object MobilePromptRenderer {

    fun latestTurn(messages: List<Message>): String =
        messages.asReversed()
            .firstOrNull { it.role() != "system" }
            ?.content()
            ?.takeIf(String::isNotBlank)
            ?: throw ChatException("Conversation has no user/tool turn to send")
}
