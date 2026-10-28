package ai.kompile.cli.main.chat.hooks;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCommandCatalog;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class HookManagementTest {
    @TempDir Path directory;
    String home;
    ToolContext context;
    HooksTool tool = new HooksTool();
    @BeforeEach void setup() {
        home = System.getProperty("user.home");
        System.setProperty("user.home", directory.resolve("home").toString());
        var permissions = new PermissionService();
        permissions.setUserOverride("hooks", PermissionService.PermissionLevel.ALLOW);
        context = new ToolContext("test", null, permissions, directory, null);
    }
    @AfterEach void restore() { System.setProperty("user.home", home); }
    JsonNode json(String text) throws Exception { return JsonUtils.standardMapper().readTree(text); }
    ToolResult call(String request) throws Exception { return tool.execute(json(request), context); }

    @Test void humanCommandsAndMcpShareConfiguration() throws Exception {
        ToolResult added = HookCommands.execute("global add {\"event\":\"PreToolUse\",\"matcher\":\"write\",\"command\":\"exit 0\"}", context);
        assertFalse(added.isError(), added.getOutput());
        String id = json(added.getOutput()).path("id").asText();
        assertFalse(HookCommands.execute("global edit " + id + " {\"timeout\":12}", context).isError());
        ToolResult read = call("{\"action\":\"get\",\"scope\":\"global\",\"id\":\"" + id + "\"}");
        assertEquals(12, json(read.getOutput()).path("hooks").get(0).path("timeout").asInt());
        assertFalse(HookCommands.execute("global disable " + id, context).isError());
        assertTrue(HookCommands.execute("", context).getOutput().contains("/hooks"));
        assertFalse(HookCommands.execute("global delete " + id, context).isError());
        assertTrue(new HookManager(directory).load().isEmpty());
        assertTrue(ChatCommandCatalog.isBuiltin("hooks"));
    }
    @Test void agentMutationsRequireTypedConfirmationAndAuthorization() throws Exception {
        String fields = "\"action\":\"add\",\"event\":\"PreToolUse\",\"command\":\"exit 0\"";
        assertTrue(call("{" + fields + "}").isError());
        assertTrue(call("{" + fields + ",\"confirmed\":\"true\"}").isError());
        context.getPermissionService().setUserOverride("hooks", PermissionService.PermissionLevel.DENY);
        assertThrows(ToolExecutionException.class, () -> call("{" + fields + ",\"confirmed\":true}"));
        assertFalse(Files.exists(new HookManager(directory).path("project")));
        assertFalse(call("{\"action\":\"list\"}").isError());
    }
    @Test void humanManagementDoesNotRepromptOnJLineButRespectsDenial() throws Exception {
        context.getPermissionService().setUserOverride("hooks", PermissionService.PermissionLevel.ASK);
        context.getPermissionService().setPromptListener(prompt -> fail("explicit slash command must not wait for JLine input"));
        ToolResult result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
                HookCommands.execute("add {\"event\":\"PreToolUse\",\"command\":\"exit 0\"}", context));
        assertFalse(result.isError(), result.getOutput());
        assertEquals(PermissionService.PermissionLevel.ASK, context.getPermissionService().getEffectiveLevel(null, "hooks"),
                "management must not authorize automatic command execution");
        context.getPermissionService().setUserOverride("hooks", PermissionService.PermissionLevel.DENY);
        assertTrue(HookCommands.execute("add {\"event\":\"PreToolUse\",\"command\":\"exit 0\"}", context).isError());
    }

    @Test void delegatedChildrenCannotChangeHooksEvenWithAutoApproval() throws Exception {
        context.markSupervisedChild();
        context.setAutoApproveAll(true);
        assertTrue(call("{\"action\":\"add\",\"event\":\"PreToolUse\",\"command\":\"exit 0\",\"confirmed\":true}").isError());
        assertFalse(call("{\"action\":\"list\"}").isError());
    }
    @Test void schemaAndUnknownFieldsAreValidatedForMcpAndSlashInput() throws Exception {
        assertTrue(tool.parameterSchema().path("properties").has("confirmed"));
        assertTrue(call("{\"action\":\"add\",\"confirmed\":true,\"scope\":false}").isError());
        assertTrue(call("{\"action\":\"list\",\"execute\":true}").isError());
        assertThrows(IllegalArgumentException.class, () -> HookCommands.parse("add {\"action\":\"delete\"}"));
        assertThrows(IllegalArgumentException.class, () -> HookCommands.parse("edit id {\"id\":\"other\"}"));
        assertTrue(HookCommands.execute("edit id", context).isError());
    }
    @Test void registryExecutionPreservesToolIdentityAndBlocksBeforeDelegate() throws Exception {
        var registry = new ToolRegistry(JsonUtils.standardMapper());
        AtomicInteger calls = new AtomicInteger();
        CliTool fixture = fixture(calls);
        registry.register(fixture);
        assertSame(fixture, registry.get("write"), "existing instanceof/cast behavior must not change");
        var ctx = new ToolContext("test", null, context.getPermissionService(), directory, registry);
        assertFalse(registry.execute("write", json("{}"), ctx).isError());
        assertEquals(1, calls.get());
        HookCommands.execute("add {\"event\":\"PreToolUse\",\"matcher\":\"^write$\",\"command\":\"exit 2\"}", context);
        assertTrue(registry.execute("write", json("{}"), ctx).isError());
        assertEquals(1, calls.get());
    }
    @Test void productConstructionNeverAddsGenericShellHookAuthority() throws Exception {
        HookCommands.execute("add {\"event\":\"PreToolUse\",\"command\":\"printf x > forbidden.txt\"}", context);
        ToolRegistry product = ToolRegistry.createProduct(() -> new ToolRegistry(JsonUtils.standardMapper()));
        product.register(fixture(new AtomicInteger()));
        ToolContext ctx = new ToolContext("product", null, context.getPermissionService(), directory, product);
        assertFalse(product.execute("write", json("{}"), ctx).isError());
        assertFalse(Files.exists(directory.resolve("forbidden.txt")));
    }
    @Test void readOnlyWorkflowCannotConfigureAutomaticShellHooks() throws Exception {
        var team = new ai.kompile.cli.main.chat.workflow.WorkflowTeam("review", 1, "reader",
                java.util.Map.of("reader", new ai.kompile.cli.main.chat.workflow.WorkflowTeam.Participant(
                        "reader", "reviewer", "cli", java.util.List.of("read"), java.util.List.of())),
                java.util.Map.of(), null, null);
        context.bindWorkflow(new ai.kompile.cli.main.chat.workflow.WorkflowTeamEnforcement(
                new ai.kompile.cli.main.chat.workflow.WorkflowTeamSnapshot(team, java.util.Map.of("reader", "reviewer"), null), "reader"));
        context.setAutoApproveAll(true);
        assertThrows(ToolExecutionException.class, () -> call("{\"action\":\"add\",\"event\":\"PreToolUse\",\"command\":\"exit 0\",\"confirmed\":true}"));
        assertFalse(call("{\"action\":\"list\"}").isError());
        assertFalse(Files.exists(new HookManager(directory).path("project")));
    }

    private CliTool fixture(AtomicInteger calls) {
        return new CliTool() {
            public String id() { return "write"; }
            public String description() { return "fixture"; }
            public String permissionKey() { return "write"; }
            public JsonNode parameterSchema() { return JsonUtils.standardMapper().createObjectNode(); }
            public ToolResult execute(JsonNode args, ToolContext context) { calls.incrementAndGet(); return ToolResult.success("ok"); }
        };
    }
}
