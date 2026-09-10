package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.ServerSubagentRunner;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TaskToolOverridesTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentRegistry registry = new AgentRegistry();
    private final AtomicReference<AgentConfig> launched = new AtomicReference<>();

    private TaskTool tool() {
        return new TaskTool(registry, (agent, prompt, context) -> {
            launched.set(agent);
            return "done";
        });
    }

    private ToolContext context() {
        ToolContext context = new ToolContext("task-test", AgentConfig.builder("parent").build(),
                new PermissionService(), directory, new ToolRegistry(mapper));
        context.setAutoApproveAll(true);
        context.setOutputConsumer(ignored -> {});
        return context;
    }

    private ObjectNode request() {
        return mapper.createObjectNode().put("description", "Audit release boundaries")
                .put("prompt", "Read-only architecture audit");
    }

    @Test
    void schemaExposesOptionalSelectorsWithoutChangingLegacyRequiredFields() {
        var schema = tool().parameterSchema();
        for (String field : List.of("agent_type", "model", "thinking", "role")) {
            assertEquals("string", schema.path("properties").path(field).path("type").asText());
        }
        assertEquals(mapper.valueToTree(List.of("description", "prompt")), schema.path("required"));
    }

    @Test
    void requestOverridesAreCopiedWithoutChangingProfileOrToolPolicy() throws Exception {
        AgentConfig profile = AgentConfig.builder("restricted").displayName("Restricted")
                .description("Audit only").systemPrompt("Do not edit")
                .enabledTools(Set.of("read", "grep"))
                .permissionOverrides(Map.of("edit", PermissionService.PermissionLevel.DENY))
                .isSubagent(true).isCustom(true).modelHint("fast")
                .allowedModels(List.of("old-model", "gpt-5.6-luna"))
                .modelOverride("old-model").thinkingOverride("low").build();
        registry.register(profile);
        ToolResult result = tool().execute(request().put("agent_type", "restricted")
                .put("model", "gpt-5.6-luna").put("thinking", "xhigh"), context());
        assertFalse(result.isError(), result.getOutput());
        AgentConfig child = launched.get();
        assertNotSame(profile, child);
        assertEquals("gpt-5.6-luna", child.getModelOverride());
        assertEquals("xhigh", child.getThinkingOverride());
        assertEquals(profile.getSystemPrompt(), child.getSystemPrompt());
        assertEquals(profile.getEnabledTools(), child.getEnabledTools());
        assertEquals(profile.getPermissionOverrides(), child.getPermissionOverrides());
        assertEquals(profile.getAllowedModels(), child.getAllowedModels());
        assertEquals(profile.getModelHint(), child.getModelHint());
        assertTrue(child.isSubagent());
        assertTrue(child.isCustom());
        assertFalse(child.canSpawnSubagents());
        assertEquals("old-model", registry.get("restricted").getModelOverride());
        assertEquals("low", registry.get("restricted").getThinkingOverride());
        assertEquals("gpt-5.6-luna", result.getMetadata().get("model"));
        assertEquals("xhigh", result.getMetadata().get("thinking"));
        assertFalse(tool().execute(request().put("agent_type", "restricted"), context()).isError());
        assertEquals("old-model", launched.get().getModelOverride());
        assertEquals("low", launched.get().getThinkingOverride());
    }

    @Test
    void roleSelectsItsPromptAndPermissionsWithoutAllowingRecursiveDelegation() throws Exception {
        RoleConfig role = RoleConfig.builder().name("release-auditor").displayName("Release auditor")
                .systemPrompt("Inspect boundaries; do not move files")
                .enabledTools(Set.of("read", "grep"))
                .permissionOverrides(Map.of("edit", PermissionService.PermissionLevel.DENY))
                .canSpawnSubagents(true).build();
        registry.registerRole(role);
        ToolResult result = tool().execute(request().put("role", "release-auditor")
                .put("model", "gpt-5.6-luna").put("thinking", "xhigh"), context());
        assertFalse(result.isError(), result.getOutput());
        assertEquals(role.getSystemPrompt(), launched.get().getSystemPrompt());
        assertEquals(role.getEnabledTools(), launched.get().getEnabledTools());
        assertEquals(role.getPermissionOverrides(), launched.get().getPermissionOverrides());
        assertEquals("release-auditor", launched.get().getRoleName());
        assertEquals("xhigh", launched.get().getThinkingOverride());
        assertFalse(launched.get().canSpawnSubagents());
        assertTrue(launched.get().isSubagent());
    }

    @Test
    void invalidOrAmbiguousSelectorsFailBeforeLaunch() throws Exception {
        registry.register(AgentConfig.builder("restricted").isSubagent(true)
                .allowedModels(List.of("allowed")).build());
        for (ObjectNode request : List.of(
                request().put("agent_type", "missing"),
                request().put("role", "missing"),
                request().put("role", "architect").put("agent_type", "architect"),
                request().put("agent", "codex"),
                request().put("thinking", 5),
                request().put("model", true),
                request().put("agent_type", "restricted").put("model", "not-allowed"))) {
            ToolResult result = tool().execute(request, context());
            assertTrue(result.isError(), request.toString());
            assertNull(launched.get());
        }
    }

    @Test
    void omittedAndBlankSelectorsPreserveDefaultSubagent() throws Exception {
        for (ObjectNode request : List.of(request(), request().put("model", " ")
                .putNull("thinking").put("role", ""))) {
            ToolResult result = tool().execute(request, context());
            assertFalse(result.isError(), result.getOutput());
            assertEquals("explore-quick", launched.get().getName());
            assertNull(launched.get().getModelOverride());
            assertNull(launched.get().getThinkingOverride());
        }
    }

    @Test
    void serverModeRejectsOverridesInsteadOfSilentlyLaunchingDifferentSettings() throws Exception {
        ServerSubagentRunner runner = new ServerSubagentRunner("http://127.0.0.1:1",
                new ToolRegistry(mapper), new PermissionService(), mapper, new TerminalRenderer(false));
        TaskTool tool = new TaskTool(registry, runner);
        for (String selector : List.of("model", "thinking")) {
            ToolResult result = tool.execute(request().put(selector, "explicit"), context());
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("does not support model/thinking overrides"), result.getOutput());
            assertTrue(result.getOutput().contains("no agent was launched"), result.getOutput());
        }
    }
}
