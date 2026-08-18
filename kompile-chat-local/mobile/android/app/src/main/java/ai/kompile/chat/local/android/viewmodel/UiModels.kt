package ai.kompile.chat.local.android.viewmodel

import ai.kompile.chat.local.android.acquisition.HuggingFaceGgmlAcquisition
import ai.kompile.chat.local.android.diagnostics.ImportDiagnostic
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticPolicy
import ai.kompile.chat.local.android.prefs.HuggingFaceImportCheckpoint
import ai.kompile.chat.local.android.prefs.HuggingFaceImportCheckpointStage
import ai.kompile.chat.local.android.staging.ModelStagingHandoff

/**
 * A single row in the chat message list. The [toolRounds] list is populated for
 * assistant messages that made tool calls; the UI renders them as collapsible cards.
 */
data class UiMessage(
    val role: String,           // "user" | "assistant"
    val content: String,
    val toolRounds: List<ToolRoundUi> = emptyList(),
    val protocolExchanges: List<ProtocolExchangeUi> = emptyList(),
    val id: Long = System.nanoTime()
)

/** One tool dispatch within a single assistant turn. */
data class ToolRoundUi(
    val tool: String,
    val argsJson: String,
    val resultJson: String
)

/** Raw structured-chat transport retained only for the user-requested transcript export. */
data class ProtocolExchangeUi(
    val requestJson: String,
    val rawResponse: String,
    val protocolErrors: List<String>
)

/** One live tool invocation, shown before the final assistant message exists. */
data class ToolActivityUi(
    val tool: String,
    val argsJson: String,
    val resultJson: String = "",
    val status: String = "running"
)

/** Incremental assistant turn state; this is separate from committed conversation history. */
data class StreamingUiState(
    val phase: String = "starting",
    val reasoning: String = "",
    val content: String = "",
    val toolActivities: List<ToolActivityUi> = emptyList(),
    val protocolExchangeCount: Int = 0,
    /** Raw request/response pairs observed before a turn is committed to history. */
    val protocolExchanges: List<ProtocolExchangeUi> = emptyList()
)

/** Explicit local-model lifecycle state; expected first-run setup is not an error. */
sealed interface ModelUiState {
    data object Checking : ModelUiState
    data object Missing : ModelUiState
    data class Ready(val path: String, val route: String) : ModelUiState
    data class Failed(
        val path: String,
        val message: String,
        val stackTrace: String = IllegalStateException(message).stackTraceToString()
    ) : ModelUiState
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
    data class Failed(
        val route: String?,
        val message: String,
        val failure: Throwable
    ) : ModelSmokeUiState
}

/** Exact operation holding the single import gate; UI must never infer this from a Boolean. */
enum class ImportOperationKind {
    NONE,
    HUGGING_FACE,
    LOCAL_MODEL_OPTIMIZATION,
    MODEL_ARCHIVE,
    PROJECT_ARCHIVE,
    GRAPH,
    MODEL_UNLOAD
}

internal val ImportOperationKind.isBusy: Boolean
    get() = this != ImportOperationKind.NONE

internal const val TENSOR_G3_TARGET_PROFILE = "android-arm64-nnapi-accelerator"
internal const val TENSOR_G3_MAX_GENERATION_TOKENS = 128
private const val DEFAULT_MAX_GENERATION_TOKENS = 4096

/** Tensor G3 must stay within the KV-cache window proven by the activation smoke decode. */
internal fun maxGenerationTokensForTarget(targetProfile: String): Int =
    if (targetProfile == TENSOR_G3_TARGET_PROFILE) {
        TENSOR_G3_MAX_GENERATION_TOKENS
    } else {
        DEFAULT_MAX_GENERATION_TOKENS
    }

internal fun effectiveMaxTokensForTarget(requested: Int, targetProfile: String): Int =
    requested.coerceIn(1, maxGenerationTokensForTarget(targetProfile))

internal fun isModelLoading(
    modelState: ModelUiState,
    operation: ImportOperationKind
): Boolean = modelState is ModelUiState.Checking || operation in setOf(
    ImportOperationKind.HUGGING_FACE,
    ImportOperationKind.LOCAL_MODEL_OPTIMIZATION,
    ImportOperationKind.MODEL_ARCHIVE,
    ImportOperationKind.PROJECT_ARCHIVE
)

/** Exact long-running native preparation phase shown in the shared model banner. */
data class ModelLoadProgressUi(
    val title: String,
    val detail: String,
)

/** One top-of-screen model identity slot shared by Chat and Settings. */
internal data class ModelStatusUi(
    val title: String,
    val detail: String,
    val loading: Boolean = false,
    val error: Boolean = false,
)

