package ai.kompile.chat.local.android.viewmodel

import ai.kompile.chat.local.android.staging.ModelStagingHandoff

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

/** A real, bounded token decode proves more than merely opening a native model session. */
sealed interface ModelSmokeUiState {
    data object NotRun : ModelSmokeUiState
    data class Running(val route: String) : ModelSmokeUiState
    data class Passed(
        val route: String,
        val elapsedMs: Long,
        val preview: String
    ) : ModelSmokeUiState
    data class Failed(val route: String?, val message: String) : ModelSmokeUiState
}

/** End-to-end public Hugging Face acquisition state, including SDX activation. */
sealed interface HuggingFaceImportUiState {
    data object Idle : HuggingFaceImportUiState
    data class Downloading(
        val fileName: String,
        val downloadedBytes: Long,
        val expectedBytes: Long?
    ) : HuggingFaceImportUiState
    data class Activating(val fileName: String) : HuggingFaceImportUiState
    data class Active(val path: String, val route: String) : HuggingFaceImportUiState
    data class Failed(val message: String) : HuggingFaceImportUiState
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
            "The selected model is no longer available. Choose another .sdz, .gguf, or .ggml model."
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

/**
 * One-line explanation for the chat surface while the engine cannot accept input,
 * so a disabled send button is never silent. Null when the engine is ready.
 * [actionable] states need the user to import or fix something; the rest are transient.
 */
internal data class EngineNotice(val message: String, val actionable: Boolean)

internal fun engineNotice(model: ModelUiState, graph: GraphUiState): EngineNotice? = when (model) {
    is ModelUiState.Ready -> when (graph) {
        is GraphUiState.Ready -> null
        is GraphUiState.Failed -> EngineNotice(graph.message, actionable = true)
        GraphUiState.Checking, GraphUiState.WaitingForModel ->
            EngineNotice("Opening the offline knowledge graph…", actionable = false)
    }
    ModelUiState.Missing -> EngineNotice(
        "No local model is active. Import a prepared project/model or a Hugging Face GGUF/GGML model to chat.",
        actionable = true
    )
    is ModelUiState.Failed -> EngineNotice(model.message, actionable = true)
    ModelUiState.Checking -> EngineNotice("Starting the local SDX runtime…", actionable = false)
}

/**
 * Field-level prepared-artifact URL validation; null means acceptable. Blank is allowed here
 * (the feature is simply unset) — launching additionally requires a value, see
 * [stagingUrlLaunchProblem].
 */
internal fun stagingUrlProblem(raw: String): String? {
    return ModelStagingHandoff.baseUrlProblem(raw, allowBlank = true)
}

/** Gate for opening prepared Kompile downloads: unset and invalid both block. */
internal fun stagingUrlLaunchProblem(raw: String): String? =
    if (raw.isBlank()) "Configure a Kompile prepared-artifact server URL in Settings first."
    else stagingUrlProblem(raw)

/**
 * A persisted selection prepared for another accelerator APK (for example restored
 * by Android backup onto a different flavor) must fail closed with an actionable
 * message instead of an opaque native startup error.
 */
internal fun targetProfileProblem(selectedTarget: String, buildTarget: String): String? =
    if (selectedTarget.isBlank() || selectedTarget == buildTarget) null
    else "The active selection was prepared for target '$selectedTarget', but this APK runs '" +
        buildTarget + "'. Import a .kproject prepared for this APK."

/** Single-flight policy for SAF imports; imports never overlap each other or a reply. */
internal fun importBlockedReason(importBusy: Boolean, generating: Boolean): String? = when {
    importBusy -> "Another import is already running. Wait for it to finish."
    generating -> "Wait for the current response to finish (or cancel it) before importing."
    else -> null
}

/** Result of selecting a graph file; engine activation may wait for a model. */
sealed interface GraphImportOutcome {
    data class Active(val path: String) : GraphImportOutcome
    data class Deferred(val path: String, val message: String) : GraphImportOutcome
    data class Failed(val message: String) : GraphImportOutcome
}

/** Stable phases keep archive/target/runtime errors actionable in first-run UI. */
enum class ProjectImportPhase(val label: String) {
    BLOCKED("Import blocked"),
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
