package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        ChatCommand command = parse("--url", "http://localhost:8080");

        ChatConfig config = command.configFromExplicitRoute();

        assertNotNull(config);
        assertTrue(config.isValid());
        assertEquals("kompile", config.getProvider());
        assertEquals("standard", config.getChatMode());
    }

    @Test
    void explicitLocalRouteStillNeedsProviderConfig() {
        ChatCommand command = parse("--local");

        assertNull(command.configFromExplicitRoute());
    }

    @Test
    void chatHelpKeepsUrlAndModelExamplesReadable() {
        String usage = new CommandLine(new ChatCommand()).getUsageMessage();

        assertTrue(usage.contains("Example: http://localhost:8080"), usage);
        assertTrue(usage.contains("Examples: haiku, gpt-5.2-codex"), usage);
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