internal fun modelStatusUi(
    modelState: ModelUiState,
    operation: ImportOperationKind,
    loadProgress: ModelLoadProgressUi? = null,
): ModelStatusUi {
    if (isModelLoading(modelState, operation) || loadProgress != null) {
        loadProgress?.let {
            return ModelStatusUi(it.title, it.detail, loading = true)
        }
        val (title, detail) = when (operation) {
            ImportOperationKind.HUGGING_FACE ->
                "Preparing Hugging Face model…" to "Importing and proving the local runtime"
            ImportOperationKind.LOCAL_MODEL_OPTIMIZATION ->
                "Optimizing local model…" to "Preparing an accelerator-ready chat model"
            ImportOperationKind.MODEL_ARCHIVE ->
                "Loading model archive…" to "Opening and proving the selected model"
            ImportOperationKind.PROJECT_ARCHIVE ->
                "Loading project model…" to "Opening the project model for chat"
            else ->
                "Loading local model…" to "Opening the saved model for chat"
        }
        return ModelStatusUi(title, detail, loading = true)
    }

    return when (modelState) {
        is ModelUiState.Ready -> ModelStatusUi(
            title = modelState.path.substringAfterLast('/').ifBlank { "Local model" },
            detail = if (modelState.route == "LOCAL_TENSOR_G3_NNAPI") {
                "Ready · google-edgetpu NNAPI islands + ARM64 replay"
            } else {
                "Loaded and ready for chat"
            },
        )
        ModelUiState.Missing -> ModelStatusUi(
            title = "No model loaded",
            detail = "Open Settings to import or prepare one",
        )
        is ModelUiState.Failed -> ModelStatusUi(
            title = "Model unavailable",
            detail = modelState.message,
            error = true,
        )
        ModelUiState.Checking -> error("Checking must be represented as loading")
    }
}

/** Artifact changes require an explicit unload so one proven runtime remains authoritative. */
internal val ImportOperationKind.requiresUnloadedModel: Boolean
    get() = this != ImportOperationKind.NONE && this != ImportOperationKind.MODEL_UNLOAD

/** One-shot destinations emitted only after a lifecycle transaction has committed. */
sealed interface AppNavigationEvent {
    data object OpenChat : AppNavigationEvent
}

/** App-owned raw model retained for repeatable local optimization experiments. */
data class LocalModelSource(
    val path: String,
    val displayName: String,
    val bytes: Long,
)

/** Ordered, observable stages for end-to-end Hugging Face acquisition and activation. */
enum class HuggingFaceImportStep(val label: String) {
    RESOLVE("Resolve repository"),
    PREFLIGHT("Check storage"),
    CONNECT("Connect"),
    DOWNLOAD("Download"),
    VERIFY("Verify model bytes"),
    TOKENIZER_ASSETS("Fetch tokenizer assets"),
    CONVERT_SDZ("Convert and optimize SDZ"),
    TARGET_CACHE("Prepare accelerator cache"),
    SDX_LOAD("Compile or restore accelerator plan"),
    SMOKE_DECODE("Run smoke decode"),
    ACTIVATE("Finish chat setup"),
    ACTIVE("Active")
}

internal val HuggingFaceImportStep.isTransferStep: Boolean
    get() = this == HuggingFaceImportStep.CONNECT ||
        this == HuggingFaceImportStep.DOWNLOAD ||
        this == HuggingFaceImportStep.VERIFY ||
        this == HuggingFaceImportStep.TOKENIZER_ASSETS

/** What app-owned bytes can be reused when this import resumes. */
enum class HuggingFaceStorageReuse(val label: String) {
    NONE("No saved model bytes"),
    VALIDATED_PARTIAL("Validator-backed partial download"),
    VERIFIED_MODEL("Verified app-owned model")
}

/** A storage failure is distinct from network, verification, and SDX execution failures. */
enum class HuggingFaceStorageBlockReason(val label: String) {
    STORAGE_UNAVAILABLE("No writable app storage"),
    INSUFFICIENT_STORAGE("Not enough free app storage")
}

/**
 * Immutable storage arithmetic for one selected destination. [usableBytes] is the free space
 * Android reports for the filesystem containing this APK's private files directory; Android does
 * not impose a second model-size quota here. Existing validated bytes are counted separately
 * because their allocated blocks are already excluded from [usableBytes].
 */
data class HuggingFaceStoragePreflight(
    val applicationId: String,
    val destinationPath: String,
    val expectedBytes: Long?,
    val usableBytes: Long,
    val reserveBytes: Long,
    val availableAfterReserveBytes: Long,
    val reusableBytes: Long,
    val additionalBytesRequired: Long?,
    val transferLimitBytes: Long,
    val reuse: HuggingFaceStorageReuse,
    val blockReason: HuggingFaceStorageBlockReason?
) {
    val canProceed: Boolean get() = blockReason == null
}

