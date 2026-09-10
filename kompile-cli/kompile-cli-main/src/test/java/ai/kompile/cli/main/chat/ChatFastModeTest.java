package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import org.jline.reader.Candidate;
import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatFastModeTest {
    @TempDir Path tempDir;

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void commandTogglesPersistsAndRejectsUnsupportedProvidersWithoutReplacingHistory() throws Exception {
        String previousHome = System.getProperty("user.home");
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        System.setProperty("user.home", home.toString());
        ChatRepl repl = null;
        try {
            ChatConfig config = new ChatConfig("openai-codex", null, "gpt-5.5", "http://unused.invalid");
            config.setAuthenticationMethod("none");
            config.setThinking("high");
            config.saveProject(project);
            repl = new ChatRepl(null, null, "fast-mode-command-test", false, "default", false, config, project);
            ChatCommandRouter router = field(repl, "commandRouter", ChatCommandRouter.class);
            DirectLlmClient client = field(repl, "directClient", DirectLlmClient.class);
            client.addToHistory("user", "keep this context");
            int historySize = client.getHistorySize();

            assertTrue(router.handleSlashCommand("/fast status"));
            assertFalse(config.isFastMode());
            assertTrue(router.handleSlashCommand("/fast"));
            assertTrue(config.isFastMode());
            assertTrue(ChatConfig.loadProject(project).isFastMode());
            assertTrue(router.handleSlashCommand("/fast on"));
            assertTrue(config.isFastMode(), "on must be idempotent, not a second toggle");
            assertTrue(router.handleSlashCommand("/fast invalid"));
            assertTrue(config.isFastMode(), "invalid commands must not change the preference");
            assertTrue(router.handleSlashCommand("/fast off"));
            assertFalse(config.isFastMode());
            assertFalse(ChatConfig.loadProject(project).isFastMode());
            assertEquals("high", config.getThinking());
            assertEquals(historySize, client.getHistorySize());

            config.setProvider("custom");
            assertTrue(router.handleSlashCommand("/fast on"));
            assertFalse(config.isFastMode());
            config.setFastMode(true); // Simulate an old setting after eligibility changed.
            assertTrue(router.handleSlashCommand("/fast off"));
            assertFalse(config.isFastMode());
        } finally {
            if (repl != null) {
                field(repl, "processManager", BackgroundProcessManager.class).close();
                field(repl, "directClient", DirectLlmClient.class).close();
            }
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void modelCandidatesKeepFastModeOnlyWithinSupportedProviderRoutes() {
        ChatConfig active = new ChatConfig("openai-codex", null, "gpt-5.5", null);
        active.setFastMode(true);
        assertTrue(ChatRepl.buildModelProviderCandidateFrom(active, "openai-codex", "gpt-5.6-sol", null).isFastMode());
        assertFalse(ChatRepl.buildModelProviderCandidateFrom(active, "anthropic", "claude-opus-5", null).isFastMode());
        assertFalse(ChatRepl.buildModelProviderCandidateFrom(active, "openai-codex", "gpt-5.3-codex-spark", null).isFastMode());
        assertTrue(active.isFastMode(), "candidate creation/cancellation must not change active state");
    }

    @Test
    void commandLineProviderAndModelOverridesClearIncompatibleSpeedPreferences() {
        ChatConfig config = new ChatConfig("openai-codex", null, "gpt-5.5", null);
        config.setFastMode(true);
        ChatCommand modelSwitch = new ChatCommand();
        new picocli.CommandLine(modelSwitch).parseArgs("--model", "gpt-5.3-codex-spark");
        modelSwitch.applyCommandLineOverrides(config);
        assertFalse(config.isFastMode());

        config.setModel("gpt-5.5");
        config.setFastMode(true);
        ChatCommand providerSwitch = new ChatCommand();
        new picocli.CommandLine(providerSwitch).parseArgs("--provider", "anthropic", "--model", "claude-opus-5");
        providerSwitch.applyCommandLineOverrides(config);
        assertFalse(config.isFastMode());
    }

    @Test
    void statusLabelAndCompletionExposeSpeedSeparatelyFromEffort() {
        assertEquals("model / effort: high / fast: on (requested)",
                ChatRepl.modelTopPaneLabel("model", "high", true, true));
        assertEquals("model / effort: high / fast: off",
                ChatRepl.modelTopPaneLabel("model", "high", true, false));
        assertEquals("model / effort: high",
                ChatRepl.modelTopPaneLabel("model", "high", false, true));
        ChatCompleter completer = new ChatCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();
        String line = "/fast ";
        completer.complete(null, new DefaultParser().parse(line, line.length(), Parser.ParseContext.COMPLETE), candidates);
        assertEquals(List.of("on", "off", "status"), candidates.stream().map(Candidate::value).toList());
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
