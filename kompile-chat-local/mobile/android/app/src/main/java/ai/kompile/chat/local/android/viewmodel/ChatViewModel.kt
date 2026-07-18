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
import ai.kompile.chat.local.android.graph.AndroidNativeGraphBackend
import ai.kompile.chat.local.android.graph.KgraphArtifactValidator
import ai.kompile.chat.local.Message
import ai.kompile.chat.local.android.model.AcceleratedChatModelAndroid
import ai.kompile.chat.local.android.model.MobileModelArtifactResolver
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
    }

    private val context: Context get() = getApplication<Application>().applicationContext
    val prefs = AppPreferences(context)

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

    /** Exact device route: LOCAL_VULKAN, LOCAL_HEXAGON, LOCAL_TENSOR_G3_NNAPI, LOCAL_TENSOR_G5, or NONE. */
    private val _activeRoute = MutableStateFlow("NONE")
    val activeRoute: StateFlow<String> = _activeRoute.asStateFlow()

    /** First-run/model-load state, kept distinct from native graph readiness. */
    private val _modelState = MutableStateFlow<ModelUiState>(ModelUiState.Checking)
    val modelState: StateFlow<ModelUiState> = _modelState.asStateFlow()

    /** Native graph state; startup is deliberately deferred until a model is usable. */
    private val _graphState = MutableStateFlow<GraphUiState>(GraphUiState.WaitingForModel)
    val graphState: StateFlow<GraphUiState> = _graphState.asStateFlow()

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

        // The product flavor supplies exactly one device-only JavaCPP provider.
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
    suspend fun importKgraphAndApply(uri: Uri): GraphImportOutcome {
        val imported = importKgraph(uri) ?: return GraphImportOutcome.Failed(
            "Graph import failed. Select a valid .kgraph file."
        )
        rebuildEngine(resetConversation = true)
        return graphImportOutcome(imported, _graphState.value)
    }

    /**
     * Copy a model file chosen via the SAF file picker into filesDir/models/
     * and update the model preference. Caller should then call [onSettingsChanged].
     */
    suspend fun importModel(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "models").apply { mkdirs() }
            val name = File(resolveFileName(uri) ?: "imported.sdz").name
            require(name.endsWith(".sdz", ignoreCase = true)) {
                "Local accelerator models use the canonical SameDiff .sdz format"
            }
            val imported = copyForActivation(uri, dir, name)
            try {
                // Validation also installs this flavor's immutable embedded target object.
                MobileModelArtifactResolver.resolve(context, imported.absolutePath)
                prefs.modelPath = imported.absolutePath
                imported.absolutePath
            } catch (failure: Exception) {
                imported.delete()
                throw failure
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model from $uri", e)
            null
        }
    }

    /** Import, validate, and immediately activate a SameDiff model selection. */
    suspend fun importModelAndActivate(uri: Uri): Result<String> {
        val imported = importModel(uri) ?: return Result.failure(
            IllegalArgumentException(
                "Model import failed. Select a target-compiled SameDiff .sdz file."
            )
        )
        rebuildEngine(resetConversation = true)
        return when (val state = _modelState.value) {
            is ModelUiState.Ready -> Result.success(imported)
            is ModelUiState.Failed -> Result.failure(IllegalStateException(state.message))
            ModelUiState.Missing -> Result.failure(
                IllegalStateException("The imported model was not selected.")
            )
            ModelUiState.Checking -> Result.failure(
                IllegalStateException("The imported model is still starting.")
            )
        }
    }

    /**
     * Install one canonical project archive and publish it only after this APK's model,
     * graph AOT runtime, and chat engine all open successfully.
     */
    suspend fun importProjectAndActivate(uri: Uri): ProjectImportOutcome =
        withContext(Dispatchers.IO) {
            var phase = ProjectImportPhase.COPY
            var importedArchive: File? = null
            var installed: ProjectArchiveInstaller.InstalledProject? = null
            var activated = false
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

    /**
     * Open the configured staging service in an external browser. This process keeps no
     * INTERNET permission; the browser downloads the resulting .kproject for SAF import.
     */
    fun openModelStaging(): Result<Unit> = runCatching {
        val base = prefs.modelStagingUrl.trim()
        require(base.isNotEmpty()) {
            "Configure a Kompile model staging server URL in Settings first."
        }
        val parsed = Uri.parse(base)
        require(parsed.scheme.equals("https", ignoreCase = true)
                || parsed.scheme.equals("http", ignoreCase = true)) {
            "The Kompile staging URL must use http or https."
        }
        val request = parsed.buildUpon()
            .appendQueryParameter("target", BuildConfig.SDX_TARGET_PROFILE)
            .appendQueryParameter("artifact", "kproject")
            .appendQueryParameter("source", "android")
            .build()
        context.startActivity(
            Intent(Intent.ACTION_VIEW, request).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
            val openedModel = AcceleratedChatModelAndroid(
                context,
                installed.modelPath().toString(),
                prefs.temperature,
                prefs.maxTokens
            )
            candidateModel = openedModel
            if (!openedModel.isAvailable()) {
                throw IOException(
                    openedModel.startupError
                        ?: "The project model could not open on this accelerator."
                )
            }
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
