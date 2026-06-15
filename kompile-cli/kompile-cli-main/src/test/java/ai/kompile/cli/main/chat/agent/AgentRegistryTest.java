package ai.kompile.cli.main.chat.agent;

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
