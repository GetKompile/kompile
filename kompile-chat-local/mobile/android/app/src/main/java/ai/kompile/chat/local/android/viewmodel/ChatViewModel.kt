package ai.kompile.chat.local.android.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.kompile.chat.local.ChatEngine
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.ChatResponse
import ai.kompile.chat.local.ChatStreamListener
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.GraphToolBackend
import ai.kompile.chat.local.InferenceRouter
import ai.kompile.chat.local.ProjectArchiveInstaller
import ai.kompile.chat.local.android.BuildConfig
import ai.kompile.chat.local.android.ChatGenerationForegroundService
import ai.kompile.chat.local.android.HuggingFaceImportForegroundService
import ai.kompile.chat.local.android.KompileChatApplication
import ai.kompile.chat.local.android.acquisition.HuggingFaceGgmlAcquisition
import ai.kompile.chat.local.android.diagnostics.DspDiagnosticsTraceLog
import ai.kompile.chat.local.android.diagnostics.ImportDiagnostic
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticPolicy
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticSeverity
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticStore
import ai.kompile.chat.local.android.diagnostics.NativeOperationCheckpoint
import ai.kompile.chat.local.android.diagnostics.NativeOperationDiagnosticPolicy
import ai.kompile.chat.local.android.diagnostics.NativeOperationKind
import ai.kompile.chat.local.android.diagnostics.NativeOperationRecoveryTarget
import ai.kompile.chat.local.android.diagnostics.RecoveredNativeOperation
import ai.kompile.chat.local.android.diagnostics.SmokeDecodeTraceLog
import ai.kompile.chat.local.android.graph.AndroidNativeGraphBackend
import ai.kompile.chat.local.android.staging.ModelStagingHandoff
import ai.kompile.chat.local.android.graph.KgraphArtifactValidator
import ai.kompile.chat.local.Message
import ai.kompile.chat.local.android.model.AcceleratedChatModelAndroid
import ai.kompile.chat.local.android.model.MobileModelArtifactResolver
import ai.kompile.chat.local.android.model.ModelPreparationOptions
import ai.kompile.chat.local.android.model.PreparedModelInfo
import ai.kompile.chat.local.android.model.PreparationStage
import ai.kompile.chat.local.android.model.SdxGgufModelImporter
import ai.kompile.chat.local.android.prefs.ActiveProjectSelection
import ai.kompile.chat.local.android.prefs.AppPreferences
import ai.kompile.chat.local.android.prefs.HuggingFaceImportCheckpoint
import ai.kompile.chat.local.android.prefs.HuggingFaceImportCheckpointStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nd4j.dsp.model.HuggingFaceGgmlResolver
import org.nd4j.dsp.model.ResumableModelDownloader
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
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
        val modelId: String,
        val preparedModel: PreparedModelInfo?
    )

    private data class HuggingFaceImportPreflight(
        val sourceName: String,
        val finalFile: File,
        val reusableDownload: HuggingFaceGgmlAcquisition.DownloadMetadata?,
        val obsoleteCacheFilesAfterActivation: List<File>,
        val storage: HuggingFaceStoragePreflight
    )

    /** Stable in-memory retry boundary for one selected candidate and its app-owned bytes. */
    private data class HuggingFacePreparedImport(
        val candidate: HuggingFaceGgmlResolver.Candidate,
        val preflight: HuggingFaceImportPreflight,
        val verifiedDownload: HuggingFaceGgmlAcquisition.DownloadMetadata?
    )

    private data class HuggingFaceCheckpointLoad(
        val checkpoint: HuggingFaceImportCheckpoint?,
        val failure: Throwable?
    )

    private val kompileApplication = getApplication<Application>() as KompileChatApplication
    private val context: Context get() = kompileApplication.applicationContext
    val prefs = AppPreferences(context)
    private val importDiagnosticStore = ImportDiagnosticStore(context)
    private val recoveredNativeOperations = kompileApplication.recoveredNativeOperations
        .sortedByDescending { it.attempt.checkpointEpochMillis }
    private val huggingFaceCheckpointLoad = loadHuggingFaceCheckpoint()
    private var huggingFaceCheckpoint = huggingFaceCheckpointLoad.checkpoint

    private fun recoveredOperationTargetsActiveSelection(
        recovered: RecoveredNativeOperation
    ): Boolean {
        val activeModelPath = prefs.modelPath.takeIf(String::isNotBlank) ?: return false
        return NativeOperationDiagnosticPolicy.modelPathFingerprint(activeModelPath) ==
            recovered.attempt.modelPathFingerprint
    }

    private val recoveredImportOperation = recoveredNativeOperations.firstOrNull {
        it.diagnostic.severity == ImportDiagnosticSeverity.ERROR &&
        !recoveredOperationTargetsActiveSelection(it) &&
            (
                it.attempt.operation.recoveryTarget == NativeOperationRecoveryTarget.HUGGING_FACE_IMPORT ||
                    (
                        huggingFaceCheckpoint != null &&
                            it.attempt.operation in setOf(
                                NativeOperationKind.SDX_MODEL_LOAD,
                                NativeOperationKind.SDX_MODEL_EXECUTION
                            )
                        )
                )
    }
    private val recoveredActiveModelOperation = recoveredNativeOperations.firstOrNull {
        it.attempt.attemptId != recoveredImportOperation?.attempt?.attemptId &&
            it.attempt.operation.recoveryTarget == NativeOperationRecoveryTarget.ACTIVE_MODEL
    }
    private val recoveredNativeOperationDiagnostic =
        recoveredNativeOperations.firstOrNull()?.diagnostic ?: kompileApplication.startupDiagnosticFallback
    private val initialHuggingFaceImportState = huggingFaceCheckpointLoad.failure
        ?.let(::huggingFaceCheckpointFailureState)
        ?: recoveredImportOperation?.let(::recoveredNativeOperationFailureState)
        ?: kompileApplication.startupDiagnosticFallback
            ?.takeIf { huggingFaceCheckpoint != null }
            ?.let { recoveredNativeOperationFailureState(null, it) }
        ?: huggingFaceCheckpoint?.let(::interruptedHuggingFaceImportState)
        ?: HuggingFaceImportUiState.Idle

    private fun loadHuggingFaceCheckpoint(): HuggingFaceCheckpointLoad = try {
        HuggingFaceCheckpointLoad(prefs.loadHuggingFaceImportCheckpoint(), null)
    } catch (failure: Throwable) {
        HuggingFaceCheckpointLoad(null, failure)
    }

    private fun recoveredNativeOperationFailureState(
        recovered: RecoveredNativeOperation
    ): HuggingFaceImportUiState.Failed =
        recoveredNativeOperationFailureState(recovered, recovered.diagnostic)

    private fun recoveredNativeOperationFailureState(
        recovered: RecoveredNativeOperation?,
        diagnostic: ImportDiagnostic
    ): HuggingFaceImportUiState.Failed {
        val failure = ChatException(
            buildString {
                append(diagnostic.summary)
                if (diagnostic.technicalDetails.isNotBlank()) {
                    append("\n").append(diagnostic.technicalDetails)
                }
            }
        )
        return HuggingFaceImportUiState.Failed(
            progress = HuggingFaceImportProgress(
                step = recoveredHuggingFaceStep(recovered),
                message = diagnostic.summary,
                attempt = 1,
                maxAttempts = 1,
                resumedBytes = 0L,
                completedBytes = 0L,
                totalBytes = null,
                smoothedBytesPerSecond = null,
                etaSeconds = null,
                retryWillResumeOrReuse = true
            ),
            diagnostic = diagnostic,
            failure = failure
        )
    }

    private fun recoveredHuggingFaceStep(
        recovered: RecoveredNativeOperation?
    ): HuggingFaceImportStep = when (recovered?.attempt?.checkpoint) {
        NativeOperationCheckpoint.START_IMPORTER_PROCESS,
        NativeOperationCheckpoint.LOAD_IMPORTER_TRANSPORT,
        NativeOperationCheckpoint.CREATE_IMPORTER_RUNTIME,
        NativeOperationCheckpoint.QUERY_IMPORTER_ABI,
        NativeOperationCheckpoint.CONVERT_OPTIMIZE_SDZ,
        NativeOperationCheckpoint.READ_PREPARED_MODEL,
        NativeOperationCheckpoint.DESTROY_IMPORTER_RUNTIME,
        NativeOperationCheckpoint.STOP_IMPORTER_PROCESS -> HuggingFaceImportStep.CONVERT_SDZ

        NativeOperationCheckpoint.RENDER_CHAT_TEMPLATE,
        NativeOperationCheckpoint.ENCODE_PROMPT,
        NativeOperationCheckpoint.RESET_TEXT_SESSION,
        NativeOperationCheckpoint.GENERATE_TOKENS,
        NativeOperationCheckpoint.DECODE_TOKENS,
        NativeOperationCheckpoint.EXECUTE_LITERT_GENERATION -> HuggingFaceImportStep.SMOKE_DECODE

        else -> HuggingFaceImportStep.SDX_LOAD
    }

    private fun huggingFaceCheckpointFailureState(
        failure: Throwable
    ): HuggingFaceImportUiState.Failed {
        val summary = "Saved Hugging Face import checkpoint could not be loaded: " +
            (failure.message ?: failure.javaClass.name)
        val progress = HuggingFaceImportProgress(
            step = HuggingFaceImportStep.PREFLIGHT,
            message = summary,
            attempt = 1,
            maxAttempts = 1,
            resumedBytes = 0L,
            completedBytes = 0L,
            totalBytes = null,
            smoothedBytesPerSecond = null,
            etaSeconds = null,
            retryWillResumeOrReuse = false
        )
        val diagnostic = ImportDiagnosticPolicy.create(
            timestampEpochMillis = System.currentTimeMillis(),
            operation = "hugging face model",
            phase = "restore import checkpoint",
            severity = ImportDiagnosticSeverity.ERROR,
            summary = summary,
            remediation = "Expand and copy the full stack trace, then start a new import to replace the malformed checkpoint.",
            technicalDetails = ImportDiagnosticPolicy.failureDetails(failure)
        )
        return HuggingFaceImportUiState.Failed(
            progress = progress,
            diagnostic = diagnostic,
            failure = failure
        )
    }

    // --- Observable state ---

    /** Full conversation history shown in the message list. */
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    /** True while a generate call is in-flight. */
    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    /** Live provider events for the assistant turn currently being generated. */
    private val _streaming = MutableStateFlow<StreamingUiState?>(null)
    val streaming: StateFlow<StreamingUiState?> = _streaming.asStateFlow()

    /** Non-null when the last operation produced an error banner. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Complete untruncated stack for [error] when it represents an actual caught failure. */
    private val _errorStackTrace = MutableStateFlow<String?>(null)
    val errorStackTrace: StateFlow<String?> = _errorStackTrace.asStateFlow()

    /** Exact SDX route, including SDX_GGUF_AOT for directly imported GGUF/GGML models. */
    private val _activeRoute = MutableStateFlow("NONE")
    val activeRoute: StateFlow<String> = _activeRoute.asStateFlow()

    /** First-run/model-load state, kept distinct from native graph readiness. */
    private val _modelState = MutableStateFlow<ModelUiState>(ModelUiState.Checking)
    val modelState: StateFlow<ModelUiState> = _modelState.asStateFlow()

    /** Exact native preparation phase; NNAPI compilation exposes no numeric percentage. */
    private val _modelLoadProgress = MutableStateFlow<ModelLoadProgressUi?>(null)
    val modelLoadProgress: StateFlow<ModelLoadProgressUi?> = _modelLoadProgress.asStateFlow()

    private val _navigationEvents = Channel<AppNavigationEvent>(Channel.BUFFERED)
    val navigationEvents: Flow<AppNavigationEvent> = _navigationEvents.receiveAsFlow()

    /** Result of an actual bounded token decode, separate from native session startup. */
    private val _modelSmokeState = MutableStateFlow<ModelSmokeUiState>(ModelSmokeUiState.NotRun)
    val modelSmokeState: StateFlow<ModelSmokeUiState> = _modelSmokeState.asStateFlow()

    /** Native graph state; startup is deliberately deferred until a model is usable. */
    private val _graphState = MutableStateFlow<GraphUiState>(GraphUiState.WaitingForModel)
    val graphState: StateFlow<GraphUiState> = _graphState.asStateFlow()

    /**
     * Exact owner of the single import gate. The UI consumes this type so Hugging Face can never
     * fall through to an unrelated archive/project progress indicator again.
     */
    private val _importOperation = MutableStateFlow(ImportOperationKind.NONE)
    val importOperation: StateFlow<ImportOperationKind> = _importOperation.asStateFlow()
    val importBusy: StateFlow<Boolean> = importOperation
        .map { it.isBusy }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun publishModelPreparationStage(stage: PreparationStage) {
        _modelLoadProgress.value = when (stage) {
            PreparationStage.CONVERT_AND_CACHE_SDZ -> ModelLoadProgressUi(
                title = "Converting and caching model…",
                detail = "Building the canonical sharded SDZ and text assets",
            )
            PreparationStage.TARGET_CACHE_READY -> ModelLoadProgressUi(
                title = "Preparing accelerator artifacts…",
                detail = "Canonical SDZ is ready; resolving the strict Tensor G3 target",
            )
            PreparationStage.LOAD_ACCELERATOR -> {
                val driverCache = File(context.codeCacheDir, "sdx-device-compilation")
                val cacheCandidatePresent = driverCache.list()?.isNotEmpty() == true
                ModelLoadProgressUi(
                    title = "Compiling or restoring Edge TPU plan…",
                    detail = if (cacheCandidatePresent) {
                        "NNAPI driver-cache files found; Android is validating the google-edgetpu plan"
                    } else {
                        "First load: compiling google-edgetpu segments; later loads reuse the driver cache"
                    },
                )
            }
        }
    }

    /** Public Hugging Face acquisition is complete only after a real SDX decode activates. */
    private val _huggingFaceImportState = MutableStateFlow<HuggingFaceImportUiState>(
        initialHuggingFaceImportState
    )
    val huggingFaceImportState: StateFlow<HuggingFaceImportUiState> =
        _huggingFaceImportState.asStateFlow()

    private val _modelPreparationOptions = MutableStateFlow(prefs.modelPreparationOptions)
    val modelPreparationOptions: StateFlow<ModelPreparationOptions> =
        _modelPreparationOptions.asStateFlow()

    private val _localModelOptimizationState =
        MutableStateFlow<HuggingFaceImportUiState>(HuggingFaceImportUiState.Idle)
    val localModelOptimizationState: StateFlow<HuggingFaceImportUiState> =
        _localModelOptimizationState.asStateFlow()

    private val _localModelSources = MutableStateFlow<List<LocalModelSource>>(emptyList())
    val localModelSources: StateFlow<List<LocalModelSource>> = _localModelSources.asStateFlow()

    private val _huggingFaceReference = MutableStateFlow(
        huggingFaceCheckpoint?.rawReference ?: prefs.huggingFaceReference
    )
    val huggingFaceReference: StateFlow<String> = _huggingFaceReference.asStateFlow()

    private val _huggingFaceDiscovery =
        MutableStateFlow<HuggingFaceGgmlResolver.Discovery?>(null)
    val huggingFaceDiscovery: StateFlow<HuggingFaceGgmlResolver.Discovery?> =
        _huggingFaceDiscovery.asStateFlow()

    private val _huggingFaceSelection =
        MutableStateFlow<HuggingFaceGgmlResolver.Candidate?>(null)
    val huggingFaceSelection: StateFlow<HuggingFaceGgmlResolver.Candidate?> =
        _huggingFaceSelection.asStateFlow()

    private val _huggingFaceConfigurationState =
        MutableStateFlow<HuggingFaceConfigurationUiState>(HuggingFaceConfigurationUiState.Idle)
    val huggingFaceConfigurationState: StateFlow<HuggingFaceConfigurationUiState> =
        _huggingFaceConfigurationState.asStateFlow()

    /** Durable, bounded, sanitized import/activation/execution history shown in the app. */
    private val _importDiagnostics = MutableStateFlow(
        importDiagnosticStore.load()
            .let { retained ->
                recoveredNativeOperations.fold(retained) { entries, recovered ->
                    prependDiagnosticIfAbsent(entries, recovered.diagnostic)
                }
            }
            .let { retained -> prependDiagnosticIfAbsent(retained, recoveredNativeOperationDiagnostic) }
            .let { retained ->
                prependDiagnosticIfAbsent(
                    retained,
                    (initialHuggingFaceImportState as? HuggingFaceImportUiState.Failed)?.diagnostic
                )
            }
    )
    val importDiagnostics: StateFlow<List<ImportDiagnostic>> = _importDiagnostics.asStateFlow()

    private val importGate = java.util.concurrent.atomic.AtomicBoolean(false)
    private val sendGate = AtomicSendGate()
    private var huggingFaceJob: Job? = null
    private var huggingFaceDownloadCancellation: ResumableModelDownloader.CancellationHandle? = null
    private var huggingFacePreparedImport: HuggingFacePreparedImport? = null
    private var lastHuggingFaceProgressDiagnosticKey: String? = null
    private var lastLocalOptimizationPath: String? = null

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

    private fun prependDiagnosticIfAbsent(
        entries: List<ImportDiagnostic>,
        diagnostic: ImportDiagnostic?
    ): List<ImportDiagnostic> = when {
        diagnostic == null || diagnostic in entries -> entries
        else -> ImportDiagnosticPolicy.prependBounded(entries, diagnostic)
    }

    private fun recoveredRuntimeTargetsActiveModel(): Boolean {
        val recovered = recoveredActiveModelOperation ?: return false
        val activeModelPath = prefs.modelPath.takeIf(String::isNotBlank) ?: return false
        return NativeOperationDiagnosticPolicy.modelPathFingerprint(activeModelPath) ==
            recovered.attempt.modelPathFingerprint
    }

    private fun publishRecoveredRuntimeFailureForActiveModel() {
        val diagnostic = recoveredActiveModelOperation?.diagnostic ?: return
        _modelState.value = ModelUiState.Failed(
            path = prefs.modelPath,
            message = diagnostic.summary,
            stackTrace = diagnostic.technicalDetails
        )
        _error.value = diagnostic.summary
        _errorStackTrace.value = diagnostic.technicalDetails
        _activeRoute.value = "NONE"
        _graphState.value = GraphUiState.WaitingForModel
    }

    // --- Init ---

    init {
        val startupFailureHandler = CoroutineExceptionHandler { _, failure ->
            val modelPath = prefs.modelPath
            publishModelStartupFailure(
                modelPath,
                failure.message ?: "Local model startup failed without an error message.",
                failure
            )
            Log.e(TAG, "Local model startup failed", failure)
        }
        refreshLocalModelSources()
        viewModelScope.launch(startupFailureHandler) {
            bootstrapAssets()
            if (recoveredRuntimeTargetsActiveModel()) {
                // Do not auto-enter the exact native boundary that killed the preceding process.
                // The full exit evidence is already visible and an explicit retry remains available.
                publishRecoveredRuntimeFailureForActiveModel()
            } else {
                rebuildEngine()
            }
        }
    }

    // --- Public API ---

    fun clearImportDiagnostics() {
        importDiagnosticStore.clear()
        _importDiagnostics.value = emptyList()
    }

    fun updateModelPreparationOptions(options: ModelPreparationOptions) {
        check(!_importOperation.value.isBusy) {
            "Model preparation options cannot change while an import or optimization is running."
        }
        prefs.modelPreparationOptions = options
        _modelPreparationOptions.value = options
    }

    fun refreshLocalModelSources() {
        val roots = listOf(
            File(context.filesDir, "models/hugging-face"),
            File(context.filesDir, "models/local-sources"),
        )
        _localModelSources.value = roots
            .flatMap { root -> root.listFiles()?.asList().orEmpty() }
            .filter { file ->
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    file.extension.lowercase() in setOf("gguf", "ggml")
            }
            .distinctBy { it.absolutePath }
            .sortedByDescending(File::lastModified)
            .map { LocalModelSource(it.absolutePath, it.name, it.length()) }
    }

    private fun recordImportDiagnostic(
        operation: String,
        phase: String,
        severity: ImportDiagnosticSeverity,
        summary: String,
        remediation: String,
        failure: Throwable? = null,
        technicalDetails: String = ""
    ): ImportDiagnostic {
        val entry = ImportDiagnosticPolicy.create(
            timestampEpochMillis = System.currentTimeMillis(),
            operation = operation,
            phase = phase,
            severity = severity,
            summary = summary,
            remediation = remediation,
            technicalDetails = when {
                technicalDetails.isNotBlank() -> technicalDetails
                failure != null -> ImportDiagnosticPolicy.failureDetails(failure)
                else -> ""
            }
        )
        _importDiagnostics.value = ImportDiagnosticPolicy.prependBounded(_importDiagnostics.value, entry)
        importDiagnosticStore.append(entry)
        return entry
    }

    /** Publish the current failure and its exact diagnostic as one indivisible UI update. */
    private fun publishHuggingFaceFailure(
        progress: HuggingFaceImportProgress,
        summary: String = progress.message,
        remediation: String = huggingFaceStepResumeBehavior(progress.step),
        failure: Throwable,
        operation: String = "hugging face model",
        phase: String = progress.step.label.lowercase()
    ): HuggingFaceImportUiState.Failed {
        var diagnostic = ImportDiagnosticPolicy.create(
            timestampEpochMillis = System.currentTimeMillis(),
            operation = operation,
            phase = phase,
            severity = ImportDiagnosticSeverity.ERROR,
            summary = summary,
            remediation = remediation,
            technicalDetails = ImportDiagnosticPolicy.failureDetails(failure)
        )
        var state = HuggingFaceImportUiState.Failed(
            progress.copy(message = summary),
            diagnostic,
            failure
        )
        _huggingFaceImportState.value = state
        _importDiagnostics.value = ImportDiagnosticPolicy.prependBounded(_importDiagnostics.value, diagnostic)
        try {
            importDiagnosticStore.append(diagnostic)
        } catch (persistenceFailure: RuntimeException) {
            failure.addSuppressed(persistenceFailure)
            diagnostic = ImportDiagnosticPolicy.create(
                timestampEpochMillis = System.currentTimeMillis(),
                operation = operation,
                phase = phase,
                severity = ImportDiagnosticSeverity.ERROR,
                summary = "$summary (diagnostic persistence also failed)",
                remediation = remediation,
                technicalDetails = ImportDiagnosticPolicy.failureDetails(failure)
            )
            state = HuggingFaceImportUiState.Failed(
                progress.copy(message = summary),
                diagnostic,
                failure
            )
            _huggingFaceImportState.value = state
            _importDiagnostics.value = ImportDiagnosticPolicy.prependBounded(
                _importDiagnostics.value,
                diagnostic
            )
        }
        return state
    }

    private fun publishModelStartupFailure(
        modelPath: String,
        summary: String,
        failure: Throwable
    ) {
        _modelLoadProgress.value = null
        var state = ModelUiState.Failed(modelPath, summary, failure.stackTraceToString())
        _modelState.value = state
        var diagnostic = ImportDiagnosticPolicy.create(
            timestampEpochMillis = System.currentTimeMillis(),
            operation = "local model",
            phase = "activation",
            severity = ImportDiagnosticSeverity.ERROR,
            summary = summary,
            remediation = "Copy the full stack trace, verify the APK flavor and model assets, then import or retry the model.",
            technicalDetails = ImportDiagnosticPolicy.failureDetails(failure)
        )
        _importDiagnostics.value = ImportDiagnosticPolicy.prependBounded(_importDiagnostics.value, diagnostic)
        try {
            importDiagnosticStore.append(diagnostic)
        } catch (persistenceFailure: RuntimeException) {
            failure.addSuppressed(persistenceFailure)
            state = ModelUiState.Failed(modelPath, summary, failure.stackTraceToString())
            _modelState.value = state
            diagnostic = ImportDiagnosticPolicy.create(
                timestampEpochMillis = System.currentTimeMillis(),
                operation = "local model",
                phase = "activation",
                severity = ImportDiagnosticSeverity.ERROR,
                summary = "$summary (diagnostic persistence also failed)",
                remediation = "Copy the full stack trace and check this APK's private storage.",
                technicalDetails = ImportDiagnosticPolicy.failureDetails(failure)
            )
            _importDiagnostics.value = ImportDiagnosticPolicy.prependBounded(
                _importDiagnostics.value,
                diagnostic
            )
        }
    }

    /**
     * Send a user message through the [ChatEngine] and append the result.
     */
    fun sendMessage(userText: String) {
        if (userText.isBlank()) return
        if (!sendGate.tryAcquire()) return
        viewModelScope.launch {
            var foregroundGenerationId: Long? = null
            try {
                withContext(Dispatchers.IO) {
                    DspDiagnosticsTraceLog(context).resetForChat()
                }
                val generationId = ChatGenerationForegroundService.start(context) {
                    localModel?.cancel()
                }
                foregroundGenerationId = generationId
                appendUiMessage(UiMessage(role = "user", content = userText))
                _thinking.value = true
                _streaming.value = StreamingUiState()
                _error.value = null
                _errorStackTrace.value = null
                val streamParser = StreamingTextParser()
                val generatedCharacters = AtomicInteger(0)
                val listener = object : ChatStreamListener {
                    override fun onStatus(status: String) {
                        ChatGenerationForegroundService.publish(generationId, phase = status)
                        mutateStreaming { it.copy(phase = status) }
                    }

                    override fun onText(text: String) {
                        ChatGenerationForegroundService.publish(
                            generationId,
                            generatedCharacters = generatedCharacters.addAndGet(text.length)
                        )
                        streamParser.append(text) { reasoning, content ->
                            mutateStreaming {
                                it.copy(reasoning = reasoning, content = content)
                            }
                        }
                    }

                    override fun onToolCall(tool: String, argsJson: String) {
                        ChatGenerationForegroundService.publish(generationId, phase = "running tool")
                        mutateStreaming {
                            it.copy(
                                phase = "running_tool",
                                toolActivities = it.toolActivities +
                                    ToolActivityUi(tool = tool, argsJson = argsJson)
                            )
                        }
                    }

                    override fun onToolResult(
                        tool: String,
                        argsJson: String,
                        resultJson: String
                    ) {
                        mutateStreaming {
                            val index = it.toolActivities.indexOfLast { activity ->
                                activity.tool == tool &&
                                    activity.argsJson == argsJson &&
                                    activity.status == "running"
                            }
                            if (index < 0) {
                                it.copy(toolActivities = it.toolActivities +
                                    ToolActivityUi(tool, argsJson, resultJson, "complete"))
                            } else {
                                it.copy(toolActivities = it.toolActivities.toMutableList().also { activities ->
                                    activities[index] = activities[index].copy(
                                        resultJson = resultJson,
                                        status = "complete"
                                    )
                                })
                            }
                        }
                    }

                    override fun onProtocolExchange(
                        requestJson: String,
                        rawResponse: String,
                        protocolErrors: List<String>
                    ) {
                        mutateStreaming {
                            it.copy(
                                protocolExchangeCount = it.protocolExchangeCount + 1,
                                protocolExchanges = (
                                    it.protocolExchanges + ProtocolExchangeUi(
                                        requestJson = requestJson,
                                        rawResponse = rawResponse,
                                        protocolErrors = protocolErrors
                                    )
                                ).takeLast(32)
                            )
                        }
                    }

                    override fun onResponse(response: ChatResponse) {
                        ChatGenerationForegroundService.publish(generationId, phase = "Finalizing reply")
                        streamParser.finish { reasoning, content ->
                            mutateStreaming { it.copy(reasoning = reasoning, content = content) }
                        }
                        if (response.reasoningContent().isNotBlank()) {
                            mutateStreaming { it.copy(reasoning = response.reasoningContent()) }
                        }
                        if (response.content().isNotBlank()) {
                            mutateStreaming {
                                if (it.content.isBlank()) it.copy(content = response.content())
                                else it
                            }
                        }
                    }
                }

                withContext(Dispatchers.IO) {
                    engineMutex.withLock {
                        try {
                            val eng = engine ?: run {
                                // Engine not ready yet -- attempt a serialized lazy rebuild.
                                rebuildEngineLocked(resetConversation = false)
                                engine
                            }
                            if (eng == null) {
                                val startupFailure = when (val model = _modelState.value) {
                                    ModelUiState.Missing -> null
                                    is ModelUiState.Failed -> model.message to model.stackTrace
                                    else -> when (val graph = _graphState.value) {
                                        is GraphUiState.Failed -> graph.message to graph.stackTrace
                                        else -> "The local accelerator is still starting." to null
                                    }
                                }
                                startupFailure?.let { (message, stackTrace) ->
                                    _error.value = message
                                    _errorStackTrace.value = stackTrace
                                }
                                _streaming.value = null
                                return@withLock
                            }

                            val opts = currentGenOptions()
                            val result = eng.chatStreaming(history, userText, opts, listener)
                            val answer = result.answer().trim()
                            if (answer.isEmpty()) {
                                throw ChatException("The local model returned no assistant text.")
                            }

                            // Append only a proven visible assistant answer to history and UI.
                            history.add(Message.user(userText))
                            history.add(Message.assistant(answer))

                            val toolRoundsList: List<ToolRoundUi> = result.rounds().map { round ->
                                ToolRoundUi(round.tool(), round.argsJson(), round.resultJson())
                            }
                            val protocolExchanges = result.exchanges().map { exchange ->
                                ProtocolExchangeUi(
                                    requestJson = exchange.requestJson(),
                                    rawResponse = exchange.rawResponse(),
                                    protocolErrors = exchange.protocolErrors()
                                )
                            }

                            withContext(Dispatchers.Main.immediate) {
                                _streaming.value = null
                                appendUiMessage(
                                    UiMessage(
                                        role = "assistant",
                                        content = answer,
                                        toolRounds = toolRoundsList,
                                        protocolExchanges = protocolExchanges
                                    )
                                )
                            }
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            currentCoroutineContext().ensureActive()
                            val summary = if (failure is ChatException) {
                                failure.message ?: "Local chat generation failed."
                            } else {
                                "Unexpected error: ${failure.message ?: failure.javaClass.name}"
                            }
                            try {
                                recordImportDiagnostic(
                                    "local chat",
                                    "generation",
                                    ImportDiagnosticSeverity.ERROR,
                                    summary,
                                    "Expand and copy the full stack trace before retrying or running the local model decode test.",
                                    failure = failure
                                )
                            } catch (persistenceFailure: RuntimeException) {
                                failure.addSuppressed(persistenceFailure)
                            }
                            Log.e(TAG, "Chat failed", failure)
                            withContext(Dispatchers.Main.immediate) {
                                mutateStreaming { it.copy(phase = "failed") }
                                _error.value = summary
                                _errorStackTrace.value = failure.stackTraceToString()
                            }
                        }
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                val summary = "Android could not start protected background generation: " +
                    (failure.message ?: failure.javaClass.name)
                Log.e(TAG, "Background generation lease failed", failure)
                _streaming.value = null
                _error.value = summary
                _errorStackTrace.value = failure.stackTraceToString()
            } finally {
                foregroundGenerationId?.let { generationId ->
                    ChatGenerationForegroundService.stop(context, generationId)
                }
                _thinking.value = false
                sendGate.release()
            }
        }
    }

    fun clearError() {
        _error.value = null
        _errorStackTrace.value = null
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
     * Stop the active response and retire the model runtime in one lifecycle-safe action.
     *
     * Native generation is non-interruptible from the coroutine's perspective, so request
     * cancellation first and then let [unloadActiveModel] wait for the engine lease to be
     * released before closing the provider session and clearing the active model.
     */
    suspend fun stopResponseAndUnloadModel(): Result<Unit> {
        var cancellationFailure: Throwable? = null
        try {
            withContext(Dispatchers.IO) {
                localModel?.cancel()
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            cancellationFailure = failure
            Log.w(TAG, "Native response cancellation reported a failure; continuing with model unload", failure)
        }

        val unloadResult = unloadActiveModel()
        val cancellation = cancellationFailure
        if (cancellation != null) {
            unloadResult.exceptionOrNull()?.addSuppressed(cancellation)
        }
        return unloadResult
    }

    /** Run a bounded real decode through the active SDX/provider session. */
    fun runModelSmokeTest() {
        if (_thinking.value || _importOperation.value.isBusy) {
            _error.value = "Wait for the current response or import to finish before testing the model."
            _errorStackTrace.value = null
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                engineMutex.withLock {
                    try {
                        val model = localModel
                            ?: throw IOException("Import and activate a local .sdz, .gguf, or .ggml model first.")
                        val smoke = smokeTestModelLocked(model)
                        if (smoke is ModelSmokeUiState.Failed) {
                            _error.value = smoke.message
                            _errorStackTrace.value = smoke.failure.stackTraceToString()
                        }
                    } catch (failure: Throwable) {
                        Log.e(TAG, "Local model decode test failed", failure)
                        _error.value = failure.message ?: "Local model decode test failed"
                        _errorStackTrace.value = failure.stackTraceToString()
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
        val trace = SmokeDecodeTraceLog(getApplication<Application>())
        _modelSmokeState.value = ModelSmokeUiState.Running(route)
        trace.record(
            "smoke_started",
            fields = mapOf(
                "route" to route,
                "max_tokens" to SMOKE_TEST_MAX_TOKENS,
                "trace_location" to trace.locationDescription()
            )
        )
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
                throw ChatException(
                    "The SDX model decoded no tokens on $route after ${elapsedMs}ms."
                )
            }
            val passed = ModelSmokeUiState.Passed(
                route,
                elapsedMs,
                answer.take(SMOKE_TEST_PREVIEW_CHARS)
            )
            _modelSmokeState.value = passed
            trace.record(
                "smoke_passed",
                fields = mapOf(
                    "route" to route,
                    "elapsed_ms" to elapsedMs,
                    "answer_chars" to answer.length,
                    "preview_chars" to SMOKE_TEST_PREVIEW_CHARS
                )
            )
            Log.i(TAG, "Model smoke decode passed on $route in ${elapsedMs}ms")
            return passed
        } catch (failure: Throwable) {
            trace.recordFailure(
                "smoke_failed",
                attemptId = null,
                failure = failure,
                fields = mapOf("route" to route)
            )
            Log.e(TAG, "Model smoke decode failed on $route", failure)
            val failed = ModelSmokeUiState.Failed(
                route,
                failure.message ?: failure.javaClass.simpleName,
                failure
            )
            _modelSmokeState.value = failed
            try {
                recordImportDiagnostic(
                    "local model",
                    "smoke decode",
                    ImportDiagnosticSeverity.ERROR,
                    failure.message ?: failure.javaClass.simpleName,
                    "Copy the details, verify model compatibility, and retry the decode test.",
                    failure = failure
                )
            } catch (persistenceFailure: Throwable) {
                failure.addSuppressed(persistenceFailure)
                Log.e(TAG, "Persisting the model smoke-decode failure also failed", persistenceFailure)
            }
            return failed
        }
    }

    /**
     * Call after the user changes settings in SettingsScreen to pick up new values.
     */
    fun onSettingsChanged() {
        val startupFailureHandler = CoroutineExceptionHandler { _, failure ->
            publishModelStartupFailure(
                prefs.modelPath,
                failure.message ?: "Local model restart failed without an error message.",
                failure
            )
            Log.e(TAG, "Local model restart failed", failure)
        }
        viewModelScope.launch(startupFailureHandler) {
            rebuildEngine(resetConversation = true)
        }
    }

    /**
     * Explicitly retire the sole active runtime before another artifact can be imported.
     * The model file and independently selected graph remain on disk; only active ownership
     * and project provenance are cleared.
     */
    suspend fun unloadActiveModel(): Result<Unit> = runExclusiveImport(
        operation = ImportOperationKind.MODEL_UNLOAD,
        blocked = { reason -> Result.failure(IllegalStateException(reason)) }
    ) {
        try {
            withContext(Dispatchers.IO) {
                engineMutex.withLock {
                    var cleanupFailure: Throwable? = null
                    try {
                        closeEngineResourcesLocked()
                    } catch (failure: Throwable) {
                        // Resources are detached before close. A dead isolated runtime must not
                        // keep the persisted model selected or strand the user on Settings.
                        cleanupFailure = failure
                    }

                    if (!prefs.deactivateModel()) {
                        val persistenceFailure = IllegalStateException(
                            "Could not persist the unloaded model state."
                        )
                        cleanupFailure?.let(persistenceFailure::addSuppressed)
                        try {
                            rebuildEngineLocked(resetConversation = false)
                        } catch (restoreFailure: Throwable) {
                            persistenceFailure.addSuppressed(restoreFailure)
                        }
                        throw persistenceFailure
                    }

                    clearConversationLocked()
                    _activeRoute.value = "NONE"
                    _modelState.value = ModelUiState.Missing
                    _modelSmokeState.value = ModelSmokeUiState.NotRun
                    _graphState.value = GraphUiState.WaitingForModel
                    _huggingFaceImportState.value = HuggingFaceImportUiState.Idle
                    _localModelOptimizationState.value = HuggingFaceImportUiState.Idle
                    _huggingFaceDiscovery.value = null
                    _huggingFaceSelection.value = null
                    _error.value = null
                    _errorStackTrace.value = null
                    cleanupFailure?.let {
                        Log.w(TAG, "Model ownership cleared after native cleanup reported a failure", it)
                    }
                }
            }
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            _error.value = failure.message ?: "The active model could not be unloaded."
            _errorStackTrace.value = failure.stackTraceToString()
            Result.failure(failure)
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
        _modelLoadProgress.value = null
        _graphState.value = GraphUiState.WaitingForModel
        _modelSmokeState.value = ModelSmokeUiState.NotRun
        _error.value = null
        _errorStackTrace.value = null

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

        // Every selected model reaches the same canonical-SDZ provider seam.
        val newLocal = try {
            AcceleratedChatModelAndroid(
                context = context,
                modelPath = modelFilePath,
                temperature = prefs.temperature,
                maxTokens = effectiveMaxTokensForTarget(
                    prefs.maxTokens,
                    BuildConfig.SDX_TARGET_PROFILE
                ),
                preparationOptions = prefs.modelPreparationOptions,
                onPreparationStage = ::publishModelPreparationStage,
            )
        } catch (failure: Throwable) {
            val message = failure.message ?: "The selected model could not open on this accelerator."
            publishModelStartupFailure(modelFilePath, message, failure)
            Log.e(TAG, "Local accelerator model failed to start", failure)
            return
        }

        val route = newLocal.routeName
        localModel = newLocal
        _modelState.value = ModelUiState.Ready(modelFilePath, route)
        _modelLoadProgress.value = null
        _activeRoute.value = route
        _graphState.value = GraphUiState.Checking

        val graphPath = prefs.kgraphPath.takeIf(String::isNotBlank)
        val newBridge = try {
            buildBridge()
        } catch (failure: Exception) {
            val message = "Native graph runtime unavailable: " +
                (failure.message ?: failure.javaClass.simpleName)
            localModel = null
            try {
                newLocal.close()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            try {
                recordImportDiagnostic(
                    "knowledge graph",
                    "activation",
                    ImportDiagnosticSeverity.ERROR,
                    message,
                    "Expand and copy the full stack trace, then verify the graph runtime packaged for this APK.",
                    failure = failure
                )
            } catch (persistenceFailure: RuntimeException) {
                failure.addSuppressed(persistenceFailure)
            }
            _graphState.value = GraphUiState.Failed(
                graphPath,
                message,
                failure.stackTraceToString()
            )
            Log.e(TAG, "Native AOT graph runtime failed to start", failure)
            return
        }

        bridge = newBridge
        _graphState.value = GraphUiState.Ready(graphPath)
        try {
            engine = ChatEngine(
                InferenceRouter(newLocal, null),
                newBridge,
                prefs.maxToolRounds,
                ChatEngine.ToolRouting.RELEVANT
            )
        } catch (failure: Exception) {
            val message = "Chat engine initialization failed: " +
                (failure.message ?: failure.javaClass.simpleName)
            engine = null
            bridge = null
            localModel = null
            try {
                newBridge.close()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            try {
                newLocal.close()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            try {
                recordImportDiagnostic(
                    "local chat",
                    "engine initialization",
                    ImportDiagnosticSeverity.ERROR,
                    message,
                    "Expand and copy the full stack trace, then reopen the selected model and graph.",
                    failure = failure
                )
            } catch (persistenceFailure: RuntimeException) {
                failure.addSuppressed(persistenceFailure)
            }
            _graphState.value = GraphUiState.Failed(
                graphPath,
                message,
                failure.stackTraceToString()
            )
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
                _errorStackTrace.value = failure.stackTraceToString()
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
        operation: ImportOperationKind,
        blocked: (String) -> T,
        body: suspend () -> T
    ): T {
        require(operation.isBusy) { "An exclusive import must identify its operation." }
        if (!importGate.compareAndSet(false, true)) {
            return blocked(importBlockedReason(importBusy = true, generating = false)!!)
        }
        _importOperation.value = operation
        try {
            importBlockedReason(
                importBusy = false,
                generating = _thinking.value,
                activeModelLoaded = operation.requiresUnloadedModel &&
                    _modelState.value is ModelUiState.Ready
            )?.let {
                return blocked(it)
            }
            return body()
        } finally {
            _importOperation.value = ImportOperationKind.NONE
            importGate.set(false)
        }
    }

    /**
     * Copy a file chosen via the SAF file picker into filesDir/graphs/ and
     * update the kgraph preference. Caller should then call [onSettingsChanged].
     */
    suspend fun importKgraph(uri: Uri): String = withContext(Dispatchers.IO) {
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
    }

    /** Import and select a graph; activation is explicitly deferred without a model. */
    suspend fun importKgraphAndApply(uri: Uri): GraphImportOutcome = runExclusiveImport(
        operation = ImportOperationKind.GRAPH,
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
        val imported = try {
            importKgraph(uri)
        } catch (failure: Exception) {
            val message = failure.message ?: "Graph import failed. Select a valid .kgraph file."
            try {
                recordImportDiagnostic(
                    "graph",
                    "copy and validation",
                    ImportDiagnosticSeverity.ERROR,
                    message,
                    "Expand and copy the full stack trace, then select a valid exported .kgraph file and retry.",
                    failure = failure
                )
            } catch (persistenceFailure: RuntimeException) {
                failure.addSuppressed(persistenceFailure)
            }
            Log.e(TAG, "Graph copy or validation failed", failure)
            return@runExclusiveImport GraphImportOutcome.Failed(
                message,
                failure.stackTraceToString()
            )
        }
        val activationOutcome = try {
            rebuildEngine(resetConversation = true)
            graphImportOutcome(imported, _graphState.value)
        } catch (failure: Exception) {
            GraphImportOutcome.Failed(
                failure.message ?: "Graph activation failed.",
                failure.stackTraceToString()
            )
        }
        val finalOutcome = if (activationOutcome is GraphImportOutcome.Failed) {
            var message = activationOutcome.message
            var stackTrace = activationOutcome.stackTrace
            fun appendRollbackFailure(label: String, failure: Throwable) {
                message += " $label: ${failure.message ?: failure.javaClass.name}."
                stackTrace += "\n\n$label:\n${failure.stackTraceToString()}"
            }
            if (imported != previousSelection.graphPath) {
                try {
                    val importedFile = File(imported)
                    check(!importedFile.exists() || importedFile.delete()) {
                        "Could not remove the rejected graph at ${importedFile.absolutePath}."
                    }
                } catch (cleanupFailure: RuntimeException) {
                    appendRollbackFailure("Rejected graph cleanup failed", cleanupFailure)
                }
            }
            if (!prefs.activateProject(previousSelection)) {
                appendRollbackFailure(
                    "Previous selection restore failed",
                    IllegalStateException("Android could not restore the previous selection.")
                )
            }
            try {
                rebuildEngine(resetConversation = false)
            } catch (restoreFailure: Exception) {
                appendRollbackFailure("Previous runtime restore failed", restoreFailure)
            }
            GraphImportOutcome.Failed(
                "$message The previous selection remains authoritative.",
                stackTrace
            )
        } else {
            activationOutcome
        }
        try {
            when (finalOutcome) {
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
                    finalOutcome.message,
                    "Import a complete .sdz model to activate graph reasoning."
                )
                is GraphImportOutcome.Failed -> recordImportDiagnostic(
                    "graph",
                    "activation",
                    ImportDiagnosticSeverity.ERROR,
                    finalOutcome.message,
                    "Expand and copy the full stack trace, then verify the graph export and native runtime before retrying.",
                    technicalDetails = finalOutcome.stackTrace
                )
            }
            finalOutcome
        } catch (persistenceFailure: RuntimeException) {
            val precedingStack = (finalOutcome as? GraphImportOutcome.Failed)?.stackTrace
            GraphImportOutcome.Failed(
                "${(finalOutcome as? GraphImportOutcome.Failed)?.message ?: "Graph import completed"} " +
                    "Diagnostic persistence failed: ${persistenceFailure.message ?: persistenceFailure.javaClass.name}.",
                buildString {
                    precedingStack?.let { append(it).append("\n\n") }
                    append("Diagnostic persistence failure:\n")
                    append(persistenceFailure.stackTraceToString())
                }
            )
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
            MobileModelArtifactResolver.resolveWithOwnJournal(
                context,
                imported.absolutePath
            )
            return imported
        } catch (failure: Exception) {
            try {
                check(!imported.exists() || imported.delete()) {
                    "Could not remove the model that failed artifact validation at ${imported.absolutePath}."
                }
            } catch (cleanupFailure: RuntimeException) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    /**
     * Import, run a real bounded decode, and atomically activate a SameDiff model.
     * The previous model remains authoritative if any asset, provider, template,
     * tokenizer, graph, or decode check fails.
     */
    suspend fun importModelAndActivate(uri: Uri): Result<String> = runExclusiveImport(
        operation = ImportOperationKind.MODEL_ARCHIVE,
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
                imported?.let { candidate ->
                    try {
                        check(!candidate.exists() || candidate.delete()) {
                            "Could not remove the rejected model import at ${candidate.absolutePath}."
                        }
                    } catch (cleanupFailure: RuntimeException) {
                        failure.addSuppressed(cleanupFailure)
                    }
                }
                try {
                    recordImportDiagnostic(
                        "model",
                        phase,
                        ImportDiagnosticSeverity.ERROR,
                        failure.message ?: "Complete SDZ import failed.",
                        "Expand and copy the full stack trace. Verify the SDZ contains tokenizer, chat template, text-generation contract, and this APK's target before retrying.",
                        failure = failure
                    )
                } catch (persistenceFailure: RuntimeException) {
                    failure.addSuppressed(persistenceFailure)
                }
                Log.e(TAG, "Complete SDZ import failed", failure)
                Result.failure(failure)
            }
        }
    }

    /**
     * Install one canonical project archive and publish it only after this APK's model,
     * graph AOT runtime, and chat engine all open successfully.
     */
    suspend fun importProjectAndActivate(uri: Uri): ProjectImportOutcome = runExclusiveImport(
        operation = ImportOperationKind.PROJECT_ARCHIVE,
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
                MobileModelArtifactResolver.resolveWithOwnJournal(
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
                val active = ProjectImportOutcome.Active(
                    projectId = candidateProject.projectId(),
                    projectName = candidateProject.projectName(),
                    revision = candidateProject.revision(),
                    modelPath = candidateProject.modelPath().toString(),
                    graphPath = candidateProject.graphPath().toString(),
                    sourcesPath = candidateProject.sourcesRoot().toString(),
                    sourceCount = candidateProject.sourcePaths().size
                )
                phase = ProjectImportPhase.CLEANUP
                importedArchive?.let { archive ->
                    check(!archive.exists() || archive.delete()) {
                        "Could not remove the temporary project archive at ${archive.absolutePath}."
                    }
                }
                recordImportDiagnostic(
                    "project",
                    ProjectImportPhase.ACTIVATION.label,
                    ImportDiagnosticSeverity.SUCCESS,
                    "Project model, graph, Markdown sources, and accelerator decode are active.",
                    "Return to Chat to use the synchronized knowledge project."
                )
                active
            } catch (failure: Exception) {
                importedArchive?.let { archive ->
                    try {
                        check(!archive.exists() || archive.delete()) {
                            "Could not remove the rejected project archive at ${archive.absolutePath}."
                        }
                    } catch (cleanupFailure: RuntimeException) {
                        failure.addSuppressed(cleanupFailure)
                    }
                }
                if (!activated) {
                    try {
                        installed?.delete()
                    } catch (cleanupFailure: Throwable) {
                        failure.addSuppressed(cleanupFailure)
                    }
                }
                try {
                    recordImportDiagnostic(
                        "project",
                        phase.label,
                        ImportDiagnosticSeverity.ERROR,
                        failure.message ?: failure.javaClass.simpleName,
                        "Expand and copy the full stack trace. Review this phase, verify the prepared project and matching APK target, then retry.",
                        failure = failure
                    )
                } catch (persistenceFailure: RuntimeException) {
                    failure.addSuppressed(persistenceFailure)
                }
                Log.e(TAG, "Project import failed during $phase", failure)
                ProjectImportOutcome.Failed(
                    phase,
                    failure.message ?: failure.javaClass.simpleName,
                    failure.stackTraceToString()
                )
            }
        }
    }

    private fun clearHuggingFacePreparedImport(deleteAbandonedUnpinnedBytes: Boolean) {
        val prepared = huggingFacePreparedImport ?: return
        huggingFacePreparedImport = null
        if (!deleteAbandonedUnpinnedBytes || prepared.candidate.isCommitPinned) return
        val destination = prepared.preflight.finalFile.toPath().toAbsolutePath().normalize()
        val active = prefs.modelPath.takeIf(String::isNotBlank)
            ?.let { File(it).toPath().toAbsolutePath().normalize() }
        val disposablePaths = buildList {
            if (destination != active) add(destination)
            add(destination.resolveSibling(destination.fileName.toString() + ".partial"))
            add(destination.resolveSibling(destination.fileName.toString() + ".partial.metadata"))
        }
        disposablePaths.forEach { path ->
            Files.deleteIfExists(path)
        }
    }

    private fun clearHuggingFaceImportCheckpoint(): Boolean {
        if (huggingFaceCheckpoint == null) return true
        if (!prefs.clearHuggingFaceImportCheckpoint()) return false
        huggingFaceCheckpoint = null
        return true
    }

    private fun persistHuggingFaceImportCheckpoint(
        candidate: HuggingFaceGgmlResolver.Candidate,
        stage: HuggingFaceImportCheckpointStage
    ) {
        val discovery = _huggingFaceDiscovery.value ?: return
        val created = HuggingFaceImportCheckpoint.createOrNull(
            rawReference = _huggingFaceReference.value,
            discovery = discovery,
            candidate = candidate,
            stage = stage
        )
        if (created == null) {
            check(clearHuggingFaceImportCheckpoint()) {
                "Android could not clear the obsolete Hugging Face import checkpoint."
            }
            return
        }
        val existing = huggingFaceCheckpoint
        val target = if (
            existing?.stage == HuggingFaceImportCheckpointStage.VERIFIED_DOWNLOAD &&
            existing.matchingCandidate(discovery)?.let { resolved ->
                resolved.path == candidate.path && resolved.downloadUri == candidate.downloadUri
            } == true
        ) {
            existing
        } else {
            created
        }
        if (target != existing) {
            check(prefs.saveHuggingFaceImportCheckpoint(target)) {
                "Android could not save the Hugging Face import checkpoint."
            }
        }
        huggingFaceCheckpoint = target
    }

    private fun persistHuggingFaceObservation(progress: HuggingFaceImportProgress) {
        val checkpoint = huggingFaceCheckpoint ?: return
        val observed = checkpoint.withObservation(
            step = progress.step.name,
            message = progress.message,
            attempt = progress.attempt,
            maxAttempts = progress.maxAttempts,
            resumedBytes = progress.resumedBytes,
            completedBytes = progress.completedBytes,
            totalBytes = progress.totalBytes,
            retryWillResumeOrReuse = progress.retryWillResumeOrReuse
        )
        if (observed == checkpoint) return
        check(prefs.saveHuggingFaceImportCheckpoint(observed)) {
            "Android could not save the current Hugging Face import stage."
        }
        huggingFaceCheckpoint = observed
    }

    private fun persistCurrentHuggingFaceObservation() {
        val progress = (_huggingFaceImportState.value as? HuggingFaceImportUiState.Observable)
            ?.progress ?: return
        persistHuggingFaceObservation(progress)
    }

    fun updateHuggingFaceReference(rawReference: String) {
        if (huggingFaceJob?.isActive == true) return
        if (rawReference != _huggingFaceReference.value) {
            try {
                clearHuggingFacePreparedImport(deleteAbandonedUnpinnedBytes = true)
            } catch (failure: Exception) {
                publishHuggingFaceFailure(
                    progress = huggingFaceProgress(
                        HuggingFaceImportStep.PREFLIGHT,
                        failure.message ?: failure.javaClass.name
                    ),
                    remediation = "Copy the full error, check this APK's private model storage, then retry changing the reference.",
                    failure = failure,
                    phase = "abandoned cache cleanup"
                )
                return
            }
            if (!clearHuggingFaceImportCheckpoint()) {
                val detail = "Android could not clear the previous import checkpoint."
                publishHuggingFaceFailure(
                    progress = huggingFaceProgress(HuggingFaceImportStep.PREFLIGHT, detail),
                    summary = detail,
                    remediation = "Retry after checking this APK's app storage.",
                    failure = IllegalStateException(detail)
                )
                return
            }
        }
        _huggingFaceReference.value = rawReference
        prefs.huggingFaceReference = rawReference
        _huggingFaceDiscovery.value = null
        _huggingFaceSelection.value = null
        _huggingFaceConfigurationState.value = HuggingFaceConfigurationUiState.Idle
        if (_huggingFaceImportState.value !is HuggingFaceImportUiState.Active) {
            _huggingFaceImportState.value = HuggingFaceImportUiState.Idle
        }
    }

    private fun resolveHuggingFaceRepositoryConfiguration(
        discovery: HuggingFaceGgmlResolver.Discovery
    ): HuggingFaceGgmlAcquisition.ResolvedRepositoryConfiguration {
        val repository = discovery.reference.repository
        _huggingFaceConfigurationState.value =
            HuggingFaceConfigurationUiState.Resolving(repository)
        return try {
            HuggingFaceGgmlAcquisition.resolveRepositoryConfiguration(discovery).also { resolved ->
                _huggingFaceConfigurationState.value =
                    HuggingFaceConfigurationUiState.Resolved(resolved)
            }
        } catch (failure: Exception) {
            _huggingFaceConfigurationState.value = HuggingFaceConfigurationUiState.Failed(
                repository = repository,
                message = failure.message ?: failure.javaClass.name
            )
            throw failure
        }
    }

    fun selectHuggingFaceCandidate(candidate: HuggingFaceGgmlResolver.Candidate) {
        if (huggingFaceJob?.isActive == true) return
        val discovery = _huggingFaceDiscovery.value
            ?: throw IllegalStateException("Resolve the Hugging Face repository first.")
        require(discovery.candidates.any {
            it.path == candidate.path && it.downloadUri == candidate.downloadUri
        }) { "The selected model is not part of the current repository resolution." }
        if (_huggingFaceSelection.value != candidate) {
            try {
                clearHuggingFacePreparedImport(deleteAbandonedUnpinnedBytes = true)
            } catch (failure: Exception) {
                publishHuggingFaceFailure(
                    progress = huggingFaceProgress(
                        HuggingFaceImportStep.PREFLIGHT,
                        failure.message ?: failure.javaClass.name
                    ),
                    remediation = "Copy the full error, check this APK's private model storage, then select the model again.",
                    failure = failure,
                    phase = "abandoned cache cleanup"
                )
                return
            }
            check(clearHuggingFaceImportCheckpoint()) {
                "Android could not clear the previous import checkpoint."
            }
        }
        _huggingFaceSelection.value = candidate
    }

    /** Resolve the current public reference and automatically import only an unambiguous file. */
    fun startHuggingFaceResolution(): Boolean = launchHuggingFaceOperation {
        val rawReference = _huggingFaceReference.value.trim()
        require(rawReference.isNotEmpty()) { "Enter a Hugging Face repository or GGUF/GGML URL." }
        clearHuggingFacePreparedImport(deleteAbandonedUnpinnedBytes = true)
        check(clearHuggingFaceImportCheckpoint()) {
            "Android could not clear the previous import checkpoint."
        }
        prefs.huggingFaceReference = rawReference
        _huggingFaceDiscovery.value = null
        _huggingFaceSelection.value = null
        _huggingFaceConfigurationState.value = HuggingFaceConfigurationUiState.Idle
        val discovery = discoverHuggingFaceAcquisitionExclusively(rawReference)
        _huggingFaceDiscovery.value = discovery
        resolveHuggingFaceRepositoryConfiguration(discovery)
        if (discovery.requiresSelection()) {
            _huggingFaceImportState.value = HuggingFaceImportUiState.SelectionRequired(
                discovery.reference.repository,
                discovery.candidates.size
            )
        } else {
            val candidate = discovery.selectedCandidate().orElseThrow()
            _huggingFaceSelection.value = candidate
            importHuggingFaceModelAndActivate(candidate)
        }
    }

    fun startSelectedHuggingFaceImport(): Boolean {
        if (huggingFaceJob?.isActive == true) return false
        val candidate = _huggingFaceSelection.value ?: return false
        return launchHuggingFaceOperation {
            val resolved = _huggingFaceConfigurationState.value
                as? HuggingFaceConfigurationUiState.Resolved
                ?: throw IllegalStateException(
                    "Resolve the repository tokenizer/configuration before importing a model."
                )
            require(candidate.path in resolved.configuration.modelCandidatePaths) {
                "The selected model is not part of the resolved repository configuration."
            }
            clearHuggingFacePreparedImport(deleteAbandonedUnpinnedBytes = true)
            importHuggingFaceModelAndActivate(candidate, null)
        }
    }

    private fun resumeHuggingFaceImport(
        checkpoint: HuggingFaceImportCheckpoint
    ): Boolean = launchHuggingFaceOperation {
        clearHuggingFacePreparedImport(deleteAbandonedUnpinnedBytes = false)
        _huggingFaceReference.value = checkpoint.rawReference
        prefs.huggingFaceReference = checkpoint.rawReference
        _huggingFaceDiscovery.value = null
        _huggingFaceSelection.value = null
        _huggingFaceConfigurationState.value = HuggingFaceConfigurationUiState.Idle

        val discovery = discoverHuggingFaceAcquisitionExclusively(checkpoint.rawReference)
        _huggingFaceDiscovery.value = discovery
        resolveHuggingFaceRepositoryConfiguration(discovery)
        val candidate = checkpoint.matchingCandidate(discovery)
        if (candidate == null) {
            check(clearHuggingFaceImportCheckpoint()) {
                "Android could not discard an invalid Hugging Face import checkpoint."
            }
            val detail = "The saved immutable model no longer matches repository resolution. " +
                "Resolve and select the model again."
            publishHuggingFaceFailure(
                progress = huggingFaceProgress(HuggingFaceImportStep.RESOLVE, detail),
                summary = detail,
                remediation = "Resolve the repository again and explicitly select the intended immutable model.",
                failure = IllegalStateException(detail)
            )
            return@launchHuggingFaceOperation
        }
        _huggingFaceSelection.value = candidate
        importHuggingFaceModelAndActivate(candidate, null)
    }

    /** A retry button belongs to one visible phase and cannot accidentally retry stale UI state. */
    fun retryHuggingFaceStep(step: HuggingFaceImportStep): Boolean {
        val state = _huggingFaceImportState.value as? HuggingFaceImportUiState.Observable
            ?: return false
        if (state.step != step || !isHuggingFaceTerminal(state)) return false
        return retryHuggingFaceFailedStep()
    }

    fun retryHuggingFaceFailedStep(): Boolean {
        val state = _huggingFaceImportState.value
        if (state is HuggingFaceImportUiState.Interrupted) {
            val checkpoint = huggingFaceCheckpoint ?: return false
            return resumeHuggingFaceImport(checkpoint)
        }
        if (state !is HuggingFaceImportUiState.Failed &&
            state !is HuggingFaceImportUiState.Cancelled) return false
        val step = state.step
        val selected = _huggingFaceSelection.value
        val prepared = huggingFacePreparedImport?.takeIf { it.candidate == selected }
        if (selected == null) {
            huggingFaceCheckpoint?.let { checkpoint ->
                return resumeHuggingFaceImport(checkpoint)
            }
        }
        return when (huggingFaceRetryAction(step, selected != null, prepared != null)) {
            HuggingFaceRetryAction.RESOLVE_REFERENCE -> startHuggingFaceResolution()
            HuggingFaceRetryAction.IMPORT_SELECTED -> startSelectedHuggingFaceImport()
            HuggingFaceRetryAction.RETRY_PREPARED_IMPORT -> {
                prepared ?: return false
                launchHuggingFaceOperation {
                    importHuggingFaceModelAndActivate(prepared.candidate, prepared)
                }
            }
        }
    }

    /** A cancel button belongs to one visible phase and cannot cancel a later phase by mistake. */
    fun cancelHuggingFaceStep(step: HuggingFaceImportStep): Boolean {
        val state = _huggingFaceImportState.value as? HuggingFaceImportUiState.Observable
            ?: return false
        if (state.step != step) return false
        return cancelHuggingFaceImport()
    }

    /** Network transfer is interruptible; blocking native load/decode remains transactional. */
    fun cancelHuggingFaceImport(): Boolean {
        val state = _huggingFaceImportState.value as? HuggingFaceImportUiState.Observable
            ?: return false
        if (state.step.ordinal > HuggingFaceImportStep.TOKENIZER_ASSETS.ordinal) return false
        huggingFaceDownloadCancellation?.cancel()
        huggingFaceJob?.cancel(CancellationException("Hugging Face import cancelled by user"))
        return true
    }

    /** Open Android's exact per-package storage screen for the installed APK flavor. */
    fun openAppStorageSettings() {
        try {
            val packageUri = Uri.fromParts("package", context.packageName, null)
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (failure: RuntimeException) {
            val summary = "Android could not open this APK's storage settings: " +
                (failure.message ?: failure.javaClass.name)
            val previous = _huggingFaceImportState.value as? HuggingFaceImportUiState.Observable
            val progress = previous?.progress?.copy(message = summary)
                ?: huggingFaceProgress(HuggingFaceImportStep.PREFLIGHT, summary)
            publishHuggingFaceFailure(
                progress = progress,
                summary = summary,
                remediation = "Expand and copy the full stack trace, then open App info from Android Settings manually.",
                failure = failure,
                phase = "open app storage settings"
            )
        }
    }

    private fun launchHuggingFaceOperation(block: suspend () -> Unit): Boolean {
        if (huggingFaceJob?.isActive == true) return false
        lastHuggingFaceProgressDiagnosticKey = null
        val failureHandler = CoroutineExceptionHandler { _, failure ->
            val previous = _huggingFaceImportState.value as? HuggingFaceImportUiState.Observable
            val summary = if (failure is SocketTimeoutException) {
                "Transfer timed out: ${failure.message ?: "no network data arrived"}. " +
                    HuggingFaceGgmlAcquisition.TRANSFER_POLICY_SUMMARY
            } else {
                failure.message?.takeIf(String::isNotBlank)
                    ?: "${failure.javaClass.name} failed without an error message"
            }
            val progress = previous?.progress ?: huggingFaceProgress(
                HuggingFaceImportStep.RESOLVE,
                summary
            )
            publishHuggingFaceFailure(
                progress = progress.copy(message = summary),
                summary = summary,
                remediation = huggingFaceStepResumeBehavior(progress.step) +
                    " The complete exception, causes, suppressed cleanup failures, and verification evidence are available in this failed step.",
                failure = failure
            )
            Log.e(TAG, "Hugging Face operation failed during ${progress.step}", failure)
        }
        val job = viewModelScope.launch(failureHandler, start = CoroutineStart.LAZY) {
            var notificationStarted = false
            var notificationProgressJob: Job? = null
            try {
                HuggingFaceImportForegroundService.start(context, null)
                notificationStarted = true
                notificationProgressJob = launch {
                    _huggingFaceImportState.collect { state ->
                        (state as? HuggingFaceImportUiState.Observable)?.progress?.let(
                            HuggingFaceImportForegroundService::publish
                        )
                    }
                }
                block()
            } finally {
                notificationProgressJob?.cancel()
                if (notificationStarted) {
                    HuggingFaceImportForegroundService.stop(context)
                }
            }
        }
        huggingFaceJob = job
        job.invokeOnCompletion { completion ->
            if (completion is CancellationException) {
                val previous = _huggingFaceImportState.value as? HuggingFaceImportUiState.Observable
                val progress = previous?.progress ?: huggingFaceProgress(
                    HuggingFaceImportStep.RESOLVE,
                    "Hugging Face import cancelled"
                )
                _huggingFaceImportState.value = HuggingFaceImportUiState.Cancelled(
                    progress.copy(message = "${progress.step.label} cancelled")
                )
                recordImportDiagnostic(
                    "hugging face model",
                    progress.step.label.lowercase(),
                    ImportDiagnosticSeverity.INFO,
                    "${progress.step.label} cancelled by the user.",
                    "Resume this exact step; validator-backed saved bytes remain eligible for reuse."
                )
            }
            huggingFaceDownloadCancellation = null
            if (huggingFaceJob === job) huggingFaceJob = null
        }
        job.start()
        return true
    }

    private fun huggingFaceProgress(
        step: HuggingFaceImportStep,
        message: String,
        attempt: Int = 1,
        maxAttempts: Int = HuggingFaceGgmlAcquisition.DEFAULT_MAX_ATTEMPTS,
        resumedBytes: Long = 0L,
        completedBytes: Long = 0L,
        totalBytes: Long? = null,
        bytesPerSecond: Double? = null,
        etaSeconds: Long? = null,
        retryWillResumeOrReuse: Boolean = false,
        storagePreflight: HuggingFaceStoragePreflight? = huggingFacePreparedImport?.preflight?.storage
    ) = HuggingFaceImportProgress(
        step = step,
        message = message,
        attempt = attempt,
        maxAttempts = maxAttempts,
        resumedBytes = resumedBytes,
        completedBytes = completedBytes,
        totalBytes = totalBytes,
        smoothedBytesPerSecond = bytesPerSecond,
        etaSeconds = etaSeconds,
        retryWillResumeOrReuse = retryWillResumeOrReuse,
        storagePreflight = storagePreflight
    )

    private fun publishHuggingFaceDownloadProgress(
        progress: HuggingFaceGgmlAcquisition.DownloadProgress,
        forcedStep: HuggingFaceImportStep? = null
    ) {
        val step = forcedStep ?: huggingFaceDownloadStep(progress.event)
        val retryDelay = progress.retryDelayMillis?.let { delay ->
            " Retrying in ${kotlin.math.ceil(delay / 1000.0).toLong()}s."
        }.orEmpty()
        val message = if (forcedStep != null) {
            progress.message
        } else when (progress.event) {
            HuggingFaceGgmlAcquisition.DownloadEvent.CONNECT ->
                "Connecting to Hugging Face for ${progress.safeFilename} " +
                    "(${HuggingFaceGgmlAcquisition.CONNECT_TIMEOUT_SECONDS}s timeout)"
            HuggingFaceGgmlAcquisition.DownloadEvent.RESUME ->
                "Resuming ${progress.safeFilename} from the verified partial"
            HuggingFaceGgmlAcquisition.DownloadEvent.DOWNLOAD ->
                "Downloading ${progress.safeFilename}; no-data timeout is " +
                    "${HuggingFaceGgmlAcquisition.READ_IDLE_TIMEOUT_MINUTES}m"
            HuggingFaceGgmlAcquisition.DownloadEvent.RETRY ->
                "Attempt ${progress.attempt} failed.${retryDelay} ${progress.message}"
            HuggingFaceGgmlAcquisition.DownloadEvent.VERIFY ->
                "${progress.message} · ${progress.safeFilename}"
            HuggingFaceGgmlAcquisition.DownloadEvent.COMPLETE ->
                "Verified ${progress.safeFilename} · ${progress.message}"
        }
        val snapshot = huggingFaceProgress(
            step = step,
            message = message,
            attempt = progress.attempt,
            maxAttempts = progress.maxAttempts,
            resumedBytes = progress.resumedBytes,
            completedBytes = progress.downloadedBytes,
            totalBytes = progress.expectedBytes,
            bytesPerSecond = progress.smoothedBytesPerSecond,
            etaSeconds = progress.estimatedRemainingMillis?.let { (it + 999L) / 1000L },
            retryWillResumeOrReuse = progress.retryWillResume
        )
        _huggingFaceImportState.value =
            if (progress.event == HuggingFaceGgmlAcquisition.DownloadEvent.RETRY) {
                HuggingFaceImportUiState.Retrying(snapshot)
            } else {
                HuggingFaceImportUiState.Working(snapshot)
            }
        val diagnosticKey = "${step.name}:${progress.event}:${progress.safeFilename}:${progress.attempt}"
        if (lastHuggingFaceProgressDiagnosticKey != diagnosticKey) {
            lastHuggingFaceProgressDiagnosticKey = diagnosticKey
            persistHuggingFaceObservation(snapshot)
            recordImportDiagnostic(
                "hugging face model",
                step.label.lowercase(),
                when (progress.event) {
                    HuggingFaceGgmlAcquisition.DownloadEvent.COMPLETE ->
                        ImportDiagnosticSeverity.SUCCESS
                    HuggingFaceGgmlAcquisition.DownloadEvent.RETRY ->
                        ImportDiagnosticSeverity.ERROR
                    else -> ImportDiagnosticSeverity.INFO
                },
                message,
                when (progress.event) {
                    HuggingFaceGgmlAcquisition.DownloadEvent.RETRY ->
                        "The downloader will retry automatically; manual retry reuses valid saved bytes."
                    HuggingFaceGgmlAcquisition.DownloadEvent.COMPLETE ->
                        "The verified artifact is reusable by the next import stage."
                    else -> "This event is retained in the Hugging Face import log and App Diagnostics."
                },
                technicalDetails = progress.message
            )
        }
    }

    /**
     * Resolve owner/repository, repository/tree URLs, or exact GGUF/GGML file URLs
     * directly through Hugging Face. Kompile staging is not part of this path.
     */
    private suspend fun discoverHuggingFaceAcquisitionExclusively(
        rawReference: String
    ): HuggingFaceGgmlResolver.Discovery = runExclusiveImport(
        operation = ImportOperationKind.HUGGING_FACE,
        blocked = { reason -> throw IllegalStateException(reason) }
    ) {
        discoverHuggingFaceAcquisition(rawReference)
    }

    suspend fun discoverHuggingFaceAcquisition(
        rawReference: String
    ): HuggingFaceGgmlResolver.Discovery {
        _huggingFaceImportState.value = HuggingFaceImportUiState.Working(
            huggingFaceProgress(
                HuggingFaceImportStep.RESOLVE,
                "Resolving the Hugging Face repository"
            )
        )
        val discovery = withContext(Dispatchers.IO) {
            HuggingFaceGgmlAcquisition.discover(rawReference)
        }
        _huggingFaceImportState.value = HuggingFaceImportUiState.Working(
            huggingFaceProgress(
                HuggingFaceImportStep.RESOLVE,
                "Resolved ${discovery.candidates.size} GGUF/GGML candidate(s)"
            )
        )
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
        return discovery
    }

    private fun prepareHuggingFaceImport(
        candidate: HuggingFaceGgmlResolver.Candidate,
        activeModelPath: String
    ): HuggingFaceImportPreflight {
        val modelsDir = File(context.filesDir, "models/hugging-face").apply { mkdirs() }
        require(modelsDir.isDirectory) {
            "Android could not create app-owned model storage."
        }

        val sourceName = HuggingFaceGgmlAcquisition.safeFilename(candidate)
        val extension = sourceName.substringAfterLast('.', "gguf")
        val stem = sourceName.substringBeforeLast('.', sourceName)
        val expectedBytes = HuggingFaceGgmlAcquisition.expectedImportBytes(candidate)
        // This ceiling validates only the selected immutable model. Companion tokenizer/config
        // files have their own bounded requests and are included separately in storage preflight.
        val validationLimitBytes = candidate.size.takeIf { it >= 0L }
            ?.coerceAtLeast(1L) ?: Long.MAX_VALUE
        val pinnedPlan = if (candidate.isCommitPinned) {
            HuggingFaceGgmlAcquisition.planPinnedDownload(
                candidate = candidate,
                directory = modelsDir.toPath(),
                activeModelPath = activeModelPath.takeIf(String::isNotBlank)?.let {
                    File(it).toPath()
                },
                maxBytes = validationLimitBytes
            )
        } else {
            null
        }
        val finalFile = pinnedPlan?.finalPath?.toFile() ?: File(
            modelsDir,
            "$stem-${System.currentTimeMillis()}.$extension"
        )
        val reusableDownload = pinnedPlan?.reusableDownload
        val obsoleteCacheFiles = pinnedPlan?.obsoletePathsAfterActivation
            ?.map { it.toFile() }
            .orEmpty()
        val reusableModelBytes = when {
            reusableDownload != null -> reusableDownload.downloadedBytes
            candidate.isCommitPinned -> HuggingFaceGgmlAcquisition.resumablePartialBytes(
                candidate,
                finalFile.toPath(),
                validationLimitBytes
            )
            else -> 0L
        }
        val reusableTokenizerBytes = HuggingFaceGgmlAcquisition.reusableTokenizerAssetBytes(
            candidate,
            finalFile.toPath()
        )
        val resumableBytes = if (Long.MAX_VALUE - reusableModelBytes < reusableTokenizerBytes) {
            Long.MAX_VALUE
        } else {
            reusableModelBytes + reusableTokenizerBytes
        }
        val reuse = when {
            reusableDownload != null -> HuggingFaceStorageReuse.VERIFIED_MODEL
            resumableBytes > 0L -> HuggingFaceStorageReuse.VALIDATED_PARTIAL
            else -> HuggingFaceStorageReuse.NONE
        }
        // usableSpace already excludes the partial's allocated blocks. Only the remaining bytes
        // must fit after the explicit safety reserve.
        val storage = huggingFaceStoragePreflight(
            applicationId = context.packageName,
            destinationPath = finalFile.absolutePath,
            expectedBytes = expectedBytes,
            usableBytes = modelsDir.usableSpace,
            reserveBytes = DOWNLOAD_SPACE_RESERVE_BYTES,
            reusableBytes = resumableBytes,
            reuse = reuse
        )
        return HuggingFaceImportPreflight(
            sourceName = sourceName,
            finalFile = finalFile,
            reusableDownload = reusableDownload,
            obsoleteCacheFilesAfterActivation = obsoleteCacheFiles,
            storage = storage
        )
    }

    /** Re-run storage arithmetic for the same destination without discarding retry bytes. */
    private fun refreshHuggingFaceImportPreflight(
        candidate: HuggingFaceGgmlResolver.Candidate,
        preflight: HuggingFaceImportPreflight,
        verifiedDownload: HuggingFaceGgmlAcquisition.DownloadMetadata?
    ): HuggingFaceImportPreflight {
        val modelsDir = preflight.finalFile.parentFile
            ?: throw IllegalStateException("The model destination has no app-owned parent directory.")
        modelsDir.mkdirs()
        require(modelsDir.isDirectory) {
            "Android could not reopen app-owned model storage."
        }
        val expectedBytes = HuggingFaceGgmlAcquisition.expectedImportBytes(candidate)
        val validationLimitBytes = candidate.size.takeIf { it >= 0L }
            ?.coerceAtLeast(1L) ?: Long.MAX_VALUE
        val reusableModelBytes = when {
            verifiedDownload != null -> verifiedDownload.downloadedBytes
            candidate.isCommitPinned -> HuggingFaceGgmlAcquisition.resumablePartialBytes(
                candidate,
                preflight.finalFile.toPath(),
                validationLimitBytes
            )
            else -> 0L
        }
        val reusableTokenizerBytes = HuggingFaceGgmlAcquisition.reusableTokenizerAssetBytes(
            candidate,
            preflight.finalFile.toPath()
        )
        val resumableBytes = if (Long.MAX_VALUE - reusableModelBytes < reusableTokenizerBytes) {
            Long.MAX_VALUE
        } else {
            reusableModelBytes + reusableTokenizerBytes
        }
        val reuse = when {
            verifiedDownload != null -> HuggingFaceStorageReuse.VERIFIED_MODEL
            resumableBytes > 0L -> HuggingFaceStorageReuse.VALIDATED_PARTIAL
            else -> HuggingFaceStorageReuse.NONE
        }
        return preflight.copy(
            storage = huggingFaceStoragePreflight(
                applicationId = context.packageName,
                destinationPath = preflight.finalFile.absolutePath,
                expectedBytes = expectedBytes,
                usableBytes = modelsDir.usableSpace,
                reserveBytes = DOWNLOAD_SPACE_RESERVE_BYTES,
                reusableBytes = resumableBytes,
                reuse = reuse
            )
        )
    }


    suspend fun optimizeLocalModel(uri: Uri): Result<String> = runExclusiveImport(
        operation = ImportOperationKind.LOCAL_MODEL_OPTIMIZATION,
        blocked = { reason -> Result.failure(IllegalStateException(reason)) }
    ) {
        withContext(Dispatchers.IO) {
            val options = _modelPreparationOptions.value
            val rawName = File(resolveFileName(uri) ?: "local-model.gguf").name
            var progress = localOptimizationProgress(
                HuggingFaceImportStep.PREFLIGHT,
                "Checking the selected local model and ${options.weightOptimization.label} profile"
            )
            _localModelOptimizationState.value = HuggingFaceImportUiState.Working(progress)
            try {
                require(rawName.substringAfterLast('.', "").lowercase() in setOf("gguf", "ggml")) {
                    "Local optimization requires a .gguf or .ggml source model."
                }
                val sourceDirectory = File(context.filesDir, "models/local-sources").apply { mkdirs() }
                require(sourceDirectory.isDirectory) {
                    "Android could not create retained local model storage."
                }
                val retainedName = rawName.substringBeforeLast('.', rawName) +
                    "-" + System.currentTimeMillis() + "." + rawName.substringAfterLast('.').lowercase()
                progress = localOptimizationProgress(
                    HuggingFaceImportStep.VERIFY,
                    "Copying and verifying $rawName into app-private retained model storage"
                )
                _localModelOptimizationState.value = HuggingFaceImportUiState.Working(progress)
                val retained = copyForActivation(uri, sourceDirectory, retainedName)
                refreshLocalModelSources()
                optimizeLocalModelExclusively(retained, options)
            } catch (failure: Throwable) {
                failLocalModelOptimization(
                    failure = failure,
                    progress = progress,
                    fields = mapOf(
                        "source_name" to rawName,
                        "profile_sha256" to options.profileSha256(),
                        "weight_optimization" to options.weightOptimization.name,
                        "diagnostic_mode" to options.diagnosticMode.name,
                    ),
                )
            }
        }
    }

    suspend fun optimizeLocalModel(sourcePath: String): Result<String> = runExclusiveImport(
        operation = ImportOperationKind.LOCAL_MODEL_OPTIMIZATION,
        blocked = { reason -> Result.failure(IllegalStateException(reason)) }
    ) {
        withContext(Dispatchers.IO) {
            val source = File(sourcePath)
            val options = _modelPreparationOptions.value
            val progress = localOptimizationProgress(
                HuggingFaceImportStep.PREFLIGHT,
                "Checking ${source.name} and the ${options.weightOptimization.label} preparation profile"
            )
            try {
                optimizeLocalModelExclusively(source, options)
            } catch (failure: Throwable) {
                failLocalModelOptimization(
                    failure = failure,
                    progress = progress,
                    fields = modelPreparationTraceFields(options, source),
                )
            }
        }
    }

    fun retryLocalModelOptimizationStep(step: HuggingFaceImportStep): Boolean {
        val state = _localModelOptimizationState.value as? HuggingFaceImportUiState.Failed
            ?: return false
        val sourcePath = lastLocalOptimizationPath ?: return false
        if (state.step != step || _importOperation.value.isBusy) return false
        viewModelScope.launch { optimizeLocalModel(sourcePath) }
        return true
    }

    private fun localOptimizationProgress(
        step: HuggingFaceImportStep,
        message: String,
        completedBytes: Long = 0L,
        totalBytes: Long? = null,
    ): HuggingFaceImportProgress = HuggingFaceImportProgress(
        step = step,
        message = message,
        attempt = 1,
        maxAttempts = 1,
        resumedBytes = 0L,
        completedBytes = completedBytes,
        totalBytes = totalBytes,
        smoothedBytesPerSecond = null,
        etaSeconds = null,
        retryWillResumeOrReuse = true,
        storagePreflight = null,
    )

    private fun modelPreparationTraceFields(
        options: ModelPreparationOptions,
        source: File,
    ): Map<String, Any?> = mapOf(
        "source_name" to source.name,
        "source_bytes" to source.length(),
        "profile_sha256" to options.profileSha256(),
        "weight_optimization" to options.weightOptimization.name,
        "conversion_mode" to options.weightOptimization.conversionMode,
        "requantize_type" to options.weightOptimization.requantizeType,
        "kv_cache_optimization" to options.kvCacheOptimization.name,
        "tensor_batch_size" to options.tensorBatchSize,
        "use_memory_mapping" to options.useMemoryMapping,
        "diagnostic_mode" to options.diagnosticMode.name,
    )

    private fun failLocalModelOptimization(
        failure: Throwable,
        progress: HuggingFaceImportProgress,
        fields: Map<String, Any?>,
    ): Result<String> {
        SmokeDecodeTraceLog(context).recordFailure(
            "local_optimization_failed",
            attemptId = null,
            failure = failure,
            fields = fields + mapOf("stage" to progress.step.name),
        )
        val diagnostic = try {
            recordImportDiagnostic(
                "local model optimization",
                progress.step.label.lowercase(),
                ImportDiagnosticSeverity.ERROR,
                failure.message ?: "Local model optimization failed.",
                "Any completed app-private source copy is retained. Copy the traces, adjust the optimization or diagnostics profile if needed, and retry.",
                failure = failure,
                technicalDetails = ImportDiagnosticPolicy.failureDetails(failure) +
                    "\n" + fields.entries.joinToString(separator = "\n") { (key, value) -> "$key=$value" },
            )
        } catch (persistenceFailure: Throwable) {
            failure.addSuppressed(persistenceFailure)
            ImportDiagnosticPolicy.create(
                timestampEpochMillis = System.currentTimeMillis(),
                operation = "local model optimization",
                phase = progress.step.label.lowercase(),
                severity = ImportDiagnosticSeverity.ERROR,
                summary = failure.message ?: "Local model optimization failed.",
                remediation = "Any completed app-private source copy is retained; copy the full traces and retry.",
                technicalDetails = ImportDiagnosticPolicy.failureDetails(failure),
            )
        }
        _localModelOptimizationState.value = HuggingFaceImportUiState.Failed(
            progress = progress.copy(message = diagnostic.summary),
            diagnostic = diagnostic,
            failure = failure,
        )
        return Result.failure(failure)
    }

    private suspend fun optimizeLocalModelExclusively(
        sourceFile: File,
        options: ModelPreparationOptions,
    ): Result<String> {
        val source = sourceFile.canonicalFile
        val modelsRoot = File(context.filesDir, "models").canonicalFile
        require(source.toPath().startsWith(modelsRoot.toPath())) {
            "Local optimization only accepts models retained in this APK's private model directory."
        }
        require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "The selected local model is not a regular file: ${source.name}"
        }
        require(source.extension.lowercase() in setOf("gguf", "ggml")) {
            "Local optimization requires a .gguf or .ggml source model."
        }
        require(source.length() > 0L) { "The selected local model is empty." }

        lastLocalOptimizationPath = source.absolutePath
        val trace = SmokeDecodeTraceLog(context)
        val fields = modelPreparationTraceFields(options, source)
        var progress = localOptimizationProgress(
            HuggingFaceImportStep.PREFLIGHT,
            "Checking ${source.name} and the ${options.weightOptimization.label} preparation profile"
        )
        _localModelOptimizationState.value = HuggingFaceImportUiState.Working(progress)
        trace.record("local_optimization_started", fields = fields)
        return try {
            val availableAfterReserve =
                (source.parentFile?.usableSpace ?: 0L) - DOWNLOAD_SPACE_RESERVE_BYTES
            require(availableAfterReserve > 0L) {
                "No writable app storage remains after the ${DOWNLOAD_SPACE_RESERVE_BYTES}-byte safety reserve."
            }

            progress = localOptimizationProgress(
                HuggingFaceImportStep.VERIFY,
                "Hashing ${source.name} before conversion",
                totalBytes = source.length(),
            )
            _localModelOptimizationState.value = HuggingFaceImportUiState.Working(progress)
            val sourceSha256 = sha256(source)
            val tokenizerPath = HuggingFaceGgmlAcquisition
                .existingTokenizerJsonPath(source.toPath())
                ?.toString()
            require(tokenizerPath != null) {
                "Canonical tokenizer.json is unavailable for ${source.name}. " +
                    "Models downloaded through Hugging Face retain this verified sidecar; " +
                    "manually retained models must provide tokenizer.json beside the GGUF."
            }
            progress = progress.copy(
                message = "Verified ${source.name}; preparing ${options.weightOptimization.label}",
                completedBytes = source.length(),
            )
            _localModelOptimizationState.value = HuggingFaceImportUiState.Working(progress)
            recordImportDiagnostic(
                "local model optimization",
                "verify",
                ImportDiagnosticSeverity.SUCCESS,
                "Verified ${source.name} (${source.length()} bytes) for profile ${options.profileSha256()}.",
                "The original source is retained; conversion output and SDZ cache are content-addressed.",
                technicalDetails = fields.entries.joinToString(separator = "\n") { (key, value) ->
                    "$key=$value"
                },
            )

            val previousSelection = prefs.snapshotActiveSelection()
            val activation = engineMutex.withLock {
                activateStandaloneModelLocked(
                    modelPath = source.absolutePath,
                    previousSelection = previousSelection,
                    tokenizerPath = tokenizerPath,
                    verifiedSourceSha256 = sourceSha256,
                    verifiedSourceBytes = source.length(),
                    preparationOptions = options,
                ) { step ->
                    progress = localOptimizationProgress(
                        step,
                        when (step) {
                            HuggingFaceImportStep.CONVERT_SDZ ->
                                "Converting ${source.name} with ${options.weightOptimization.label}"
                            HuggingFaceImportStep.TARGET_CACHE ->
                                "Preparing the ${BuildConfig.SDX_TARGET_PROFILE} accelerator cache"
                            HuggingFaceImportStep.SDX_LOAD ->
                                "Loading the optimized SameDiff SDZ model"
                            HuggingFaceImportStep.SMOKE_DECODE ->
                                "Running a bounded real-token decode"
                            HuggingFaceImportStep.ACTIVATE ->
                                "Publishing the verified optimized model for chat"
                            else -> step.label
                        },
                    )
                    _localModelOptimizationState.value = HuggingFaceImportUiState.Working(progress)
                    trace.record(
                        "local_optimization_stage",
                        fields = fields + mapOf("stage" to step.name),
                    )
                    recordImportDiagnostic(
                        "local model optimization",
                        step.label.lowercase(),
                        ImportDiagnosticSeverity.INFO,
                        progress.message,
                        "The original raw model remains retained and this stage can reuse complete content-addressed outputs.",
                    )
                }
            }

            val prepared = activation.preparedModel
            val activeStorage = prepared?.modelPath ?: activation.modelPath
            _localModelOptimizationState.value = HuggingFaceImportUiState.Active(
                artifactName = prepared?.optimizedSourcePath
                    ?.takeIf(String::isNotBlank)
                    ?.let(::File)
                    ?.name
                    ?: source.name,
                route = activation.route,
                storageLocation = activeStorage,
                message = "${options.weightOptimization.label} profile loaded, smoke-decoded, and activated; original retained at ${source.absolutePath}",
            )
            recordImportDiagnostic(
                "local model optimization",
                "active",
                ImportDiagnosticSeverity.SUCCESS,
                "${source.name} optimized with ${options.weightOptimization.label} and activated on ${activation.route}.",
                "Return to Chat, or choose another profile to run a repeatable comparison from the retained source.",
                technicalDetails = buildString {
                    append(fields.entries.joinToString(separator = "\n") { (key, value) -> "$key=$value" })
                    prepared?.let {
                        append("\noptimized_source_path=").append(it.optimizedSourcePath)
                        append("\noptimized_source_bytes=").append(it.optimizedSourceBytes)
                        append("\ncanonical_sdz_path=").append(it.canonicalSdzPath)
                        append("\nactive_model_path=").append(it.modelPath)
                    }
                },
            )
            trace.record(
                "local_optimization_completed",
                fields = fields + mapOf(
                    "route" to activation.route,
                    "active_model_bytes" to (File(activeStorage).takeIf(File::isFile)?.length() ?: 0L),
                ),
            )
            refreshLocalModelSources()
            Result.success(activeStorage)
        } catch (failure: Throwable) {
            failLocalModelOptimization(failure, progress, fields)
        }
    }

    /**
     * Download one resolved public Hugging Face model into app-owned storage, load it through
     * libsdx_llm, run a bounded decode, and publish it as the active chat model transactionally.
     * A completed HTTP transfer alone is never reported as success.
     */
    suspend fun importHuggingFaceModelAndActivate(
        candidate: HuggingFaceGgmlResolver.Candidate
    ): String = importHuggingFaceModelAndActivate(candidate, null)

    private suspend fun importHuggingFaceModelAndActivate(
        candidate: HuggingFaceGgmlResolver.Candidate,
        preparedRetry: HuggingFacePreparedImport?
    ): String = runExclusiveImport(
        operation = ImportOperationKind.HUGGING_FACE,
        blocked = { reason -> throw IllegalStateException(reason) }
    ) {
        withContext(Dispatchers.IO) {
            val resolvedConfiguration = _huggingFaceConfigurationState.value
                as? HuggingFaceConfigurationUiState.Resolved
                ?: throw IllegalStateException(
                    "Resolve the repository tokenizer/configuration before importing a model."
                )
            require(candidate.path in resolvedConfiguration.configuration.modelCandidatePaths) {
                "The selected model is not part of the resolved repository configuration."
            }
            val preparationOptions = _modelPreparationOptions.value
            if (preparedRetry == null) {
                persistHuggingFaceImportCheckpoint(
                    candidate,
                    HuggingFaceImportCheckpointStage.SELECTED
                )
            }
            val previousSelection = prefs.snapshotActiveSelection()
            _huggingFaceImportState.value = HuggingFaceImportUiState.Working(
                huggingFaceProgress(
                    HuggingFaceImportStep.PREFLIGHT,
                    if (preparedRetry == null) {
                        "Calculating app-volume storage for the selected model"
                    } else {
                        "Rechecking app-volume storage without discarding saved model bytes"
                    },
                    retryWillResumeOrReuse = preparedRetry != null
                )
            )
            persistCurrentHuggingFaceObservation()
            val preflight = if (preparedRetry == null) {
                prepareHuggingFaceImport(candidate, previousSelection.modelPath)
            } else {
                refreshHuggingFaceImportPreflight(
                    candidate,
                    preparedRetry.preflight,
                    preparedRetry.verifiedDownload
                )
            }
            val sourceName = preflight.sourceName
            val finalFile = preflight.finalFile
            val storage = preflight.storage
            val expectedBytes = storage.expectedBytes
            val expectedModelBytes = candidate.size.takeIf { it >= 0L }
            val reusableDownload = preparedRetry?.verifiedDownload ?: preflight.reusableDownload
            val maxBytes = expectedModelBytes?.coerceAtLeast(1L) ?: storage.transferLimitBytes

            huggingFacePreparedImport = HuggingFacePreparedImport(
                candidate = candidate,
                preflight = preflight,
                verifiedDownload = reusableDownload
            )
            val preflightDetail = huggingFaceStoragePreflightMessage(storage)
            val preflightProgress = huggingFaceProgress(
                step = HuggingFaceImportStep.PREFLIGHT,
                message = preflightDetail,
                resumedBytes = storage.reusableBytes,
                completedBytes = storage.reusableBytes,
                totalBytes = expectedBytes,
                retryWillResumeOrReuse = storage.reusableBytes > 0L,
                storagePreflight = storage
            )
            if (!storage.canProceed) {
                val failure = IllegalStateException(preflightDetail)
                publishHuggingFaceFailure(
                    progress = preflightProgress,
                    remediation = "Free space on this APK's app volume or remove an inactive model, then retry this preflight step.",
                    failure = failure,
                    phase = "preflight"
                )
                persistHuggingFaceObservation(preflightProgress)
                throw failure
            }
            _huggingFaceImportState.value = HuggingFaceImportUiState.Working(preflightProgress)
            persistHuggingFaceObservation(preflightProgress)
            recordImportDiagnostic(
                "hugging face model",
                HuggingFaceImportStep.PREFLIGHT.label.lowercase(),
                ImportDiagnosticSeverity.SUCCESS,
                preflightDetail,
                "The destination, reusable bytes, required bytes, reserve, and available app-volume bytes were checked."
            )
            currentCoroutineContext().ensureActive()

            var phase = if (reusableDownload == null) {
                HuggingFaceImportStep.CONNECT
            } else {
                HuggingFaceImportStep.TOKENIZER_ASSETS
            }
            _huggingFaceImportState.value = HuggingFaceImportUiState.Working(
                huggingFaceProgress(
                    step = phase,
                    message = if (reusableDownload == null) {
                        "Preparing to connect for $sourceName"
                    } else {
                        "Verified model is reusable; checking its pinned tokenizer/config assets"
                    },
                    resumedBytes = if (reusableDownload == null) storage.reusableBytes else 0L,
                    completedBytes = if (reusableDownload == null) storage.reusableBytes else 0L,
                    totalBytes = expectedModelBytes.takeIf { reusableDownload == null },
                    retryWillResumeOrReuse = storage.reusableBytes > 0L,
                    storagePreflight = storage
                )
            )
            persistCurrentHuggingFaceObservation()
            recordImportDiagnostic(
                "hugging face model",
                phase.label.lowercase(),
                ImportDiagnosticSeverity.INFO,
                if (reusableDownload == null) {
                    "App-owned download started for ${candidate.path}."
                } else {
                    "Reusing the verified app-owned download for ${candidate.path}."
                },
                "Activation follows only after SDX loads and decodes the exact file."
            )

            val cancellation = HuggingFaceGgmlAcquisition.newDownloadCancellation()
                huggingFaceDownloadCancellation = cancellation
                val downloaded = reusableDownload ?: HuggingFaceGgmlAcquisition.download(
                    candidate = candidate,
                    finalPath = finalFile.toPath(),
                    maxBytes = maxBytes,
                    onProgress = { progress -> publishHuggingFaceDownloadProgress(progress) },
                    cancellation = cancellation
                )
                huggingFacePreparedImport = HuggingFacePreparedImport(
                    candidate = candidate,
                    preflight = preflight,
                    verifiedDownload = downloaded
                )
                currentCoroutineContext().ensureActive()
                val modelFile = downloaded.finalPath.toFile()
                phase = HuggingFaceImportStep.TOKENIZER_ASSETS
                _huggingFaceImportState.value = HuggingFaceImportUiState.Working(
                    huggingFaceProgress(
                        step = phase,
                        message = "Preparing ${candidate.tokenizerAssets.size} canonical Hugging Face tokenizer/config assets from the pinned configuration repository",
                        retryWillResumeOrReuse = true
                    )
                )
                persistCurrentHuggingFaceObservation()
                val tokenizerAssets = HuggingFaceGgmlAcquisition.ensureTokenizerAssets(
                    candidate = candidate,
                    modelPath = modelFile.toPath(),
                    onProgress = { progress ->
                        publishHuggingFaceDownloadProgress(
                            progress,
                            HuggingFaceImportStep.TOKENIZER_ASSETS
                        )
                    },
                    cancellation = cancellation
                )
                recordImportDiagnostic(
                    "hugging face model",
                    HuggingFaceImportStep.TOKENIZER_ASSETS.label.lowercase(),
                    ImportDiagnosticSeverity.SUCCESS,
                    "Verified ${tokenizerAssets.paths.size} canonical tokenizer/config asset(s) from the independently pinned configuration revision.",
                    "SDX now has the tokenizer, special-token, generation, configuration, and chat-template metadata available for load."
                )
                persistHuggingFaceImportCheckpoint(
                    candidate,
                    HuggingFaceImportCheckpointStage.VERIFIED_DOWNLOAD
                )
                currentCoroutineContext().ensureActive()
                phase = HuggingFaceImportStep.CONVERT_SDZ
                _huggingFaceImportState.value = HuggingFaceImportUiState.Working(
                    huggingFaceProgress(
                        step = phase,
                        message = "Preparing ${downloaded.safeFilename}: the verified GGUF is imported once, " +
                            "optimized, and cached as SDZ with ${tokenizerAssets.paths.size} tokenizer/config assets",
                        retryWillResumeOrReuse = true
                    )
                )
                persistCurrentHuggingFaceObservation()
                val preparationFields = modelPreparationTraceFields(preparationOptions, modelFile)
                SmokeDecodeTraceLog(context).record(
                    "hugging_face_preparation_profile",
                    fields = preparationFields,
                )
                recordImportDiagnostic(
                    "hugging face model",
                    "preparation profile",
                    ImportDiagnosticSeverity.INFO,
                    "Selected ${preparationOptions.weightOptimization.label}, ${preparationOptions.kvCacheOptimization.label}, " +
                        "batch ${preparationOptions.tensorBatchSize}, diagnostics ${preparationOptions.diagnosticMode.label}.",
                    "These immutable settings are included in the conversion cache key and runtime trace.",
                    technicalDetails = preparationFields.entries.joinToString(separator = "\n") { (key, value) -> "$key=$value" },
                )
                // From this visible state onward native work is deliberately non-interruptible.
                huggingFaceDownloadCancellation = null
                currentCoroutineContext().ensureActive()

                val activation = engineMutex.withLock {
                    activateStandaloneModelLocked(
                        modelFile.absolutePath,
                        previousSelection,
                        clearHuggingFaceImportOnPromotion = true,
                        tokenizerPath = tokenizerAssets.paths["tokenizer.json"]?.toString(),
                        verifiedSourceSha256 = downloaded.sha256,
                        verifiedSourceBytes = downloaded.downloadedBytes,
                        preparationOptions = preparationOptions,
                    ) { activationStep ->
                        phase = activationStep
                        _huggingFaceImportState.value = HuggingFaceImportUiState.Working(
                            huggingFaceProgress(
                                step = activationStep,
                                message = when (activationStep) {
                                    HuggingFaceImportStep.CONVERT_SDZ ->
                                        "Importing once and caching an optimized SDZ from ${downloaded.safeFilename}"
                                    HuggingFaceImportStep.TARGET_CACHE ->
                                        "Canonical SDZ is cached; preparing the strict ${BuildConfig.SDX_TARGET_PROFILE} target"
                                    HuggingFaceImportStep.SDX_LOAD ->
                                        "Compiling or restoring the google-edgetpu NNAPI plan through ${BuildConfig.SDX_TARGET_PROFILE}"
                                    HuggingFaceImportStep.SMOKE_DECODE ->
                                        "Running a bounded real-token decode"
                                    HuggingFaceImportStep.ACTIVATE ->
                                        "Publishing the verified model for chat"
                                    else -> activationStep.label
                                },
                                retryWillResumeOrReuse = true
                            )
                        )
                        persistCurrentHuggingFaceObservation()
                        val diagnosticKey = "activation:${activationStep.name}"
                        if (lastHuggingFaceProgressDiagnosticKey != diagnosticKey) {
                            lastHuggingFaceProgressDiagnosticKey = diagnosticKey
                            recordImportDiagnostic(
                                "hugging face model",
                                activationStep.label.lowercase(),
                                ImportDiagnosticSeverity.INFO,
                                when (activationStep) {
                                    HuggingFaceImportStep.CONVERT_SDZ ->
                                        "SDX is converting the verified raw model once; a complete content-addressed SDZ is reused on retry."
                                    HuggingFaceImportStep.TARGET_CACHE ->
                                        "The CPU importer has been released; SDX is reusing or preparing the strict accelerator target bundle."
                                    HuggingFaceImportStep.SDX_LOAD ->
                                        "SDX is compiling or restoring google-edgetpu segments through Android's NNAPI driver cache; Android exposes no numeric compiler percentage."
                                    HuggingFaceImportStep.SMOKE_DECODE ->
                                        "SDX is running a bounded real-token decode before activation."
                                    HuggingFaceImportStep.ACTIVATE ->
                                        "The proven model is being published transactionally for chat."
                                    else -> activationStep.label
                                },
                                "A failure here retains the previously active chat model and can reuse the verified download."
                            )
                        }
                    }
                }
                phase = HuggingFaceImportStep.ACTIVATE
                _huggingFaceImportState.value = HuggingFaceImportUiState.Working(
                    huggingFaceProgress(
                        step = phase,
                        message = "Finalizing the active model and removing obsolete cache files",
                        retryWillResumeOrReuse = true,
                        storagePreflight = preflight.storage
                    )
                )
                // The model is already transactionally promoted. Do not recreate the import
                // checkpoint here: a later chat-process death must be reported as chat execution.
                preflight.obsoleteCacheFilesAfterActivation.forEach { obsolete ->
                    val modelPath = obsolete.toPath()
                    val obsoletePaths = listOf(modelPath) +
                        HuggingFaceGgmlAcquisition.tokenizerAssetPathsForModel(modelPath)
                    obsoletePaths.forEach { path ->
                        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                                "Obsolete Hugging Face cache entry is not a regular file: $path"
                            }
                            Files.delete(path)
                        }
                    }
                }
                val preparedModel = activation.preparedModel
                val activeStorage = preparedModel?.modelPath ?: modelFile.absolutePath
                recordImportDiagnostic(
                    "hugging face model",
                    HuggingFaceImportStep.ACTIVE.label.lowercase(),
                    ImportDiagnosticSeverity.SUCCESS,
                    "${candidate.path} ${if (reusableDownload == null) "downloaded" else "reused"}, " +
                        "loaded, decoded, and activated on ${activation.route} (${activation.modelId}). " +
                        (preparedModel?.let {
                            "Source GGUF=${modelFile.absolutePath}; canonical SDZ=${it.canonicalSdzPath} " +
                                "(${it.canonicalSdzBytes} bytes); active target bundle=${it.modelPath}; " +
                                "conversionCacheHit=${it.cacheHit}."
                        } ?: "Active model=$activeStorage."),
                    "Return to Chat; execution uses the displayed active target, while the verified source remains reusable."
                )
                check(clearHuggingFaceImportCheckpoint()) {
                    "The completed Hugging Face import checkpoint could not be retired."
                }
                clearHuggingFacePreparedImport(deleteAbandonedUnpinnedBytes = false)
                _huggingFaceImportState.value = HuggingFaceImportUiState.Active(
                    artifactName = downloaded.safeFilename,
                    route = activation.route,
                    storageLocation = activeStorage,
                    message = preparedModel?.let {
                        "Loaded from cached SDZ and activated for chat; source GGUF retained at ${modelFile.absolutePath}"
                    } ?: "Loaded, smoke-decoded, and activated for chat",
                    storagePreflight = preflight.storage
                )
                modelFile.absolutePath
        }
    }

    /**
     * Open the configured Kompile prepared-artifact surface in an external browser.
     * It supplies target-complete .sdz/.kproject downloads and receives no Hugging Face source.
     */
    fun openModelStaging(artifact: ModelStagingHandoff.Artifact): Result<Unit> {
        try {
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
            context.startActivity(
                Intent(Intent.ACTION_VIEW, request).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            recordImportDiagnostic(
                "browser handoff",
                artifact.queryValue,
                ImportDiagnosticSeverity.INFO,
                "External browser opened for a prepared ${artifact.fileExtension} download.",
                "Download the prepared package, return here, then use the matching import button."
            )
            return Result.success(Unit)
        } catch (caught: RuntimeException) {
            val failure = if (caught is android.content.ActivityNotFoundException) {
                IllegalStateException(
                    "No web browser is installed on this device, so prepared artifact downloads " +
                        "cannot open. Install a browser or stage on another machine and transfer the file.",
                    caught
                )
            } else {
                caught
            }
            try {
                recordImportDiagnostic(
                    "browser handoff",
                    artifact.queryValue,
                    ImportDiagnosticSeverity.ERROR,
                    failure.message ?: "The browser handoff could not be opened.",
                    "Expand and copy the full stack trace. Check the prepared-artifact server URL and browser availability, then retry.",
                    failure = failure
                )
            } catch (persistenceFailure: RuntimeException) {
                failure.addSuppressed(persistenceFailure)
            }
            return Result.failure(failure)
        }
    }

    /**
     * Caller holds [engineMutex]. Ingestion is complete before this method receives a runnable
     * model. The session that proves decode is the session promoted into chat; reopening the same
     * canonical SDZ would repeat accelerator setup and briefly duplicate driver-owned resources.
     */
    private fun openVerifiedModelLocked(
        modelPath: String,
        unavailableMessage: String,
        tokenizerPath: String? = null,
        verifiedSourceSha256: String? = null,
        verifiedSourceBytes: Long? = null,
        preparationOptions: ModelPreparationOptions = prefs.modelPreparationOptions,
        onImportStep: (HuggingFaceImportStep) -> Unit = {}
    ): AcceleratedChatModelAndroid {
        val candidate = try {
            openAvailableModel(
                modelPath = modelPath,
                tokenizerPath = tokenizerPath,
                verifiedSourceSha256 = verifiedSourceSha256,
                verifiedSourceBytes = verifiedSourceBytes,
                preparationOptions = preparationOptions,
            ) { preparationStage ->
                onImportStep(
                    when (preparationStage) {
                        PreparationStage.CONVERT_AND_CACHE_SDZ -> HuggingFaceImportStep.CONVERT_SDZ
                        PreparationStage.TARGET_CACHE_READY -> HuggingFaceImportStep.TARGET_CACHE
                        PreparationStage.LOAD_ACCELERATOR -> HuggingFaceImportStep.SDX_LOAD
                    }
                )
            }
        } catch (failure: Throwable) {
            _modelLoadProgress.value = null
            throw failure
        }
        try {
            if (!candidate.isAvailable()) {
                throw IOException(unavailableMessage)
            }
            onImportStep(HuggingFaceImportStep.SMOKE_DECODE)
            _modelLoadProgress.value = ModelLoadProgressUi(
                title = "Proving loaded model…",
                detail = "Running a bounded real-token decode before chat activation",
            )
            when (val smoke = smokeTestModelLocked(candidate)) {
                is ModelSmokeUiState.Passed -> Unit
                is ModelSmokeUiState.Failed -> throw ChatException(
                    "Model decode self-test failed on ${smoke.route}: ${smoke.message}",
                    smoke.failure
                )
                is ModelSmokeUiState.Running,
                ModelSmokeUiState.NotRun -> throw IOException(
                    "Model decode self-test did not complete."
                )
            }
        } catch (failure: Throwable) {
            _modelLoadProgress.value = null
            try {
                candidate.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
        return candidate
    }

    private fun openAvailableModel(
        modelPath: String,
        tokenizerPath: String? = null,
        verifiedSourceSha256: String? = null,
        verifiedSourceBytes: Long? = null,
        preparedModelInfo: PreparedModelInfo? = null,
        preparationOptions: ModelPreparationOptions = prefs.modelPreparationOptions,
        onPreparationStage: (PreparationStage) -> Unit = {}
    ): AcceleratedChatModelAndroid =
        AcceleratedChatModelAndroid(
            context = context,
            modelPath = modelPath,
            temperature = prefs.temperature,
            maxTokens = effectiveMaxTokensForTarget(
                prefs.maxTokens,
                BuildConfig.SDX_TARGET_PROFILE
            ),
            tokenizerPath = tokenizerPath,
            verifiedSourceSha256 = verifiedSourceSha256,
            verifiedSourceBytes = verifiedSourceBytes,
            onPreparationStage = { stage ->
                publishModelPreparationStage(stage)
                onPreparationStage(stage)
            },
            preparedModelInfo = preparedModelInfo,
            preparationOptions = preparationOptions,
        )

    /** Caller holds [engineMutex]. Publish a standalone model only after real decode. */
    private fun activateStandaloneModelLocked(
        modelPath: String,
        previousSelection: ActiveProjectSelection,
        clearHuggingFaceImportOnPromotion: Boolean = false,
        tokenizerPath: String? = null,
        verifiedSourceSha256: String? = null,
        verifiedSourceBytes: Long? = null,
        preparationOptions: ModelPreparationOptions = prefs.modelPreparationOptions,
        onImportStep: (HuggingFaceImportStep) -> Unit = {}
    ): StandaloneActivation {
        var candidateBridge: GraphToolBackend? = null
        val candidateSelection = ActiveProjectSelection(
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

        try {
            val activation =
                ProvenModelActivationTransaction<String, AcceleratedChatModelAndroid, StandaloneActivation>(
                stagePendingSelection = { prefs.stagePendingSelection(candidateSelection) },
                detachPreviousRuntime = { closeEngineResourcesLocked() },
                openCandidate = { exactModelPath ->
                    openVerifiedModelLocked(
                        modelPath = exactModelPath,
                        unavailableMessage = "The imported model could not open in the local SDX runtime",
                        tokenizerPath = tokenizerPath,
                        verifiedSourceSha256 = verifiedSourceSha256,
                        verifiedSourceBytes = verifiedSourceBytes,
                        preparationOptions = preparationOptions,
                        onImportStep = onImportStep
                    )
                },
                decodedGeneration = {
                    (_modelSmokeState.value as? ModelSmokeUiState.Passed)?.preview.orEmpty()
                },
                publish = { exactModelPath, openedModel ->
                    onImportStep(HuggingFaceImportStep.ACTIVATE)
                    val route = openedModel.routeName
                    val modelId = openedModel.modelId()
                    val preparedModel = openedModel.preparationInfo
                    val activeModelPath = preparedModel?.canonicalSdzPath ?: exactModelPath
                    if (activeModelPath != exactModelPath) {
                        check(
                            prefs.stagePendingSelection(
                                candidateSelection.copy(modelPath = activeModelPath)
                            )
                        ) {
                            "The cached canonical SDZ could not replace the raw pending model selection."
                        }
                    }
                    val openedBridge = buildBridge(previousSelection.graphPath)
                    candidateBridge = openedBridge
                    val candidateEngine = ChatEngine(
                        InferenceRouter(openedModel, null),
                        openedBridge,
                        prefs.maxToolRounds,
                        ChatEngine.ToolRouting.RELEVANT
                    )
                    val activation = StandaloneActivation(
                        modelPath = activeModelPath,
                        route = route,
                        modelId = modelId,
                        preparedModel = preparedModel
                    )

                    engine = candidateEngine
                    bridge = openedBridge
                    localModel = openedModel
                    candidateBridge = null
                    clearConversationLocked()
                    _error.value = null
                    _errorStackTrace.value = null
                    _activeRoute.value = route
                    _modelState.value = ModelUiState.Ready(activeModelPath, route)
                    _modelLoadProgress.value = null
                    _graphState.value =
                        GraphUiState.Ready(previousSelection.graphPath.takeIf(String::isNotBlank))
                    activation
                },
                promotePendingSelection = {
                    if (clearHuggingFaceImportOnPromotion) {
                        prefs.promotePendingSelectionAndClearHuggingFaceImport()
                    } else {
                        prefs.promotePendingSelection()
                    }
                },
                rollbackPublished = { closeEngineResourcesLocked() },
                discardPendingSelection = prefs::discardPendingSelection,
                restorePreviousSelection = { prefs.activateProject(previousSelection) },
                restorePreviousRuntime = {
                    check(prefs.snapshotActiveSelection() == previousSelection) {
                        "The previous proven model selection could not be restored."
                    }
                    rebuildEngineLocked(resetConversation = false)
                    check(previousSelection.modelPath.isBlank() || engine != null) {
                        "The previous proven model runtime could not be restored."
                    }
                },
                closeCandidate = AcceleratedChatModelAndroid::close
            ).execute(modelPath)
            _navigationEvents.trySend(AppNavigationEvent.OpenChat)
            return activation
        } catch (failure: Throwable) {
            try {
                candidateBridge?.close()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
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
        var candidateBridge: GraphToolBackend? = null
        val candidateSelection = ActiveProjectSelection(
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
        try {
            ProvenModelActivationTransaction<String, AcceleratedChatModelAndroid, StandaloneActivation>(
                stagePendingSelection = { prefs.stagePendingSelection(candidateSelection) },
                detachPreviousRuntime = { closeEngineResourcesLocked() },
                openCandidate = {
                    openVerifiedModelLocked(
                        installed.modelPath().toString(),
                        "The project model could not open on this accelerator"
                    )
                },
                decodedGeneration = {
                    (_modelSmokeState.value as? ModelSmokeUiState.Passed)?.preview.orEmpty()
                },
                publish = { _, openedModel ->
                    val openedBridge = buildBridge(installed.graphPath().toString())
                    candidateBridge = openedBridge
                    val candidateEngine = ChatEngine(
                        InferenceRouter(openedModel, null),
                        openedBridge,
                        prefs.maxToolRounds,
                        ChatEngine.ToolRouting.RELEVANT
                    )
                    engine = candidateEngine
                    bridge = openedBridge
                    localModel = openedModel
                    candidateBridge = null
                    clearConversationLocked()
                    _error.value = null
                    _errorStackTrace.value = null
                    _activeRoute.value = openedModel.routeName
                    _modelState.value = ModelUiState.Ready(
                        installed.modelPath().toString(),
                        openedModel.routeName
                    )
                    _modelLoadProgress.value = null
                    _graphState.value = GraphUiState.Ready(installed.graphPath().toString())
                    StandaloneActivation(
                        modelPath = installed.modelPath().toString(),
                        route = openedModel.routeName,
                        modelId = openedModel.modelId(),
                        preparedModel = null
                    )
                },
                promotePendingSelection = prefs::promotePendingSelection,
                rollbackPublished = { closeEngineResourcesLocked() },
                discardPendingSelection = prefs::discardPendingSelection,
                restorePreviousSelection = { prefs.activateProject(previousSelection) },
                restorePreviousRuntime = {
                    check(prefs.snapshotActiveSelection() == previousSelection) {
                        "The previous proven project selection could not be restored."
                    }
                    rebuildEngineLocked(resetConversation = false)
                    check(previousSelection.modelPath.isBlank() || engine != null) {
                        "The previous proven project runtime could not be restored."
                    }
                },
                closeCandidate = AcceleratedChatModelAndroid::close
            ).execute(installed.modelPath().toString())
            _navigationEvents.trySend(AppNavigationEvent.OpenChat)
            Log.i(
                TAG,
                "Activated project " + installed.projectId()
                        + " revision=" + installed.revision()
                        + " target=" + BuildConfig.SDX_TARGET_PROFILE
            )
        } catch (failure: Throwable) {
            try {
                candidateBridge?.close()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
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
            try {
                Files.deleteIfExists(pending.toPath())
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        // A provider that rejects metadata queries is an import failure, not a silent fallback.
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
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
        var firstFailure: Throwable? = null
        try {
            oldBridge?.close()
        } catch (failure: Throwable) {
            firstFailure = failure
        }
        try {
            oldModel?.close()
        } catch (failure: Throwable) {
            if (firstFailure == null) {
                firstFailure = failure
            } else {
                firstFailure.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }

    private val streamingStateLock = Any()

    private fun mutateStreaming(transform: (StreamingUiState) -> StreamingUiState) {
        synchronized(streamingStateLock) {
            _streaming.value = _streaming.value?.let(transform)
        }
    }

    /**
     * Splits model-owned <think> blocks for presentation only. Tokenization and
     * chat-template rendering remain entirely in the imported HF/native runtime.
     */
    private class StreamingTextParser {
        private var inReasoning = false
        private var pending = ""
        private var reasoning = ""
        private var content = ""

        fun append(chunk: String, onUpdate: (String, String) -> Unit) {
            if (chunk.isEmpty()) return
            pending += chunk
            drain(final = false)
            onUpdate(reasoning, content)
        }

        fun finish(onUpdate: (String, String) -> Unit) {
            drain(final = true)
            onUpdate(reasoning, content)
        }

        private fun drain(final: Boolean) {
            while (true) {
                val marker = if (inReasoning) "</think>" else "<think>"
                val index = pending.indexOf(marker)
                if (index >= 0) {
                    emit(pending.substring(0, index))
                    pending = pending.substring(index + marker.length)
                    inReasoning = !inReasoning
                    continue
                }
                val keep = if (final) 0 else marker.length - 1
                if (pending.length > keep) {
                    emit(pending.substring(0, pending.length - keep))
                    pending = pending.substring(pending.length - keep)
                }
                return
            }
        }

        private fun emit(text: String) {
            if (text.isEmpty()) return
            if (inReasoning) reasoning += text else content += text
        }
    }

    private fun clearConversationLocked() {
        history.clear()
        _messages.value = emptyList()
        _streaming.value = null
    }

    private fun currentGenOptions(): GenOptions =
        GenOptions.builder()
            .temperature(prefs.temperature.toDouble())
            .maxTokens(
                effectiveMaxTokensForTarget(
                    prefs.maxTokens,
                    BuildConfig.SDX_TARGET_PROFILE
                )
            )
            .build()

    private fun appendUiMessage(msg: UiMessage) {
        _messages.value = _messages.value + msg
    }

    override fun onCleared() {
        huggingFaceDownloadCancellation?.cancel()
        huggingFaceJob?.cancel()
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

internal fun huggingFaceDownloadStep(
    event: HuggingFaceGgmlAcquisition.DownloadEvent
): HuggingFaceImportStep = when (event) {
    HuggingFaceGgmlAcquisition.DownloadEvent.CONNECT -> HuggingFaceImportStep.CONNECT
    HuggingFaceGgmlAcquisition.DownloadEvent.RESUME,
    HuggingFaceGgmlAcquisition.DownloadEvent.DOWNLOAD,
    HuggingFaceGgmlAcquisition.DownloadEvent.RETRY -> HuggingFaceImportStep.DOWNLOAD
    HuggingFaceGgmlAcquisition.DownloadEvent.VERIFY,
    HuggingFaceGgmlAcquisition.DownloadEvent.COMPLETE -> HuggingFaceImportStep.VERIFY
}

internal fun huggingFaceObservedOrFallbackStep(
    observed: HuggingFaceImportUiState.Observable?,
    fallback: HuggingFaceImportStep
): HuggingFaceImportStep = observed?.step ?: fallback
