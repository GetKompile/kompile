package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.SetupWizard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ChatWebHandoffTest {
    @TempDir Path directory;

    @Test void setupWebHandsOffOnlyAfterValidSelection() throws Exception {
        Stub command = new Stub();
        command.config = new ChatConfig("custom", "secret", "model", "https://example.test/v1");
        command.config.setChatMode("standard");
        String output = successfulOutput(command, "--setup", "--web", "--global-config",
                "--working-dir", directory.toString());
        assertTrue(output.contains("Web chat: http://127.0.0.1:1234"));
        assertTrue(command.selected);
        assertFalse(command.wizard);
        assertEquals(directory.toRealPath(), command.started);
        assertNull(command.opened);
    }

    @Test void webDefaultsToPrintedUrlWithoutBrowser() {
        Stub command = new Stub();
        command.config = new ChatConfig("ollama", null, "model", null);
        String output = successfulOutput(command, "--web", "--working-dir", directory.toString());
        assertTrue(output.contains("Web chat: http://127.0.0.1:1234"));
        assertNull(command.opened);
        assertFalse(command.wizard);
    }

    @Test void explicitBrowserOptInPrintsUrlAndDoesNotRunDestinationWizard() {
        for (boolean setup : new boolean[] {false, true}) {
            Stub command = new Stub();
            command.config = new ChatConfig("ollama", null, "model", null);
            String[] args = setup
                    ? new String[] {"--web", "--open-browser", "--setup", "--working-dir", directory.toString()}
                    : new String[] {"--web", "--open-browser", "--working-dir", directory.toString()};
            String output = successfulOutput(command, args);
            assertTrue(output.contains("Web chat: http://127.0.0.1:1234"));
            assertEquals("http://127.0.0.1:1234", command.opened);
            assertTrue(command.selected);
            assertFalse(command.wizard);
        }
    }

    @Test void browserOptInRequiresExplicitWebBeforeAnySetupOrLaunch() {
        for (String[] args : new String[][] {
                {"--open-browser"}, {"--open-browser", "--setup"},
                {"--open-browser", "--resume", "id"}}) {
            Stub command = new Stub();
            assertEquals(2, new CommandLine(command).execute(args));
            assertFalse(command.wizard);
            assertFalse(command.selected);
            assertNull(command.started);
            assertNull(command.opened);
        }
    }

    @Test void cancellationOrSaveFailureNeverStartsServer() {
        Stub command = new Stub();
        assertEquals(1, new CommandLine(command).execute("--setup", "--web"));
        assertNull(command.started);
        assertNull(command.opened);
    }

    @Test void incompatibleOptionsAreRejectedBeforeSetup() {
        for (String[] options : new String[][] {
                {"--resume", "id"}, {"--mode", "standard"}, {"--provider", "custom"},
                {"--url", "http://localhost:8081"}, {"--no-start"}, {"--capabilities"},
                {"--multi-session"}, {"--local"}, {"--no-memory"}, {"hello"}}) {
            Stub command = new Stub();
            String[] args = new String[options.length + 1];
            args[0] = "--web";
            System.arraycopy(options, 0, args, 1, options.length);
            assertEquals(2, new CommandLine(command).execute(args));
            assertFalse(command.selected);
            assertNull(command.started);
        }
    }

    @Test void incompatibleConfigurationsAreNotReinterpreted() {
        for (String mode : new String[] {"passthrough", "resume", "resume-all"}) {
            ChatConfig config = new ChatConfig("custom", "secret", "model", "https://example.test/v1");
            config.setChatMode(mode);
            assertNotNull(ChatCommand.webConfigError(config));
        }
        assertNotNull(ChatCommand.webConfigError(new ChatConfig("kompile", null, null, "http://localhost:8081")));
    }

    @Test void wizardBrowserUsesExistingHandoffWithoutSelectingOrAuthenticatingAgain() {
        Stub command = new Stub();
        command.config = new ChatConfig("ollama", null, "model", null);
        command.destination = SetupWizard.Destination.BROWSER;
        String output = successfulOutput(command, "--setup", "--working-dir", directory.toString());
        assertTrue(output.contains("Web chat: http://127.0.0.1:1234"));
        assertTrue(command.wizard);
        assertFalse(command.selected);
        assertNotNull(command.started);
        assertNull(command.opened);
    }

    @Test void terminalSetupExitsAndCancelledSetupDoesNotLaunch() {
        Stub command = new Stub();
        command.config = new ChatConfig("ollama", null, "model", null);
        assertEquals(0, new CommandLine(command).execute("--setup"));
        assertNull(command.started);
        command.config = null;
        assertEquals(1, new CommandLine(command).execute("--setup"));
        assertNull(command.started);
    }

    private static String successfulOutput(Stub command, String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            assertEquals(0, new CommandLine(command).execute(args));
        } finally {
            System.setOut(original);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static class Stub extends ChatCommand {
        SetupWizard.Destination destination = SetupWizard.Destination.TERMINAL;
        boolean wizard;
        @Override SetupWizard.SetupResult runSetupWizard() {
            wizard = true;
            return config == null ? null : new SetupWizard.SetupResult(config, destination);
        }
        ChatConfig config;
        boolean selected;
        Path started;
        String opened;
        @Override ChatConfig selectWebConfig(Path path) { selected = true; return config; }
        @Override ChatInstanceBootstrap.StartupResult startWeb(Path path) {
            started = path;
            return new ChatInstanceBootstrap.StartupResult("http://127.0.0.1:1234", true);
        }
        @Override void openWebBrowser(String address) { opened = address; }
    }
}
