package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.permission.PermissionService;
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
                .put("prompt", "Read-only audit").put("background", true);
    }

    @Test
    void backgroundRequestFlowsThroughTheContextHook() throws Exception {
        AtomicInteger hookCalls = new AtomicInteger();
        ToolResult result = tool().execute(request(), context(hookCalls, true));
        assertFalse(result.isError(), result.getOutput());
        assertEquals(1, hookCalls.get());
        assertTrue((Boolean) result.getMetadata().get("backgrounded"));
        assertTrue((Boolean) result.getMetadata().get("backgroundRequested"));
    }

    @Test
    void rejectedRequestFallsBackToSynchronousExecution() throws Exception {
        AtomicInteger hookCalls = new AtomicInteger();
        ToolResult result = tool().execute(request(), context(hookCalls, false));
        assertFalse(result.isError(), result.getOutput());
        assertEquals("done", result.getOutput());
        assertFalse((Boolean) result.getMetadata().get("backgrounded"));
        assertTrue((Boolean) result.getMetadata().get("backgroundRequested"));
    }

    @Test
    void missingHookRunsSynchronouslyAndReportsTheUnhonoredRequest() throws Exception {
        // backgroundRequested mirrors what the model asked for; backgrounded=false
        // is the silent-fallback signal, so the pair (true, false) is observable.
        ToolResult result = tool().execute(request(), context(null, false));
        assertFalse(result.isError(), result.getOutput());
        assertEquals("done", result.getOutput());
        assertFalse((Boolean) result.getMetadata().get("backgrounded"));
        assertTrue((Boolean) result.getMetadata().get("backgroundRequested"));
    }

    @Test
    void backgroundDefaultsToFalseAndSyncMetadataStaysHonest() throws Exception {
        ToolResult result = tool().execute(mapper.createObjectNode()
                .put("description", "Sync audit").put("prompt", "Read-only audit"),
                context(new AtomicInteger(), true));
        assertFalse(result.isError(), result.getOutput());
        assertFalse((Boolean) result.getMetadata().get("backgrounded"));
        assertFalse((Boolean) result.getMetadata().get("backgroundRequested"));
    }

    @Test
    void hookExceptionIsTreatedAsRejectionNotToolFailure() throws Exception {
        ToolContext context = new ToolContext("self-background-test", AgentConfig.builder("parent").build(),
                new PermissionService(), directory, new ToolRegistry(mapper));
        context.setAutoApproveAll(true);
        context.setOutputConsumer(ignored -> {});
        context.linkSelfBackgroundRequest(() -> { throw new IllegalStateException("harness race"); });
        ToolResult result = tool().execute(request(), context);
        assertFalse(result.isError(), result.getOutput());
        assertEquals("done", result.getOutput());
        assertFalse((Boolean) result.getMetadata().get("backgrounded"));
    }
}
