package ai.kompile.cli.main.chat.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SubagentDelegationGuardTest {

    @Test
    void parsesDepth() {
        assertEquals(0, SubagentDelegationGuard.depth(null));
        assertEquals(0, SubagentDelegationGuard.depth("junk"));
        assertEquals(2, SubagentDelegationGuard.depth(" 2 "));
        assertEquals(0, SubagentDelegationGuard.depth("-4"));
    }

    @Test
    void blocksOnlyDelegationToolsInsideSubagents() {
        for (String tool : new String[]{"task", "multi_task", "quorum_task", "agent", "agents"}) {
            assertTrue(SubagentDelegationGuard.blocks(tool, "1"), tool);
            assertFalse(SubagentDelegationGuard.blocks(tool, null), tool);
            assertFalse(SubagentDelegationGuard.blocks(tool, "0"), tool);
        }
        assertFalse(SubagentDelegationGuard.blocks("read", "2"));
        assertFalse(SubagentDelegationGuard.blocks(null, "2"));
    }

    @Test
    void extendedDelegationToolsBlockAboveDepthZero() {
        for (String tool : new String[]{"a2a", "enforcer"}) {
            assertTrue(SubagentDelegationGuard.blocksWithDepth(tool, "1"), tool);
            assertTrue(SubagentDelegationGuard.blocksWithDepth(tool, "3"), tool);
            assertFalse(SubagentDelegationGuard.blocksWithDepth(tool, null), tool);
            assertFalse(SubagentDelegationGuard.blocksWithDepth(tool, "0"), tool);
        }
        // Core delegation tools ride the same gate.
        assertTrue(SubagentDelegationGuard.blocksWithDepth("task", "1"));
        // Ordinary tools never block.
        assertFalse(SubagentDelegationGuard.blocksWithDepth("read", "2"));
        assertFalse(SubagentDelegationGuard.blocksWithDepth(null, "2"));
    }

    @Test
    void claudeDisallowListCoversNativeAndMcpDelegation() {
        assertTrue(SubagentDelegationGuard.CLAUDE_DISALLOWED.contains("Task"));
        assertTrue(SubagentDelegationGuard.CLAUDE_DISALLOWED.contains("Agent"));
        assertTrue(SubagentDelegationGuard.CLAUDE_DISALLOWED.contains("mcp__kompile__multi_task"));
        assertTrue(SubagentDelegationGuard.CLAUDE_DISALLOWED.contains("mcp__kompile__task"));
        assertTrue(SubagentDelegationGuard.CLAUDE_DISALLOWED.contains("mcp__kompile__quorum_task"));
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void connectionDepthDoesNotInheritTheSharedDaemonsEnvironment() throws Exception {
        String javaBinary = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String daemonDepth : new String[]{"0", "1"}) {
            ProcessBuilder builder = new ProcessBuilder(javaBinary, "-cp", classpath, DepthProbe.class.getName());
            builder.environment().put(SubagentDelegationGuard.DEPTH_ENV, daemonDepth);
            Process child = builder.redirectErrorStream(true).start();
            try {
                assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "depth probe timed out");
                assertEquals(0, child.exitValue(), new String(child.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                if (child.isAlive()) child.destroyForcibly();
            }
        }
    }

    public static class DepthProbe {
        public static void main(String[] args) {
            assertFalse(SubagentDelegationGuard.isSubagent("0"));
            assertFalse(SubagentDelegationGuard.isSubagent((String) null));
            assertTrue(SubagentDelegationGuard.isSubagent("1"));
            assertFalse(SubagentDelegationGuard.blocksWithDepth("task", "0"));
            assertTrue(SubagentDelegationGuard.blocksWithDepth("task", "1"));
            assertEquals(SubagentDelegationGuard.depth(System.getenv(SubagentDelegationGuard.DEPTH_ENV)) >= 1,
                    SubagentDelegationGuard.isSubagent(), "stdio still uses its own environment");
        }
    }

    @Test
    void qwenChildFlagExcludesNativeAgentTool() {
        assertEquals("agent", SubagentDelegationGuard.QWEN_EXCLUDE_TOOLS);
    }
}
