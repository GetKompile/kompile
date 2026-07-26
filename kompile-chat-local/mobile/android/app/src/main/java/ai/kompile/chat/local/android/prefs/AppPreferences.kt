package ai.kompile.chat.local.android.prefs

import android.content.Context
import android.content.SharedPreferences
import ai.kompile.chat.local.android.BuildConfig

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
        // Accelerator APKs are strictly offline; synchronously remove those legacy values.
        prefs.edit()
            .remove(LEGACY_KEY_REMOTE_BASE_URL)
            .remove(LEGACY_KEY_REMOTE_MODEL)
            .remove(LEGACY_KEY_REMOTE_API_KEY)
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

    // ── Misc ──────────────────────────────────────────────────────────────────

    /** URL opened in an external browser to prepare a target-specific .sdz or .kproject. */
    var modelStagingUrl: String
        get() = prefs.getString(KEY_MODEL_STAGING_URL, BuildConfig.MODEL_STAGING_URL)
            ?: BuildConfig.MODEL_STAGING_URL
        set(value) = prefs.edit().putString(KEY_MODEL_STAGING_URL, value.trim()).apply()

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
        private const val KEY_MODEL_STAGING_URL = "model_staging_url"
        private const val KEY_MAX_TOOL_ROUNDS  = "max_tool_rounds"
        private const val KEY_TEMPERATURE      = "temperature"
        private const val KEY_MAX_TOKENS       = "max_tokens"
        private const val KEY_BOOTSTRAP_DONE   = "bootstrap_done"
    }
}
