package ai.kompile.cli.main.mcp;

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
class McpStdioHooksTest {
    @TempDir Path directory;
    @Test void finalToolMapWrapperBlocksDelegateAndPreservesDiscovery() throws Exception {
        String home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", directory.resolve("home").toString());
            var request = JsonUtils.standardMapper().createObjectNode();
            request.put("action", "add").put("event", "PreToolUse").put("matcher", "^fixture$").put("command", "exit 2");
            new HookManager(directory).manage(request);
            var permissions = new PermissionService();
            permissions.setUserOverride("hooks", PermissionService.PermissionLevel.ALLOW);
            var context = new ToolContext("mcp-test", null, permissions, directory, null);
            AtomicInteger calls = new AtomicInteger();
            var definition = new McpStdioCommand.ToolDef("fixture", "description", request, McpToolAnnotations.READ_ONLY,
                    args -> { calls.incrementAndGet(); return ToolResult.success("done"); }, "compact");
            var wrapped = McpStdioCommand.withHooks(definition, () -> context);
            assertSame(definition.schema(), wrapped.schema());
            assertSame(definition.annotations(), wrapped.annotations());
            assertEquals("compact", wrapped.compactHint());
            assertTrue(wrapped.executor().apply(Map.of("value", 42)).isError());
            assertEquals(0, calls.get());
            Files.writeString(new HookManager(directory).path("project"), "malformed");
            var control = new McpStdioCommand.ToolDef("hooks", "control", request, McpToolAnnotations.WRITE,
                    args -> ToolResult.success("recovery"));
            assertFalse(McpStdioCommand.withHooks(control, () -> context).executor().apply(Map.of()).isError());
        } finally { System.setProperty("user.home", home); }
    }

    @Test void finalToolMapWrapperFiresFailureHookForErrorResult() throws Exception {
        String home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", directory.resolve("home").toString());
            var request = JsonUtils.standardMapper().createObjectNode();
            request.put("action", "add").put("event", "PostToolUseFailure").put("command", "printf failed > observed.txt");
            new HookManager(directory).manage(request);
            var permissions = new PermissionService();
            permissions.setUserOverride("hooks", PermissionService.PermissionLevel.ALLOW);
            var context = new ToolContext("mcp-test", null, permissions, directory, null);
            var definition = new McpStdioCommand.ToolDef("fixture", "description", request, McpToolAnnotations.WRITE,
                    args -> ToolResult.error("original failure"));
            ToolResult result = McpStdioCommand.withHooks(definition, () -> context).executor().apply(Map.of());
            assertTrue(result.isError());
            assertEquals("original failure", result.getOutput());
            assertEquals("failed", Files.readString(directory.resolve("observed.txt")));
        } finally { System.setProperty("user.home", home); }
    }
}
