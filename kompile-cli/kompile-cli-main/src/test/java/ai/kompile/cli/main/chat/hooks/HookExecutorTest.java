package ai.kompile.cli.main.chat.hooks;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class HookExecutorTest {
    @TempDir Path directory;
    HookManager manager;
    ToolContext context;
    JsonNode input;

    @BeforeEach void setup() throws Exception {
        manager = new HookManager(directory, directory.resolve("home"));
        var permissions = new PermissionService();
        permissions.setUserOverride("hooks", PermissionService.PermissionLevel.ALLOW);
        context = new ToolContext("hook-test-session", null, permissions, directory, null);
        context.setOutputConsumer(line -> {});
        input = JsonUtils.standardMapper().readTree("{\"path\":\"space \\\"quotes\\\" $HOME; $(false)\"}");
    }
    void add(String event, String command) throws Exception { add(event, command, 5); }
    void add(String event, String command, int timeout) throws Exception {
        var request = JsonUtils.standardMapper().createObjectNode();
        request.put("action", "add").put("event", event).put("command", command).put("timeout", timeout).put("matcher", "^write$");
        manager.manage(request);
    }
    ToolResult execute(HookExecutor.Invocation invocation) throws Exception {
        return HookExecutor.execute("write", input, context, invocation, manager);
    }

    @Test void absentHooksPreserveIdentityAndExecuteOnce() throws Exception {
        ToolResult original = ToolResult.success("ok");
        AtomicInteger calls = new AtomicInteger();
        assertSame(original, execute(args -> { calls.incrementAndGet(); return original; }));
        assertEquals(1, calls.get());
    }
    @Test void preExitTwoBlocksSideEffect() throws Exception {
        add("PreToolUse", "printf 'policy says no' >&2; exit 2");
        ToolResult result = execute(args -> { fail("tool must not run"); return null; });
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("policy says no"));
    }
    @Test void jsonDenyAndAskCannotAuthorizeExecution() throws Exception {
        for (String decision : new String[]{"deny", "ask"}) {
            add("PreToolUse", "printf '%s' '{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\""
                    + decision + "\",\"permissionDecisionReason\":\"approval needed\"}}'");
            assertTrue(execute(args -> { fail(); return null; }).isError());
        }
    }
    @Test void allowStillRunsDelegatePermissionChecks() throws Exception {
        add("PreToolUse", "printf '%s' '{\"hookSpecificOutput\":{\"permissionDecision\":\"allow\"}}'");
        context.getPermissionService().setUserOverride("write", PermissionService.PermissionLevel.DENY);
        assertThrows(ToolExecutionException.class, () -> execute(args -> {
            context.checkPermission("write", "fixture");
            return ToolResult.success("impossible");
        }));
    }
    @Test void successfulAndFailedToolsFireDifferentEvents() throws Exception {
        add("PostToolUse", "printf success >> success.txt");
        add("PostToolUseFailure", "printf failure >> failure.txt");
        assertFalse(execute(args -> ToolResult.success("ok")).isError());
        assertEquals("success", Files.readString(directory.resolve("success.txt")));
        assertFalse(Files.exists(directory.resolve("failure.txt")));
        assertTrue(execute(args -> ToolResult.error("bad tool")).isError());
        assertEquals("failure", Files.readString(directory.resolve("failure.txt")));
        assertEquals("success", Files.readString(directory.resolve("success.txt")));
    }
    @Test void exceptionFailureHookRunsWithoutSwallowingOriginalException() throws Exception {
        add("PostToolUseFailure", "IFS= read -r payload; printf '%s' \"$payload\" > failure.json");
        ToolExecutionException original = new ToolExecutionException("fixture failure");
        assertSame(original, assertThrows(ToolExecutionException.class, () -> execute(args -> { throw original; })));
        JsonNode payload = JsonUtils.standardMapper().readTree(Files.readString(directory.resolve("failure.json")));
        assertEquals("fixture failure", payload.path("error").asText());
        assertEquals("PostToolUseFailure", payload.path("hook_event_name").asText());
    }
    @Test void payloadUsesStdinNotShellInterpolationAndPostContainsResponse() throws Exception {
        add("PostToolUse", "IFS= read -r payload; printf '%s' \"$payload\" > payload.json; printf '%s' \"$KOMPILE_SESSION_ID\" > session.txt");
        execute(args -> ToolResult.success("title", "fixture output", Map.of("fixture", true)));
        JsonNode payload = JsonUtils.standardMapper().readTree(Files.readString(directory.resolve("payload.json")));
        assertEquals(input, payload.path("tool_input"));
        assertEquals("write", payload.path("tool_name").asText());
        assertEquals(directory.toString(), payload.path("cwd").asText());
        assertEquals("fixture output", payload.path("tool_response").path("output").asText());
        assertTrue(payload.path("tool_response").path("metadata").path("fixture").asBoolean());
        assertEquals("hook-test-session", Files.readString(directory.resolve("session.txt")));
    }
    @Test void timeoutFailsClosedAndStopsSpawnedChild() throws Exception {
        add("PreToolUse", "sleep 15 & child=$!; printf '%s' \"$child\" > child.pid; wait", 1);
        ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(4), () -> execute(args -> { fail(); return null; }));
        assertTrue(result.isError());
        long pid = Long.parseLong(Files.readString(directory.resolve("child.pid")));
        for (int i = 0; i < 50 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++) Thread.sleep(20);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "timeout must kill hook child");
    }
    @Test void cancellationStopsHookAndDoesNotInvokeTool() throws Exception {
        add("PreToolUse", "sleep 15", 10);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> execute(args -> { fail(); return null; }));
            Thread.sleep(150);
            context.abort();
            assertTrue(result.get(3, TimeUnit.SECONDS).isError());
        } finally { executor.shutdownNow(); }
    }
    @Test void malformedOutputAndInputRewritesFailClosed() throws Exception {
        for (String response : new String[]{"{bad json}", "{} {}", "{\"continue\":\"false\"}",
                "{\"hookSpecificOutput\":{\"permissionDecision\":null}}", "{\"decision\":\"unknown\"}"}) {
            add("PreToolUse", "printf '%s' '" + response + "'");
            assertTrue(execute(args -> { fail(); return null; }).isError(), response);
            Files.delete(manager.path("project"));
        }
        add("PreToolUse", "printf '%s' '{\"hookSpecificOutput\":{\"updatedInput\":{\"command\":\"different\"}}}'");
        ToolResult result = execute(args -> { fail(); return null; });
        assertTrue(result.getOutput().contains("updatedInput is unsupported"));
    }
    @Test void postHookFailureDoesNotConvertCompletedToolToFailure() throws Exception {
        add("PostToolUse", "printf post-error >&2; exit 2");
        ToolResult result = execute(args -> ToolResult.success("title", "actual output", Map.of("keep", 42)));
        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("post-error"));
        assertTrue(result.getOutput().contains("already completed"));
        assertEquals(42, result.getMetadata().get("keep"));
    }
    @Test void streamedToolStillPublishesHookDiagnostics() throws Exception {
        add("PostToolUse", "printf post-error >&2; exit 2");
        var displayed = new java.util.ArrayList<String>();
        context.setOutputConsumer(displayed::add);
        ToolResult result = execute(args -> ToolResult.success("title", "already displayed", Map.of(ToolResult.OUTPUT_STREAMED_METADATA, true)));
        assertFalse(result.isError());
        assertTrue(result.isOutputStreamed());
        assertTrue(displayed.stream().anyMatch(line -> line.contains("post-error")));
    }

    @Test void readOnlyWorkflowCannotLaunchShellFromReadHook() throws Exception {
        var request = JsonUtils.standardMapper().createObjectNode();
        request.put("action", "add").put("event", "PreToolUse").put("matcher", "^read$").put("command", "printf forbidden > forbidden.txt");
        manager.manage(request);
        var team = new ai.kompile.cli.main.chat.workflow.WorkflowTeam("review", 1, "reader",
                Map.of("reader", new ai.kompile.cli.main.chat.workflow.WorkflowTeam.Participant(
                        "reader", "reviewer", "cli", java.util.List.of("read"), java.util.List.of())), Map.of(), null, null);
        context.bindWorkflow(new ai.kompile.cli.main.chat.workflow.WorkflowTeamEnforcement(
                new ai.kompile.cli.main.chat.workflow.WorkflowTeamSnapshot(team, Map.of("reader", "reviewer"), null), "reader"));
        context.checkPermission("read", "fixture read");
        ToolResult result = HookExecutor.execute("read", input, context, args -> { fail(); return null; }, manager);
        assertTrue(result.isError());
        assertFalse(Files.exists(directory.resolve("forbidden.txt")));
    }

    @Test void excessiveOutputIsBoundedAndFailsClosed() throws Exception {
        add("PreToolUse", "printf '%070000d' 0");
        ToolResult result = execute(args -> { fail(); return null; });
        assertTrue(result.isError());
        assertTrue(result.getOutput().length() < 2000);
    }
    @Test void permissionDenialPreventsHookShellAndTool() throws Exception {
        add("PreToolUse", "printf nope > unauthorized.txt");
        context.getPermissionService().setUserOverride("hooks", PermissionService.PermissionLevel.DENY);
        assertTrue(execute(args -> { fail(); return null; }).isError());
        assertFalse(Files.exists(directory.resolve("unauthorized.txt")));
    }
    @Test void managementExemptionWorksEvenWithMalformedConfigAndNestedCallsDoNotDoubleFire() throws Exception {
        add("PreToolUse", "printf x >> invocations.txt");
        execute(args -> HookExecutor.execute("write", args, context, inner -> ToolResult.success("nested"), manager));
        assertEquals("x", Files.readString(directory.resolve("invocations.txt")));
        Files.writeString(manager.path("project"), "not json");
        assertFalse(HookExecutor.execute("hooks", input, context, args -> ToolResult.success("recovery"), manager).isError());
        assertTrue(execute(args -> { fail(); return null; }).isError());
    }
}
