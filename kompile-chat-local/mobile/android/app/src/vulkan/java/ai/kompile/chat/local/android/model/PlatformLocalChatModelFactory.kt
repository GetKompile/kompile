package ai.kompile.chat.local.android.model

import android.content.Context
import org.nd4j.dsp.runtime.SdxRuntime

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
        maxTokens: Int
    ): PlatformLocalChatSession = SdxPlatformChatSession.open(
        context = context,
        modelPath = modelPath,
        options = SdxRuntime.ModelOptions.mobileVulkan(),
        routeName = "LOCAL_VULKAN",
        modelIdPrefix = "sdx-vulkan"
    )
}
