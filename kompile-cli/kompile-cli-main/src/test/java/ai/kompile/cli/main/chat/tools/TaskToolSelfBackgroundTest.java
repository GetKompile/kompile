package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent-initiated backgrounding: the task tool requests the owning turn's
 * self-background hook before blocking on the subagent runner. When no hook is
 * installed (headless, child contexts, legacy callers) the request must be a
 * silent no-op that still runs the delegation synchronously.
 */
class TaskToolSelfBackgroundTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentRegistry registry = new AgentRegistry();

    private TaskTool tool() {
        return new TaskTool(registry, (agent, prompt, context) -> "done");
    }

    private ToolContext context(AtomicInteger hookCalls, boolean accepted) {
        ToolContext context = new ToolContext("self-background-test", AgentConfig.builder("parent").build(),
                new PermissionService(), directory, new ToolRegistry(mapper));
        context.setAutoApproveAll(true);
        context.setOutputConsumer(ignored -> {});
        if (hookCalls != null) {
            context.linkSelfBackgroundRequest(() -> {
                hookCalls.incrementAndGet();
                return accepted;
            });
        }
        return context;
    }

    private ObjectNode request() {
        return mapper.createObjectNode().put("description", "Parallel audit")
                .put("prompt", "Read-only audit");
    }

    @Test
    void explicitBackgroundRequestFlowsThroughTheContextHook() throws Exception {
        AtomicInteger hookCalls = new AtomicInteger();
        ToolResult result = tool().execute(request().put("background", true), context(hookCalls, true));
        assertFalse(result.isError(), result.getOutput());
        assertEquals(1, hookCalls.get());
        assertTrue((Boolean) result.getMetadata().get("backgrounded"));
        assertTrue((Boolean) result.getMetadata().get("backgroundRequested"));
    }

    @Test
    void rejectedRequestFallsBackToSynchronousExecution() throws Exception {
        AtomicInteger hookCalls = new AtomicInteger();
        ToolResult result = tool().execute(request().put("background", true), context(hookCalls, false));
        assertFalse(result.isError(), result.getOutput());
        assertEquals("done", result.getOutput());
        assertFalse((Boolean) result.getMetadata().get("backgrounded"));
        assertTrue((Boolean) result.getMetadata().get("backgroundRequested"));
    }

    @Test
    void missingHookRunsSynchronouslyAndReportsTheUnhonoredRequest() throws Exception {
        // An explicit request with no hook falls back silently: (true, false).
        ToolResult result = tool().execute(request().put("background", true), context(null, false));
        assertFalse(result.isError(), result.getOutput());
        assertEquals("done", result.getOutput());
        assertFalse((Boolean) result.getMetadata().get("backgrounded"));
        assertTrue((Boolean) result.getMetadata().get("backgroundRequested"));
    }

    @Test
    void backgroundDefaultsToTrue() throws Exception {
        AtomicInteger hookCalls = new AtomicInteger();
        ToolResult result = tool().execute(request(), context(hookCalls, true));
        assertFalse(result.isError(), result.getOutput());
        assertEquals(1, hookCalls.get());
        assertTrue((Boolean) result.getMetadata().get("backgrounded"));
        assertTrue((Boolean) result.getMetadata().get("backgroundRequested"));
    }

    @Test
    void explicitFalseSkipsTheHookAndReportsSynchronousExecution() throws Exception {
        AtomicInteger hookCalls = new AtomicInteger();
        ToolResult result = tool().execute(request().put("background", false), context(hookCalls, true));
        assertFalse(result.isError(), result.getOutput());
        assertEquals("done", result.getOutput());
        assertEquals(0, hookCalls.get());
        assertFalse((Boolean) result.getMetadata().get("backgrounded"));
        assertFalse((Boolean) result.getMetadata().get("backgroundRequested"));
    }

    @Test
    void schemaAdvertisesBackgroundByDefault() {
        assertTrue(tool().parameterSchema().path("properties").path("background").path("default").asBoolean());
        assertTrue(tool().parameterSchema().path("properties").path("background")
                .path("description").asText().contains("Default: true"));
        assertTrue(tool().description().contains("background=false"));
    }

    @Test
    void providerSchemasKeepTheBackgroundDefaultAndOptOut() {
        ToolRegistry tools = new ToolRegistry(mapper);
        tools.register(tool());
        tools.getDynamicToolManager().activateGroup("delegation");
        AgentConfig parent = AgentConfig.builder("parent").build();
        JsonNode openAiBackground = tools.buildToolDefinitions(parent).get(0)
                .path("function").path("parameters").path("properties").path("background");
        JsonNode directBackground = tools.buildDirectToolDefinitions(parent).get(0)
                .path("inputSchema").path("properties").path("background");
        assertTrue(openAiBackground.path("default").asBoolean());
        assertTrue(directBackground.path("default").asBoolean());
        // Progressive schemas strip JSON defaults and truncate descriptions, so
        // the leading parameter guidance must still explain both behaviors.
        JsonNode progressiveOpenAiBackground = tools.buildProgressiveToolDefinitions(parent).get(0)
                .path("function").path("parameters").path("properties").path("background");
        JsonNode progressiveDirectBackground = tools.buildProgressiveDirectToolDefinitions(parent).get(0)
                .path("inputSchema").path("properties").path("background");
        for (JsonNode background : new JsonNode[] {progressiveOpenAiBackground, progressiveDirectBackground}) {
            String description = background.path("description").asText();
            assertTrue(description.contains("Background by default"), description);
            assertTrue(description.contains("Set false to wait"), description);
        }
    }

    @Test
    void hookExceptionIsTreatedAsRejectionNotToolFailure() throws Exception {
        ToolContext context = new ToolContext("self-background-test", AgentConfig.builder("parent").build(),
                new PermissionService(), directory, new ToolRegistry(mapper));
        context.setAutoApproveAll(true);
        context.setOutputConsumer(ignored -> {});
        context.linkSelfBackgroundRequest(() -> { throw new IllegalStateException("harness race"); });
        ToolResult result = tool().execute(request().put("background", true), context);
        assertFalse(result.isError(), result.getOutput());
        assertEquals("done", result.getOutput());
        assertFalse((Boolean) result.getMetadata().get("backgrounded"));
        assertTrue((Boolean) result.getMetadata().get("backgroundRequested"));
    }
}
