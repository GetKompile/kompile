package ai.kompile.cli.main.chat.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests agent cycling logic used by the Ctrl+X A hotkey.
 * Verifies that cycling through primary agents works correctly.
 */
class AgentCyclingTest {

    private AgentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentRegistry();
    }

    @Test
    void testPrimaryAgentsHaveAtLeastTwo() {
        List<AgentConfig> primaries = registry.getPrimaryAgents();
        assertTrue(primaries.size() >= 2,
                "Need at least 2 primary agents for cycling to work");
    }

    @Test
    void testCycleFromCoderToPlanner() {
        List<AgentConfig> primaries = registry.getPrimaryAgents();
        String currentAgent = "coder";

        int currentIdx = -1;
        for (int i = 0; i < primaries.size(); i++) {
            if (primaries.get(i).getName().equals(currentAgent)) {
                currentIdx = i;
                break;
            }
        }
        assertTrue(currentIdx >= 0, "coder should be in primary agents");

        int nextIdx = (currentIdx + 1) % primaries.size();
        String nextAgent = primaries.get(nextIdx).getName();

        assertEquals("planner", nextAgent,
                "Cycling from coder should go to planner");
    }

    @Test
    void testCycleFromPlannerBackToCoder() {
        List<AgentConfig> primaries = registry.getPrimaryAgents();
        String currentAgent = "planner";

        int currentIdx = -1;
        for (int i = 0; i < primaries.size(); i++) {
            if (primaries.get(i).getName().equals(currentAgent)) {
                currentIdx = i;
                break;
            }
        }
        assertTrue(currentIdx >= 0, "planner should be in primary agents");

        int nextIdx = (currentIdx + 1) % primaries.size();
        String nextAgent = primaries.get(nextIdx).getName();

        // Should wrap back to coder (or the first primary)
        assertEquals("coder", nextAgent,
                "Cycling from planner should wrap back to coder");
    }

    @Test
    void testCycleWrapsAround() {
        List<AgentConfig> primaries = registry.getPrimaryAgents();

        // Start at coder, cycle through all primaries, should return to coder
        String current = "coder";
        for (int cycle = 0; cycle < primaries.size(); cycle++) {
            int currentIdx = -1;
            for (int i = 0; i < primaries.size(); i++) {
                if (primaries.get(i).getName().equals(current)) {
                    currentIdx = i;
                    break;
                }
            }
            assertTrue(currentIdx >= 0, "Agent " + current + " should exist");
            int nextIdx = (currentIdx + 1) % primaries.size();
            current = primaries.get(nextIdx).getName();
        }

        assertEquals("coder", current,
                "After cycling through all primaries, should return to coder");
    }

    @Test
    void testCycleSkipsSubagents() {
        List<AgentConfig> primaries = registry.getPrimaryAgents();

        for (AgentConfig agent : primaries) {
            assertFalse(agent.isSubagent(),
                    "Primary agent list should not contain subagents: " + agent.getName());
        }
    }

    @Test
    void testCycledAgentHasDisplayName() {
        List<AgentConfig> primaries = registry.getPrimaryAgents();

        for (AgentConfig agent : primaries) {
            assertNotNull(agent.getDisplayName(),
                    "Agent " + agent.getName() + " should have a display name");
            assertFalse(agent.getDisplayName().isEmpty(),
                    "Agent " + agent.getName() + " display name should not be empty");
        }
    }

    @Test
    void testCycledAgentHasDescription() {
        List<AgentConfig> primaries = registry.getPrimaryAgents();

        for (AgentConfig agent : primaries) {
            assertNotNull(agent.getDescription());
            assertFalse(agent.getDescription().isEmpty(),
                    "Agent " + agent.getName() + " description should not be empty");
        }
    }

    @Test
    void testCycleWithCustomPrimaryAgent() {
        // Add a custom primary agent
        AgentConfig custom = AgentConfig.builder("custom-primary")
                .displayName("Custom")
                .description("A custom primary agent")
                .isCustom(true)
                .build();
        registry.register(custom);

        List<AgentConfig> primaries = registry.getPrimaryAgents();
        assertTrue(primaries.size() >= 3,
                "Should have at least 3 primaries after adding custom");

        // Cycle should include the custom agent
        boolean seenCustom = false;
        String current = "coder";
        for (int i = 0; i < primaries.size(); i++) {
            int currentIdx = -1;
            for (int j = 0; j < primaries.size(); j++) {
                if (primaries.get(j).getName().equals(current)) {
                    currentIdx = j;
                    break;
                }
            }
            int nextIdx = (currentIdx + 1) % primaries.size();
            current = primaries.get(nextIdx).getName();
            if ("custom-primary".equals(current)) seenCustom = true;
        }

        assertTrue(seenCustom, "Cycling should visit the custom primary agent");
    }
}
