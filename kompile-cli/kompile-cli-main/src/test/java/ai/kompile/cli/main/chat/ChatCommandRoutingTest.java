package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Reference;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCommandRoutingTest {

    @Test
    void directPassthroughDoesNotConsiderImplicitProjectEnforcement() {
        ChatConfig config = passthroughConfig(false);

        assertFalse(ChatCommand.shouldConsiderEnforcement(config, false));
    }

    @Test
    void directPassthroughCanStillUseExplicitRuleFlags() {
        ChatConfig config = passthroughConfig(false);

        assertTrue(ChatCommand.shouldConsiderEnforcement(config, true));
    }

    @Test
    void managedPassthroughConsidersProjectEnforcement() {
        ChatConfig config = passthroughConfig(true);

        assertTrue(ChatCommand.shouldConsiderEnforcement(config, false));
    }

    @Test
    void explicitPassthroughRouteDoesNotNeedSavedConfig() {
        ChatCommand command = parse("--mode", "passthrough", "--agent", "codex");

        ChatConfig config = command.configFromExplicitRoute();

        assertNotNull(config);
        assertTrue(config.isValid());
        assertEquals("passthrough", config.getChatMode());
        assertEquals("codex", config.getPassthroughAgent());
    }

    @Test
    void explicitServerRouteDoesNotNeedSavedConfig() {
        ChatCommand command = parse("--url", "http://localhost:8081");

        ChatConfig config = command.configFromExplicitRoute();

        assertNotNull(config);
        assertTrue(config.isValid());
        assertEquals("kompile", config.getProvider());
        assertEquals("standard", config.getChatMode());
    }

    @Test
    void standardResumeDoesNotPromotePassthroughToHttp() {
        ChatCommand command = parse("--resume", "cli-test", "--mode", "standard");
        ChatConfig passthrough = passthroughConfig(true);

        ChatConfig config = command.normalizeResumeConfig(passthrough, true);

        assertNull(config);
    }

    @Test
    void standardResumeKeepsDirectProviderConfig() {
        ChatCommand command = parse("--resume", "cli-test", "--mode", "standard");
        ChatConfig direct = new ChatConfig("openai-codex", null, "gpt-5.6-sol", null);
        direct.setChatMode("standard");

        ChatConfig config = command.normalizeResumeConfig(direct, true);

        assertNotNull(config);
        assertEquals("openai-codex", config.getProvider());
        assertEquals("gpt-5.6-sol", config.getModel());
        assertEquals("standard", config.getChatMode());
        assertFalse(config.isKompileServer());
    }

    @Test
    void noStartConnectsToDefaultServerWithoutSavedConfig() {
        ChatConfig config = parse("--no-start").configFromExplicitRoute();

        assertNotNull(config);
        assertEquals("kompile", config.getProvider());
        assertEquals("standard", config.getChatMode());
    }

    @Test
    void explicitLocalRouteStillNeedsProviderConfig() {
        ChatCommand command = parse("--local");

        assertNull(command.configFromExplicitRoute());
    }

    @Test
    void bareChatKeepsWizardFirstFlow() {
        ChatCommand command = parse();

        assertTrue(command.shouldRunSetupWizard(null, false));
        ChatConfig savedDirectConfig = new ChatConfig("ollama", null, "llama3.3", null);
        assertTrue(command.shouldRunSetupWizard(savedDirectConfig, false));
    }

    @Test
    void onlyStandardDirectConfigsCanHotSwitchInsideTheCurrentTranscript() {
        ChatConfig direct = new ChatConfig("anthropic", "key", "claude-sonnet-4-20250514", null);
        direct.setChatMode("standard");
        ChatConfig server = new ChatConfig("kompile", null, null, "http://localhost:8081");
        server.setChatMode("standard");
        ChatConfig passthrough = passthroughConfig(true);

        assertTrue(ChatRepl.canHotSwitchLocalProvider(direct));
        assertFalse(ChatRepl.canHotSwitchLocalProvider(server));
        assertFalse(ChatRepl.canHotSwitchLocalProvider(
                new ChatConfig("kompile-local", null, "Qwen2.5-0.5B-Instruct", null)));
        assertFalse(ChatRepl.canHotSwitchLocalProvider(passthrough));
    }

    @Test
    void modeHotkeysUseCtrlXChordsWithoutStealingPrintableLetters() {
        KeyMap<Binding> keyMap = new KeyMap<>();

        ChatRepl.bindModeSwitchingHotkeys(keyMap);

        for (char printable : new char[]{'p', 'P', 't', 'T', 'a', 'A'}) {
            assertNull(keyMap.getBound(String.valueOf(printable)),
                    () -> "Printable letter must remain available for typing: " + printable);
        }
        assertWidgetBinding(keyMap, 'p', "toggle-plan-mode");
        assertWidgetBinding(keyMap, 'P', "toggle-plan-mode");
        assertWidgetBinding(keyMap, 't', "show-todos");
        assertWidgetBinding(keyMap, 'T', "show-todos");
        assertWidgetBinding(keyMap, 'a', "cycle-agent");
        assertWidgetBinding(keyMap, 'A', "cycle-agent");
    }

    @Test
    void directProviderAndMissingConfigNeverStartInstalledSubprocess() {
        ChatCommand command = parse();
        ChatConfig direct = new ChatConfig("ollama", null, "llama3.3", null);
        ChatConfig firstPartyLocal =
                new ChatConfig("kompile-local", null, "Qwen2.5-0.5B-Instruct", null);

        assertFalse(command.shouldStartInstalledChatSubprocess(direct, true));
        assertFalse(command.shouldStartInstalledChatSubprocess(firstPartyLocal, true));
        assertFalse(command.shouldStartInstalledChatSubprocess(null, true));
    }

    @Test
    void firstPartyLocalConfigIsValidWithoutInstanceOrApiKey() {
        ChatConfig config =
                new ChatConfig("kompile-local", null, "Qwen2.5-0.5B-Instruct", null);

        assertTrue(config.isValid());
        assertTrue(config.isKompileLocalServing());
        assertFalse(config.isKompileServer());
        assertNull(config.resolveBaseUrl());
    }

    @Test
    void localKompileProviderCanStartInstalledSubprocessAfterSetup() {
        ChatCommand command = parse();
        ChatConfig localKompile = new ChatConfig("kompile", null, null,
                "http://localhost:9191");

        assertTrue(command.shouldStartInstalledChatSubprocess(localKompile, true));
        assertFalse(command.shouldStartInstalledChatSubprocess(localKompile, false));
    }

    @Test
    void configuredRemoteKompileInstanceRemainsConnectOnly() {
        ChatCommand command = parse();
        ChatConfig remoteKompile = new ChatConfig("kompile", null, null,
                "https://chat.example.com");

        assertFalse(command.shouldStartInstalledChatSubprocess(remoteKompile, true));
    }

    @Test
    void explicitRoutesAndNoStartDisableInstalledSubprocessFallback() {
        assertFalse(parse("--no-start").canUseInstalledChatSubprocessFallback());
        assertFalse(parse("--local").canUseInstalledChatSubprocessFallback());
        assertFalse(parse("--url", "http://localhost:8081")
                .canUseInstalledChatSubprocessFallback());
        assertFalse(parse("--port", "8081").canUseInstalledChatSubprocessFallback());
        assertFalse(parse("--mode", "passthrough").canUseInstalledChatSubprocessFallback());
        assertTrue(parse("--mode", "standard").canUseInstalledChatSubprocessFallback());
    }

    @Test
    void kompileConfigResolvesSavedServerUrlWithoutExplicitFlags() {
        ChatCommand command = parse();
        ChatConfig config = new ChatConfig("kompile", null, null, "http://localhost:9191");

        assertEquals("http://localhost:9191", command.resolveServerUrl(config));
    }

    @Test
    void explicitServerUrlOverridesSavedKompileUrl() {
        ChatCommand command = parse("--url", "http://localhost:8181");
        ChatConfig config = new ChatConfig("kompile", null, null, "http://localhost:9191");

        assertEquals("http://localhost:8181", command.resolveServerUrl(config));
    }

    @Test
    void standardChatExposesExplicitDangerousPermissionBypass() {
        assertFalse(parse().dangerouslySkipsPermissions());
        assertTrue(parse("--dangerously-skip-permissions").dangerouslySkipsPermissions());
    }

    @Test
    void chatHelpKeepsUrlAndModelExamplesReadable() {
        String usage = new CommandLine(new ChatCommand()).getUsageMessage();

        assertTrue(usage.contains("Example: http://localhost:8081"), usage);
        assertTrue(usage.contains("Examples: haiku, gpt-5.2-codex"), usage);
        assertTrue(usage.contains("--[no-]start"), usage);
        assertTrue(usage.contains("--startup-timeout"), usage);
        assertTrue(usage.contains("--dangerously-skip-permissions"), usage);
        assertFalse(usage.contains("--project"), usage);
        assertFalse(usage.contains("http:\n"), usage);
        assertFalse(usage.contains("gpt-5.\n"), usage);
    }

    @Test
    void passthroughHelpMentionsOpenCodeAndKeepsModelExampleReadable() {
        String usage = new CommandLine(new PassthroughCommand()).getUsageMessage();

        assertTrue(usage.contains("claude, codex, opencode, gemini"), usage);
        assertTrue(usage.contains("Examples: haiku, gpt-5.2-codex"), usage);
        assertFalse(usage.contains("gpt-5.\n"), usage);
    }

    private static void assertWidgetBinding(
            KeyMap<Binding> keyMap, char key, String expectedWidget) {
        Binding binding = keyMap.getBound(KeyMap.ctrl('X') + String.valueOf(key));
        assertInstanceOf(Reference.class, binding);
        assertEquals(expectedWidget, ((Reference) binding).name());
    }

    private static ChatConfig passthroughConfig(boolean managed) {
        ChatConfig config = new ChatConfig(null, null, null, null);
        config.setChatMode("passthrough");
        config.setPassthroughManaged(managed);
        config.setPassthroughAgent("codex");
        return config;
    }

    private static ChatCommand parse(String... args) {
        ChatCommand command = new ChatCommand();
        new CommandLine(command).parseArgs(args);
        return command;
    }
}
