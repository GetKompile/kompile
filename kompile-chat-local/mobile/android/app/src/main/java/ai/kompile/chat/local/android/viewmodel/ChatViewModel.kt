package ai.kompile.chat.local.android.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.kompile.chat.local.ChatEngine
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.GraphToolBridge
import ai.kompile.chat.local.InferenceRouter
import ai.kompile.chat.local.Message
import ai.kompile.chat.local.android.model.AndroidRemoteChatModel
import ai.kompile.chat.local.android.model.SdxChatModelAndroid
import ai.kompile.chat.local.android.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * ViewModel for the chat screen.
 *
 * Owns the [ChatEngine] lifecycle and the in-memory conversation history.
 * Re-initialises the engine whenever relevant settings change (model path,
 * remote URL, kgraph path).
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

    /** One of "LOCAL", "REMOTE", "NONE". */
    private val _activeRoute = MutableStateFlow("NONE")
    val activeRoute: StateFlow<String> = _activeRoute.asStateFlow()

    // --- Engine state (rebuilt on settings change) ---

    private var engine: ChatEngine? = null
    private var bridge: GraphToolBridge? = null
    private var localModel: SdxChatModelAndroid? = null

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
                try {
                    val eng = engine ?: run {
                        // Engine not ready yet -- attempt lazy rebuild.
                        rebuildEngine()
                        engine
                    }
                    if (eng == null) {
                        _error.value = "No inference backend configured. " +
                            "Add a remote URL in Settings or copy a model file."
                        return@withContext
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

            _thinking.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearHistory() {
        history.clear()
        _messages.value = emptyList()
    }

    /**
     * Call after the user changes settings in SettingsScreen to pick up new values.
     */
    fun onSettingsChanged() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                rebuildEngine()
            }
        }
    }

    // --- Engine lifecycle ---

    private suspend fun rebuildEngine() {
        withContext(Dispatchers.IO) {
        // Close previous resources.
        bridge?.close()
        bridge = null
        localModel?.close()
        localModel = null
        engine = null

        // Graph tool bridge.
        val newBridge = buildBridge()
        bridge = newBridge

        // Local SDX model (guarded -- may not have the .so).
        val modelFilePath = prefs.modelPath
        val newLocal: SdxChatModelAndroid? = if (modelFilePath.isNotBlank()) {
            SdxChatModelAndroid(modelFilePath).also { localModel = it }
        } else {
            null
        }

        // Remote model.
        val remoteUrl = prefs.remoteBaseUrl
        val newRemote: AndroidRemoteChatModel? = if (remoteUrl.isNotBlank()) {
            AndroidRemoteChatModel(
                baseUrl = remoteUrl,
                model = prefs.remoteModel,
                apiKey = prefs.remoteApiKey.takeIf { it.isNotBlank() }
            )
        } else {
            null
        }

        val router = InferenceRouter(newLocal, newRemote)
        val newEngine = ChatEngine(router, newBridge, prefs.maxToolRounds)
        engine = newEngine

        val route = router.activeRoute()
        withContext(Dispatchers.Main.immediate) {
            _activeRoute.value = route
        }

        Log.i(TAG, "Engine rebuilt, route=$route")
        }
    }

    private fun buildBridge(): GraphToolBridge {
        val kgraphPath = prefs.kgraphPath
        if (kgraphPath.isBlank()) {
            return GraphToolBridge.empty()
        }
        val path: Path = Paths.get(kgraphPath)
        return openBridgeSafe(path, kgraphPath)
    }

    private fun openBridgeSafe(path: Path, kgraphPath: String): GraphToolBridge {
        try {
            return GraphToolBridge.open(path)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open kgraph at $kgraphPath, using empty bridge", e)
            return GraphToolBridge.empty()
        }
    }

    // --- Asset bootstrap ---

    /**
     * On first run, copy any bundled assets/graphs/ (*.kgraph files) and assets/models/
     * into filesDir so the app works without the user manually importing files.
     */
    private suspend fun bootstrapAssets() = withContext(Dispatchers.IO) {
        if (prefs.bootstrapDone) return@withContext

        val assets = context.assets

        // Copy .kgraph files.
        val graphsDir = File(context.filesDir, "graphs").apply { mkdirs() }
        runCatching {
            val graphAssets = assets.list("graphs") ?: emptyArray()
            for (name in graphAssets) {
                if (!name.endsWith(".kgraph")) continue
                val dest = File(graphsDir, name)
                if (!dest.exists()) {
                    assets.open("graphs/$name").use { src ->
                        dest.outputStream().use { src.copyTo(it) }
                    }
                    Log.i(TAG, "Bootstrapped graph: $name")
                    // Use the first found graph as the default.
                    if (prefs.kgraphPath.isBlank()) {
                        prefs.kgraphPath = dest.absolutePath
                    }
                }
            }
        }.onFailure { Log.d(TAG, "No bundled graphs in assets/graphs/: ${it.message}") }

        // Copy model files.
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        runCatching {
            val modelAssets = assets.list("models") ?: emptyArray()
            for (name in modelAssets) {
                val dest = File(modelsDir, name)
                if (!dest.exists()) {
                    assets.open("models/$name").use { src ->
                        dest.outputStream().use { src.copyTo(it, bufferSize = 8_192) }
                    }
                    Log.i(TAG, "Bootstrapped model: $name")
                    if (prefs.modelPath.isBlank()) {
                        prefs.modelPath = dest.absolutePath
                    }
                }
            }
        }.onFailure { Log.d(TAG, "No bundled models in assets/models/: ${it.message}") }

        prefs.bootstrapDone = true
    }

    // --- SAF copy helpers (called from SettingsScreen) ---

    /**
     * Copy a file chosen via the SAF file picker into filesDir/graphs/ and
     * update the kgraph preference. Caller should then call [onSettingsChanged].
     */
    fun importKgraph(uri: Uri): String? {
        return try {
            val dir = File(context.filesDir, "graphs").apply { mkdirs() }
            val name = resolveFileName(uri) ?: "imported.kgraph"
            val dest = File(dir, name)
            context.contentResolver.openInputStream(uri)?.use { src ->
                dest.outputStream().use { src.copyTo(it) }
            }
            prefs.kgraphPath = dest.absolutePath
            dest.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import kgraph from $uri", e)
            null
        }
    }

    /**
     * Copy a model file chosen via the SAF file picker into filesDir/models/
     * and update the model preference. Caller should then call [onSettingsChanged].
     */
    fun importModel(uri: Uri): String? {
        return try {
            val dir = File(context.filesDir, "models").apply { mkdirs() }
            val name = resolveFileName(uri) ?: "imported.gguf"
            val dest = File(dir, name)
            context.contentResolver.openInputStream(uri)?.use { src ->
                dest.outputStream().use { src.copyTo(it, bufferSize = 8_192) }
            }
            prefs.modelPath = dest.absolutePath
            dest.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model from $uri", e)
            null
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

    // --- Helpers ---

    private fun currentGenOptions(): GenOptions =
        GenOptions.builder()
            .temperature(prefs.temperature.toDouble())
            .maxTokens(prefs.maxTokens)
            .build()

    private fun appendUiMessage(msg: UiMessage) {
        _messages.value = _messages.value + msg
    }

    override fun onCleared() {
        super.onCleared()
        bridge?.close()
        localModel?.close()
    }
}
