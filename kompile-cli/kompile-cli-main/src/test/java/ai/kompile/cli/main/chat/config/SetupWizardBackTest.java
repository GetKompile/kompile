package ai.kompile.cli.main.chat.config;

import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SetupWizardBackTest {
    @TempDir Path project;

    private static Terminal terminal() throws IOException {
        return TerminalBuilder.builder().system(false).dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).build();
    }

    private static final class ScriptedReader extends SetupWizard.SetupReader {
        private final ArrayDeque<String> answers;
        private final List<String> prompts = new ArrayList<>();
        private boolean eof;

        ScriptedReader(Terminal terminal, String... answers) throws IOException {
            super(terminal);
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override protected String readInput(String prompt) {
            prompts.add(prompt);
            if (answers.isEmpty()) {
                if (eof) throw new EndOfFileException();
                throw new AssertionError("Unexpected additional wizard prompt: " + prompt);
            }
            return answers.removeFirst();
        }

        void assertConsumed() {
            assertTrue(answers.isEmpty(), () -> "Unconsumed answers: " + answers);
        }
    }

    private SetupWizard.SetupResult run(ScriptedReader reader, boolean destination) throws IOException {
        SetupWizard.SetupResult result = SetupWizard.runInteractive(reader, ChatConfig.Scope.PROJECT,
                project, false, destination, null);
        reader.assertConsumed();
        return result;
    }

    @Test void backReopensVendorRuntimeAndChatModeWithoutExiting() throws Exception {
        try (Terminal terminal = terminal()) {
            ScriptedReader reader = new ScriptedReader(terminal,
                    "1", "3", "0", "4", "back", "0", "1", "4", "http://localhost:9009", "2", "1");
            SetupWizard.SetupResult result = run(reader, false);
            assertNotNull(result);
            assertEquals("standard", result.config().getChatMode());
            assertEquals("kompile", result.config().getProvider());
            assertEquals("http://localhost:9009", result.config().getBaseUrl());
            assertEquals(SetupWizard.Destination.TERMINAL, result.destination());
            assertEquals(2, reader.prompts.stream().filter(p -> p.contains("URL (Enter")).count());
            assertTrue(reader.prompts.stream().anyMatch(p -> p.contains("0 = Back")));
            assertEquals("kompile", ChatConfig.loadProject(project).getProvider());
        }
    }

    @Test void backFromAuthReturnsToVendorAndDiscardsTheOldRoute() throws Exception {
        int openai = SetupWizard.directVendorOrder().indexOf("openai") + 1;
        assertTrue(openai > 0);
        try (Terminal terminal = terminal()) {
            ScriptedReader reader = new ScriptedReader(terminal,
                    "1", "3", Integer.toString(openai), "0", "0", "4", "", "2", "1");
            SetupWizard.SetupResult result = run(reader, false);
            assertNotNull(result);
            assertEquals("kompile", result.config().getProvider());
            assertNull(result.config().getModel());
            assertNull(result.config().getCredentialName());
        }
    }

    @Test void backFromResumeAllWindowReturnsToModeWithoutLaunchingResume() throws Exception {
        try (Terminal terminal = terminal()) {
            ScriptedReader reader = new ScriptedReader(terminal, "5", "0", "1", "4", "", "2", "1");
            assertEquals("standard", run(reader, false).config().getChatMode());
        }
    }

    private void saveProfile() throws IOException {
        ChatProfiles.save(project, ChatProfiles.capture("local",
                new ChatConfig("custom", null, "test-model", "http://localhost:9001/v1")), false);
    }

    @Test void profileAndBrowserPortBackKeepTheEarlierProfileChoice() throws Exception {
        saveProfile();
        try (Terminal terminal = terminal()) {
            ScriptedReader reader = new ScriptedReader(terminal,
                    "1", "1", "1", "0", "1", "1", "2", "back", "1", "1");
            SetupWizard.SetupResult result = run(reader, true);
            assertNotNull(result);
            assertEquals("test-model", result.config().getModel());
            assertEquals(SetupWizard.Destination.TERMINAL, result.destination());
            assertEquals(0, result.webPort());
            assertEquals(1, reader.prompts.stream().filter(p -> p.contains("Web UI port")).count());
        }
    }

    @Test void automaticPortZeroIsNotBack() throws Exception {
        saveProfile();
        try (Terminal terminal = terminal()) {
            ScriptedReader reader = new ScriptedReader(terminal, "1", "1", "1", "1", "2", "0", "1");
            SetupWizard.SetupResult result = run(reader, true);
            assertEquals(SetupWizard.Destination.BROWSER, result.destination());
            assertEquals(0, result.webPort());
        }
    }

    @Test void backCanChangeProfileNameBeforeAnythingIsSaved() throws Exception {
        try (Terminal terminal = terminal()) {
            ScriptedReader reader = new ScriptedReader(terminal,
                    "1", "4", "", "1", "discarded", "0", "kept", "1");
            assertNotNull(run(reader, false));
            assertEquals(List.of("kept"), ChatProfiles.list(project, "standard").stream()
                    .map(ChatProfiles.Profile::name).toList());
        }
    }

    @Test void backingOutOfProfileSaveDoesNotLeaveADiscardedProfile() throws Exception {
        try (Terminal terminal = terminal()) {
            ScriptedReader reader = new ScriptedReader(terminal,
                    "1", "4", "", "1", "discarded", "0", "back", "2", "1");
            assertNotNull(run(reader, false));
            assertTrue(ChatProfiles.list(project, "standard").isEmpty());
        }
    }

    @Test void cancelAndEofDoNotSavePartialConfigurationOrProfile() throws Exception {
        for (String[] answers : List.of(new String[]{"6"}, new String[]{"1", "5"},
                new String[]{"1", "4", "cancel"},
                new String[]{"1", "4", "", "1", "discarded", "3"},
                new String[]{"1", "4"})) {
            try (Terminal terminal = terminal()) {
                ScriptedReader reader = new ScriptedReader(terminal, answers);
                reader.eof = true;
                assertNull(run(reader, false));
                assertFalse(Files.exists(ChatConfig.configPath(ChatConfig.Scope.PROJECT, project)));
                assertTrue(ChatProfiles.list(project, "standard").isEmpty());
            }
        }
    }

    @Test void sharedCredentialPickerCannotSwallowBackOrCancel() throws Exception {
        for (String command : List.of("0", "back", "cancel")) {
            try (Terminal terminal = terminal()) {
                ScriptedReader reader = new ScriptedReader(terminal, "1", command);
                SetupWizard.selectNumbered(reader, "Mode", List.of("First", "Second"));
                RuntimeException navigation = assertThrows(RuntimeException.class, () -> reader.interaction(() ->
                        ai.kompile.cli.main.auth.CredentialMenu.select(reader, "Credentials", List.of("A", "B"))));
                assertEquals(command.equals("cancel") ? "Cancelled" : "BackRequested",
                        navigation.getClass().getSimpleName());
                reader.assertConsumed();
            }
        }
    }

    @Test @org.junit.jupiter.api.Timeout(10)
    void realJlineTerminalAcceptsBackAndCompletesWithoutAnExtraPrompt() throws Exception {
        try (var terminal = new org.jline.terminal.impl.LineDisciplineTerminal("setup-back", "xterm",
                new ByteArrayOutputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
            terminal.setSize(new org.jline.terminal.Size(100, 30));
            terminal.processInputBytes("1\n3\n0\n4\n\n2\n1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SetupWizard.SetupResult result = SetupWizard.runInteractive(new SetupWizard.SetupReader(terminal),
                    ChatConfig.Scope.PROJECT, project, false, false, null);
            assertNotNull(result);
            assertEquals("kompile", result.config().getProvider());
            assertTrue(Files.exists(ChatConfig.configPath(ChatConfig.Scope.PROJECT, project)));
        }
    }

    @Test void rewindingLaterChoicesDoesNotRepeatCompletedSideEffects() throws Exception {
        try (Terminal terminal = terminal()) {
            ScriptedReader reader = new ScriptedReader(terminal, "1", "key", "1", "back", "2");
            AtomicInteger authentications = new AtomicInteger();
            int attempts = 0;
            while (true) {
                reader.restart();
                try {
                    SetupWizard.selectNumbered(reader, "Mode", List.of("A", "B"));
                    reader.interaction(() -> {
                        authentications.incrementAndGet();
                        return reader.readLine("Credential: ");
                    });
                    int model = SetupWizard.selectNumbered(reader, "Model", List.of("First", "Second"));
                    if (attempts++ == 0) reader.readLine("Continue: ");
                    assertEquals(1, model);
                    break;
                } catch (RuntimeException e) {
                    assertEquals("BackRequested", e.getClass().getSimpleName());
                    reader.back();
                }
            }
            reader.assertConsumed();
            assertEquals(1, authentications.get());
        }
    }
}
