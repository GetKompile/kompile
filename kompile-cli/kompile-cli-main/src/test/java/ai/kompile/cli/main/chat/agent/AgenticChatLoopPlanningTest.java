package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

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
    private PermissionService perms;
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        agentRegistry = new AgentRegistry();
        perms = new PermissionService();
        toolRegistry = ToolRegistryFactory.create(om, "", agentRegistry, perms,
                new ai.kompile.cli.main.chat.render.TerminalRenderer(false),
                null);

        loop = new AgenticChatLoop(
                null, om, toolRegistry, perms,
                agentRegistry, Paths.get("."), null, null);
    }

    @Test
    void providerSwitchRebuildsWireHistoryWithoutReplacingTheConversation() {
        ChatConfig config = new ChatConfig("openai", "test-key", "gpt-4o", null);
        DirectLlmClient directClient = new DirectLlmClient(config, om);
        AgenticChatLoop directLoop = new AgenticChatLoop(
                null, om, toolRegistry, perms, agentRegistry, Paths.get("."),
                directClient, null);

        directLoop.restoreHistory(List.of(
                new ChatHistory.Turn("user", "Remember the deployment target."),
                new ChatHistory.Turn("assistant", "The target is staging.")));
        directClient.addToHistory("tool", "provider-specific wire envelope");

        assertEquals(3, directClient.getHistorySize());
        assertEquals(2, directLoop.conversationEntryCount());

        int replayed = directLoop.rebuildDirectHistoryForProviderSwitch();

        assertEquals(2, replayed);
        assertEquals(2, directClient.getHistorySize(),
                "the new provider receives the same text conversation without stale wire envelopes");
        assertEquals(2, directLoop.conversationEntryCount(),
                "the canonical session conversation must not be reset");
    }

    @Test
    void folderLocalModelsUseProgressiveToolsByDefault() {
        ChatConfig config = new ChatConfig(
                "kompile-local", null, "lfm2.5-1.2b-instruct", null);
        AgenticChatLoop localLoop = new AgenticChatLoop(
                null, om, toolRegistry, perms, agentRegistry, Paths.get("."),
                new DirectLlmClient(config, om), null);

        assertTrue(localLoop.usesProgressiveToolLoading());
    }

    @Test
    void realCoderRegistryStartsWithCompactCoreInsteadOfFullCatalog() {
        AgentConfig coder = agentRegistry.get("coder");
        var full = toolRegistry.buildDirectToolDefinitions(coder);
        var progressive = toolRegistry.buildProgressiveDirectToolDefinitions(coder);

        assertTrue(full.size() > 40, "the regression fixture must contain the real broad catalog");
        assertTrue(progressive.size() <= 10,
                () -> "local small-model core unexpectedly contains " + progressive.size() + " tools");
        assertTrue(ToolSchemaOptimizer.estimateTokens(progressive) < 2_500,
                () -> "local small-model core still costs about "
                        + ToolSchemaOptimizer.estimateTokens(progressive) + " schema tokens");
        assertTrue(hasDirectTool(progressive, "activate_tools"));
        assertTrue(hasDirectTool(progressive, "read"));
        assertFalse(hasDirectTool(progressive, "crawl_discover"));
        assertFalse(hasDirectTool(progressive, "knowledge_graph"));

        toolRegistry.getDynamicToolManager().activateGroup("crawl");
        var crawlEnabled = toolRegistry.buildProgressiveDirectToolDefinitions(coder);
        assertTrue(hasDirectTool(crawlEnabled, "crawl_discover"));
        assertTrue(hasDirectTool(crawlEnabled, "model_runtime"));
        assertFalse(hasDirectTool(crawlEnabled, "knowledge_graph"),
                "a crawl activation must not load the graph-query bundle");
    }

    @Test
    void progressiveToolPropertyCanOverrideAnyProvider() {
        String property = "kompile.chat.progressiveTools";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "true");
            ChatConfig config = new ChatConfig("openai", "test-key", "gpt-4o", null);
            AgenticChatLoop directLoop = new AgenticChatLoop(
                    null, om, toolRegistry, perms, agentRegistry, Paths.get("."),
                    new DirectLlmClient(config, om), null);
            assertTrue(directLoop.usesProgressiveToolLoading());

            System.setProperty(property, "false");
            assertFalse(directLoop.usesProgressiveToolLoading());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
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
    void chatAgentConfigHasNoExecutionLimitSurface() {
        assertThrows(NoSuchFieldException.class,
                () -> AgentConfig.class.getDeclaredField("maxSteps"));
        assertThrows(NoSuchMethodException.class,
                () -> AgentConfig.class.getMethod("getMaxSteps"));
    }

    @Test
    void testSetAgentConfig() {
        AgentConfig planner = agentRegistry.get("planner");
        loop.setAgentConfig(planner);
        assertEquals("planner", loop.getCurrentAgentConfig().getName());
    }

    private static boolean hasDirectTool(
            com.fasterxml.jackson.databind.node.ArrayNode tools, String name) {
        for (var tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return true;
            }
        }
        return false;
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
