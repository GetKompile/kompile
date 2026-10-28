package ai.kompile.cli.main.serve;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.hooks.HookManager;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class McpSocketHooksTest {
    @TempDir Path directory;
    @Test void socketToolWrapperBlocksAndRetainsSchema() throws Exception {
        String home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", directory.resolve("home").toString());
            var request = JsonUtils.standardMapper().createObjectNode();
            request.put("action", "add").put("event", "PreToolUse").put("command", "exit 2");
            new HookManager(directory).manage(request);
            var permissions = new PermissionService();
            permissions.setUserOverride("hooks", PermissionService.PermissionLevel.ALLOW);
            var context = new ToolContext("socket-test", null, permissions, directory, null);
            AtomicInteger calls = new AtomicInteger();
            var definition = new McpSocketSession.ToolDef("fixture", "description", request, McpToolAnnotations.READ_ONLY,
                    args -> { calls.incrementAndGet(); return ToolResult.success("done"); });
            var wrapped = McpSocketSession.withHooks(definition, () -> context);
            assertSame(definition.schema(), wrapped.schema());
            assertSame(definition.annotations(), wrapped.annotations());
            assertTrue(wrapped.executor().apply(Map.of()).isError());
            assertEquals(0, calls.get());
        } finally { System.setProperty("user.home", home); }
    }
    @Test void clientOwnedDepthAndRecursionStateReachActualSessionContext() throws Exception {
        String home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", directory.resolve("home").toString());
            var session = new McpSocketSession(null, null, "socket-child", () -> {});
            var header = JsonUtils.standardMapper().createObjectNode();
            header.put(ai.kompile.cli.main.chat.agent.SubagentDelegationGuard.DEPTH_ENV, "1").put("hookRunning", true);
            session.configureClientContext(header);
            ToolContext child = session.ctx(directory);
            assertTrue(child.isSupervisedChild());
            assertTrue(child.isHookExecutionSuppressed());
            assertTrue(child.forkForToolExecution().isHookExecutionSuppressed());
            var request = JsonUtils.standardMapper().createObjectNode();
            request.put("action", "add").put("event", "PreToolUse").put("command", "exit 2").put("confirmed", true);
            assertTrue(new ai.kompile.cli.main.chat.tools.HooksTool().execute(request, child).isError());
            assertFalse(Files.exists(new HookManager(directory).path("project")));
            new HookManager(directory).manage(request);
            var definition = new McpSocketSession.ToolDef("fixture", "description", request, McpToolAnnotations.WRITE,
                    args -> ToolResult.success("not recursively hooked"));
            assertFalse(McpSocketSession.withHooks(definition, () -> session.ctx(directory)).executor().apply(Map.of()).isError());
            var ordinary = new McpSocketSession(null, null, "socket-parent", () -> {});
            ordinary.configureClientContext(JsonUtils.standardMapper().createObjectNode());
            assertFalse(ordinary.ctx(directory).isSupervisedChild());
            assertFalse(ordinary.ctx(directory).isHookExecutionSuppressed());
            assertTrue(McpSocketSession.withHooks(definition, () -> ordinary.ctx(directory)).executor().apply(Map.of()).isError());
        } finally { System.setProperty("user.home", home); }
    }

    @Test void socketToolWrapperFiresPostHookWithoutChangingResult() throws Exception {
        String home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", directory.resolve("home").toString());
            var request = JsonUtils.standardMapper().createObjectNode();
            request.put("action", "add").put("event", "PostToolUse").put("command", "printf observed > observed.txt");
            new HookManager(directory).manage(request);
            var permissions = new PermissionService();
            permissions.setUserOverride("hooks", PermissionService.PermissionLevel.ALLOW);
            var context = new ToolContext("socket-test", null, permissions, directory, null);
            ToolResult original = ToolResult.success("title", "output", Map.of("metadata", true));
            var definition = new McpSocketSession.ToolDef("fixture", "description", request, McpToolAnnotations.WRITE, args -> original);
            assertSame(original, McpSocketSession.withHooks(definition, () -> context).executor().apply(Map.of()));
            assertEquals("observed", Files.readString(directory.resolve("observed.txt")));
        } finally { System.setProperty("user.home", home); }
    }
}
