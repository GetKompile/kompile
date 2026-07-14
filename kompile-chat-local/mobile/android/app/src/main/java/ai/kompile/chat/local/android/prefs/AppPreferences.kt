package ai.kompile.chat.local.android.prefs

import android.content.Context
import android.content.SharedPreferences

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

    // ── Remote endpoint ───────────────────────────────────────────────────────

    var remoteBaseUrl: String
        get() = prefs.getString(KEY_REMOTE_BASE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REMOTE_BASE_URL, value).apply()

    var remoteModel: String
        get() = prefs.getString(KEY_REMOTE_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
        set(value) = prefs.edit().putString(KEY_REMOTE_MODEL, value).apply()

    var remoteApiKey: String
        get() = prefs.getString(KEY_REMOTE_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REMOTE_API_KEY, value).apply()

    // ── Local paths (set by the SAF file pickers) ─────────────────────────────

    /** Absolute path inside filesDir where the active .kgraph was copied. */
    var kgraphPath: String
        get() = prefs.getString(KEY_KGRAPH_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_KGRAPH_PATH, value).apply()

    /** Absolute path inside filesDir where the active model file was copied. */
    var modelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL_PATH, value).apply()

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

    /** True once the first-run asset bootstrap has been completed. */
    var bootstrapDone: Boolean
        get() = prefs.getBoolean(KEY_BOOTSTRAP_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_BOOTSTRAP_DONE, value).apply()

    companion object {
        private const val KEY_REMOTE_BASE_URL  = "remote_base_url"
        private const val KEY_REMOTE_MODEL     = "remote_model"
        private const val KEY_REMOTE_API_KEY   = "remote_api_key"
        private const val KEY_KGRAPH_PATH      = "kgraph_path"
        private const val KEY_MODEL_PATH       = "model_path"
        private const val KEY_MAX_TOOL_ROUNDS  = "max_tool_rounds"
        private const val KEY_TEMPERATURE      = "temperature"
        private const val KEY_MAX_TOKENS       = "max_tokens"
        private const val KEY_BOOTSTRAP_DONE   = "bootstrap_done"
    }
}
