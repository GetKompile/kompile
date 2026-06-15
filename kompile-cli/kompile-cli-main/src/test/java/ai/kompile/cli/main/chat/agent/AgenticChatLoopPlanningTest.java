package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests planning mode state management in AgenticChatLoop.
 * These tests verify the planning mode toggle and ExitPlanModeTool
 * wiring without requiring a live LLM connection.
 */
class AgenticChatLoopPlanningTest {

    private AgenticChatLoop loop;
    private ToolRegistry toolRegistry;
    private AgentRegistry agentRegistry;
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        agentRegistry = new AgentRegistry();
        PermissionService perms = new PermissionService();
        toolRegistry = ToolRegistryFactory.create(om, "", agentRegistry, perms,
                new ai.kompile.cli.main.chat.render.TerminalRenderer(false),
                null);

        loop = new AgenticChatLoop(
                null, om, toolRegistry, perms,
                agentRegistry, Paths.get("."), null, null);
    }

    @Test
    void testPlanningModeDefaultOff() {
        assertFalse(loop.isPlanningMode());
    }

    @Test
    void testEnablePlanningMode() {
        loop.setPlanningMode(true);
        assertTrue(loop.isPlanningMode());
    }

    @Test
    void testDisablePlanningMode() {
        loop.setPlanningMode(true);
        assertTrue(loop.isPlanningMode());

        loop.setPlanningMode(false);
        assertFalse(loop.isPlanningMode());
    }

    @Test
    void testPlanningModeRegistersExitPlanModeTool() {
        assertNull(toolRegistry.get("exit_plan_mode"),
                "exit_plan_mode should not be registered before planning mode");

        loop.setPlanningMode(true);

        assertNotNull(toolRegistry.get("exit_plan_mode"),
                "exit_plan_mode should be registered when planning mode is enabled");
    }

    @Test
    void testPlanningModeUnregistersExitPlanModeToolOnDisable() {
        loop.setPlanningMode(true);
        assertNotNull(toolRegistry.get("exit_plan_mode"));

        loop.setPlanningMode(false);
        assertNull(toolRegistry.get("exit_plan_mode"),
                "exit_plan_mode should be unregistered when planning mode is disabled");
    }

    @Test
    void testTogglePlanningModeMultipleTimes() {
        loop.setPlanningMode(true);
        assertTrue(loop.isPlanningMode());
        assertNotNull(toolRegistry.get("exit_plan_mode"));

        loop.setPlanningMode(false);
        assertFalse(loop.isPlanningMode());
        assertNull(toolRegistry.get("exit_plan_mode"));

        loop.setPlanningMode(true);
        assertTrue(loop.isPlanningMode());
        assertNotNull(toolRegistry.get("exit_plan_mode"));
    }

    @Test
    void testExitPlanModeToolIsCallable() throws Exception {
        loop.setPlanningMode(true);

        CliTool exitTool = toolRegistry.get("exit_plan_mode");
        assertNotNull(exitTool);
        assertEquals("exit_plan_mode", exitTool.id());

        // Verify it has the right schema
        var schema = exitTool.parameterSchema();
        assertTrue(schema.has("properties"));
        assertTrue(schema.path("properties").has("plan_summary"));
    }

    @Test
    void testPlannerAgentCanAccessExitPlanModeTool() {
        loop.setPlanningMode(true);

        AgentConfig planner = agentRegistry.get("planner");
        var tools = toolRegistry.getToolsForAgent(planner);

        boolean hasExitPlanMode = tools.stream()
                .anyMatch(t -> "exit_plan_mode".equals(t.id()));
        assertTrue(hasExitPlanMode,
                "Planner agent should have access to exit_plan_mode tool");
    }

    @Test
    void testPlannerAgentHasTodoWriteTool() {
        AgentConfig planner = agentRegistry.get("planner");
        var tools = toolRegistry.getToolsForAgent(planner);

        boolean hasTodoWrite = tools.stream()
                .anyMatch(t -> "todowrite".equals(t.id()));
        boolean hasTodoRead = tools.stream()
                .anyMatch(t -> "todoread".equals(t.id()));

        assertTrue(hasTodoWrite, "Planner should have todowrite");
        assertTrue(hasTodoRead, "Planner should have todoread");
    }

    @Test
    void testAgentConfigDefaultsCorrect() {
        AgentConfig defaultAgent = loop.getCurrentAgentConfig();
        assertNotNull(defaultAgent);
        assertEquals("coder", defaultAgent.getName());
    }

    @Test
    void testSetAgentConfig() {
        AgentConfig planner = agentRegistry.get("planner");
        loop.setAgentConfig(planner);
        assertEquals("planner", loop.getCurrentAgentConfig().getName());
    }

    @Test
    void testCoderAgentHasAllToolsIncludingExitPlanMode() {
        loop.setPlanningMode(true);

        AgentConfig coder = agentRegistry.get("coder");
        var tools = toolRegistry.getToolsForAgent(coder);

        // Coder has wildcard access, should include exit_plan_mode
        boolean hasExitPlanMode = tools.stream()
                .anyMatch(t -> "exit_plan_mode".equals(t.id()));
        assertTrue(hasExitPlanMode,
                "Coder agent (wildcard) should see exit_plan_mode when registered");
    }

    @Test
    void testExplorerAgentCannotAccessExitPlanMode() {
        loop.setPlanningMode(true);

        AgentConfig explorer = agentRegistry.get("explore-quick");
        var tools = toolRegistry.getToolsForAgent(explorer);

        boolean hasExitPlanMode = tools.stream()
                .anyMatch(t -> "exit_plan_mode".equals(t.id()));
        assertFalse(hasExitPlanMode,
                "Explorer agent should not have exit_plan_mode (not in its enabled tools)");
    }
}
