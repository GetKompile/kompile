package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** resolve_model search tool: providers/families listings and models validation. */
class ResolveModelToolTest {

    @TempDir
    Path workDir;

    final ObjectMapper mapper = new ObjectMapper();

    private ToolResult run(Map<String, Object> args) {
        return new ResolveModelTool(mapper, workDir).execute(args);
    }

    @Test
    void providersActionListsRegisteredProvidersWithConfiguredFlag() {
        ToolResult result = run(Map.of("action", "providers"));
        assertFalse(result.isError(), () -> "unexpected error: " + result.getOutput());
        JsonNode parsed = read(result);
        JsonNode providers = parsed.path("providers");
        assertTrue(providers.isArray() && providers.size() > 0, "providers must be listed");
        boolean sawAnthropic = false;
        for (JsonNode provider : providers) {
            assertTrue(provider.hasNonNull("id"));
            assertTrue(provider.hasNonNull("label"));
            assertTrue(provider.has("configured"));
            if ("anthropic".equals(provider.path("id").asText())) sawAnthropic = true;
        }
        assertTrue(sawAnthropic, "anthropic must be registered");
    }

    @Test
    void familiesActionReturnsRoutingTable() {
        ToolResult result = run(Map.of("action", "families"));
        assertFalse(result.isError(), () -> "unexpected error: " + result.getOutput());
        JsonNode families = read(result).path("families");
        assertTrue(families.isArray() && families.size() > 0);
        boolean sawClaudeOwner = false;
        for (JsonNode row : families) {
            if ("anthropic".equals(row.path("provider").asText())) {
                sawClaudeOwner = true;
                assertEquals("Anthropic", row.path("providerLabel").asText());
            }
        }
        assertTrue(sawClaudeOwner, "anthropic family row must exist");
    }

    @Test
    void modelsActionRequiresProvider() {
        ToolResult result = run(Map.of("action", "models"));
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("provider is required"));
    }

    @Test
    void modelsActionRejectsUnknownProvider() {
        ToolResult result = run(Map.of("action", "models", "provider", "not-a-provider"));
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Unknown provider"));
    }

    @Test
    void invalidActionIsAnError() {
        ToolResult result = run(Map.of("action", "teleport"));
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("action must be"));
    }

    @Test
    void modelSearchReturnsCorrectionFieldsForTheExactAuthRoute() {
        var parent = new ai.kompile.cli.main.chat.config.ChatConfig("openai-codex", "old-secret", "gpt-model", null);
        for (String method : java.util.List.of("oauth", "api-key")) {
            ResolveModelTool tool = new ResolveModelTool(mapper, workDir, () -> parent) {
                @Override ai.kompile.cli.main.chat.config.ModelDiscovery.Result discoverModels(
                        ai.kompile.cli.main.chat.config.ChatConfig route) {
                    assertEquals("anthropic", route.getProvider());
                    assertEquals(method, route.getAuthenticationMethod());
                    assertEquals("oauth".equals(method), route.isClaudeCliNative());
                    return ai.kompile.cli.main.chat.config.ModelDiscovery.Result.success(java.util.List.of(
                            new ai.kompile.cli.main.chat.config.LiveModelDiscovery.Model("claude-sonnet-4-5", java.util.List.of()),
                            new ai.kompile.cli.main.chat.config.LiveModelDiscovery.Model("claude-haiku-4-5", java.util.List.of())), java.util.List.of());
                }
            };
            ToolResult result = tool.execute(Map.of("action", "models", "provider", "anthropic",
                    "authentication_method", method, "query", "SONNET"));
            assertFalse(result.isError(), result.getOutput());
            JsonNode response = read(result);
            assertEquals(1, response.path("models").size());
            JsonNode task = response.path("models").get(0).path("taskArguments");
            assertEquals("anthropic", task.path("provider").asText());
            assertEquals(method, task.path("authentication_method").asText());
            assertEquals("claude-sonnet-4-5", task.path("model").asText());
            assertEquals("openai-codex", parent.getProvider());
        }
    }

    @Test
    void catalogFailureNeverSilentlyRetriesAnotherAuthRoute() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ResolveModelTool tool = new ResolveModelTool(mapper, workDir, () -> null) {
            @Override ai.kompile.cli.main.chat.config.ModelDiscovery.Result discoverModels(
                    ai.kompile.cli.main.chat.config.ChatConfig route) {
                calls.incrementAndGet();
                assertTrue(route.isClaudeCliNative());
                return ai.kompile.cli.main.chat.config.ModelDiscovery.Result.failure(
                        ai.kompile.cli.main.chat.config.ModelDiscovery.Status.AUTH_REQUIRED, "Claude login required", java.util.List.of());
            }
        };
        ToolResult result = tool.execute(Map.of("action", "models", "provider", "anthropic"));
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("authentication_method=oauth"));
        assertTrue(result.getOutput().contains("no API-key/subscription fallback"));
        assertEquals(1, calls.get());
    }

    @Test
    void hostedModelIsNotMislabelledAsItsFamilyOwner() {
        ResolveModelTool tool = new ResolveModelTool(mapper, workDir, () -> null) {
            @Override ai.kompile.cli.main.chat.config.ModelDiscovery.Result discoverModels(
                    ai.kompile.cli.main.chat.config.ChatConfig route) {
                return ai.kompile.cli.main.chat.config.ModelDiscovery.Result.success(java.util.List.of(
                        new ai.kompile.cli.main.chat.config.LiveModelDiscovery.Model("claude-sonnet-4-5", java.util.List.of())), java.util.List.of());
            }
        };
        ToolResult result = tool.execute(Map.of("action", "models", "provider", "github-copilot"));
        assertFalse(result.isError(), result.getOutput());
        assertEquals("github-copilot", read(result).path("models").get(0).path("routesTo").asText());
        assertTrue(tool.compactHint().length() <= 200);
        assertTrue(tool.compactHint().contains("agent=kompile"));
    }

    private JsonNode read(ToolResult result) {
        try {
            return mapper.readTree(result.getOutput());
        } catch (Exception e) {
            throw new AssertionError("result output is not JSON: " + result.getOutput(), e);
        }
    }
}
