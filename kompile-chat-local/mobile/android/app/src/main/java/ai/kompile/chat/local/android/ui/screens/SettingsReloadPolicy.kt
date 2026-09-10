package ai.kompile.chat.local.android.ui.screens

/** Tensor G3 reads generation options per request; other providers retain their existing policy. */
internal fun settingsRequireEngineReload(
    targetProfile: String,
    toolRoundsChanged: Boolean,
    temperatureChanged: Boolean,
    maxTokensChanged: Boolean,
): Boolean = toolRoundsChanged ||
    (targetProfile != "android-arm64-nnapi-accelerator" &&
        (temperatureChanged || maxTokensChanged))
