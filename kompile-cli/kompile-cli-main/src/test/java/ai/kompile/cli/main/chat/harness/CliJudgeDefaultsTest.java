package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.ChatProfiles;
import ai.kompile.cli.main.chat.config.JudgeDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CliJudgeDefaultsTest {
    @TempDir Path project;
    private String home;

    @BeforeEach void isolate() {
        home = System.getProperty("user.home");
        System.setProperty("user.home", project.resolve("home").toString());
    }
    @AfterEach void restore() { System.setProperty("user.home", home); }

    @Test void codexUsesSavedVendorModelAndMinimumInNativeCommand() throws Exception {
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "openai",
                new JudgeDefaults.Selection("gpt-5.6-sol", null));
        { // Construction/command inspection never starts a provider process.
            var backend = new CliJudgeBackend("codex", project);
            List<String> command = backend.buildSingleShotCommand("codex", "question", "system");
            assertEquals("exec", command.get(1));
            assertEquals("gpt-5.6-sol", command.get(command.indexOf("--model") + 1));
            assertTrue(command.contains("model_reasoning_effort=\"low\""));
            assertTrue(backend.describe().contains("model=gpt-5.6-sol"));
        }
    }

    @Test void persistentClaudeUsesSavedModelAndKeepsIsolationFlags() throws Exception {
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "anthropic",
                new JudgeDefaults.Selection("claude-selected", "low"));
        {
            var backend = new CliJudgeBackend("claude", project);
            assertTrue(backend.describe().contains("model=claude-selected"));
            assertEquals(List.of("--tools", "", "--strict-mcp-config", "--effort", "low"),
                    backend.persistentArguments());
        }
    }

    @Test void restartReloadsDefaultsAndAgentSwitchDoesNotKeepPreviousVendor() throws Exception {
        {
            var backend = new CliJudgeBackend("claude", project);
            assertTrue(backend.describe().contains("model=haiku"));
            JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "anthropic",
                    new JudgeDefaults.Selection("new-claude", null));
            backend.restart();
            assertTrue(backend.describe().contains("model=new-claude"));
            JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "openai",
                    new JudgeDefaults.Selection("o3", null));
            backend.modify("codex");
            assertTrue(backend.describe().contains("model=o3"));
            assertFalse(backend.describe().contains("new-claude"));
        }
    }

    @Test void projectProfilesRemainIsolatedAndReloadOnRestartOrAgentSwitch() throws Exception {
        Path other = project.resolve("other");
        ChatProfiles.save(project, ChatProfiles.captureJudge("quick", "codex", "o3", "low"), false);
        ChatProfiles.activateJudge(project, "codex", "quick");
        ChatProfiles.save(other, ChatProfiles.captureJudge("quick", "codex", "other-model", null), false);
        ChatProfiles.activateJudge(other, "codex", "quick");
        var backend = new CliJudgeBackend("codex", project);
        var otherBackend = new CliJudgeBackend("codex", other);
        assertTrue(backend.describe().contains("model=o3"));
        assertTrue(otherBackend.describe().contains("model=other-model"));
        ChatProfiles.save(project, ChatProfiles.captureJudge("review", "codex", "review-model", "high"), false);
        ChatProfiles.activateJudge(project, "codex", "review");
        backend.restart();
        var command = backend.buildSingleShotCommand("codex", "question", null);
        assertEquals("review-model", command.get(command.indexOf("--model") + 1));
        assertTrue(command.contains("model_reasoning_effort=\"high\""));
        ChatProfiles.save(project, ChatProfiles.captureJudge("quick", "claude", "claude-profile", "low"), false);
        ChatProfiles.activateJudge(project, "claude", "quick");
        backend.modify("claude");
        assertTrue(backend.describe().contains("model=claude-profile"));
        assertFalse(backend.describe().contains("review-model"));
        assertTrue(otherBackend.describe().contains("model=other-model"));
    }

    @Test void openCodeFlagsFollowRunAndUnsupportedNativeThinkingIsNotInvented() throws Exception {
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "opencode",
                new JudgeDefaults.Selection("vendor/model", "low"));
        {
            var backend = new CliJudgeBackend("opencode", project);
            var command = backend.buildSingleShotCommand("opencode", "question", null);
            assertEquals("run", command.get(1));
            assertEquals("low", command.get(command.indexOf("--variant") + 1));
        }
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "gemini",
                new JudgeDefaults.Selection("gemini-selected", "low"));
        {
            var backend = new CliJudgeBackend("gemini", project);
            var command = backend.buildSingleShotCommand("gemini", "question", null);
            assertEquals("gemini-selected", command.get(command.indexOf("--model") + 1));
            assertFalse(command.contains("--effort"));
            assertFalse(command.contains("--thinking"));
        }
    }
}
