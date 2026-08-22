package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderThinkingConfigTest {
    private static final List<String> PROVIDERS = List.of(
            "openai",
            "openai-codex",
            "anthropic",
            "gemini",
            "ollama",
            "openrouter",
            "xai",
            "github-copilot",
            "radius",
            "deepseek",
            "groq",
            "kompile-local",
            "opencode",
            "custom");

    @AfterEach
    void clearCache() {
        ProviderThinkingConfig.clearCacheForTests();
    }

    @Test
    void everySelectableProviderHasAnExplicitlySourcedResource() {
        for (String provider : PROVIDERS) {
            ProviderThinkingConfig.Config config =
                    ProviderThinkingConfig.load(provider).orElseThrow();
            assertEquals(provider, config.provider());
            assertEquals(ProviderThinkingConfig.DOCUMENTED_FALLBACK,
                    config.metadataSource());
            assertTrue(config.resourcePath().endsWith("/" + provider + ".json"));
            assertTrue(config.source().url().startsWith("https://")
                    || config.source().url().startsWith("repo:"));
            assertTrue(config.source().notice().contains(
                    ProviderThinkingConfig.DOCUMENTED_FALLBACK));
            assertEquals("2026-08-20", config.source().verifiedAt());
        }
    }

    @Test
    void documentedFallbackPreservesWireValuesDefaultsAndPointsToItsResource() {
        ThinkingCapabilityProvider.ThinkingCapabilities codex =
                ProviderThinkingConfig.forProvider("openai-codex").resolve(
                        new LiveModelDiscovery.Model("gpt-5.6-terra", List.of()));

        assertEquals(ThinkingCapabilityProvider.Source.DOCUMENTED_FALLBACK, codex.source());
        assertEquals(List.of("low", "medium", "high", "xhigh", "max", "ultra"),
                codex.options().stream().map(ThinkingCapabilityProvider.Option::value).toList());
        assertEquals("medium", codex.defaultValue());
        assertTrue(codex.sourceIndicator().contains("DOCUMENTED FALLBACK"));
        assertTrue(codex.sourceIndicator().contains("openai-codex.json"));
        assertTrue(codex.sourceIndicator().contains("developers.openai.com/codex/app-server"));
        String pickerIndicator = SetupWizard.thinkingOptions(
                "openai-codex", "gpt-5.6-terra").get(0).label();
        assertTrue(pickerIndicator.contains("DOCUMENTED FALLBACK"));
        assertTrue(pickerIndicator.contains("openai-codex.json"));
        assertTrue(pickerIndicator.contains("developers.openai.com/codex/app-server"));

        ThinkingCapabilityProvider.ThinkingCapabilities xai =
                ProviderThinkingConfig.forProvider("xai").resolve(
                        new LiveModelDiscovery.Model("grok-4.6", List.of()));
        assertEquals(List.of("low", "medium", "high", "xhigh"),
                xai.options().stream().map(ThinkingCapabilityProvider.Option::value).toList());
        assertEquals("high", xai.defaultValue());
        assertTrue(xai.mandatory());

        ThinkingCapabilityProvider.ThinkingCapabilities groq =
                ProviderThinkingConfig.forProvider("groq").resolve(
                        new LiveModelDiscovery.Model("openai/gpt-oss-120b", List.of()));
        assertEquals(List.of("low", "medium", "high"),
                groq.options().stream().map(ThinkingCapabilityProvider.Option::value).toList());
    }

    @Test
    void liveProviderMetadataAlwaysWinsOverTheDocumentedFallback() {
        LiveModelDiscovery.Model live = new LiveModelDiscovery.Model(
                "gpt-5.6",
                List.of("wire-low", "wire-high"),
                Map.of("wire-low", "Provider low", "wire-high", "Provider high"),
                "wire-high",
                false,
                "live:test-provider");

        ThinkingCapabilityProvider.ThinkingCapabilities capabilities =
                ChatProviderRegistry.find("openai")
                        .thinkingCapabilityProvider().resolve(live);

        assertEquals(ThinkingCapabilityProvider.Source.LIVE_PROVIDER, capabilities.source());
        assertEquals(List.of("wire-low", "wire-high"),
                capabilities.options().stream()
                        .map(ThinkingCapabilityProvider.Option::value).toList());
        assertEquals("wire-high", capabilities.defaultValue());
        assertFalse(capabilities.documentedFallback());
    }

    @Test
    void parsesAnthropicAndOpenRouterNativeCapabilityShapes() {
        LiveModelDiscovery.Model anthropic = LiveModelDiscovery.parseHttpModels("""
                {"data":[{"id":"claude-live","capabilities":{"effort":{
                  "supported":true,
                  "low":{"supported":true},
                  "medium":{"supported":true},
                  "high":{"supported":true},
                  "xhigh":{"supported":false},
                  "max":{"supported":true}
                }}}]}
                """, "anthropic").get(0);
        assertEquals(List.of("low", "medium", "high", "max"), anthropic.variants());

        LiveModelDiscovery.Model openRouter = LiveModelDiscovery.parseHttpModels("""
                {"data":[{"id":"router-live","reasoning":{
                  "supported_efforts":["high","medium","low","minimal"],
                  "default_effort":"medium",
                  "mandatory":true
                }}]}
                """, "openrouter").get(0);
        assertEquals(List.of("high", "medium", "low", "minimal"), openRouter.variants());
        assertEquals("medium", openRouter.defaultVariant());
        assertTrue(openRouter.reasoningMandatory());
    }

    @Test
    void unmatchedModelsRemainFreeOfAThinkingSelector() {
        assertFalse(ProviderThinkingConfig.forProvider("openai").resolve(
                new LiveModelDiscovery.Model("gpt-4o", List.of())).supported());
        assertFalse(ProviderThinkingConfig.forProvider("anthropic").resolve(
                new LiveModelDiscovery.Model("unknown-claude", List.of())).supported());
        assertTrue(SetupWizard.thinkingOptions("openai", "gpt-4o").isEmpty());
    }
}
