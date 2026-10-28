package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.config.ModelRouting;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Routing rules for delegation requests that specify only a model. */
class ModelRoutingTest {

    private static Set<String> providers(String... ids) {
        return new LinkedHashSet<>(java.util.Arrays.asList(ids));
    }

    @Test
    void familyOwnersResolveExactly() {
        assertEquals("anthropic", ModelRouting.familyOwner("claude-sonnet-4-5"));
        assertEquals("openai", ModelRouting.familyOwner("gpt-5.2"));
        assertEquals("gemini", ModelRouting.familyOwner("gemini-3-pro"));
        assertEquals("xai", ModelRouting.familyOwner("grok-4"));
        assertEquals("zai", ModelRouting.familyOwner("glm-5.3-flash"));
        assertEquals("deepseek", ModelRouting.familyOwner("deepseek-v4"));
        assertEquals("groq", ModelRouting.familyOwner("llama-4-scout"));
        assertEquals("openai-codex", ModelRouting.familyOwner("codex-mini-latest"));
        assertNull(ModelRouting.familyOwner("my-local-finetune"));
        assertNull(ModelRouting.familyOwner(null));
        assertNull(ModelRouting.familyOwner(""));
    }

    @Test
    void configuredRouteHostingTheFamilyWins() {
        // Codex hosts gpt-* — a configured codex route with a gpt model stays.
        ModelRouting.Route kept = ModelRouting.resolve("gpt-6.1-sol", "openai-codex",
                providers("openai", "openai-codex", "anthropic"));
        assertEquals("openai-codex", kept.provider());
        // github-copilot hosts claude-* — a configured copilot route with claude stays.
        ModelRouting.Route copilot = ModelRouting.resolve("claude-sonnet-4-5", "github-copilot",
                providers("anthropic", "github-copilot"));
        assertEquals("github-copilot", copilot.provider());
    }

    @Test
    void firstPartyOwnerIsTheDeterministicSwitchTarget() {
        // The reported case: claude-* dispatched on the codex route auto-routes
        // to anthropic even when aggregator hosts are configured — the owner wins.
        ModelRouting.Route switched = ModelRouting.resolve("claude-sonnet-4-5", "openai-codex",
                providers("openai-codex", "anthropic", "openrouter", "github-copilot"));
        assertEquals("anthropic", switched.provider());
        // glm-* on a codex route switches to zai.
        ModelRouting.Route glm = ModelRouting.resolve("glm-5.3-flash", "openai-codex",
                providers("openai-codex", "zai"));
        assertEquals("zai", glm.provider());
    }

    @Test
    void ownerAbsentSingleConfiguredHostRoutesToIt() {
        // No anthropic configured, but openrouter is: claude-* has exactly one
        // configured host, so it routes there instead of failing.
        ModelRouting.Route viaAggregator = ModelRouting.resolve("claude-sonnet-4-5", "openai-codex",
                providers("openai-codex", "openrouter"));
        assertEquals("openrouter", viaAggregator.provider());
    }

    @Test
    void multipleConfiguredHostsWithoutOwnerIsAmbiguous() {
        ModelRouting.AmbiguousModelRouteException e = assertThrows(
                ModelRouting.AmbiguousModelRouteException.class,
                () -> ModelRouting.resolve("claude-sonnet-4-5", "openai-codex",
                        providers("openai-codex", "openrouter", "github-copilot")));
        assertTrue(e.candidates().contains("openrouter"));
        assertTrue(e.candidates().contains("github-copilot"));
        assertEquals(2, e.candidates().size());
        assertTrue(e.getMessage().contains("provider=<id>"));
    }

    @Test
    void familyWithNoConfiguredHostIsUnroutable() {
        ModelRouting.UnroutableModelException e = assertThrows(
                ModelRouting.UnroutableModelException.class,
                () -> ModelRouting.resolve("grok-4", "openai-codex",
                        providers("openai-codex", "anthropic")));
        assertTrue(e.getMessage().contains("xai"));
    }

    @Test
    void unrecognizedFamilyStaysOnConfiguredRoute() {
        ModelRouting.Route kept = ModelRouting.resolve("my-local-finetune", "openai-codex",
                providers("openai-codex", "anthropic"));
        assertEquals("openai-codex", kept.provider());
        assertTrue(kept.reason().contains("unrecognized"));
    }

    @Test
    void nullModelKeepsConfiguredRoute() {
        ModelRouting.Route kept = ModelRouting.resolve(null, "openai-codex",
                providers("openai-codex", "anthropic"));
        assertEquals("openai-codex", kept.provider());
    }

    @Test
    void familyCatalogListsOwnersAndHosts() {
        var catalog = ModelRouting.familyCatalog();
        assertFalse(catalog.isEmpty());
        boolean sawAnthropic = false;
        for (var row : catalog) {
            if ("anthropic".equals(row.get("provider"))) {
                sawAnthropic = true;
                @SuppressWarnings("unchecked")
                java.util.List<String> families = (java.util.List<String>) row.get("families");
                assertTrue(families.contains("claude-*"));
                @SuppressWarnings("unchecked")
                java.util.List<String> also = (java.util.List<String>) row.get("alsoHostedOn");
                assertTrue(also.contains("github-copilot"));
                assertTrue(also.contains("openrouter"));
            }
        }
        assertTrue(sawAnthropic, "anthropic row must exist in the family catalog");
    }
}