/** Pure, overflow-safe preflight calculation shared by the ViewModel and host acceptance tests. */
internal fun huggingFaceStoragePreflight(
    applicationId: String,
    destinationPath: String,
    expectedBytes: Long?,
    usableBytes: Long,
    reserveBytes: Long,
    reusableBytes: Long,
    reuse: HuggingFaceStorageReuse
): HuggingFaceStoragePreflight {
    require(applicationId.isNotBlank()) { "The APK application ID must be visible during preflight." }
    require(destinationPath.isNotBlank()) { "The model destination must be visible during preflight." }
    require(expectedBytes == null || expectedBytes >= 0L) { "Resolved import size cannot be negative." }

    val usable = usableBytes.coerceAtLeast(0L)
    val reserve = reserveBytes.coerceAtLeast(0L)
    val available = if (usable > reserve) usable - reserve else 0L
    val saved = reusableBytes.coerceAtLeast(0L).let { bytes ->
        expectedBytes?.let { expected -> minOf(bytes, expected) } ?: bytes
    }
    val additional = expectedBytes?.let { expected ->
        if (saved >= expected) 0L else expected - saved
    }
    val transferLimit = when {
        expectedBytes != null -> expectedBytes.coerceAtLeast(1L)
        available > Long.MAX_VALUE - saved -> Long.MAX_VALUE
        else -> (available + saved).coerceAtLeast(1L)
    }
    val verifiedImportComplete = reuse == HuggingFaceStorageReuse.VERIFIED_MODEL && additional == 0L
    val blockReason = when {
        verifiedImportComplete -> null
        additional != null && additional > available ->
            HuggingFaceStorageBlockReason.INSUFFICIENT_STORAGE
        expectedBytes == null && available == 0L ->
            HuggingFaceStorageBlockReason.STORAGE_UNAVAILABLE
        else -> null
    }
    return HuggingFaceStoragePreflight(
        applicationId = applicationId,
        destinationPath = destinationPath,
        expectedBytes = expectedBytes,
        usableBytes = usable,
        reserveBytes = reserve,
        availableAfterReserveBytes = available,
        reusableBytes = saved,
        additionalBytesRequired = additional,
        transferLimitBytes = transferLimit,
        reuse = reuse,
        blockReason = blockReason
    )
}

/** Complete, human-readable result shown before any connection or native model work begins. */
internal fun huggingFaceStoragePreflightMessage(plan: HuggingFaceStoragePreflight): String =
    when (plan.blockReason) {
        HuggingFaceStorageBlockReason.INSUFFICIENT_STORAGE ->
            "Blocked: ${formatBinaryBytes(plan.additionalBytesRequired ?: 0L)} of new model/tokenizer data is required, " +
                "but only ${formatBinaryBytes(plan.availableAfterReserveBytes)} is available after the " +
                "${formatBinaryBytes(plan.reserveBytes)} safety reserve. Destination: ${plan.destinationPath}"
        HuggingFaceStorageBlockReason.STORAGE_UNAVAILABLE ->
            "Blocked: no writable app storage remains after the ${formatBinaryBytes(plan.reserveBytes)} " +
                "safety reserve. Destination: ${plan.destinationPath}"
        null -> when {
            plan.reuse == HuggingFaceStorageReuse.VERIFIED_MODEL && plan.additionalBytesRequired == 0L ->
                "Ready: ${formatBinaryBytes(plan.reusableBytes)} is already verified in app storage; no download is required."
            plan.additionalBytesRequired != null ->
                "Ready: ${formatBinaryBytes(plan.additionalBytesRequired)} of new model/tokenizer data fits in " +
                    "${formatBinaryBytes(plan.availableAfterReserveBytes)} available after the safety reserve."
            else ->
                "Ready: the server will report the final size; transfer is bounded to " +
                    "${formatBinaryBytes(plan.transferLimitBytes)} on this app volume."
        }
    }

/**
 * One immutable progress observation. It deliberately contains no repository URL,
 * access token, or other request detail that could disclose a secret through UI state.
 */
data class HuggingFaceImportProgress(
    val step: HuggingFaceImportStep,
    val message: String,
    val attempt: Int,
    val maxAttempts: Int,
    val resumedBytes: Long,
    val completedBytes: Long,
    val totalBytes: Long?,
    val smoothedBytesPerSecond: Double?,
    val etaSeconds: Long?,
    val retryWillResumeOrReuse: Boolean,
    val storagePreflight: HuggingFaceStoragePreflight? = null
) {
    val determinateFraction: Float?
        get() = determinateFraction(completedBytes, totalBytes)

    val percent: Int?
        get() = safePercent(determinateFraction)
}

