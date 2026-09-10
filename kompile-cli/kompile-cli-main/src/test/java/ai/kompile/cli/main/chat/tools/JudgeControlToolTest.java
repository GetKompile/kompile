package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.enforcer.*;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JudgeControlToolTest {
    @TempDir Path directory;
    final ObjectMapper mapper = JsonUtils.standardMapper();
    final JudgeControlTool tool = new JudgeControlTool();

    ToolContext context(String session) {
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("judge_control", PermissionService.PermissionLevel.ALLOW);
        ToolContext context = new ToolContext(session, null, permissions, directory, null);
        context.bindJudgeControl(new JudgeControl(session, directory), true);
        context.setOutputConsumer(line -> {});
        return context;
    }

    ToolResult call(ToolContext context, String json) throws Exception {
        return tool.execute(mapper.readTree(json), context);
    }

    @Test void statusDoesNotConsumeApprovalAndForkSharesLiveState() throws Exception {
        ToolContext context = context("a");
        assertFalse(call(context, "{\"action\":\"approve\",\"command\":\"git log **\",\"pattern\":true,\"confirmed\":true}").isError());
        ToolContext fork = context.forkForToolExecution();
        assertSame(context.getJudgeControl(), fork.getJudgeControl());
        ToolResult status = call(fork, "{\"action\":\"status\"}");
        assertTrue(status.getOutput().contains("next_non_control_tool_call"));
        assertTrue(context.getJudgeControl().isApprovalPatternNext());
        EnforcerToolCallGuard.evaluateSession(null, "judge_control", Map.of("action", "status"), context.getJudgeControl(), mapper);
        assertEquals("git log **", context.getJudgeControl().getApprovedCommandNext());
        assertFalse(call(fork, "{\"action\":\"cancel_approval\",\"confirmed\":true}").isError());
        assertEquals("", context.getJudgeControl().getApprovedCommandNext());
    }

    @Test void confirmationPermissionsAndChildBoundaryAreIndependent() throws Exception {
        ToolContext context = context("a");
        assertTrue(call(context, "{\"action\":\"disable\"}").isError());
        assertTrue(call(context, "{\"action\":\"disable\",\"confirmed\":\"true\"}").isError());
        context.getPermissionService().setUserOverride("judge_control", PermissionService.PermissionLevel.DENY);
        assertThrows(ToolExecutionException.class, () -> call(context, "{\"action\":\"disable\",\"confirmed\":true}"));
        assertTrue(context.getJudgeControl().isEnabled());
        assertFalse(call(context, "{\"action\":\"status\"}").isError());
        context.getPermissionService().setUserOverride("judge_control", PermissionService.PermissionLevel.ALLOW);
        context.markSupervisedChild();
        context.setAutoApproveAll(true);
        assertTrue(call(context, "{\"action\":\"disable\",\"confirmed\":true}").isError());
        assertTrue(context.getJudgeControl().isEnabled());
    }

    @Test void feedbackPersistsButSwitchesAndApprovalsDoNotLeak() throws Exception {
        ToolContext a = context("a"), b = context("b");
        assertFalse(call(a, "{\"action\":\"feedback\",\"text\":\"Prefer scoped changes\",\"confirmed\":true}").isError());
        assertEquals("Prefer scoped changes", new JudgeControl("a", directory).getGuidance());
        assertEquals("", b.getJudgeControl().getGuidance());
        assertFalse(call(a, "{\"action\":\"disable\",\"confirmed\":true}").isError());
        assertFalse(a.getJudgeControl().isEnabled());
        assertTrue(b.getJudgeControl().isEnabled());
        assertTrue(new JudgeControl("a", directory).isEnabled());
        assertFalse(call(a, "{\"action\":\"clear_feedback\",\"confirmed\":true}").isError());
        assertEquals("", new JudgeControl("a", directory).getGuidance());
        assertTrue(call(a, "{\"action\":\"status\",\"session_id\":\"b\"}").isError());
    }

    @Test void invalidPatternAndUnknownActionLeaveStateUnchanged() throws Exception {
        ToolContext context = context("a");
        assertFalse(call(context, "{\"action\":\"approve\",\"command\":\"git show HEAD\",\"confirmed\":true}").isError());
        assertTrue(call(context, "{\"action\":\"approve\",\"command\":\"**\",\"pattern\":true,\"confirmed\":true}").isError());
        assertTrue(call(context, "{\"action\":\"approve\",\"command\":\"\",\"confirmed\":true}").isError());
        assertTrue(call(context, "{\"action\":\"unknown\",\"confirmed\":true}").isError());
        assertEquals("git show HEAD", context.getJudgeControl().getApprovedCommandNext());
    }

    EnforcerToolCallGuard guard(String rules, AtomicReference<String> prompt) throws Exception {
        HarnessConfig config = new HarnessConfig();
        config.setJudgeGlobalEnabled(true);
        var runtime = EnforcerRuntimePolicy.create(directory, new EnforcerPolicy(rules, 1, false), config, mapper);
        JudgeBackend backend = new JudgeBackend() {
            public String generate(String user, String system) {
                prompt.set(user);
                return "{\"action\":\"BLOCK\",\"reason\":\"fixture verdict\"}";
            }
            public boolean isAvailable() { return true; }
        };
        return new EnforcerToolCallGuard(mapper, runtime, new EnforcerJudge(backend, mapper));
    }

    @Test void standaloneApprovalIsConsumedAndCannotBypassHardProtections() throws Exception {
        ToolContext context = context("a");
        JudgeControl control = context.getJudgeControl();
        try (var guard = guard("BAN_TOOL: bash", new AtomicReference<>())) {
            call(context, "{\"action\":\"approve\",\"command\":\"rm -rf target/cache-*\",\"pattern\":true,\"confirmed\":true}");
            assertTrue(EnforcerToolCallGuard.evaluateSession(guard, "bash", Map.of("command", "rm -rf target/cache-42"), control, mapper).isAllowed());
            assertFalse(EnforcerToolCallGuard.evaluateSession(guard, "bash", Map.of("command", "rm -rf target/cache-42"), control, mapper).isAllowed());
            call(context, "{\"action\":\"approve\",\"command\":\"rm -rf .kompile/memory\",\"confirmed\":true}");
            assertFalse(EnforcerToolCallGuard.evaluateSession(guard, "bash", Map.of("command", "rm -rf .kompile/memory"), control, mapper).isAllowed());
            assertEquals("", control.getApprovedCommandNext());
        }
    }

    @Test void standaloneFeedbackAndReportOnlyReachRealGuardWithoutBypassingKeywordBans() throws Exception {
        ToolContext context = context("a");
        var prompt = new AtomicReference<String>();
        try (var guard = guard("Use appropriate tools.\nBAN_TOOL: bash", prompt)) {
            call(context, "{\"action\":\"feedback\",\"text\":\"USER_GUIDANCE_MARKER\",\"confirmed\":true}");
            call(context, "{\"action\":\"override\",\"confirmed\":true}");
            assertTrue(EnforcerToolCallGuard.evaluateSession(guard, "read", Map.of("file_path", "x"), context.getJudgeControl(), mapper).isAllowed());
            assertTrue(prompt.get().contains("USER_GUIDANCE_MARKER"));
            assertFalse(context.getJudgeControl().isOverrideNextSet());
            assertFalse(EnforcerToolCallGuard.evaluateSession(guard, "read", Map.of("file_path", "x"), context.getJudgeControl(), mapper).isAllowed());
            call(context, "{\"action\":\"override\",\"confirmed\":true}");
            assertFalse(EnforcerToolCallGuard.evaluateSession(guard, "bash", Map.of("command", "git revert HEAD"), context.getJudgeControl(), mapper).isAllowed());
            call(context, "{\"action\":\"override\",\"confirmed\":true}");
            call(context, "{\"action\":\"cancel_override\",\"confirmed\":true}");
            assertFalse(context.getJudgeControl().isOverrideNextSet());
        }
    }

    @Test void stdioAndSocketAdaptersRetainControlStateAcrossToolContexts() throws Exception {
        var stdio = new ai.kompile.cli.main.mcp.McpStdioCommand();
        org.springframework.test.util.ReflectionTestUtils.setField(stdio, "transcriptId", "judge-wire");
        org.springframework.test.util.ReflectionTestUtils.setField(stdio, "mcpJudgeControl", new JudgeControl("judge-wire", directory));
        assertAdapterState(stdio, "registerCliTool");
        var socket = new ai.kompile.cli.main.serve.McpSocketSession(null, null, "judge-socket", () -> {});
        org.springframework.test.util.ReflectionTestUtils.setField(socket, "mcpJudgeControl", new JudgeControl("judge-socket", directory));
        assertAdapterState(socket, "register");
    }

    @SuppressWarnings("unchecked")
    private void assertAdapterState(Object adapter, String methodName) throws Exception {
        Map<String, Object> tools = new java.util.LinkedHashMap<>();
        var register = adapter.getClass().getDeclaredMethod(methodName, Map.class, CliTool.class, ObjectMapper.class, Path.class);
        register.setAccessible(true);
        register.invoke(adapter, tools, tool, mapper, directory);
        Object definition = tools.get("judge_control");
        assertNotNull(definition);
        var accessor = definition.getClass().getDeclaredMethod("executor");
        accessor.setAccessible(true);
        var execute = (java.util.function.Function<Map<String, Object>, ToolResult>) accessor.invoke(definition);
        assertFalse(execute.apply(Map.of("action", "approve", "command", "git log **", "pattern", true, "confirmed", true)).isError());
        var status = execute.apply(Map.of("action", "status"));
        assertFalse(status.isError(), status.getOutput());
        assertEquals("git log **", status.getMetadata().get("approvedCommand"));
        assertEquals("next_non_control_tool_call", status.getMetadata().get("oneShotScope"));
        assertFalse(execute.apply(Map.of("action", "cancel_approval", "confirmed", true)).isError());
        assertEquals("", execute.apply(Map.of("action", "status")).getMetadata().get("approvedCommand"));
    }

    @Test void schemaIsStrictAndDefaultMutationPermissionRequiresOperatorDecision() {
        assertFalse(tool.parameterSchema().path("additionalProperties").asBoolean(true));
        assertFalse(tool.parameterSchema().path("properties").has("session_id"));
        assertEquals(PermissionService.PermissionLevel.ASK,
                new PermissionService().getEffectiveLevel(null, "judge_control"));
    }
}
