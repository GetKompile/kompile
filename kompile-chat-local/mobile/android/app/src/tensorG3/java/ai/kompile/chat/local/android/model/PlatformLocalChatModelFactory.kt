package ai.kompile.chat.local.android.model

import android.content.Context

/**
 * Pixel 8a / Tensor G3 path through SDX's ARM64 hybrid placement policy.
 *
 * NNAPI-capable islands are pinned to one DEVICE_ACCELERATOR. Remaining ARM64
 * islands execute through SDX functional replay; raw slot-by-slot is reserved
 * for graph warmup and is never the steady-state runtime backend.
 */
internal object PlatformLocalChatModelFactory {

    fun prepareStorageMutation(context: Context) {
        SdxPlatformChatSession.prepareStorageMutation(context)
    }

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
        routeName = "LOCAL_TENSOR_G3_NNAPI",
        modelIdPrefix = "sdx-tensor-g3-nnapi"
    )
}
