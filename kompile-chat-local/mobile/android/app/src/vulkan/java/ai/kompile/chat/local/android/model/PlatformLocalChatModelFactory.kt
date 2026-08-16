package ai.kompile.chat.local.android.model

import android.content.Context

/**
 * Android Vulkan implementation. The runtime requires bundle-owned AOT SPIR-V,
 * records the lowered command sequence, and fails closed instead of using CPU/NNAPI.
 */
internal object PlatformLocalChatModelFactory {

    @Suppress("UNUSED_PARAMETER")
    fun open(
        context: Context,
        modelPath: String,
        temperature: Float,
        maxTokens: Int,
        diagnosticModelPath: String = modelPath,
        diagnosticMode: ModelDiagnosticMode = ModelDiagnosticMode.STANDARD,
    ): PlatformLocalChatSession = SdxPlatformChatSession.open(
        context = context,
        modelPath = modelPath,
        diagnosticModelPath = diagnosticModelPath,
        diagnosticMode = diagnosticMode,
        routeName = "LOCAL_VULKAN",
        modelIdPrefix = "sdx-vulkan"
    )
}
