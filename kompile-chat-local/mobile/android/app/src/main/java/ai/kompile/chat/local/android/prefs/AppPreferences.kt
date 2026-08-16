package ai.kompile.chat.local.android.prefs

import android.content.Context
import android.content.SharedPreferences
import ai.kompile.chat.local.android.BuildConfig
import ai.kompile.chat.local.android.model.ModelPreparationOptions
import org.nd4j.dsp.model.HuggingFaceGgmlResolver

/** Return a safe canonical reference for persistence, or null for malformed/credential-bearing input. */
internal fun canonicalHuggingFaceReferenceOrNull(reference: String): String? {
    val trimmed = reference.trim()
    if (trimmed.isEmpty()) return ""
    return runCatching { HuggingFaceGgmlResolver.parse(trimmed).canonicalReference }.getOrNull()
}

data class ActiveProjectSelection(
    val installationRoot: String,
    val modelPath: String,
    val graphPath: String,
    val sourcesPath: String,
    val sourceCount: Int,
    val projectId: String,
    val projectName: String,
    val revision: String,
    val targetProfile: String
)

/**
 * Thin wrapper around [SharedPreferences] for all user-configurable settings.
 *
 * Settings are stored under the "kompile_chat_prefs" file, which persists across
 * app restarts. All keys and defaults live here so SettingsScreen and ChatViewModel
 * read from the same single source.
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("kompile_chat_prefs", Context.MODE_PRIVATE)

    init {
        // Older prototypes stored an unused remote endpoint and plaintext API key.
        // Accelerator APKs are strictly offline. A pending activation is never authoritative:
        // a process death before promotion must restart from the last proven active selection.
        prefs.edit()
            .remove(LEGACY_KEY_REMOTE_BASE_URL)
            .remove(LEGACY_KEY_REMOTE_MODEL)
            .remove(LEGACY_KEY_REMOTE_API_KEY)
            .clearPendingSelection()
            .commit()
    }

    // ── Local paths (set by the SAF file pickers) ─────────────────────────────

    /** Absolute path inside filesDir where the active .kgraph was copied. */
    var kgraphPath: String
        get() = prefs.getString(KEY_KGRAPH_PATH, "") ?: ""
        set(value) = prefs.edit()
            .putString(KEY_KGRAPH_PATH, value)
            .clearProjectMetadata()
            .apply()

    /** Absolute path inside filesDir where the active model file was copied. */
    var modelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        set(value) = prefs.edit()
            .putString(KEY_MODEL_PATH, value)
            .clearProjectMetadata()
            .apply()

    val activeProjectInstallationRoot: String
        get() = prefs.getString(KEY_PROJECT_INSTALLATION_ROOT, "") ?: ""

    val activeProjectSourcesPath: String
        get() = prefs.getString(KEY_PROJECT_SOURCES_PATH, "") ?: ""

    val activeProjectSourceCount: Int
        get() = prefs.getInt(KEY_PROJECT_SOURCE_COUNT, 0)

    val activeProjectId: String
        get() = prefs.getString(KEY_PROJECT_ID, "") ?: ""

    val activeProjectName: String
        get() = prefs.getString(KEY_PROJECT_NAME, "") ?: ""

    val activeProjectRevision: String
        get() = prefs.getString(KEY_PROJECT_REVISION, "") ?: ""

    val activeProjectTargetProfile: String
        get() = prefs.getString(KEY_PROJECT_TARGET_PROFILE, "") ?: ""

    fun snapshotActiveSelection(): ActiveProjectSelection = ActiveProjectSelection(
        installationRoot = activeProjectInstallationRoot,
        modelPath = modelPath,
        graphPath = kgraphPath,
        sourcesPath = activeProjectSourcesPath,
        sourceCount = activeProjectSourceCount,
        projectId = activeProjectId,
        projectName = activeProjectName,
        revision = activeProjectRevision,
        targetProfile = activeProjectTargetProfile
    )

    /** Publish or restore all runtime and provenance paths in one synchronous transaction. */
    fun activateProject(selection: ActiveProjectSelection): Boolean = prefs.edit()
        .putString(KEY_MODEL_PATH, selection.modelPath)
        .putString(KEY_KGRAPH_PATH, selection.graphPath)
        .putString(KEY_PROJECT_INSTALLATION_ROOT, selection.installationRoot)
        .putString(KEY_PROJECT_SOURCES_PATH, selection.sourcesPath)
        .putInt(KEY_PROJECT_SOURCE_COUNT, selection.sourceCount)
        .putString(KEY_PROJECT_ID, selection.projectId)
        .putString(KEY_PROJECT_NAME, selection.projectName)
        .putString(KEY_PROJECT_REVISION, selection.revision)
        .putString(KEY_PROJECT_TARGET_PROFILE, selection.targetProfile)
        .commit()

    /** Persist a candidate without changing the last proven active selection. */
    fun stagePendingSelection(selection: ActiveProjectSelection): Boolean = prefs.edit()
        .putBoolean(KEY_PENDING_SELECTION, true)
        .putString(KEY_PENDING_MODEL_PATH, selection.modelPath)
        .putString(KEY_PENDING_KGRAPH_PATH, selection.graphPath)
        .putString(KEY_PENDING_PROJECT_INSTALLATION_ROOT, selection.installationRoot)
        .putString(KEY_PENDING_PROJECT_SOURCES_PATH, selection.sourcesPath)
        .putInt(KEY_PENDING_PROJECT_SOURCE_COUNT, selection.sourceCount)
        .putString(KEY_PENDING_PROJECT_ID, selection.projectId)
        .putString(KEY_PENDING_PROJECT_NAME, selection.projectName)
        .putString(KEY_PENDING_PROJECT_REVISION, selection.revision)
        .putString(KEY_PENDING_PROJECT_TARGET_PROFILE, selection.targetProfile)
        .commit()

    /**
     * Atomically make the staged selection active and clear the journal marker. SharedPreferences
     * commits one XML replacement, so startup observes either the old active state plus a stale
     * pending journal, or the fully promoted state, never a mixture.
     */
    fun promotePendingSelection(): Boolean = promotePendingSelection(clearHuggingFaceImport = false)

    /** Atomically publish a proven Hugging Face model and retire its separate resume journal. */
    fun promotePendingSelectionAndClearHuggingFaceImport(): Boolean =
        promotePendingSelection(clearHuggingFaceImport = true)

    private fun promotePendingSelection(clearHuggingFaceImport: Boolean): Boolean {
        if (!prefs.getBoolean(KEY_PENDING_SELECTION, false)) return false
        val pending = ActiveProjectSelection(
            installationRoot = prefs.getString(KEY_PENDING_PROJECT_INSTALLATION_ROOT, "") ?: "",
            modelPath = prefs.getString(KEY_PENDING_MODEL_PATH, "") ?: "",
            graphPath = prefs.getString(KEY_PENDING_KGRAPH_PATH, "") ?: "",
            sourcesPath = prefs.getString(KEY_PENDING_PROJECT_SOURCES_PATH, "") ?: "",
            sourceCount = prefs.getInt(KEY_PENDING_PROJECT_SOURCE_COUNT, 0),
            projectId = prefs.getString(KEY_PENDING_PROJECT_ID, "") ?: "",
            projectName = prefs.getString(KEY_PENDING_PROJECT_NAME, "") ?: "",
            revision = prefs.getString(KEY_PENDING_PROJECT_REVISION, "") ?: "",
            targetProfile = prefs.getString(KEY_PENDING_PROJECT_TARGET_PROFILE, "") ?: ""
        )
        val editor = prefs.edit()
            .putString(KEY_MODEL_PATH, pending.modelPath)
            .putString(KEY_KGRAPH_PATH, pending.graphPath)
            .putString(KEY_PROJECT_INSTALLATION_ROOT, pending.installationRoot)
            .putString(KEY_PROJECT_SOURCES_PATH, pending.sourcesPath)
            .putInt(KEY_PROJECT_SOURCE_COUNT, pending.sourceCount)
            .putString(KEY_PROJECT_ID, pending.projectId)
            .putString(KEY_PROJECT_NAME, pending.projectName)
            .putString(KEY_PROJECT_REVISION, pending.revision)
            .putString(KEY_PROJECT_TARGET_PROFILE, pending.targetProfile)
            .clearPendingSelection()
        if (clearHuggingFaceImport) editor.clearHuggingFaceImportCheckpoint()
        return editor.commit()
    }

    fun discardPendingSelection(): Boolean = prefs.edit().clearPendingSelection().commit()

    // ── Generation options ────────────────────────────────────────────────────

    var maxToolRounds: Int
        get() = prefs.getInt(KEY_MAX_TOOL_ROUNDS, 4)
        set(value) = prefs.edit().putInt(KEY_MAX_TOOL_ROUNDS, value).apply()

    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, 0.7f)
        set(value) = prefs.edit().putFloat(KEY_TEMPERATURE, value).apply()

    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, 1024)
        set(value) = prefs.edit().putInt(KEY_MAX_TOKENS, value).apply()

    // ── Model preparation ─────────────────────────────────────────────────────

    var modelPreparationOptions: ModelPreparationOptions
        get() = ModelPreparationOptions.fromWire(
            weightOptimization = prefs.getString(KEY_MODEL_WEIGHT_OPTIMIZATION, null),
            kvCacheOptimization = prefs.getString(KEY_MODEL_KV_CACHE_OPTIMIZATION, null),
            tensorBatchSize = prefs.getInt(KEY_MODEL_TENSOR_BATCH_SIZE, 4),
            useMemoryMapping = prefs.getBoolean(KEY_MODEL_USE_MEMORY_MAPPING, true),
            diagnosticMode = prefs.getString(KEY_MODEL_DIAGNOSTIC_MODE, null),
        )
        set(value) {
            prefs.edit()
                .putString(KEY_MODEL_WEIGHT_OPTIMIZATION, value.weightOptimization.name)
                .putString(KEY_MODEL_KV_CACHE_OPTIMIZATION, value.kvCacheOptimization.name)
                .putInt(KEY_MODEL_TENSOR_BATCH_SIZE, value.tensorBatchSize)
                .putBoolean(KEY_MODEL_USE_MEMORY_MAPPING, value.useMemoryMapping)
                .putString(KEY_MODEL_DIAGNOSTIC_MODE, value.diagnosticMode.name)
                .apply()
        }

    // ── Misc ──────────────────────────────────────────────────────────────────

    /** URL opened in an external browser to prepare a target-specific .sdz or .kproject. */
    var modelStagingUrl: String
        get() = prefs.getString(KEY_MODEL_STAGING_URL, BuildConfig.MODEL_STAGING_URL)
            ?: BuildConfig.MODEL_STAGING_URL
        set(value) = prefs.edit().putString(KEY_MODEL_STAGING_URL, value.trim()).apply()

    /** Last public Hugging Face reference, canonicalized so credentials or arbitrary queries never persist. */
    var huggingFaceReference: String
        get() {
            val stored = prefs.getString(KEY_HUGGING_FACE_REFERENCE, "") ?: ""
            val canonical = canonicalHuggingFaceReferenceOrNull(stored)
            if (canonical == null || canonical.isEmpty()) {
                if (stored.isNotEmpty()) prefs.edit().remove(KEY_HUGGING_FACE_REFERENCE).apply()
                return ""
            }
            if (canonical != stored) {
                prefs.edit().putString(KEY_HUGGING_FACE_REFERENCE, canonical).apply()
            }
            return canonical
        }
        set(value) {
            val canonical = canonicalHuggingFaceReferenceOrNull(value)
            if (canonical.isNullOrEmpty()) {
                prefs.edit().remove(KEY_HUGGING_FACE_REFERENCE).apply()
            } else {
                prefs.edit().putString(KEY_HUGGING_FACE_REFERENCE, canonical).apply()
            }
        }

    /**
     * Load the independently persisted, process-death-safe Hugging Face import boundary.
     * Invalid persisted data is an explicit failure: the ViewModel renders the original exception
     * and leaves the evidence in place until the user starts a new import or explicitly retries.
     */
    fun loadHuggingFaceImportCheckpoint(): HuggingFaceImportCheckpoint? {
        if (!prefs.contains(KEY_HF_IMPORT_VERSION)) return null
        val checkpoint = HuggingFaceImportCheckpoint(
            rawReference = prefs.getString(KEY_HF_IMPORT_RAW_REFERENCE, "") ?: "",
            repository = prefs.getString(KEY_HF_IMPORT_REPOSITORY, "") ?: "",
            resolvedRevision = prefs.getString(KEY_HF_IMPORT_REVISION, "") ?: "",
            candidatePath = prefs.getString(KEY_HF_IMPORT_CANDIDATE_PATH, "") ?: "",
            expectedBytes = prefs.getLong(
                KEY_HF_IMPORT_EXPECTED_BYTES,
                HuggingFaceImportCheckpoint.UNKNOWN_SIZE
            ),
            expectedSha256 = prefs.getString(KEY_HF_IMPORT_EXPECTED_SHA256, "") ?: "",
            stage = HuggingFaceImportCheckpointStage.valueOf(
                prefs.getString(KEY_HF_IMPORT_STAGE, "") ?: ""
            ),
            observedStep = prefs.getString(KEY_HF_IMPORT_OBSERVED_STEP, "") ?: "",
            observedMessage = prefs.getString(KEY_HF_IMPORT_OBSERVED_MESSAGE, "") ?: "",
            observedAttempt = prefs.getInt(KEY_HF_IMPORT_OBSERVED_ATTEMPT, 1),
            observedMaxAttempts = prefs.getInt(KEY_HF_IMPORT_OBSERVED_MAX_ATTEMPTS, 1),
            observedResumedBytes = prefs.getLong(KEY_HF_IMPORT_OBSERVED_RESUMED_BYTES, 0L),
            observedCompletedBytes = prefs.getLong(KEY_HF_IMPORT_OBSERVED_COMPLETED_BYTES, 0L),
            observedTotalBytes = prefs.getLong(
                KEY_HF_IMPORT_OBSERVED_TOTAL_BYTES,
                HuggingFaceImportCheckpoint.UNKNOWN_SIZE
            ),
            observedRetryWillResumeOrReuse = prefs.getBoolean(
                KEY_HF_IMPORT_OBSERVED_RETRY_REUSE,
                false
            ),
            version = prefs.getInt(KEY_HF_IMPORT_VERSION, 0)
        )
        require(checkpoint.isStructurallyValid()) {
            "The saved Hugging Face import checkpoint is malformed or incompatible with this APK."
        }
        return checkpoint
    }

    /** Synchronously record a safe resume boundary before proceeding to the next import phase. */
    fun saveHuggingFaceImportCheckpoint(checkpoint: HuggingFaceImportCheckpoint): Boolean {
        require(checkpoint.isStructurallyValid()) { "Invalid Hugging Face import checkpoint" }
        return prefs.edit()
            .putInt(KEY_HF_IMPORT_VERSION, checkpoint.version)
            .putString(KEY_HF_IMPORT_RAW_REFERENCE, checkpoint.rawReference)
            .putString(KEY_HF_IMPORT_REPOSITORY, checkpoint.repository)
            .putString(KEY_HF_IMPORT_REVISION, checkpoint.resolvedRevision)
            .putString(KEY_HF_IMPORT_CANDIDATE_PATH, checkpoint.candidatePath)
            .putLong(KEY_HF_IMPORT_EXPECTED_BYTES, checkpoint.expectedBytes)
            .putString(KEY_HF_IMPORT_EXPECTED_SHA256, checkpoint.expectedSha256)
            .putString(KEY_HF_IMPORT_STAGE, checkpoint.stage.name)
            .putString(KEY_HF_IMPORT_OBSERVED_STEP, checkpoint.observedStep)
            .putString(KEY_HF_IMPORT_OBSERVED_MESSAGE, checkpoint.observedMessage)
            .putInt(KEY_HF_IMPORT_OBSERVED_ATTEMPT, checkpoint.observedAttempt)
            .putInt(KEY_HF_IMPORT_OBSERVED_MAX_ATTEMPTS, checkpoint.observedMaxAttempts)
            .putLong(KEY_HF_IMPORT_OBSERVED_RESUMED_BYTES, checkpoint.observedResumedBytes)
            .putLong(KEY_HF_IMPORT_OBSERVED_COMPLETED_BYTES, checkpoint.observedCompletedBytes)
            .putLong(KEY_HF_IMPORT_OBSERVED_TOTAL_BYTES, checkpoint.observedTotalBytes)
            .putBoolean(
                KEY_HF_IMPORT_OBSERVED_RETRY_REUSE,
                checkpoint.observedRetryWillResumeOrReuse
            )
            .commit()
    }

    fun clearHuggingFaceImportCheckpoint(): Boolean =
        prefs.edit().clearHuggingFaceImportCheckpoint().commit()

    /** True once the first-run asset bootstrap has been completed. */
    var bootstrapDone: Boolean
        get() = prefs.getBoolean(KEY_BOOTSTRAP_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_BOOTSTRAP_DONE, value).apply()

    private fun SharedPreferences.Editor.clearProjectMetadata(): SharedPreferences.Editor =
        remove(KEY_PROJECT_INSTALLATION_ROOT)
            .remove(KEY_PROJECT_SOURCES_PATH)
            .remove(KEY_PROJECT_SOURCE_COUNT)
            .remove(KEY_PROJECT_ID)
            .remove(KEY_PROJECT_NAME)
            .remove(KEY_PROJECT_REVISION)
            .remove(KEY_PROJECT_TARGET_PROFILE)

    private fun SharedPreferences.Editor.clearPendingSelection(): SharedPreferences.Editor =
        remove(KEY_PENDING_SELECTION)
            .remove(KEY_PENDING_MODEL_PATH)
            .remove(KEY_PENDING_KGRAPH_PATH)
            .remove(KEY_PENDING_PROJECT_INSTALLATION_ROOT)
            .remove(KEY_PENDING_PROJECT_SOURCES_PATH)
            .remove(KEY_PENDING_PROJECT_SOURCE_COUNT)
            .remove(KEY_PENDING_PROJECT_ID)
            .remove(KEY_PENDING_PROJECT_NAME)
            .remove(KEY_PENDING_PROJECT_REVISION)
            .remove(KEY_PENDING_PROJECT_TARGET_PROFILE)

    private fun SharedPreferences.Editor.clearHuggingFaceImportCheckpoint(): SharedPreferences.Editor =
        remove(KEY_HF_IMPORT_VERSION)
            .remove(KEY_HF_IMPORT_RAW_REFERENCE)
            .remove(KEY_HF_IMPORT_REPOSITORY)
            .remove(KEY_HF_IMPORT_REVISION)
            .remove(KEY_HF_IMPORT_CANDIDATE_PATH)
            .remove(KEY_HF_IMPORT_EXPECTED_BYTES)
            .remove(KEY_HF_IMPORT_EXPECTED_SHA256)
            .remove(KEY_HF_IMPORT_STAGE)
            .remove(KEY_HF_IMPORT_OBSERVED_STEP)
            .remove(KEY_HF_IMPORT_OBSERVED_MESSAGE)
            .remove(KEY_HF_IMPORT_OBSERVED_ATTEMPT)
            .remove(KEY_HF_IMPORT_OBSERVED_MAX_ATTEMPTS)
            .remove(KEY_HF_IMPORT_OBSERVED_RESUMED_BYTES)
            .remove(KEY_HF_IMPORT_OBSERVED_COMPLETED_BYTES)
            .remove(KEY_HF_IMPORT_OBSERVED_TOTAL_BYTES)
            .remove(KEY_HF_IMPORT_OBSERVED_RETRY_REUSE)

    companion object {
        private const val LEGACY_KEY_REMOTE_BASE_URL = "remote_base_url"
        private const val LEGACY_KEY_REMOTE_MODEL = "remote_model"
        private const val LEGACY_KEY_REMOTE_API_KEY = "remote_api_key"
        private const val KEY_KGRAPH_PATH      = "kgraph_path"
        private const val KEY_MODEL_PATH       = "model_path"
        private const val KEY_PROJECT_INSTALLATION_ROOT = "project_installation_root"
        private const val KEY_PROJECT_SOURCES_PATH = "project_sources_path"
        private const val KEY_PROJECT_SOURCE_COUNT = "project_source_count"
        private const val KEY_PROJECT_ID = "project_id"
        private const val KEY_PROJECT_NAME = "project_name"
        private const val KEY_PROJECT_REVISION = "project_revision"
        private const val KEY_PROJECT_TARGET_PROFILE = "project_target_profile"
        private const val KEY_PENDING_SELECTION = "pending_selection"
        private const val KEY_PENDING_MODEL_PATH = "pending_model_path"
        private const val KEY_PENDING_KGRAPH_PATH = "pending_kgraph_path"
        private const val KEY_PENDING_PROJECT_INSTALLATION_ROOT = "pending_project_installation_root"
        private const val KEY_PENDING_PROJECT_SOURCES_PATH = "pending_project_sources_path"
        private const val KEY_PENDING_PROJECT_SOURCE_COUNT = "pending_project_source_count"
        private const val KEY_PENDING_PROJECT_ID = "pending_project_id"
        private const val KEY_PENDING_PROJECT_NAME = "pending_project_name"
        private const val KEY_PENDING_PROJECT_REVISION = "pending_project_revision"
        private const val KEY_PENDING_PROJECT_TARGET_PROFILE = "pending_project_target_profile"
        private const val KEY_MODEL_STAGING_URL = "model_staging_url"
        private const val KEY_MODEL_WEIGHT_OPTIMIZATION = "model_weight_optimization"
        private const val KEY_MODEL_KV_CACHE_OPTIMIZATION = "model_kv_cache_optimization"
        private const val KEY_MODEL_TENSOR_BATCH_SIZE = "model_tensor_batch_size"
        private const val KEY_MODEL_USE_MEMORY_MAPPING = "model_use_memory_mapping"
        private const val KEY_MODEL_DIAGNOSTIC_MODE = "model_diagnostic_mode"
        private const val KEY_HUGGING_FACE_REFERENCE = "hugging_face_reference"
        private const val KEY_HF_IMPORT_VERSION = "hf_import_version"
        private const val KEY_HF_IMPORT_RAW_REFERENCE = "hf_import_raw_reference"
        private const val KEY_HF_IMPORT_REPOSITORY = "hf_import_repository"
        private const val KEY_HF_IMPORT_REVISION = "hf_import_revision"
        private const val KEY_HF_IMPORT_CANDIDATE_PATH = "hf_import_candidate_path"
        private const val KEY_HF_IMPORT_EXPECTED_BYTES = "hf_import_expected_bytes"
        private const val KEY_HF_IMPORT_EXPECTED_SHA256 = "hf_import_expected_sha256"
        private const val KEY_HF_IMPORT_STAGE = "hf_import_stage"
        private const val KEY_HF_IMPORT_OBSERVED_STEP = "hf_import_observed_step"
        private const val KEY_HF_IMPORT_OBSERVED_MESSAGE = "hf_import_observed_message"
        private const val KEY_HF_IMPORT_OBSERVED_ATTEMPT = "hf_import_observed_attempt"
        private const val KEY_HF_IMPORT_OBSERVED_MAX_ATTEMPTS = "hf_import_observed_max_attempts"
        private const val KEY_HF_IMPORT_OBSERVED_RESUMED_BYTES = "hf_import_observed_resumed_bytes"
        private const val KEY_HF_IMPORT_OBSERVED_COMPLETED_BYTES = "hf_import_observed_completed_bytes"
        private const val KEY_HF_IMPORT_OBSERVED_TOTAL_BYTES = "hf_import_observed_total_bytes"
        private const val KEY_HF_IMPORT_OBSERVED_RETRY_REUSE = "hf_import_observed_retry_reuse"
        private const val KEY_MAX_TOOL_ROUNDS  = "max_tool_rounds"
        private const val KEY_TEMPERATURE      = "temperature"
        private const val KEY_MAX_TOKENS       = "max_tokens"
        private const val KEY_BOOTSTRAP_DONE   = "bootstrap_done"
    }
}
