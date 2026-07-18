package ai.kompile.chat.local.android.model

import android.content.Context
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.ChatModel
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.Message
import java.util.function.Consumer

/**
 * Android's only local-model seam. The implementation selected by the Gradle
 * flavor is a thin JavaCPP facade over the device provider; prompt tokenization,
 * decode, sampling, KV state, and graph execution stay in SDX/Kompile.
 */
class AcceleratedChatModelAndroid(
    context: Context,
    modelPath: String,
    temperature: Float,
    maxTokens: Int
) : ChatModel, AutoCloseable {

    private val opened = runCatching {
        PlatformLocalChatModelFactory.open(
            context.applicationContext,
            modelPath,
            temperature,
            maxTokens
        )
    }

    private var session: PlatformLocalChatSession? = opened.getOrNull()
    val startupError: String? = opened.exceptionOrNull()?.let {
        buildString {
            append(it.message ?: it.javaClass.simpleName)
            it.cause?.message?.takeIf(String::isNotBlank)?.let { cause ->
                append(": ")
                append(cause)
            }
        }
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
            "Local accelerator provider is unavailable" +
                (startupError?.let { ": $it" } ?: "")
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

/** Shared prompt/history helpers; provider code remains lifecycle-only. */
internal object MobilePromptRenderer {

    fun chatMl(messages: List<Message>): String = buildString {
        for (message in messages) {
            val role = when (message.role()) {
                "tool_result" -> "user"
                else -> message.role()
            }
            append("<|")
            append(role)
            append("|>\n")
            append(message.content())
            append("\n<|end|>\n")
        }
        append("<|assistant|>\n")
    }

    fun latestTurn(messages: List<Message>): String =
        messages.asReversed()
            .firstOrNull { it.role() != "system" }
            ?.content()
            ?.takeIf(String::isNotBlank)
            ?: throw ChatException("Conversation has no user/tool turn to send")
}
