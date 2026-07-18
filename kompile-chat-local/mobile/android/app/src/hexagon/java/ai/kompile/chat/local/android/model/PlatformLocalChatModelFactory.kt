package ai.kompile.chat.local.android.model

import android.content.Context
import org.nd4j.dsp.runtime.SdxRuntime

/** Qualcomm Hexagon/HTP implementation. No backend or host fallback is exposed. */
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
        options = SdxRuntime.ModelOptions.mobileHexagon(),
        routeName = "LOCAL_HEXAGON",
        modelIdPrefix = "sdx-hexagon"
    )
}