/**
 * Configuration resolution for the exact selected repository candidate. This remains separate
 * from transfer progress so a missing tokenizer/config asset is visible before import can start.
 */
sealed interface HuggingFaceConfigurationUiState {
    data object Idle : HuggingFaceConfigurationUiState
    data class Resolving(val repository: String) : HuggingFaceConfigurationUiState
    data class Resolved(
        val configuration: HuggingFaceGgmlAcquisition.ResolvedRepositoryConfiguration
    ) : HuggingFaceConfigurationUiState
    data class Failed(
        val repository: String,
        val message: String
    ) : HuggingFaceConfigurationUiState
}

/** End-to-end public Hugging Face acquisition state, including SDX activation. */
sealed interface HuggingFaceImportUiState {
    data object Idle : HuggingFaceImportUiState

    /** Repository resolution completed and the user must choose one explicit file. */
    data class SelectionRequired(
        val repository: String,
        val candidateCount: Int
    ) : HuggingFaceImportUiState

    sealed interface Observable : HuggingFaceImportUiState {
        val progress: HuggingFaceImportProgress
        val step: HuggingFaceImportStep get() = progress.step
        val message: String get() = progress.message
        val attempt: Int get() = progress.attempt
        val maxAttempts: Int get() = progress.maxAttempts
        val resumedBytes: Long get() = progress.resumedBytes
        val completedBytes: Long get() = progress.completedBytes
        val totalBytes: Long? get() = progress.totalBytes
        val smoothedBytesPerSecond: Double? get() = progress.smoothedBytesPerSecond
        val etaSeconds: Long? get() = progress.etaSeconds
        val retryWillResumeOrReuse: Boolean get() = progress.retryWillResumeOrReuse
        val storagePreflight: HuggingFaceStoragePreflight? get() = progress.storagePreflight
    }

    data class Working(override val progress: HuggingFaceImportProgress) : Observable
    data class Retrying(override val progress: HuggingFaceImportProgress) : Observable
    data class Failed(
        override val progress: HuggingFaceImportProgress,
        /** Exact current failure evidence; never recovered heuristically from retained history. */
        val diagnostic: ImportDiagnostic,
        /**
         * The original failure object from the operation boundary. Causes and suppressed cleanup
         * failures remain structured until the UI renders or copies them.
         */
        val failure: Throwable
    ) : Observable {
        val exceptionType: String get() = failure.javaClass.name
        val stackTrace: String get() = failure.stackTraceToString()
    }
    data class Cancelled(override val progress: HuggingFaceImportProgress) : Observable

    /** A durable immutable checkpoint survived process death and is ready for explicit resume. */
    data class Interrupted(override val progress: HuggingFaceImportProgress) : Observable

    /** Terminal success. [artifactName] is display-only and must not contain a URL. */
    data class Active(
        val artifactName: String,
        val route: String,
        val storageLocation: String,
        val message: String = "Model active",
        val storagePreflight: HuggingFaceStoragePreflight? = null
    ) : HuggingFaceImportUiState {
        val step: HuggingFaceImportStep = HuggingFaceImportStep.ACTIVE
    }
}

internal enum class HuggingFaceRetryAction {
    RESOLVE_REFERENCE,
    IMPORT_SELECTED,
    RETRY_PREPARED_IMPORT
}

internal data class HuggingFaceActivePresentation(
    val summary: String,
    val storage: String
)

/** Clipboard text for the exact failure currently on screen, including resumable byte/path context. */
internal fun huggingFaceFailureCopyText(state: HuggingFaceImportUiState.Failed): String = buildString {
    append("Hugging Face model import failure")
    append("\nStep: ").append(state.step.label)
    append("\nAttempt: ").append(state.attempt).append('/').append(state.maxAttempts)
    append("\nBytes processed: ").append(state.completedBytes)
    state.totalBytes?.let { append(" / ").append(it) }
    append(" bytes")
    state.storagePreflight?.destinationPath?.takeIf(String::isNotBlank)?.let {
        append("\nDestination: ").append(it)
    }
    append("\nException: ").append(state.exceptionType)
    append("\nSummary: ").append(state.diagnostic.summary)
    append("\nNext: ").append(state.diagnostic.remediation)
    append("\n\nFull stack trace:\n").append(state.stackTrace)
}

internal fun huggingFaceActivePresentation(
    state: HuggingFaceImportUiState.Active
): HuggingFaceActivePresentation {
    require(state.storageLocation.isNotBlank()) { "An active model must expose its storage location." }
    return HuggingFaceActivePresentation(
        summary = "Running ${state.artifactName} through ${state.route}. ${state.message}",
        storage = "Private app storage: ${state.storageLocation}"
    )
}

