package ai.kompile.chat.local.android.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.kompile.chat.local.ChatEngine
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.GraphToolBackend
import ai.kompile.chat.local.InferenceRouter
import ai.kompile.chat.local.ProjectArchiveInstaller
import ai.kompile.chat.local.android.BuildConfig
import ai.kompile.chat.local.android.acquisition.HuggingFaceGgmlAcquisition
import ai.kompile.chat.local.android.diagnostics.ImportDiagnostic
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticPolicy
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticSeverity
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticStore
import ai.kompile.chat.local.android.graph.AndroidNativeGraphBackend
import ai.kompile.chat.local.android.staging.ModelStagingHandoff
import ai.kompile.chat.local.android.graph.KgraphArtifactValidator
import ai.kompile.chat.local.Message
import ai.kompile.chat.local.android.model.AcceleratedChatModelAndroid
import ai.kompile.chat.local.android.model.MobileModelArtifactResolver
import ai.kompile.chat.local.android.model.SdxRawGgufChatSession
import ai.kompile.chat.local.android.prefs.ActiveProjectSelection
import ai.kompile.chat.local.android.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nd4j.dsp.model.HuggingFaceGgmlResolver
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

/**
 * ViewModel for the chat screen.
 *
 * Owns the [ChatEngine] lifecycle and the in-memory conversation history.
 * Re-initialises the engine whenever the local model, graph, or generation
 * settings change.
 *
 * Heavy work (graph loading, SDX model loading, generate calls) runs on
 * [Dispatchers.IO] so the UI thread is never blocked.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"

        // Bounded, deterministic decode used by the model smoke test.
        private const val SMOKE_TEST_PROMPT = "Reply with the single word: ready"
        private const val SMOKE_TEST_MAX_TOKENS = 16
        private const val SMOKE_TEST_PREVIEW_CHARS = 120
        private const val DOWNLOAD_SPACE_RESERVE_BYTES = 128L * 1024L * 1024L
    }

    private data class StandaloneActivation(
        val modelPath: String,
        val route: String,
        val modelId: String
    )

    private val context: Context get() = getApplication<Application>().applicationContext
    val prefs = AppPreferences(context)
    private val importDiagnosticStore = ImportDiagnosticStore(context)

    // --- Observable state ---

    /** Full conversation history shown in the message list. */
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    /** True while a generate call is in-flight. */
    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    /** Non-null when the last turn produced an error banner. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Exact SDX route, including SDX_GGUF_AOT for directly imported GGUF/GGML models. */
    private val _activeRoute = MutableStateFlow("NONE")
    val activeRoute: StateFlow<String> = _activeRoute.asStateFlow()

    /** First-run/model-load state, kept distinct from native graph readiness. */
    private val _modelState = MutableStateFlow<ModelUiState>(ModelUiState.Checking)
    val modelState: StateFlow<ModelUiState> = _modelState.asStateFlow()

    /** Result of an actual bounded token decode, separate from native session startup. */
    private val _modelSmokeState = MutableStateFlow<ModelSmokeUiState>(ModelSmokeUiState.NotRun)
    val modelSmokeState: StateFlow<ModelSmokeUiState> = _modelSmokeState.asStateFlow()

    /** Native graph state; startup is deliberately deferred until a model is usable. */
    private val _graphState = MutableStateFlow<GraphUiState>(GraphUiState.WaitingForModel)
    val graphState: StateFlow<GraphUiState> = _graphState.asStateFlow()

    /**
     * True while any SAF import (project, model, or graph) is running. This is the
     * single source of truth for every screen, so an import started on one screen
     * disables import controls everywhere and imports can never overlap.
     */
    private val _importBusy = MutableStateFlow(false)
    val importBusy: StateFlow<Boolean> = _importBusy.asStateFlow()

    /** Public Hugging Face acquisition is complete only after a real SDX decode activates. */
    private val _huggingFaceImportState =
        MutableStateFlow<HuggingFaceImportUiState>(HuggingFaceImportUiState.Idle)
    val huggingFaceImportState: StateFlow<HuggingFaceImportUiState> =
        _huggingFaceImportState.asStateFlow()

    /** Durable, bounded, sanitized import/handoff history shown in Settings. */
    private val _importDiagnostics = MutableStateFlow(importDiagnosticStore.load())
    val importDiagnostics: StateFlow<List<ImportDiagnostic>> = _importDiagnostics.asStateFlow()

    private val importGate = java.util.concurrent.atomic.AtomicBoolean(false)

    // --- Engine state (rebuilt on settings change) ---

    private var engine: ChatEngine? = null
    private var bridge: GraphToolBackend? = null
    private var localModel: AcceleratedChatModelAndroid? = null
    private val engineMutex = Mutex()
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The working history passed into [ChatEngine.chat].
     * Does NOT contain the system prompt -- the engine prepends it.
     */
    private val history: MutableList<Message> = mutableListOf()

    // --- Init ---

    init {
        viewModelScope.launch {
            bootstrapAssets()
            rebuildEngine()
        }
    }

    // --- Public API ---

    fun clearImportDiagnostics() {
        importDiagnosticStore.clear()
        _importDiagnostics.value = emptyList()
    }

    private fun recordImportDiagnostic(
        operation: String,
        phase: String,
        severity: ImportDiagnosticSeverity,
        summary: String,
        remediation: String
    ) {
        val entry = ImportDiagnosticPolicy.create(
            timestampEpochMillis = System.currentTimeMillis(),
            operation = operation,
            phase = phase,
            severity = severity,
            summary = summary,
            remediation = remediation
        )
        _importDiagnostics.value = importDiagnosticStore.append(entry)
    }

    /**
     * Send a user message through the [ChatEngine] and append the result.
     */
    fun sendMessage(userText: String) {
        if (userText.isBlank()) return
        viewModelScope.launch {
            appendUiMessage(UiMessage(role = "user", content = userText))
            _thinking.value = true
            _error.value = null

            withContext(Dispatchers.IO) {
                engineMutex.withLock {
                    try {
                        val eng = engine ?: run {
                            // Engine not ready yet -- attempt a serialized lazy rebuild.
                            rebuildEngineLocked(resetConversation = false)
                            engine
                        }
                        if (eng == null) {
                            val startupMessage = when (val model = _modelState.value) {
                                ModelUiState.Missing -> null
                                is ModelUiState.Failed -> model.message
                                else -> when (val graph = _graphState.value) {
                                    is GraphUiState.Failed -> graph.message
                                    else -> "The local accelerator is still starting."
                                }
                            }
                            startupMessage?.let { _error.value = it }
                            return@withLock
                        }

                        val opts = currentGenOptions()
                        val result = eng.chat(history, userText, opts)

                        // Append user + assistant messages to our history for the next turn.
                        history.add(Message.user(userText))
                        history.add(Message.assistant(result.answer()))

                        val toolRoundsList: List<ToolRoundUi> = result.rounds().map { round ->
                            ToolRoundUi(round.tool(), round.argsJson(), round.resultJson())
                        }

                        withContext(Dispatchers.Main.immediate) {
                            appendUiMessage(
                                UiMessage(
                                    role = "assistant",
                                    content = result.answer(),
                                    toolRounds = toolRoundsList
                                )
                            )
                        }
                    } catch (e: ChatException) {
                        Log.e(TAG, "Chat failed", e)
                        withContext(Dispatchers.Main.immediate) {
                            _error.value = e.message ?: "Unknown error"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Unexpected error during chat", e)
                        withContext(Dispatchers.Main.immediate) {
                            _error.value = "Unexpected error: ${e.message}"
                        }
                    }
                }
            }

            _thinking.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearHistory() {
        viewModelScope.launch {
            engineMutex.withLock { clearConversationLocked() }
        }
    }

    fun cancelGeneration() {
        localModel?.cancel()
    }

    /** Run a bounded real decode through the active SDX/provider session. */
    fun runModelSmokeTest() {
        if (_thinking.value || _importBusy.value) {
            _error.value = "Wait for the current response or import to finish before testing the model."
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                engineMutex.withLock {
                    try {
                        val model = localModel
                            ?: throw IOException("Import and activate a local .sdz, .gguf, or .ggml model first.")
                        smokeTestModelLocked(model)
                    } catch (failure: Exception) {
                        Log.e(TAG, "Local model decode test failed", failure)
                        _error.value = failure.message ?: "Local model decode test failed"
                    }
                }
            }
        }
    }

    /**
     * Caller holds [engineMutex]. Runs one bounded, deterministic decode through the
     * active provider session and records the outcome in [modelSmokeState]. Failures
     * land there too, so a [ModelSmokeUiState.Running] state can never outlive the
     * attempt; only the missing-model precondition in [runModelSmokeTest] throws.
     */
    private fun smokeTestModelLocked(
        model: AcceleratedChatModelAndroid
    ): ModelSmokeUiState {
        val route = model.routeName
        _modelSmokeState.value = ModelSmokeUiState.Running(route)
        try {
            val startedAtNs = System.nanoTime()
            val answer = model.generate(
                listOf(Message.user(SMOKE_TEST_PROMPT)),
                GenOptions.builder()
                    .temperature(0.0)
                    .maxTokens(SMOKE_TEST_MAX_TOKENS)
                    .build()
            )
            val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000
            if (answer.isBlank()) {
                val failed = ModelSmokeUiState.Failed(
                    route,
                    "The SDX model decoded no tokens."
                )
                _modelSmokeState.value = failed
                return failed
            }
            val passed = ModelSmokeUiState.Passed(
                route,
                elapsedMs,
                answer.take(SMOKE_TEST_PREVIEW_CHARS)
            )
            _modelSmokeState.value = passed
            Log.i(TAG, "Model smoke decode passed on $route in ${elapsedMs}ms")
            return passed
        } catch (failure: Exception) {
            Log.e(TAG, "Model smoke decode failed on $route", failure)
            val failed = ModelSmokeUiState.Failed(
                route,
                failure.message ?: failure.javaClass.simpleName
            )
            _modelSmokeState.value = failed
            return failed
        }
    }

    /**
     * Call after the user changes settings in SettingsScreen to pick up new values.
     */
    fun onSettingsChanged() {
        viewModelScope.launch {
            rebuildEngine(resetConversation = true)
        }
    }

    // --- Engine lifecycle ---

    private suspend fun rebuildEngine(
        resetConversation: Boolean = false
    ) = withContext(Dispatchers.IO) {
        engineMutex.withLock { rebuildEngineLocked(resetConversation) }
    }

    /** Caller must hold [engineMutex] for the entire lifecycle transition. */
    private fun rebuildEngineLocked(resetConversation: Boolean) {
        if (resetConversation) clearConversationLocked()

        // Detach first so a close failure cannot leave stale resources selectable.
        closeEngineResourcesLocked()

        _activeRoute.value = "NONE"
        _graphState.value = GraphUiState.WaitingForModel
        _modelSmokeState.value = ModelSmokeUiState.NotRun
        _error.value = null

        // Model selection is application state, not a native graph failure. Resolve it
        // before opening either runtime so a fresh install presents the import workflow.
        val modelFilePath = prefs.modelPath
        val initialState = initialModelState(modelFilePath) { File(it).isFile }
        _modelState.value = initialState
        if (!initialState.startsNativeRuntime()) {
            Log.i(TAG, "Engine startup waiting for a usable local model: $initialState")
            return
        }

        // A selection restored onto a different accelerator APK (allowBackup) must fail
        // with an actionable message instead of an opaque native startup error.
        targetProfileProblem(prefs.activeProjectTargetProfile, BuildConfig.SDX_TARGET_PROFILE)
            ?.let { problem ->
                _modelState.value = ModelUiState.Failed(modelFilePath, problem)
                Log.e(TAG, "Refusing cross-target selection: $problem")
                return
            }

        // Prepared SDZ uses the flavor's provider; raw GGUF/GGML uses libsdx_llm AOT.
        val newLocal = AcceleratedChatModelAndroid(
            context,
            modelFilePath,
            prefs.temperature,
            prefs.maxTokens
        )
        if (!newLocal.isAvailable()) {
            val message = newLocal.startupError
                ?: "The selected model could not open on this accelerator."
            _modelState.value = ModelUiState.Failed(modelFilePath, message)
            runCatching { newLocal.close() }
            Log.e(TAG, "Local accelerator model failed to start: $message")
            return
        }

        val route = newLocal.routeName
        localModel = newLocal
        _modelState.value = ModelUiState.Ready(modelFilePath, route)
        _activeRoute.value = route
        _graphState.value = GraphUiState.Checking

        val graphPath = prefs.kgraphPath.takeIf(String::isNotBlank)
        val newBridge = try {
            buildBridge()
        } catch (failure: Exception) {
            val message = "Native graph runtime unavailable: " +
                (failure.message ?: failure.javaClass.simpleName)
            _graphState.value = GraphUiState.Failed(graphPath, message)
            localModel = null
            runCatching { newLocal.close() }
                .onFailure { failure.addSuppressed(it) }
            Log.e(TAG, "Native AOT graph runtime failed to start", failure)
            return
        }

        bridge = newBridge
        _graphState.value = GraphUiState.Ready(graphPath)
        try {
            engine = ChatEngine(InferenceRouter(newLocal, null), newBridge, prefs.maxToolRounds)
        } catch (failure: Exception) {
            val message = "Chat engine initialization failed: " +
                (failure.message ?: failure.javaClass.simpleName)
            engine = null
            bridge = null
            localModel = null
            _graphState.value = GraphUiState.Failed(graphPath, message)
            runCatching { newBridge.close() }.onFailure { failure.addSuppressed(it) }
            runCatching { newLocal.close() }.onFailure { failure.addSuppressed(it) }
            Log.e(TAG, message, failure)
            return
        }
        Log.i(TAG, "Engine rebuilt, route=$route")
    }

    private fun buildBridge(kgraphPath: String = prefs.kgraphPath): GraphToolBackend =
        if (kgraphPath.isBlank()) {
            AndroidNativeGraphBackend.empty()
        } else {
            AndroidNativeGraphBackend.open(File(kgraphPath).toPath())
        }

    // --- Asset bootstrap ---

    /**
     * Authenticate and materialize bundled graph assets on every launch. This is
     * intentionally cheap and also handles an updated fixture after an app upgrade.
     * Model weights are never bootstrapped from the APK.
     */
    private suspend fun bootstrapAssets() = withContext(Dispatchers.IO) {
        val assets = context.assets
        val expectedHashes = bundledAssetHashes()
        val graphsDir = File(context.filesDir, "graphs").apply { mkdirs() }

        try {
            val graphAssets = assets.list("graphs") ?: emptyArray()
            for (name in graphAssets) {
                if (!name.endsWith(".kgraph")) continue
                val relativePath = "graphs/$name"
                val expectedHash = expectedHashes[relativePath]
                    ?: error("Bundled graph is absent from offline-assets.json: $relativePath")
                val dest = File(graphsDir, name)
                if (!dest.exists() || sha256(dest) != expectedHash) {
                    val pending = File(graphsDir, ".$name.pending")
                    assets.open(relativePath).use { src ->
                        pending.outputStream().use { src.copyTo(it) }
                    }
                    check(sha256(pending) == expectedHash) {
                        "Bundled graph checksum mismatch: $relativePath"
                    }
                    if (dest.exists()) {
                        check(dest.delete()) {
                            "Could not replace stale bundled graph: $relativePath"
                        }
                    }
                    check(pending.renameTo(dest)) {
                        "Could not activate bundled graph: $relativePath"
                    }
                    Log.i(TAG, "Bootstrapped graph: $name")
                }
                if (shouldActivateBundledGraph(prefs.kgraphPath) { File(it).isFile }) {
                    prefs.kgraphPath = dest.absolutePath
                }
            }
            prefs.bootstrapDone = true
        } catch (failure: Exception) {
            Log.e(TAG, "Offline graph bootstrap failed", failure)
            withContext(Dispatchers.Main.immediate) {
                _error.value = failure.message ?: "Offline graph bootstrap failed"
            }
        }
    }

    // --- SAF copy helpers (called from SettingsScreen) ---

    /**
     * Runs [body] as the only import in flight. Concurrent imports (pickers resolved on
     * two screens) and imports during generation are refused via [blocked], so partial
     * installs can never race each other or contend with a native decode for the
     * engine lifecycle.
     */
    private suspend fun <T> runExclusiveImport(
        blocked: (String) -> T,
        body: suspend () -> T
    ): T {
        if (!importGate.compareAndSet(false, true)) {
            return blocked(importBlockedReason(importBusy = true, generating = false)!!)
        }
        _importBusy.value = true
        try {
            importBlockedReason(importBusy = false, generating = _thinking.value)?.let {
                return blocked(it)
            }
            return body()
        } finally {
            _importBusy.value = false
            importGate.set(false)
        }
    }

    /**
     * Copy a file chosen via the SAF file picker into filesDir/graphs/ and
     * update the kgraph preference. Caller should then call [onSettingsChanged].
     */
    suspend fun importKgraph(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "graphs").apply { mkdirs() }
            val name = File(resolveFileName(uri) ?: "imported.kgraph").name
            require(name.lowercase().endsWith(".kgraph")) {
                "Graph import requires a .kgraph file"
            }
            val dest = copyForActivation(
                uri,
                dir,
                name,
                maxBytes = KgraphArtifactValidator.MAX_ARCHIVE_BYTES
            ) { candidate ->
                KgraphArtifactValidator.validate(candidate.toPath())
            }
            prefs.kgraphPath = dest.absolutePath
            dest.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import kgraph from $uri", e)
            null
        }
    }

    /** Import and select a graph; activation is explicitly deferred without a model. */
    suspend fun importKgraphAndApply(uri: Uri): GraphImportOutcome = runExclusiveImport(
        blocked = { reason ->
            recordImportDiagnostic(
                "graph",
                "blocked",
                ImportDiagnosticSeverity.ERROR,
                reason,
                "Wait for the active generation or import to finish, then retry."
            )
            GraphImportOutcome.Failed(reason)
        }
    ) {
        recordImportDiagnostic(
            "graph",
            "copy and validation",
            ImportDiagnosticSeverity.INFO,
            "Graph import started.",
            "Keep this screen open while the selected .kgraph is validated."
        )
        // Mirror the model/project import transactions: if the new graph cannot
        // activate, the previous selection (including project provenance, which the
        // kgraph preference write clears) stays authoritative.
        val previousSelection = prefs.snapshotActiveSelection()
        val imported = importKgraph(uri) ?: return@runExclusiveImport GraphImportOutcome.Failed(
            "Graph import failed. Select a valid .kgraph file."
        ).also {
            recordImportDiagnostic(
                "graph",
                "copy and validation",
                ImportDiagnosticSeverity.ERROR,
                it.message,
                "Select a valid exported .kgraph file and retry."
            )
        }
        rebuildEngine(resetConversation = true)
        val activationOutcome = graphImportOutcome(imported, _graphState.value)
        val finalOutcome = if (activationOutcome is GraphImportOutcome.Failed) {
            if (imported != previousSelection.graphPath) {
                runCatching { File(imported).delete() }
                    .onFailure { Log.w(TAG, "Could not remove failed graph import", it) }
            }
            var message = activationOutcome.message
            if (!prefs.activateProject(previousSelection)) {
                message += " Android could not restore the previous selection."
            }
            rebuildEngine(resetConversation = false)
            GraphImportOutcome.Failed("$message The previous selection remains active.")
        } else {
            activationOutcome
        }
        finalOutcome.also { outcome ->
            when (outcome) {
                is GraphImportOutcome.Active -> recordImportDiagnostic(
                    "graph",
                    "activation",
                    ImportDiagnosticSeverity.SUCCESS,
                    "Graph imported and opened with the active model.",
                    "No action is required."
                )
                is GraphImportOutcome.Deferred -> recordImportDiagnostic(
                    "graph",
                    "activation",
                    ImportDiagnosticSeverity.INFO,
                    outcome.message,
                    "Import a complete .sdz model to activate graph reasoning."
                )
                is GraphImportOutcome.Failed -> recordImportDiagnostic(
                    "graph",
                    "activation",
                    ImportDiagnosticSeverity.ERROR,
                    outcome.message,
                    "Verify the graph export and native runtime, then retry."
                )
            }
        }
    }

    /** Copy and validate a complete runnable SDZ without publishing it yet. */
    private fun copyAndValidateModel(uri: Uri): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val displayName = File(resolveFileName(uri) ?: "imported.sdz").name
        require(displayName.endsWith(".sdz", ignoreCase = true)) {
            "Local accelerator models use the canonical SameDiff .sdz format"
        }
        val stem = displayName.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .take(80)
            .ifBlank { "imported" }
        val imported = copyForActivation(
            uri,
            dir,
            "$stem-${System.nanoTime()}.sdz"
        )
        try {
            // This installs the immutable target cache and requires tokenizer.json,
            // tokenizer_config.json, and the strict SDX text-generation contract.
            MobileModelArtifactResolver.resolve(context, imported.absolutePath)
            return imported
        } catch (failure: Exception) {
            imported.delete()
            throw failure
        }
    }

    /**
     * Import, run a real bounded decode, and atomically activate a SameDiff model.
     * The previous model remains authoritative if any asset, provider, template,
     * tokenizer, graph, or decode check fails.
     */
    suspend fun importModelAndActivate(uri: Uri): Result<String> = runExclusiveImport(
        blocked = { reason ->
            recordImportDiagnostic(
                "model",
                "blocked",
                ImportDiagnosticSeverity.ERROR,
                reason,
                "Wait for the active generation or import to finish, then retry."
            )
            Result.failure(IllegalStateException(reason))
        }
    ) {
        withContext(Dispatchers.IO) {
            val previousSelection = prefs.snapshotActiveSelection()
            var imported: File? = null
            var phase = "copy and validation"
            recordImportDiagnostic(
                "model",
                phase,
                ImportDiagnosticSeverity.INFO,
                "Complete SDZ import started.",
                "Keep this screen open while assets and the accelerator target are verified."
            )
            try {
                val candidate = copyAndValidateModel(uri)
                imported = candidate
                phase = "accelerator activation"
                engineMutex.withLock {
                    activateStandaloneModelLocked(
                        candidate.absolutePath,
                        previousSelection
                    )
                }
                recordImportDiagnostic(
                    "model",
                    phase,
                    ImportDiagnosticSeverity.SUCCESS,
                    "Model assets passed validation and a real accelerator decode.",
                    "Return to Chat to use the model. Import a graph separately for graph reasoning."
                )
                Result.success(candidate.absolutePath)
            } catch (failure: Exception) {
                imported?.delete()
                Log.e(TAG, "Complete SDZ import failed", failure)
                recordImportDiagnostic(
                    "model",
                    phase,
                    ImportDiagnosticSeverity.ERROR,
                    failure.message ?: "Complete SDZ import failed.",
                    "Use a staging-generated .sdz containing tokenizer, chat template, text-generation contract, and this APK's target."
                )
                Result.failure(failure)
            }
        }
    }

    /**
     * Install one canonical project archive and publish it only after this APK's model,
     * graph AOT runtime, and chat engine all open successfully.
     */
    suspend fun importProjectAndActivate(uri: Uri): ProjectImportOutcome = runExclusiveImport(
        blocked = { reason ->
            recordImportDiagnostic(
                "project",
                "blocked",
                ImportDiagnosticSeverity.ERROR,
                reason,
                "Wait for the active generation or import to finish, then retry."
            )
            ProjectImportOutcome.Failed(ProjectImportPhase.BLOCKED, reason)
        }
    ) {
        withContext(Dispatchers.IO) {
            var phase = ProjectImportPhase.COPY
            var importedArchive: File? = null
            var installed: ProjectArchiveInstaller.InstalledProject? = null
            var activated = false
            recordImportDiagnostic(
                "project",
                phase.label,
                ImportDiagnosticSeverity.INFO,
                "Full project import started.",
                "Keep this screen open while the archive, target, graph, sources, and runtime are verified."
            )
            try {
                val importsDir = File(context.filesDir, "project-imports").apply { mkdirs() }
                val name = File(resolveFileName(uri) ?: "imported.kproject").name
                require(name.endsWith(".kproject", ignoreCase = true)) {
                    "Offline project import requires a .kproject archive"
                }
                val copiedArchive = copyForActivation(
                    uri,
                    importsDir,
                    name,
                    maxBytes = ProjectArchiveInstaller.MAX_ARCHIVE_BYTES
                )
                importedArchive = copiedArchive

                phase = ProjectImportPhase.ARCHIVE
                val candidateProject = ProjectArchiveInstaller.install(
                    copiedArchive.toPath(),
                    BuildConfig.SDX_TARGET_PROFILE,
                    File(context.filesDir, "projects").toPath()
                )
                installed = candidateProject

                phase = ProjectImportPhase.MODEL_TARGET
                // Installs and validates only this flavor's immutable embedded target cache.
                MobileModelArtifactResolver.resolve(
                    context,
                    candidateProject.modelPath().toString()
                )

                phase = ProjectImportPhase.GRAPH
                KgraphArtifactValidator.validate(candidateProject.graphPath())

                phase = ProjectImportPhase.ACTIVATION
                engineMutex.withLock {
                    activateInstalledProjectLocked(candidateProject)
                }
                activated = true
                recordImportDiagnostic(
                    "project",
                    phase.label,
                    ImportDiagnosticSeverity.SUCCESS,
                    "Project model, graph, Markdown sources, and accelerator decode are active.",
                    "Return to Chat to use the synchronized knowledge project."
                )
                ProjectImportOutcome.Active(
                    projectId = candidateProject.projectId(),
                    projectName = candidateProject.projectName(),
                    revision = candidateProject.revision(),
                    modelPath = candidateProject.modelPath().toString(),
                    graphPath = candidateProject.graphPath().toString(),
                    sourcesPath = candidateProject.sourcesRoot().toString(),
                    sourceCount = candidateProject.sourcePaths().size
                )
            } catch (failure: Exception) {
                Log.e(TAG, "Project import failed during $phase", failure)
                recordImportDiagnostic(
                    "project",
                    phase.label,
                    ImportDiagnosticSeverity.ERROR,
                    failure.message ?: failure.javaClass.simpleName,
                    "Review this phase, verify the prepared project and matching APK target, then retry."
                )
                ProjectImportOutcome.Failed(
                    phase,
                    failure.message ?: failure.javaClass.simpleName
                )
            } finally {
                importedArchive?.delete()
                if (!activated) {
                    runCatching { installed?.delete() }
                        .onFailure { Log.w(TAG, "Could not remove failed project installation", it) }
                }
            }
        }
    }

    /**
     * Resolve owner/repository, repository/tree URLs, or exact GGUF/GGML file URLs
     * directly through Hugging Face. Kompile staging is not part of this path.
     */
    suspend fun discoverHuggingFaceAcquisition(
        rawReference: String
    ): Result<HuggingFaceGgmlResolver.Discovery> {
        _huggingFaceImportState.value = HuggingFaceImportUiState.Idle
        val result = try {
            Result.success(
                withContext(Dispatchers.IO) {
                    HuggingFaceGgmlAcquisition.discover(rawReference)
                }
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        if (result.isSuccess) {
            val discovery = result.getOrThrow()
            recordImportDiagnostic(
                "hugging face discovery",
                "ggml",
                ImportDiagnosticSeverity.INFO,
                "Resolved ${discovery.candidates.size} GGUF/GGML candidate(s) from " +
                    "${discovery.reference.repository} at ${discovery.resolvedRevision}.",
                if (discovery.requiresSelection()) {
                    "Select one quantization explicitly; no repository candidate is chosen automatically."
                } else {
                    "Download, load, smoke-decode, and activate the resolved model directly in SDX."
                }
            )
        } else {
            recordImportDiagnostic(
                "hugging face discovery",
                "ggml",
                ImportDiagnosticSeverity.ERROR,
                result.exceptionOrNull()?.message
                    ?: "The Hugging Face repository could not be resolved.",
                "Check the public owner/repository or canonical Hugging Face URL and network access."
            )
        }
        return result
    }

    /**
     * Download one resolved public Hugging Face model into app-owned storage, load it through
     * libsdx_llm, run a bounded decode, and publish it as the active chat model transactionally.
     * A completed HTTP transfer alone is never reported as success.
     */
    suspend fun importHuggingFaceModelAndActivate(
        candidate: HuggingFaceGgmlResolver.Candidate
    ): Result<String> = runExclusiveImport(
        blocked = { reason ->
            _huggingFaceImportState.value = HuggingFaceImportUiState.Failed(reason)
            recordImportDiagnostic(
                "hugging face model",
                "blocked",
                ImportDiagnosticSeverity.ERROR,
                reason,
                "Wait for the active generation or import to finish, then retry."
            )
            Result.failure(IllegalStateException(reason))
        }
    ) {
        withContext(Dispatchers.IO) {
            val previousSelection = prefs.snapshotActiveSelection()
            val modelsDir = File(context.filesDir, "models/hugging-face").apply { mkdirs() }
            require(modelsDir.isDirectory) {
                "Android could not create app-owned model storage."
            }

            val sourceName = HuggingFaceGgmlAcquisition.safeFilename(candidate)
            val extension = sourceName.substringAfterLast('.', "gguf")
            val stem = sourceName.substringBeforeLast('.', sourceName)
            val finalFile = File(
                modelsDir,
                "$stem-${System.currentTimeMillis()}.$extension"
            )
            val pendingFile = File(
                modelsDir,
                ".${finalFile.name}.${System.nanoTime()}.pending"
            )
            val expectedBytes = candidate.size.takeIf { it >= 0L }
            val availableBytes = (modelsDir.usableSpace - DOWNLOAD_SPACE_RESERVE_BYTES)
                .coerceAtLeast(0L)
            val maxBytes = minOf(
                HuggingFaceGgmlAcquisition.DEFAULT_MAX_DOWNLOAD_BYTES,
                availableBytes
            )
            require(maxBytes > 0L) {
                "Not enough free app storage to download a model."
            }
            require(expectedBytes == null || expectedBytes <= maxBytes) {
                "${candidate.path} needs $expectedBytes bytes but only $availableBytes bytes " +
                    "are available after the safety reserve."
            }

            val importJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            var imported: File? = null
            var phase = "download"
            _huggingFaceImportState.value = HuggingFaceImportUiState.Downloading(
                sourceName,
                0L,
                expectedBytes
            )
            runCatching {
                recordImportDiagnostic(
                    "hugging face model",
                    phase,
                    ImportDiagnosticSeverity.INFO,
                    "App-owned download started for ${candidate.path}.",
                    "Keep this screen open; activation follows only after SDX loads and decodes it."
                )
            }.onFailure { Log.w(TAG, "Could not record Hugging Face import start", it) }

            try {
                val downloaded = HuggingFaceGgmlAcquisition.download(
                    candidate = candidate,
                    temporaryPath = pendingFile.toPath(),
                    finalPath = finalFile.toPath(),
                    maxBytes = maxBytes,
                    onProgress = { progress ->
                        _huggingFaceImportState.value = HuggingFaceImportUiState.Downloading(
                            progress.safeFilename,
                            progress.downloadedBytes,
                            progress.expectedBytes
                        )
                    },
                    isCancelled = { importJob?.isActive == false }
                )
                val modelFile = downloaded.finalPath.toFile()
                imported = modelFile
                phase = "SDX load and decode"
                _huggingFaceImportState.value =
                    HuggingFaceImportUiState.Activating(downloaded.safeFilename)

                val activation = engineMutex.withLock {
                    activateStandaloneModelLocked(modelFile.absolutePath, previousSelection)
                }
                _huggingFaceImportState.value = HuggingFaceImportUiState.Active(
                    activation.modelPath,
                    activation.route
                )
                runCatching {
                    recordImportDiagnostic(
                        "hugging face model",
                        phase,
                        ImportDiagnosticSeverity.SUCCESS,
                        "${candidate.path} downloaded, loaded, decoded, and activated on " +
                            "${activation.route} (${activation.modelId}).",
                        "Return to Chat; this exact app-owned model is now the running model."
                    )
                }.onFailure { Log.w(TAG, "Could not record Hugging Face import success", it) }
                Result.success(modelFile.absolutePath)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                pendingFile.delete()
                imported?.delete()
                _huggingFaceImportState.value = HuggingFaceImportUiState.Idle
                throw cancelled
            } catch (failure: Exception) {
                pendingFile.delete()
                imported?.delete()
                if (importJob?.isActive == false) {
                    _huggingFaceImportState.value = HuggingFaceImportUiState.Idle
                    throw kotlinx.coroutines.CancellationException(
                        "Hugging Face model import was cancelled"
                    ).also { it.initCause(failure) }
                }
                _huggingFaceImportState.value = HuggingFaceImportUiState.Failed(
                    failure.message ?: failure.javaClass.simpleName
                )
                Log.e(TAG, "Hugging Face model import failed during $phase", failure)
                runCatching {
                    recordImportDiagnostic(
                        "hugging face model",
                        phase,
                        ImportDiagnosticSeverity.ERROR,
                        failure.message ?: "Hugging Face model import failed.",
                        "The previous model remains active. Verify storage, network, and SDX model compatibility, then retry."
                    )
                }.onFailure { Log.w(TAG, "Could not record Hugging Face import failure", it) }
                Result.failure(failure)
            }
        }
    }

    /**
     * Open the configured Kompile prepared-artifact surface in an external browser.
     * It supplies target-complete .sdz/.kproject downloads and receives no Hugging Face source.
     */
    fun openModelStaging(artifact: ModelStagingHandoff.Artifact): Result<Unit> {
        val result = runCatching {
            val base = prefs.modelStagingUrl.trim()
            stagingUrlLaunchProblem(base)?.let { problem ->
                throw IllegalArgumentException(problem)
            }
            val request = Uri.parse(
                ModelStagingHandoff.build(
                    baseUrl = base,
                    targetProfile = BuildConfig.SDX_TARGET_PROFILE,
                    artifact = artifact
                ).toASCIIString()
            )
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, request).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (missing: android.content.ActivityNotFoundException) {
                throw IllegalStateException(
                    "No web browser is installed on this device, so prepared artifact downloads "
                        + "cannot open. Install a browser or stage on another machine and "
                        + "transfer the file.",
                    missing
                )
            }
        }
        if (result.isSuccess) {
            recordImportDiagnostic(
                "browser handoff",
                artifact.queryValue,
                ImportDiagnosticSeverity.INFO,
                "External browser opened for a prepared ${artifact.fileExtension} download.",
                "Download the prepared package, return here, then use the matching import button."
            )
        } else {
            recordImportDiagnostic(
                "browser handoff",
                artifact.queryValue,
                ImportDiagnosticSeverity.ERROR,
                result.exceptionOrNull()?.message ?: "The browser handoff could not be opened.",
                "Check the prepared-artifact server URL and browser availability, then retry."
            )
        }
        return result
    }

    /**
     * Caller holds [engineMutex]. Raw Hugging Face GGUF/GGML keeps the exact SDX session
     * that passed prompt rendering and decode. Prepared provider sessions retain their
     * existing fresh-session behavior because some providers own conversational state.
     */
    private fun openVerifiedModelLocked(
        modelPath: String,
        unavailableMessage: String
    ): AcceleratedChatModelAndroid {
        val candidate = openAvailableModel(modelPath, unavailableMessage)
        var verified = false
        try {
            when (val smoke = smokeTestModelLocked(candidate)) {
                is ModelSmokeUiState.Passed -> Unit
                is ModelSmokeUiState.Failed -> throw IOException(
                    "Model decode self-test failed on ${smoke.route}: ${smoke.message}"
                )
                is ModelSmokeUiState.Running,
                ModelSmokeUiState.NotRun -> throw IOException(
                    "Model decode self-test did not complete."
                )
            }
            if (SdxRawGgufChatSession.supports(modelPath)) {
                // GenerationPipeline supports repeated generate calls on one loaded model.
                // Keep this exact decoded SDX session so HF activation cannot substitute an
                // unverified native object after the runnable-model check.
                verified = true
                return candidate
            }
        } finally {
            if (!verified) {
                runCatching { candidate.close() }
                    .onFailure { Log.w(TAG, "Failed to close model verification session", it) }
            }
        }
        return openAvailableModel(modelPath, unavailableMessage)
    }

    private fun openAvailableModel(
        modelPath: String,
        unavailableMessage: String
    ): AcceleratedChatModelAndroid {
        val model = AcceleratedChatModelAndroid(
            context,
            modelPath,
            prefs.temperature,
            prefs.maxTokens
        )
        if (!model.isAvailable()) {
            val detail = model.startupError ?: unavailableMessage
            runCatching { model.close() }
            throw IOException("$unavailableMessage: $detail")
        }
        return model
    }

    /** Caller holds [engineMutex]. Publish a standalone model only after real decode. */
    private fun activateStandaloneModelLocked(
        modelPath: String,
        previousSelection: ActiveProjectSelection
    ): StandaloneActivation {
        var candidateModel: AcceleratedChatModelAndroid? = null
        var candidateBridge: GraphToolBackend? = null
        var preferenceWriteAttempted = false

        closeEngineResourcesLocked()
        try {
            val openedModel = openVerifiedModelLocked(
                modelPath,
                "The imported model could not open in the local SDX runtime"
            )
            candidateModel = openedModel
            val openedBridge = buildBridge(previousSelection.graphPath)
            candidateBridge = openedBridge
            val candidateEngine = ChatEngine(
                InferenceRouter(openedModel, null),
                openedBridge,
                prefs.maxToolRounds
            )

            preferenceWriteAttempted = true
            val committed = prefs.activateProject(
                ActiveProjectSelection(
                    installationRoot = "",
                    modelPath = modelPath,
                    graphPath = previousSelection.graphPath,
                    sourcesPath = "",
                    sourceCount = 0,
                    projectId = "",
                    projectName = "",
                    revision = "",
                    targetProfile = ""
                )
            )
            if (!committed) {
                throw IOException("Android could not persist the imported model selection.")
            }

            engine = candidateEngine
            bridge = openedBridge
            localModel = openedModel
            candidateBridge = null
            candidateModel = null
            clearConversationLocked()
            _error.value = null
            _activeRoute.value = openedModel.routeName
            _modelState.value = ModelUiState.Ready(modelPath, openedModel.routeName)
            _graphState.value =
                GraphUiState.Ready(previousSelection.graphPath.takeIf(String::isNotBlank))
            return StandaloneActivation(
                modelPath = modelPath,
                route = openedModel.routeName,
                modelId = openedModel.modelId()
            )
        } catch (failure: Exception) {
            runCatching { candidateBridge?.close() }
                .onFailure { failure.addSuppressed(it) }
            runCatching { candidateModel?.close() }
                .onFailure { failure.addSuppressed(it) }
            if (preferenceWriteAttempted &&
                !prefs.activateProject(previousSelection)
            ) {
                failure.addSuppressed(
                    IOException("Android could not restore the previous selection.")
                )
            }
            runCatching { rebuildEngineLocked(resetConversation = false) }
                .onFailure { restoreFailure ->
                    failure.addSuppressed(restoreFailure)
                    Log.e(TAG, "Could not restore the previous runtime", restoreFailure)
                }
            throw failure
        }
    }

    /**
     * Caller holds [engineMutex]. Closing the old accelerator session first avoids a
     * two-model mobile memory spike. On any candidate failure, persisted old paths are still
     * authoritative and [rebuildEngineLocked] reopens them before the transaction returns.
     */
    private fun activateInstalledProjectLocked(
        installed: ProjectArchiveInstaller.InstalledProject
    ) {
        val previousSelection = prefs.snapshotActiveSelection()
        var candidateModel: AcceleratedChatModelAndroid? = null
        var candidateBridge: GraphToolBackend? = null
        var preferenceWriteAttempted = false

        closeEngineResourcesLocked()
        try {
            val openedModel = openVerifiedModelLocked(
                installed.modelPath().toString(),
                "The project model could not open on this accelerator"
            )
            candidateModel = openedModel
            val openedBridge = buildBridge(installed.graphPath().toString())
            candidateBridge = openedBridge
            val candidateEngine = ChatEngine(
                InferenceRouter(openedModel, null),
                openedBridge,
                prefs.maxToolRounds
            )

            preferenceWriteAttempted = true
            val committed = prefs.activateProject(
                ActiveProjectSelection(
                    installationRoot = installed.installationRoot().toString(),
                    modelPath = installed.modelPath().toString(),
                    graphPath = installed.graphPath().toString(),
                    sourcesPath = installed.sourcesRoot().toString(),
                    sourceCount = installed.sourcePaths().size,
                    projectId = installed.projectId(),
                    projectName = installed.projectName(),
                    revision = installed.revision(),
                    targetProfile = BuildConfig.SDX_TARGET_PROFILE
                )
            )
            if (!committed) {
                throw IOException("Android could not persist the active project selection.")
            }

            engine = candidateEngine
            bridge = openedBridge
            localModel = openedModel
            candidateBridge = null
            candidateModel = null

            clearConversationLocked()
            _error.value = null
            _activeRoute.value = openedModel.routeName
            _modelState.value = ModelUiState.Ready(
                installed.modelPath().toString(),
                openedModel.routeName
            )
            _graphState.value = GraphUiState.Ready(installed.graphPath().toString())
            Log.i(
                TAG,
                "Activated project " + installed.projectId()
                        + " revision=" + installed.revision()
                        + " target=" + BuildConfig.SDX_TARGET_PROFILE
            )
        } catch (failure: Exception) {
            runCatching { candidateBridge?.close() }
                .onFailure { failure.addSuppressed(it) }
            runCatching { candidateModel?.close() }
                .onFailure { failure.addSuppressed(it) }
            if (preferenceWriteAttempted && !prefs.activateProject(previousSelection)) {
                failure.addSuppressed(
                    IOException("Android could not restore the previous project selection.")
                )
            }
            runCatching { rebuildEngineLocked(resetConversation = false) }
                .onFailure { restoreFailure ->
                    failure.addSuppressed(restoreFailure)
                    Log.e(TAG, "Could not restore the previous project runtime", restoreFailure)
                }
            throw failure
        }
    }

    private fun copyForActivation(
        uri: Uri,
        directory: File,
        name: String,
        maxBytes: Long = Long.MAX_VALUE,
        validate: (File) -> Unit = {}
    ): File {
        val destination = File(directory, name)
        val pending = File(directory, ".$name.${System.nanoTime()}.pending")
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { source ->
                pending.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var total = 0L
                    var count: Int
                    while (source.read(buffer).also { count = it } != -1) {
                        require(count.toLong() <= maxBytes - total) {
                            "Imported file exceeds the ${maxBytes / (1024 * 1024)} MiB limit"
                        }
                        output.write(buffer, 0, count)
                        total += count
                    }
                }
            }
            require(pending.length() > 0L) { "Imported file is empty" }
            validate(pending)
            try {
                Files.move(
                    pending.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    pending.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            return destination
        } catch (failure: Exception) {
            pending.delete()
            throw failure
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        // Try display name from ContentResolver; fall back to last path segment.
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment
    }

    private fun bundledAssetHashes(): Map<String, String> {
        val manifest = context.assets.open("offline-assets.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        require(manifest.getInt("formatVersion") == 1) {
            "Unsupported offline asset manifest version"
        }
        require(manifest.getBoolean("offlineOnly")) {
            "Mobile release manifest must be offline-only"
        }
        val result = linkedMapOf<String, String>()
        val entries = manifest.getJSONArray("assets")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            result[entry.getString("path")] = entry.getString("sha256")
        }
        return result
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // --- Helpers ---

    private fun closeEngineResourcesLocked() {
        val oldBridge = bridge
        val oldModel = localModel
        bridge = null
        localModel = null
        engine = null
        runCatching { oldBridge?.close() }
            .onFailure { Log.w(TAG, "Failed to close graph bridge", it) }
        runCatching { oldModel?.close() }
            .onFailure { Log.w(TAG, "Failed to close model session", it) }
    }

    private fun clearConversationLocked() {
        history.clear()
        _messages.value = emptyList()
    }

    private fun currentGenOptions(): GenOptions =
        GenOptions.builder()
            .temperature(prefs.temperature.toDouble())
            .maxTokens(prefs.maxTokens)
            .build()

    private fun appendUiMessage(msg: UiMessage) {
        _messages.value = _messages.value + msg
    }

    override fun onCleared() {
        // Cancellation is lock-free so it can interrupt a blocking native decode. Cleanup
        // then waits asynchronously for the same lifecycle lock used by send/rebuild.
        localModel?.cancel()
        teardownScope.launch {
            try {
                engineMutex.withLock { closeEngineResourcesLocked() }
            } finally {
                teardownScope.cancel()
            }
        }
        super.onCleared()
    }
}
