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

/** Explicit local-model lifecycle state; expected first-run setup is not an error. */
sealed interface ModelUiState {
    data object Checking : ModelUiState
    data object Missing : ModelUiState
    data class Ready(val path: String, val route: String) : ModelUiState
    data class Failed(val path: String, val message: String) : ModelUiState
}

/** Graph startup is deferred until a usable local model exists. */
sealed interface GraphUiState {
    data object WaitingForModel : GraphUiState
    data object Checking : GraphUiState
    data class Ready(val path: String?) : GraphUiState
    data class Failed(val path: String?, val message: String) : GraphUiState
}

/**
 * Classify the persisted selection without starting either native runtime.
 * A stale non-empty selection is a load failure, not a first-run missing state.
 */
internal fun initialModelState(
    configuredPath: String,
    isRegularFile: (String) -> Boolean
): ModelUiState {
    val path = configuredPath
    return when {
        path.isBlank() -> ModelUiState.Missing
        !isRegularFile(path) -> ModelUiState.Failed(
            path,
            "The selected model is no longer available. Choose another SameDiff .sdz model."
        )
        else -> ModelUiState.Checking
    }
}

/** Only a verified, non-empty selection may advance into native startup. */
internal fun ModelUiState.startsNativeRuntime(): Boolean = this is ModelUiState.Checking

/** Recover stale or absent graph selections with the authenticated bundled graph. */
internal fun shouldActivateBundledGraph(
    configuredPath: String,
    isRegularFile: (String) -> Boolean
): Boolean = configuredPath.isBlank() || !isRegularFile(configuredPath)

/** Result of selecting a graph file; engine activation may wait for a model. */
sealed interface GraphImportOutcome {
    data class Active(val path: String) : GraphImportOutcome
    data class Deferred(val path: String, val message: String) : GraphImportOutcome
    data class Failed(val message: String) : GraphImportOutcome
}

/** Stable phases keep archive/target/runtime errors actionable in first-run UI. */
enum class ProjectImportPhase(val label: String) {
    COPY("Copy"),
    ARCHIVE("Project archive"),
    MODEL_TARGET("Model target"),
    GRAPH("Knowledge graph"),
    ACTIVATION("Native activation")
}

sealed interface ProjectImportOutcome {
    data class Active(
        val projectId: String,
        val projectName: String,
        val revision: String,
        val modelPath: String,
        val graphPath: String,
        val sourcesPath: String,
        val sourceCount: Int
    ) : ProjectImportOutcome

    data class Failed(
        val phase: ProjectImportPhase,
        val message: String
    ) : ProjectImportOutcome {
        val displayMessage: String get() = "${phase.label}: $message"
    }
}

internal fun graphImportOutcome(
    importedPath: String,
    graphState: GraphUiState
): GraphImportOutcome = when (graphState) {
    is GraphUiState.Ready -> if (graphState.path == importedPath) {
        GraphImportOutcome.Active(importedPath)
    } else {
        GraphImportOutcome.Failed("The active graph does not match the imported graph.")
    }
    GraphUiState.WaitingForModel -> GraphImportOutcome.Deferred(
        importedPath,
        "Graph selected. Import a local model to start the offline graph session."
    )
    GraphUiState.Checking -> GraphImportOutcome.Failed(
        "The imported graph is still being activated."
    )
    is GraphUiState.Failed -> GraphImportOutcome.Failed(graphState.message)
}
