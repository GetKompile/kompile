package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.agent.SubagentAdmission;
import ai.kompile.cli.main.chat.config.SubagentConfig;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class SubagentPolicyStdioTest {
    @TempDir Path dir;
    @AfterEach void noLeak() { assertEquals(0, SubagentAdmission.activeCount()); }

    @Test void managedCliModelBansAndCapAreCheckedBeforeCreatingRunner() throws Exception {
        var created = new AtomicInteger();
        var runner = new DirectSubagentRunnerStdio(dir) {
            @Override SubprocessAgentRunner createManagedRunner(String agent, boolean inject) {
                created.incrementAndGet();
                throw new IllegalArgumentException("startup fixture");
            }
        };
        new SubagentConfig(2, List.of("claude/*haiku*")).save(SubagentConfig.path());
        assertTrue(assertThrows(IllegalStateException.class, () -> runner.executeManagedSubagent(
                "claude", "test", 0, false, "claude-haiku-4.5", null)).getMessage().contains("banned by pattern"));
        assertTrue(assertThrows(IllegalStateException.class, () -> runner.executeManagedSubagent(
                "claude", "test", 0, false, null, null)).getMessage().contains("cannot be verified"));
        assertEquals(0, created.get());
        new SubagentConfig(0, List.of()).save(SubagentConfig.path());
        assertThrows(IllegalStateException.class, () -> runner.executeManagedSubagent(
                "codex", "test", 0, false, "gpt-5", null));
        assertEquals(0, created.get());
        SubagentConfig.defaults().save(SubagentConfig.path());
        assertEquals("startup fixture", assertThrows(IllegalArgumentException.class, () -> runner.executeManagedSubagent(
                "codex", "test", 0, false, "gpt-5", null)).getMessage());
        assertEquals(1, created.get());
    }
}
