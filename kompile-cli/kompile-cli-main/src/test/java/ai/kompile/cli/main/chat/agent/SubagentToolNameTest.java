package ai.kompile.cli.main.chat.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cancel key leaves only subagent launches running; everything else it interrupts. */
class SubagentToolNameTest {

    @Test
    void subagentLaunchesOnBothRoutesSurviveTheCancelKey() {
        for (String name : new String[]{"Agent", "Task", "task", "multi_task", "quorum_task", "subagent_resume",
                "mcp__kompile__task", "mcp__kompile__multi_task", "mcp__kompile__quorum_task"}) {
            assertTrue(AgenticChatLoop.isSubagentToolName(name), name);
        }
    }

    @Test
    void backgroundableShellAndOtherMcpToolsStayCancellable() {
        for (String name : new String[]{"Bash", "bash", "mcp__kompile__bash", "mcp__kompile__process",
                "mcp__kompile__crawl_source", "mcp__other__task", "", null}) {
            assertFalse(AgenticChatLoop.isSubagentToolName(name), String.valueOf(name));
        }
        // Ctrl+B is still offered for the shell even though the cancel key stops it.
        assertTrue(AgenticChatLoop.isClaudeBackgroundableName("mcp__kompile__bash"));
    }
}
