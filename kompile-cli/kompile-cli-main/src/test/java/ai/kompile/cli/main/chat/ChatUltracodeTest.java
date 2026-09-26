package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
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
import picocli.CommandLine;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatUltracodeTest {
    @TempDir Path tempDir;

    @Test
    void onlyTheClaudeCodeRouteSupportsItAndItReplacesTheEffortOnTheWire() {
        ChatConfig config = claudeCode("oauth");
        config.setThinking("high");
        assertTrue(config.supportsUltracode());
        assertTrue(claudeCode("native").supportsUltracode());
        assertEquals("high", config.effectiveEffort());
        config.setUltracode(true);
        assertTrue(config.useUltracode());
        assertEquals("ultracode", config.effectiveEffort());
        assertEquals("high", config.getThinking(), "the selected level returns when ultracode is turned off");

        // The same vendor through an API key is the Messages API, not Claude Code.
        config.setAuthenticationMethod("api-key");
        assertFalse(config.supportsUltracode());
        assertFalse(config.useUltracode());
        assertEquals("high", config.effectiveEffort());

        ChatConfig codex = new ChatConfig("openai-codex", null, "gpt-5.5", null);
        codex.setThinking("xhigh");
        codex.setUltracode(true);
        assertFalse(codex.supportsUltracode());
        assertEquals("xhigh", codex.effectiveEffort());
    }

    @Test
    void thePreferencePersistsCopiesAndOnlyCarriesAnEffectiveSetting() throws Exception {
        ChatConfig config = claudeCode("oauth");
        config.setUltracode(true);
        String json = JsonUtils.standardMapper().writeValueAsString(config);
        assertTrue(JsonUtils.standardMapper().readValue(json, ChatConfig.class).isUltracode());
        assertTrue(config.copy().isUltracode());
        ChatConfig target = new ChatConfig();
        target.applyLlmSettingsFrom(config);
        assertTrue(target.isUltracode());

        ChatConfig codex = new ChatConfig("openai-codex", null, "gpt-5.5", null);
        codex.setUltracode(true);
        ChatConfig other = new ChatConfig();
        other.applyLlmSettingsFrom(codex);
        assertFalse(other.isUltracode(), "an inert flag from another route must not travel");
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void commandReportsClearsAndRefusesOtherRoutes() throws Exception {
        String previousHome = System.getProperty("user.home");
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        System.setProperty("user.home", home.toString());
        ChatRepl repl = null;
        try {
            ChatConfig config = claudeCode("oauth");
            config.setThinking("high");
            config.setUltracode(true);
            config.saveProject(project);
            repl = new ChatRepl(null, null, "ultracode-command-test", false, "default", false, config, project);
            ChatCommandRouter router = field(repl, "commandRouter", ChatCommandRouter.class);
            ChatConfig session = repl.getChatConfig();

            // Turning it ON runs live discovery (claude auth status + Models API), so the
            // unit test covers the paths that never need it.
            assertTrue(router.handleSlashCommand("/ultracode status"));
            assertTrue(session.isUltracode(), "status must not change the preference");
            assertTrue(router.handleSlashCommand("/ultracode invalid"));
            assertTrue(session.isUltracode(), "invalid commands must not change the preference");
            assertEquals("ultracode", session.effectiveEffort());
            assertTrue(router.handleSlashCommand("/ultracode"));
            assertFalse(session.isUltracode(), "bare /ultracode toggles an enabled preference off");
            assertFalse(ChatConfig.loadSession("ultracode-command-test").isUltracode(),
                    "the toggle must persist to the session config file");
            assertEquals("high", session.effectiveEffort());

            session.setProvider("openai-codex");
            session.setAuthenticationMethod("none");
            assertTrue(router.handleSlashCommand("/ultracode on"));
            assertFalse(session.isUltracode());
            session.setUltracode(true); // Simulate an old setting after the route changed.
            assertTrue(router.handleSlashCommand("/ultracode off"));
            assertFalse(session.isUltracode());
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
    void modelCandidatesKeepItOnlyWithinTheClaudeCodeRoute() {
        ChatConfig active = claudeCode("oauth");
        active.setUltracode(true);
        // The selected model is rechecked against live discovery when it is applied.
        assertTrue(ChatRepl.buildModelProviderCandidateFrom(active, "anthropic", "claude-sonnet-4-6", null).isUltracode());
        assertFalse(ChatRepl.buildModelProviderCandidateFrom(active, "openai-codex", "gpt-5.5", null).isUltracode());
        assertTrue(active.isUltracode(), "candidate creation/cancellation must not change active state");
    }

    @Test
    void commandLineModelProviderAndAuthChangesClearTheModelBoundPreference() {
        ChatConfig config = claudeCode("oauth");
        config.setUltracode(true);
        ChatCommand sameModel = new ChatCommand();
        new CommandLine(sameModel).parseArgs("--model", "claude-opus-5-5");
        sameModel.applyCommandLineOverrides(config);
        assertTrue(config.isUltracode(), "re-selecting the same model keeps the preference");

        ChatCommand modelSwitch = new ChatCommand();
        new CommandLine(modelSwitch).parseArgs("--model", "claude-sonnet-4-6");
        modelSwitch.applyCommandLineOverrides(config);
        assertFalse(config.isUltracode());

        config.setModel("claude-opus-5-5");
        config.setUltracode(true);
        ChatCommand authSwitch = new ChatCommand();
        new CommandLine(authSwitch).parseArgs("--provider", "anthropic", "--auth", "api-key");
        authSwitch.applyCommandLineOverrides(config);
        assertFalse(config.isUltracode());

        ChatConfig other = claudeCode("oauth");
        other.setUltracode(true);
        ChatCommand providerSwitch = new ChatCommand();
        new CommandLine(providerSwitch).parseArgs("--provider", "openai-codex", "--model", "gpt-5.5");
        providerSwitch.applyCommandLineOverrides(other);
        assertFalse(other.isUltracode());
    }

    @Test
    void statusLabelShowsTheEffortSentAndCompletionOffersTheToggle() {
        ChatConfig config = claudeCode("oauth");
        config.setThinking("high");
        config.setUltracode(true);
        assertEquals("model / effort: ultracode / fast: off", ChatRepl.modelTopPaneLabel(
                "model", config.effectiveEffort(), config.supportsFastMode(), config.isFastMode()));
        ChatCompleter completer = new ChatCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();
        String line = "/ultracode ";
        completer.complete(null, new DefaultParser().parse(line, line.length(), Parser.ParseContext.COMPLETE), candidates);
        assertEquals(List.of("on", "off", "status"), candidates.stream().map(Candidate::value).toList());
    }

    private static ChatConfig claudeCode(String authenticationMethod) {
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod(authenticationMethod);
        return config;
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