internal fun interruptedHuggingFaceImportState(
    checkpoint: HuggingFaceImportCheckpoint
): HuggingFaceImportUiState.Interrupted {
    require(checkpoint.isStructurallyValid()) {
        "A malformed Hugging Face import checkpoint cannot be resumed."
    }
    val hasVerifiedDownload =
        checkpoint.stage == HuggingFaceImportCheckpointStage.VERIFIED_DOWNLOAD
    val fallbackStep = if (hasVerifiedDownload) {
        HuggingFaceImportStep.CONVERT_SDZ
    } else {
        HuggingFaceImportStep.PREFLIGHT
    }
    val observedStep = checkpoint.observedStep
        .takeIf(String::isNotBlank)
        ?.let(HuggingFaceImportStep::valueOf)
        ?.takeUnless { it == HuggingFaceImportStep.ACTIVE }
        ?: fallbackStep
    val fallbackMessage = if (hasVerifiedDownload) {
        "Android stopped the previous import after download verification. Resume to revalidate the immutable model and continue with SDX."
    } else {
        "Android stopped the previous import. Resume to re-resolve the immutable model and reuse any valid saved bytes."
    }
    val message = checkpoint.observedMessage.takeIf(String::isNotBlank)?.let { observed ->
        "Android stopped the previous import during ${observedStep.label}. Last recorded: $observed"
    } ?: fallbackMessage
    return HuggingFaceImportUiState.Interrupted(
        HuggingFaceImportProgress(
            step = observedStep,
            message = message,
            attempt = checkpoint.observedAttempt,
            maxAttempts = checkpoint.observedMaxAttempts,
            resumedBytes = checkpoint.observedResumedBytes,
            completedBytes = checkpoint.observedCompletedBytes,
            totalBytes = checkpoint.observedTotalBytes.takeIf { it >= 0L },
            smoothedBytesPerSecond = null,
            etaSeconds = null,
            retryWillResumeOrReuse =
                checkpoint.observedRetryWillResumeOrReuse || hasVerifiedDownload
        )
    )
}

internal fun isHuggingFaceTerminal(state: HuggingFaceImportUiState.Observable): Boolean =
    state is HuggingFaceImportUiState.Failed ||
        state is HuggingFaceImportUiState.Cancelled ||
        state is HuggingFaceImportUiState.Interrupted

internal fun huggingFaceRetryExplanation(state: HuggingFaceImportUiState.Observable): String = when {
    state is HuggingFaceImportUiState.Interrupted &&
        state.step.ordinal >= HuggingFaceImportStep.CONVERT_SDZ.ordinal &&
        state.retryWillResumeOrReuse ->
        "Resume re-resolves the immutable Hugging Face file, verifies its identity, and reopens the already verified app-owned model in SDX."
    state is HuggingFaceImportUiState.Interrupted ->
        "Resume re-resolves the immutable Hugging Face file and reuses validator-backed partial bytes when they are still valid."
    state.step.ordinal >= HuggingFaceImportStep.CONVERT_SDZ.ordinal && state.retryWillResumeOrReuse ->
        "Retry reopens the already verified model in SDX; it does not redownload."
    state.step.isTransferStep && state.retryWillResumeOrReuse ->
        "Retry continues from the validator-backed partial bytes already saved."
    else ->
        "Retry restarts this step; the previously active chat model remains untouched."
}

internal fun huggingFaceRetryButtonLabel(state: HuggingFaceImportUiState.Observable): String = when {
    state is HuggingFaceImportUiState.Interrupted -> "Resume interrupted import"
    state.step.ordinal >= HuggingFaceImportStep.CONVERT_SDZ.ordinal && state.retryWillResumeOrReuse ->
        "Retry ${state.step.label} from verified model"
    state.step.isTransferStep && state.retryWillResumeOrReuse ->
        "Retry ${state.step.label} using saved bytes"
    else -> "Retry ${state.step.label}"
}

/** Retry the failed stage itself; later stages reuse the same selected immutable candidate. */
internal fun huggingFaceRetryAction(
    step: HuggingFaceImportStep,
    hasSelectedCandidate: Boolean,
    hasPreparedImport: Boolean
): HuggingFaceRetryAction =
    if (step == HuggingFaceImportStep.RESOLVE || !hasSelectedCandidate) {
        HuggingFaceRetryAction.RESOLVE_REFERENCE
    } else if (hasPreparedImport && step.ordinal >= HuggingFaceImportStep.CONNECT.ordinal) {
        HuggingFaceRetryAction.RETRY_PREPARED_IMPORT
    } else {
        HuggingFaceRetryAction.IMPORT_SELECTED
    }

