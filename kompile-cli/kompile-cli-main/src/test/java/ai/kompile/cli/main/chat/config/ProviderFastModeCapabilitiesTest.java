package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderFastModeCapabilitiesTest {
    @Test
    void providerContractsAcceptAllModelIdsWithoutAnAllowlist() {
        for (String provider : List.of("openai", "openai-codex", "anthropic", "gemini", "groq", "openrouter", "xai")) {
            var capabilities = ChatProviderRegistry.find(provider).fastModeCapabilities();
            for (String model : List.of("day-one-unlisted-model", "gpt-5.3-codex-spark",
                    "ft:gpt-5.5:custom", "claude-sonnet-4-8", "claude-haiku-4-5")) {
                assertTrue(capabilities.supports(model), provider + ": " + model);
                assertEquals(List.of("off", "on"), SetupWizard.fastModeOptions(provider, model));
            }
            assertFalse(capabilities.supports(null));
            assertFalse(capabilities.supports("  "));
            assertFalse(capabilities.sourceUrl().isBlank());
            assertFalse(capabilities.notice().isBlank());
        }
    }

    @Test
    void unsupportedRoutesDoNotInheritPaidFieldsFromModelNames() {
        for (String provider : List.of("custom", "github-copilot", "opencode", "deepseek", "zai", "unknown")) {
            assertTrue(SetupWizard.fastModeOptions(provider, "gpt-5.5").isEmpty());
            assertTrue(SetupWizard.fastModeOptions(provider, "claude-opus-5").isEmpty());
        }
        assertTrue(SetupWizard.fastModeOptions(null, null).isEmpty());
        assertFalse(ProviderFastModeCapabilities.forProvider("../../anthropic").supports("claude-opus-5"));
        assertTrue(ProviderFastModeCapabilities.forProvider(" OpenAI ").supports("future-model"));
    }
}
