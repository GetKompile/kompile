package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderFastModeCapabilitiesTest {
    @Test
    void supportedProvidersOwnSourcedModelEligibility() {
        for (String provider : List.of("openai", "openai-codex")) {
            var capabilities = ChatProviderRegistry.find(provider).fastModeCapabilities();
            assertTrue(capabilities.supports("gpt-5.4"));
            assertTrue(capabilities.supports("gpt-5.5"));
            assertTrue(capabilities.supports("gpt-5.6-sol"));
            assertTrue(capabilities.supports("gpt-6-astra"));
            assertFalse(capabilities.supports("gpt-5.3-codex-spark"));
            assertFalse(capabilities.supports("gpt-future"));
            assertFalse(capabilities.supports("ft:gpt-5.5:custom"));
            assertFalse(capabilities.sourceUrl().isBlank());
            assertFalse(capabilities.notice().isBlank());
        }
        var anthropic = ChatProviderRegistry.find("anthropic").fastModeCapabilities();
        assertTrue(anthropic.supports("claude-opus-5"));
        assertTrue(anthropic.supports("claude-opus-4-8"));
        assertFalse(anthropic.supports("claude-opus-4-7"), "retired fast mode must not be advertised");
        assertFalse(anthropic.supports("claude-opus-4-6"), "standard-speed fallback is not fast support");
        assertFalse(anthropic.supports("claude-sonnet-4-8"));
        assertFalse(anthropic.supports("claude-haiku-4-5"));
    }

    @Test
    void pickerOnlyOffersSeparateSpeedChoicesForSupportedModels() {
        assertEquals(List.of("off", "on"), SetupWizard.fastModeOptions("openai-codex", "gpt-5.5"));
        assertEquals(List.of("off", "on"), SetupWizard.fastModeOptions("anthropic", "claude-opus-5"));
        for (String provider : List.of("custom", "gemini", "openrouter", "github-copilot", "ollama", "opencode")) {
            assertTrue(SetupWizard.fastModeOptions(provider, "gpt-5.5").isEmpty());
            assertTrue(SetupWizard.fastModeOptions(provider, "claude-opus-5").isEmpty());
        }
        assertTrue(SetupWizard.fastModeOptions("anthropic", "claude-sonnet-4-8").isEmpty());
        assertTrue(SetupWizard.fastModeOptions(null, null).isEmpty());
        assertFalse(ProviderFastModeCapabilities.forProvider("../../anthropic").supports("claude-opus-5"));
    }
}
