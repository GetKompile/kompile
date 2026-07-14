package ai.kompile.chat.local.android.viewmodel

/**
 * A single row in the chat message list. The [toolRounds] list is populated for
 * assistant messages that made tool calls; the UI renders them as collapsible cards.
 */
data class UiMessage(
    val role: String,           // "user" | "assistant"
    val content: String,
    val toolRounds: List<ToolRoundUi> = emptyList(),
    val id: Long = System.nanoTime()
)

/** One tool dispatch within a single assistant turn. */
data class ToolRoundUi(
    val tool: String,
    val argsJson: String,
    val resultJson: String
)