internal enum class HuggingFaceStepStatus(val label: String) {
    WAITING("Waiting"),
    RUNNING("Running"),
    RETRYING("Retrying"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    INTERRUPTED("Interrupted"),
    COMPLETE("Complete"),
    ACTIVE("Active")
}

internal enum class HuggingFaceStepProgressMode {
    EMPTY,
    INDETERMINATE,
    DETERMINATE,
    COMPLETE
}

/** One compact row for one real import phase; every phase always owns one progress bar. */
internal data class HuggingFaceStepPresentation(
    val step: HuggingFaceImportStep,
    val status: HuggingFaceStepStatus,
    val detail: String,
    val progressMode: HuggingFaceStepProgressMode,
    val progressFraction: Float?,
    val resumeBehavior: String,
    val canRetry: Boolean,
    val canCancel: Boolean,
    val actionLabel: String?
)

/**
 * The compact pipeline always renders every row together. Detail for one selected/current step is
 * deliberately separate so checkpoint prose can never turn nine visible bars into nine screens.
 */
internal data class HuggingFacePipelineDashboard(
    val rows: List<HuggingFaceStepPresentation>,
    val focused: HuggingFaceStepPresentation?
)

internal fun huggingFacePipelineDashboard(
    state: HuggingFaceImportUiState,
    requestedStep: HuggingFaceImportStep? = null
): HuggingFacePipelineDashboard {
    val rows = huggingFaceStepPresentations(state)
    val defaultStep = when (state) {
        is HuggingFaceImportUiState.Observable -> state.step
        is HuggingFaceImportUiState.SelectionRequired -> HuggingFaceImportStep.RESOLVE
        is HuggingFaceImportUiState.Active -> HuggingFaceImportStep.ACTIVE
        HuggingFaceImportUiState.Idle -> null
    }
    return HuggingFacePipelineDashboard(
        rows = rows,
        focused = rows.firstOrNull { it.step == (requestedStep ?: defaultStep) }
    )
}

internal fun huggingFaceStepResumeBehavior(step: HuggingFaceImportStep): String = when (step) {
    HuggingFaceImportStep.RESOLVE ->
        "Restart behavior: re-resolve the repository reference and match any durable immutable checkpoint before continuing."
    HuggingFaceImportStep.PREFLIGHT ->
        "Resume behavior: recheck this APK's app volume and recount validator-backed saved bytes."
    HuggingFaceImportStep.CONNECT ->
        "Resume behavior: reconnect to the selected immutable file; validated partial bytes remain eligible for reuse."
    HuggingFaceImportStep.DOWNLOAD ->
        "Resume behavior: continue from validator-backed saved bytes when the server validator still matches; otherwise restart this transfer safely."
    HuggingFaceImportStep.VERIFY ->
        "Resume behavior: verify saved model length and SHA-256 again; unverified bytes are never loaded."
    HuggingFaceImportStep.TOKENIZER_ASSETS ->
        "Resume behavior: reuse every verified tokenizer/config sidecar and continue any validator-backed partial asset."
    HuggingFaceImportStep.CONVERT_SDZ ->
        "Resume behavior: reuse a completed content-addressed SDZ conversion; otherwise restart only the one-time conversion from the verified GGUF without redownloading."
    HuggingFaceImportStep.TARGET_CACHE ->
        "Resume behavior: reuse the canonical SDZ and any complete target bundle; an interrupted target compile restarts without repeating GGUF import."
    HuggingFaceImportStep.SDX_LOAD ->
        "Resume behavior: reopen the cached strict accelerator bundle and reuse the NNAPI driver cache without redownloading or reimporting."
    HuggingFaceImportStep.SMOKE_DECODE ->
        "Resume behavior: native decode is non-interruptible; if Android stops it, reopen the verified model and rerun the bounded decode without redownloading."
    HuggingFaceImportStep.ACTIVATE ->
        "Resume behavior: chat setup transactionally publishes the already smoke-tested session; it does not load or decode the model again."
    HuggingFaceImportStep.ACTIVE ->
        "Resume behavior: no resume is required; this exact app-owned model is active for chat."
}

/** Derive the complete end-to-end pipeline from the single authoritative import state. */
internal fun huggingFaceStepPresentations(
    state: HuggingFaceImportUiState
): List<HuggingFaceStepPresentation> {
    if (state is HuggingFaceImportUiState.Idle) return emptyList()
    return HuggingFaceImportStep.entries.map { step ->
        val status = when (state) {
            is HuggingFaceImportUiState.SelectionRequired ->
                if (step == HuggingFaceImportStep.RESOLVE) {
                    HuggingFaceStepStatus.COMPLETE
                } else {
                    HuggingFaceStepStatus.WAITING
                }
            is HuggingFaceImportUiState.Active ->
                if (step == HuggingFaceImportStep.ACTIVE) {
                    HuggingFaceStepStatus.ACTIVE
                } else {
                    HuggingFaceStepStatus.COMPLETE
                }
            is HuggingFaceImportUiState.Observable -> when {
                step.ordinal < state.step.ordinal -> HuggingFaceStepStatus.COMPLETE
                step.ordinal > state.step.ordinal -> HuggingFaceStepStatus.WAITING
                state is HuggingFaceImportUiState.Working -> HuggingFaceStepStatus.RUNNING
                state is HuggingFaceImportUiState.Retrying -> HuggingFaceStepStatus.RETRYING
                state is HuggingFaceImportUiState.Failed -> HuggingFaceStepStatus.FAILED
                state is HuggingFaceImportUiState.Cancelled -> HuggingFaceStepStatus.CANCELLED
                state is HuggingFaceImportUiState.Interrupted -> HuggingFaceStepStatus.INTERRUPTED
                else -> HuggingFaceStepStatus.WAITING
            }
            HuggingFaceImportUiState.Idle -> HuggingFaceStepStatus.WAITING
        }
        val currentObservable = (state as? HuggingFaceImportUiState.Observable)
            ?.takeIf { it.step == step }
        val progressMode = when (status) {
            HuggingFaceStepStatus.COMPLETE, HuggingFaceStepStatus.ACTIVE ->
                HuggingFaceStepProgressMode.COMPLETE
            HuggingFaceStepStatus.WAITING -> HuggingFaceStepProgressMode.EMPTY
            HuggingFaceStepStatus.RUNNING, HuggingFaceStepStatus.RETRYING ->
                if (step.isTransferStep && currentObservable?.progress?.determinateFraction != null) {
                    HuggingFaceStepProgressMode.DETERMINATE
                } else {
                    HuggingFaceStepProgressMode.INDETERMINATE
                }
            HuggingFaceStepStatus.FAILED,
            HuggingFaceStepStatus.CANCELLED,
            HuggingFaceStepStatus.INTERRUPTED ->
                if (step.isTransferStep && currentObservable?.progress?.determinateFraction != null) {
                    HuggingFaceStepProgressMode.DETERMINATE
                } else {
                    HuggingFaceStepProgressMode.EMPTY
                }
        }
        val terminalCurrent = currentObservable?.let(::isHuggingFaceTerminal) == true
        val canCancel = currentObservable != null && !terminalCurrent &&
            step.ordinal <= HuggingFaceImportStep.TOKENIZER_ASSETS.ordinal
        HuggingFaceStepPresentation(
            step = step,
            status = status,
            detail = when {
                currentObservable != null -> currentObservable.message
                state is HuggingFaceImportUiState.SelectionRequired && step == HuggingFaceImportStep.RESOLVE ->
                    "Repository resolved; select one of ${state.candidateCount} model files to continue."
                state is HuggingFaceImportUiState.Active && step == HuggingFaceImportStep.ACTIVE -> state.message
                status == HuggingFaceStepStatus.COMPLETE -> "Completed."
                else -> "Waiting for the previous phase."
            },
            progressMode = progressMode,
            progressFraction = when (progressMode) {
                HuggingFaceStepProgressMode.COMPLETE -> 1f
                HuggingFaceStepProgressMode.EMPTY -> 0f
                HuggingFaceStepProgressMode.DETERMINATE ->
                    currentObservable?.progress?.determinateFraction ?: 0f
                HuggingFaceStepProgressMode.INDETERMINATE -> null
            },
            resumeBehavior = huggingFaceStepResumeBehavior(step),
            canRetry = terminalCurrent,
            canCancel = canCancel,
            actionLabel = when {
                terminalCurrent -> huggingFaceRetryButtonLabel(currentObservable!!)
                canCancel -> if (step.isTransferStep) "Cancel current transfer" else "Cancel current step"
                else -> null
            }
        )
    }
}

/** Null means progress must be rendered indeterminately. */
internal fun determinateFraction(completedBytes: Long, totalBytes: Long?): Float? {
    if (totalBytes == null || totalBytes <= 0L) return null
    return completedBytes.coerceIn(0L, totalBytes).toDouble().div(totalBytes.toDouble()).toFloat()
}

/** Convert a possibly invalid fraction into a safe, clamped whole percent. */
internal fun safePercent(fraction: Float?): Int? {
    if (fraction == null || !fraction.isFinite()) return null
    return kotlin.math.round(fraction.coerceIn(0f, 1f) * 100f).toInt()
}

/** IEC binary byte formatting with locale-stable decimal output. */
internal fun formatBinaryBytes(bytes: Long): String {
    val nonNegative = bytes.coerceAtLeast(0L)
    if (nonNegative < 1024L) return "$nonNegative B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = nonNegative.toDouble()
    var unitIndex = -1
    do {
        value /= 1024.0
        unitIndex++
    } while (value >= 1024.0 && unitIndex < units.lastIndex)
    return java.lang.String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unitIndex])
}

