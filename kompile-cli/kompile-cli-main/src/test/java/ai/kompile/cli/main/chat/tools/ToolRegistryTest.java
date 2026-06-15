package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.tui.SidePanelManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    private ToolRegistry registry;
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        registry = new ToolRegistry(om);
    }

    @Test
    void testRegisterAndGet() {
        TodoWriteTool tool = new TodoWriteTool();
        registry.register(tool);

        assertSame(tool, registry.get("todowrite"));
        assertTrue(registry.ids().contains("todowrite"));
    }

    @Test
    void testUnregister() {
        TodoWriteTool tool = new TodoWriteTool();
        registry.register(tool);
        assertNotNull(registry.get("todowrite"));

        registry.unregister("todowrite");
        assertNull(registry.get("todowrite"));
        assertFalse(registry.ids().contains("todowrite"));
    }

    @Test
    void testUnregisterNonexistent() {
        // Should not throw
        registry.unregister("nonexistent");
    }

    @Test
    void testGetToolsForAgentWithWildcard() {
        registry.register(new TodoWriteTool());
        registry.register(new TodoReadTool());
        registry.register(new ExitPlanModeTool());

        AgentConfig agent = AgentConfig.builder("coder")
                .enabledTools(Set.of("*"))
                .build();

        List<CliTool> tools = registry.getToolsForAgent(agent);
        assertEquals(3, tools.size());
    }

    @Test
    void testGetToolsForAgentFiltered() {
        registry.register(new TodoWriteTool());
        registry.register(new TodoReadTool());
        registry.register(new ExitPlanModeTool());

        AgentConfig agent = AgentConfig.builder("planner")
                .enabledTools(Set.of("todowrite", "todoread"))
                .build();

        List<CliTool> tools = registry.getToolsForAgent(agent);
        assertEquals(2, tools.size());
        assertTrue(tools.stream().anyMatch(t -> "todowrite".equals(t.id())));
        assertTrue(tools.stream().anyMatch(t -> "todoread".equals(t.id())));
        assertFalse(tools.stream().anyMatch(t -> "exit_plan_mode".equals(t.id())));
    }

    @Test
    void testBuildToolDefinitions() {
        registry.register(new ExitPlanModeTool());

        AgentConfig agent = AgentConfig.builder("test")
                .enabledTools(Set.of("*"))
                .build();

        ArrayNode defs = registry.buildToolDefinitions(agent);
        assertEquals(1, defs.size());

        var toolDef = defs.get(0);
        assertEquals("function", toolDef.path("type").asText());
        assertEquals("exit_plan_mode", toolDef.path("function").path("name").asText());
        assertFalse(toolDef.path("function").path("description").asText().isEmpty());
        assertTrue(toolDef.path("function").has("parameters"));
    }

    @Test
    void testRegisterOverwritesExisting() {
        ExitPlanModeTool tool1 = new ExitPlanModeTool();
        ExitPlanModeTool tool2 = new ExitPlanModeTool();

        registry.register(tool1);
        assertSame(tool1, registry.get("exit_plan_mode"));

        registry.register(tool2);
        assertSame(tool2, registry.get("exit_plan_mode"));
    }

    @Test
    void testAllReturnsUnmodifiable() {
        registry.register(new TodoWriteTool());
        assertThrows(UnsupportedOperationException.class, () -> {
            registry.all().clear();
        });
    }

    @Test
    void sidePanelToolExposesVersionedManagerSnapshot() {
        SidePanelManager manager = new SidePanelManager();
        SidePanelTool tool = new SidePanelTool(manager);
        registry.register(tool);

        assertSame(manager, tool.getSidePanelManager());
        assertSame(tool, registry.get("side_panel"));

        long initialVersion = manager.snapshot().version();
        manager.show("Plan", "line one\nline two");
        SidePanelManager.Snapshot shown = manager.snapshot();
        assertTrue(shown.visible());
        assertEquals("Plan", shown.title());
        assertEquals("line one\nline two", shown.content());
        assertTrue(shown.version() > initialVersion);

        manager.hide();
        SidePanelManager.Snapshot hidden = manager.snapshot();
        assertFalse(hidden.visible());
        assertTrue(hidden.version() > shown.version());
    }
}
