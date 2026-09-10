package ai.kompile.chat.local.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsReloadPolicyTest {
    @Test
    fun tensorG3GenerationOnlyChangesKeepSessionAndConversation() {
        for (rounds in listOf(false, true)) {
            for (temperature in listOf(false, true)) {
                for (tokens in listOf(false, true)) {
                    assertEquals(
                        "rounds=$rounds temperature=$temperature tokens=$tokens",
                        rounds,
                        settingsRequireEngineReload(
                            "android-arm64-nnapi-accelerator", rounds, temperature, tokens
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun otherProvidersKeepExistingReloadPolicy() {
        // LiteRT-LM currently binds sampling and token limits at session creation.
        for (target in listOf("android-arm64-google-tensor-g5", "android-arm64-vulkan",
            "android-arm64-hexagon-htp", "unknown")) {
            for (rounds in listOf(false, true)) {
                for (temperature in listOf(false, true)) {
                    for (tokens in listOf(false, true)) {
                        assertEquals(
                            "$target rounds=$rounds temperature=$temperature tokens=$tokens",
                            rounds || temperature || tokens,
                            settingsRequireEngineReload(target, rounds, temperature, tokens),
                        )
                    }
                }
            }
        }
    }
}
