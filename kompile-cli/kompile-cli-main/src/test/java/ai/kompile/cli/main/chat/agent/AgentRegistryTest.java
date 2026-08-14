package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.tools.ExitPlanModeTool;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentRegistryTest {

    private AgentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentRegistry();
    }

    // ========================================================================
    // Default agents
    // ========================================================================

    @Test
    void testDefaultAgentsRegistered() {
        assertNotNull(registry.get("coder"));
        assertNotNull(registry.get("crawler"));
        assertNotNull(registry.get("planner"));
        assertNotNull(registry.get("general"));
        assertNotNull(registry.get("explore-quick"));
        assertNotNull(registry.get("explore-deep"));
        assertNotNull(registry.get("explorer"));
        assertNotNull(registry.get("code-reviewer"));
        assertNotNull(registry.get("architect"));
        assertNotNull(registry.get("researcher"));
    }

    @Test
    void testDefaultAgent() {
        AgentConfig defaultAgent = registry.getDefault();
        assertNotNull(defaultAgent);
        assertEquals("coder", defaultAgent.getName());
    }

    // ========================================================================
    // Coder agent
    // ========================================================================

    @Test
    void testCoderAgentHasFullToolAccess() {
        AgentConfig coder = registry.get("coder");
        assertTrue(coder.getEnabledTools().contains("*"));
        assertTrue(coder.canSpawnSubagents());
        assertFalse(coder.isSubagent());
    }

    @Test
    void testCoderAgentSystemPrompt() {
        AgentConfig coder = registry.get("coder");
        assertNotNull(coder.getSystemPrompt());
        assertFalse(coder.getSystemPrompt().isEmpty());
    }

    // ========================================================================
    // Crawler agent
    // ========================================================================

    @Test
    void testCrawlerAgentOwnsIncrementalKnowledgeBaseLifecycle() {
        AgentConfig crawler = registry.get("crawler");

        assertAll(
                () -> assertTrue(crawler.getEnabledTools().contains("crawl_discover")),
                () -> assertTrue(crawler.getEnabledTools().contains("crawl_documents")),
                () -> assertTrue(crawler.getEnabledTools().contains("crawl_source")),
                () -> assertTrue(crawler.getEnabledTools().contains("crawl_control")),
                () -> assertTrue(crawler.getEnabledTools().contains("local_code_index")),
                () -> assertTrue(crawler.getEnabledTools().contains("code_graph")),
                () -> assertTrue(crawler.getEnabledTools().contains("knowledge_graph")),
                () -> assertTrue(crawler.getEnabledTools().contains("graph_embeddings")),
                () -> assertTrue(crawler.getEnabledTools().contains("graph_reason")),
                () -> assertTrue(crawler.getEnabledTools().contains("graph_reasoning_query")),
                () -> assertTrue(crawler.getEnabledTools().contains("graph_import")),
                () -> assertTrue(crawler.getEnabledTools().contains("graph_export")),
                () -> assertTrue(crawler.getEnabledTools().contains("graph_search")),
                () -> assertTrue(crawler.getEnabledTools().contains("graph_centrality")),
                () -> assertTrue(crawler.getEnabledTools().contains("ask_graph_assert")),
                () -> assertTrue(crawler.getEnabledTools().contains("ask_graph_retract")),
                () -> assertTrue(crawler.getEnabledTools().contains("ask_graph_query")));
        assertTrue(crawler.getSystemPrompt().contains("incremental"));
        assertTrue(crawler.getSystemPrompt().contains("code_graph"));
        assertTrue(crawler.getSystemPrompt().contains("TransE/RotatE"));
        assertTrue(crawler.getSystemPrompt().contains("learned models"));
        assertTrue(crawler.getSystemPrompt().contains(".kgraph"));
    }

    // ========================================================================
    // Planner agent
    // ========================================================================

    @Test
    void testPlannerAgentIsReadOnly() {
        AgentConfig planner = registry.get("planner");
        assertFalse(planner.isSubagent());
        assertTrue(planner.canSpawnSubagents());

        // Should not have wildcard access
        assertFalse(planner.getEnabledTools().contains("*"));

        // Should have read-only tools
        assertTrue(planner.getEnabledTools().contains("read"));
        assertTrue(planner.getEnabledTools().contains("grep"));
        assertTrue(planner.getEnabledTools().contains("glob"));
        assertTrue(planner.getEnabledTools().contains("list"));

        // Should have todo tools
        assertTrue(planner.getEnabledTools().contains("todowrite"));
        assertTrue(planner.getEnabledTools().contains("todoread"));
    }

    @Test
    void testPlannerAgentHasExitPlanMode() {
        AgentConfig planner = registry.get("planner");
        assertTrue(planner.getEnabledTools().contains("exit_plan_mode"),
                "Planner agent must have exit_plan_mode tool enabled");
    }

    @Test
    void testPlannerAgentDeniesEditTools() {
        AgentConfig planner = registry.get("planner");
        var overrides = planner.getPermissionOverrides();

        assertEquals(ai.kompile.cli.main.chat.permission.PermissionService.PermissionLevel.DENY,
                overrides.get("edit"));
        assertEquals(ai.kompile.cli.main.chat.permission.PermissionService.PermissionLevel.DENY,
                overrides.get("write"));
        assertEquals(ai.kompile.cli.main.chat.permission.PermissionService.PermissionLevel.DENY,
                overrides.get("patch"));
    }

    @Test
    void testPlannerSystemPromptMentionsPlanning() {
        AgentConfig planner = registry.get("planner");
        String prompt = planner.getSystemPrompt();
        assertTrue(prompt.contains("todowrite"), "Planner prompt should mention todowrite");
        assertTrue(prompt.contains("exit_plan_mode"), "Planner prompt should mention exit_plan_mode");
        assertTrue(prompt.contains("plan"), "Planner prompt should mention planning");
    }

    // ========================================================================
    // Primary vs subagents
    // ========================================================================

    @Test
    void testPrimaryAgents() {
        List<AgentConfig> primaries = registry.getPrimaryAgents();
        assertTrue(primaries.size() >= 2);

        boolean hasCoder = primaries.stream().anyMatch(a -> "coder".equals(a.getName()));
        boolean hasPlanner = primaries.stream().anyMatch(a -> "planner".equals(a.getName()));
        assertTrue(hasCoder);
        assertTrue(hasPlanner);

        // Primary agents should not be subagents
        for (AgentConfig a : primaries) {
            assertFalse(a.isSubagent(), a.getName() + " should not be a subagent");
        }
    }

    @Test
    void testSubagents() {
        List<AgentConfig> subagents = registry.getSubagents();
        assertTrue(subagents.size() >= 5);

        boolean hasGeneral = subagents.stream().anyMatch(a -> "general".equals(a.getName()));
        boolean hasExploreQuick = subagents.stream().anyMatch(a -> "explore-quick".equals(a.getName()));
        assertTrue(hasGeneral);
        assertTrue(hasExploreQuick);

        for (AgentConfig a : subagents) {
            assertTrue(a.isSubagent(), a.getName() + " should be a subagent");
        }
    }

    @Test
    void testSubagentDelegationHealthy() {
        assertTrue(registry.isSubagentDelegationHealthy());
    }

    @Test
    void directSubagentUsesProviderNeutralNonBlankToolDefinitions() {
        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        tools.register(new ExitPlanModeTool());
        DirectSubagentRunner runner = new DirectSubagentRunner(null, mapper, tools, null, null);

        ArrayNode definitions = runner.directToolDefinitionsFor(AgentConfig.builder("explore-quick")
                .enabledTools(Set.of("*"))
                .build());

        assertEquals(1, definitions.size());
        assertEquals("exit_plan_mode", definitions.path(0).path("name").asText());
        assertFalse(definitions.path(0).path("name").asText().isBlank());
        assertTrue(definitions.path(0).has("inputSchema"));
        assertFalse(definitions.path(0).has("function"));
    }

    // ========================================================================
    // Custom agent registration
    // ========================================================================

    @Test
    void testRegisterCustomAgent() {
        AgentConfig custom = AgentConfig.builder("my-custom")
                .displayName("Custom Agent")
                .description("A test agent")
                .systemPrompt("You are a test agent.")
                .enabledTools(Set.of("read", "grep"))
                .isCustom(true)
                .build();

        registry.register(custom);

        AgentConfig retrieved = registry.get("my-custom");
        assertNotNull(retrieved);
        assertEquals("Custom Agent", retrieved.getDisplayName());
        assertTrue(retrieved.isCustom());
    }

    @Test
    void testOverwriteExistingAgent() {
        AgentConfig override = AgentConfig.builder("coder")
                .displayName("Custom Coder")
                .systemPrompt("Custom prompt")
                .build();

        registry.register(override);

        assertEquals("Custom Coder", registry.get("coder").getDisplayName());
    }

    // ========================================================================
    // Explorer subagents
    // ========================================================================

    @Test
    void testExploreQuickHasFastModelHint() {
        AgentConfig quick = registry.get("explore-quick");
        assertEquals("fast", quick.getModelHint());
        assertTrue(quick.getMaxSteps() <= 10,
                "Quick explorer should have low max steps");
    }

    @Test
    void testExploreDeepHasDefaultModelHint() {
        AgentConfig deep = registry.get("explore-deep");
        assertEquals("default", deep.getModelHint());
        assertTrue(deep.getMaxSteps() >= 20,
                "Deep explorer should have higher max steps");
    }

    @Test
    void testExplorerSubagentsDenyWriteTools() {
        for (String name : List.of("explore-quick", "explore-deep", "explorer", "code-reviewer")) {
            AgentConfig agent = registry.get(name);
            var overrides = agent.getPermissionOverrides();
            assertEquals(ai.kompile.cli.main.chat.permission.PermissionService.PermissionLevel.DENY,
                    overrides.get("edit"), name + " should deny edit");
            assertEquals(ai.kompile.cli.main.chat.permission.PermissionService.PermissionLevel.DENY,
                    overrides.get("write"), name + " should deny write");
        }
    }

    // ========================================================================
    // Subagent summary
    // ========================================================================

    @Test
    void testSubagentSummary() {
        String summary = registry.getSubagentSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("subagent"));
        assertTrue(summary.contains("general"));
    }
}
