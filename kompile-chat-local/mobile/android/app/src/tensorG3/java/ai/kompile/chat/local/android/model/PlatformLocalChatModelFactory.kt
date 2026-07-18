package ai.kompile.chat.local.android.model

import android.content.Context
import org.nd4j.dsp.runtime.SdxRuntime

/**
 * Pixel 8a / Tensor G3 path through libnd4j's NNAPI graph backend.
 *
 * The native runtime enumerates DEVICE_ACCELERATOR implementations, requires one
 * device to cover every operation, pins every segment to that same device, and
 * fails instead of partitioning or falling back to CPU/GPU execution.
 */
internal object PlatformLocalChatModelFactory {

    @Suppress("UNUSED_PARAMETER")
    fun open(
        context: Context,
        modelPath: String,
        temperature: Float,
        maxTokens: Int
    ): PlatformLocalChatSession = SdxPlatformChatSession.open(
        context = context,
        modelPath = modelPath,
        options = SdxRuntime.ModelOptions.mobileNnapiAccelerator(),
        routeName = "LOCAL_TENSOR_G3_NNAPI",
        modelIdPrefix = "sdx-tensor-g3-nnapi"
    )
}
