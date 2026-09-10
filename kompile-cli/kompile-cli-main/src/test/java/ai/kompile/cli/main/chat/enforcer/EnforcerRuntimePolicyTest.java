package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnforcerRuntimePolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsPolicyFileForChildProcesses() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HarnessConfig config = new HarnessConfig();
        config.setJudgeMode("remote");
        config.setJudgeProvider("openai");
        config.setJudgeModel("gpt-test");
        config.setJudgeApiKey("sk-test");

        EnforcerPolicy policy = new EnforcerPolicy("Never call bash.", 3, true);
        ReminderManager reminders = ReminderManager.forStorage(mapper,
                tempDir.resolve("session-reminders.json"),
                tempDir.resolve("project/.kompile/chat-reminders.json"));
        reminders.add(ReminderManager.Scope.PROJECT, "Plan before making changes");
        reminders.add(ReminderManager.Scope.SESSION, "Run focused tests");
        EnforcerRuntimePolicy runtime = EnforcerRuntimePolicy.create(
                tempDir, "enforcer-test", policy, config, mapper, reminders);

        assertTrue(runtime.getPolicyFile().toFile().exists());
        assertTrue(runtime.getContextFile().toFile().exists());
        assertEquals("true", runtime.toEnvironment().get(EnforcerRuntimePolicy.ENV_ACTIVE));
        assertEquals(runtime.getContextFile().toAbsolutePath().toString(),
                runtime.toEnvironment().get(EnforcerRuntimePolicy.ENV_CONTEXT_FILE));
        var persisted = mapper.readTree(runtime.getPolicyFile().toFile());
        assertTrue(persisted.hasNonNull("reminderSessionFile"));
        assertTrue(persisted.hasNonNull("reminderProjectFile"));
        assertTrue(!persisted.has("reminderConstraints"),
                "file-backed session reminder text must not be duplicated into project runtime state");

        EnforcerRuntimePolicy loaded = EnforcerRuntimePolicy.load(runtime.getPolicyFile(), mapper);
        assertNotNull(loaded);
        assertEquals("enforcer-test", loaded.getSessionId());
        assertEquals(runtime.getContextFile().toAbsolutePath().normalize(), loaded.getContextFile());
        assertEquals("Never call bash.", loaded.getPolicy().getRules());
        assertEquals(3, loaded.getPolicy().getMaxCorrections());
        assertEquals("remote", loaded.getHarnessConfig().getJudgeMode());
        assertEquals("openai", loaded.getHarnessConfig().getJudgeProvider());
        assertEquals("gpt-test", loaded.getHarnessConfig().getJudgeModel());
        assertEquals("sk-test", loaded.getHarnessConfig().getJudgeApiKey());
        assertTrue(loaded.getReminderConstraints().contains(
                "1. [project] Plan before making changes"));
        assertTrue(loaded.getReminderConstraints().contains(
                "2. [session] Run focused tests"));

        reminders.add(ReminderManager.Scope.PROJECT, "Do not use git reset");
        assertTrue(loaded.getReminderConstraints().contains("Do not use git reset"),
                "nested MCP readers must see project reminder updates without policy restart");
        reminders.handleCommand(ReminderManager.Scope.SESSION, "interval off");
        assertEquals("", loaded.getReminderConstraints(),
                "interval off must immediately disable nested reminder enforcement");

        assertTrue(runtime.isEnabled(mapper));
        runtime.setEnabled(false, mapper);
        assertTrue(!runtime.isEnabled(mapper), "session switch must reach nested MCP readers");
        runtime.setEnabled(true, mapper);
        assertTrue(runtime.isEnabled(mapper));
    }

    @Test
    void inMemoryReminderSourceUsesPortableSnapshotFallback() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ReminderManager reminders = ReminderManager.inMemory(
                List.of("Keep project context"), List.of("Be explicit"));
        EnforcerRuntimePolicy runtime = EnforcerRuntimePolicy.create(
                tempDir, "enforcer-memory", new EnforcerPolicy("rules", 1, false),
                new HarnessConfig(), mapper, reminders);

        var persisted = mapper.readTree(runtime.getPolicyFile().toFile());
        assertTrue(persisted.hasNonNull("reminderConstraints"));
        assertTrue(!persisted.has("reminderSessionFile"));
        EnforcerRuntimePolicy loaded = EnforcerRuntimePolicy.load(runtime.getPolicyFile(), mapper);
        assertNotNull(loaded);
        assertTrue(loaded.getReminderConstraints().contains("[project] Keep project context"));
        assertTrue(loaded.getReminderConstraints().contains("[session] Be explicit"));
    }
}