/** Smoothed binary transfer rate, or an em dash when no valid observation exists. */
internal fun formatBinaryRate(bytesPerSecond: Double?): String {
    if (bytesPerSecond == null || !bytesPerSecond.isFinite() || bytesPerSecond < 0.0) return "—"
    if (bytesPerSecond < 1024.0) {
        return java.lang.String.format(java.util.Locale.ROOT, "%.0f B/s", bytesPerSecond)
    }
    val units = arrayOf("KiB/s", "MiB/s", "GiB/s", "TiB/s", "PiB/s", "EiB/s")
    var value = bytesPerSecond
    var unitIndex = -1
    do {
        value /= 1024.0
        unitIndex++
    } while (value >= 1024.0 && unitIndex < units.lastIndex)
    return java.lang.String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unitIndex])
}

/** Compact ETA text, or an em dash when ETA is not currently knowable. */
internal fun formatEta(etaSeconds: Long?): String {
    if (etaSeconds == null || etaSeconds < 0L) return "—"
    if (etaSeconds < 60L) return "${etaSeconds}s"
    val minutes = etaSeconds / 60L
    val seconds = etaSeconds % 60L
    if (minutes < 60L) return "${minutes}m ${seconds}s"
    val hours = minutes / 60L
    val remainingMinutes = minutes % 60L
    return "${hours}h ${remainingMinutes}m"
}

