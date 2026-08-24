package ai.kompile.chat.local.android.model

import android.content.Context

/** Qualcomm Hexagon/HTP implementation. No backend or host fallback is exposed. */
internal object PlatformLocalChatModelFactory {

    @Suppress("UNUSED_PARAMETER")
    fun open(
        context: Context,
        modelPath: String,
        temperature: Float,
        maxTokens: Int,
        diagnosticModelPath: String = modelPath,
        diagnosticMode: ModelDiagnosticMode = ModelDiagnosticMode.OFF,
        expectedCompileKey: String? = null,
    ): PlatformLocalChatSession = SdxPlatformChatSession.open(
        context = context,
        modelPath = modelPath,
        diagnosticModelPath = diagnosticModelPath,
        diagnosticMode = diagnosticMode,
        expectedCompileKey = expectedCompileKey,
        routeName = "LOCAL_HEXAGON",
        modelIdPrefix = "sdx-hexagon"
    )
}