/** Graph startup is deferred until a usable local model exists. */
sealed interface GraphUiState {
    data object WaitingForModel : GraphUiState
    data object Checking : GraphUiState
    data class Ready(val path: String?) : GraphUiState
    data class Failed(
        val path: String?,
        val message: String,
        val stackTrace: String = IllegalStateException(message).stackTraceToString()
    ) : GraphUiState
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

/** Pure route-to-label mapping so every executable SDX route is testable without Compose. */
internal data class RouteBadgeUi(val label: String, val active: Boolean)

internal fun routeBadgeUi(route: String): RouteBadgeUi = when (route) {
    "LOCAL_VULKAN" -> RouteBadgeUi("VULKAN", active = true)
    "LOCAL_HEXAGON" -> RouteBadgeUi("HEXAGON", active = true)
    "LOCAL_TENSOR_G3_NNAPI" -> RouteBadgeUi("TENSOR G3", active = true)
    "LOCAL_TENSOR_G5" -> RouteBadgeUi("TENSOR G5", active = true)
    "SDX_GGUF_AOT" -> RouteBadgeUi("SDX GGUF", active = true)
    else -> RouteBadgeUi("NO MODEL", active = false)
}

/** Raw ABI-v2 GGUF generation is blocking; only prepared provider sessions can cancel. */
internal fun routeCanCancelGeneration(route: String): Boolean = when (route) {
    "LOCAL_VULKAN",
    "LOCAL_HEXAGON",
    "LOCAL_TENSOR_G3_NNAPI",
    "LOCAL_TENSOR_G5" -> true
    else -> false
}

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

/** Single-owner policy for imports; an active runtime must be explicitly unloaded first. */
internal fun importBlockedReason(
    importBusy: Boolean,
    generating: Boolean,
    activeModelLoaded: Boolean = false
): String? = when {
    importBusy -> "Another import is already running. Wait for it to finish."
    generating -> "Wait for the current response to finish before importing."
    activeModelLoaded -> "Unload the active model before importing or changing model artifacts."
    else -> null
}

/** Result of selecting a graph file; engine activation may wait for a model. */
sealed interface GraphImportOutcome {
    data class Active(val path: String) : GraphImportOutcome
    data class Deferred(val path: String, val message: String) : GraphImportOutcome
    data class Failed(
        val message: String,
        val stackTrace: String = IllegalStateException(message).stackTraceToString()
    ) : GraphImportOutcome
}

/** Stable phases keep archive/target/runtime errors actionable in first-run UI. */
enum class ProjectImportPhase(val label: String) {
    BLOCKED("Import blocked"),
    COPY("Copy"),
    ARCHIVE("Project archive"),
    MODEL_TARGET("Model target"),
    GRAPH("Knowledge graph"),
    ACTIVATION("Native activation"),
    CLEANUP("Cleanup")
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
        val message: String,
        val stackTrace: String = IllegalStateException(message).stackTraceToString()
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
    is GraphUiState.Failed -> GraphImportOutcome.Failed(
        graphState.message,
        graphState.stackTrace
    )
}
