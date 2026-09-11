package ai.kompile.cli.main.chat.config;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SetupWizardProfilesTest {
    @TempDir Path project;

    @Test
    void selectingProfileUsesVendorThenNameWithoutAskingForCredentials() throws Exception {
        ChatProfiles.save(project, ChatProfiles.capture("work",
                new ChatConfig("anthropic", null, "claude", null)), false);
        ChatConfig config = new ChatConfig("openai-codex", null, "gpt", null);
        config.setThinking("high");
        config.setAuthenticationMethod("oauth");
        ChatProfiles.save(project, ChatProfiles.capture("work", config), false);
        SetupWizard.ProfileSelection selected = SetupWizard.selectProjectProfile(
                reader("yes", "2", "1"), project, "standard");
        assertFalse(selected.cancelled());
        assertEquals("openai-codex", selected.config().getProvider());
        assertEquals("gpt", selected.config().getModel());
        assertEquals("high", selected.config().getThinking());
        assertEquals("oauth", selected.config().getAuthenticationMethod());
    }

    @Test
    void noAndEnterKeepNormalSetupWhileCancelAndEofAbort() throws Exception {
        ChatProfiles.save(project, ChatProfiles.capture("work", ChatProfilesTest.nativeConfig(true)), false);
        for (String answer : List.of("no", "n", "")) {
            SetupWizard.ProfileSelection skipped = SetupWizard.selectProjectProfile(reader(answer), project, "passthrough");
            assertNull(skipped.config());
            assertFalse(skipped.cancelled());
        }
        for (LineReader input : List.of(reader("cancel"), reader(), reader("y", "q"), reader("y", "1", "q"))) {
            SetupWizard.ProfileSelection cancelled = SetupWizard.selectProjectProfile(input, project, "passthrough");
            assertNull(cancelled.config());
            assertTrue(cancelled.cancelled());
        }
    }

    @Test
    void emptyProjectAndOtherModesDoNotPrompt() throws Exception {
        assertFalse(SetupWizard.selectProjectProfile(reader(), project, "standard").cancelled());
        ChatProfiles.save(project, ChatProfiles.capture("work", ChatProfilesTest.nativeConfig(true)), false);
        assertFalse(SetupWizard.selectProjectProfile(reader(), project, "standard").cancelled());
        assertFalse(SetupWizard.selectProjectProfile(reader(), project, "resume-all").cancelled());
    }

    @Test
    void bothPassthroughStylesAreSelectableBeforePickingAnAgent() throws Exception {
        ChatProfiles.save(project, ChatProfiles.capture("managed", ChatProfilesTest.nativeConfig(true)), false);
        ChatProfiles.save(project, ChatProfiles.capture("direct", ChatProfilesTest.nativeConfig(false)), false);
        for (int choice = 1; choice <= 2; choice++) {
            ChatConfig selected = SetupWizard.selectProjectProfile(
                    reader("y", "1", Integer.toString(choice)), project, "passthrough").config();
            assertNotNull(selected);
            assertEquals(choice == 1, selected.isPassthroughManaged());
            assertEquals("codex", selected.getPassthroughAgent());
            assertEquals("high", selected.getThinking());
        }
    }

    @Test
    void invalidYesNoRequiresAnAnswerBeforeSelectingAProfile() throws Exception {
        ChatProfiles.save(project, ChatProfiles.capture("work", ChatProfilesTest.nativeConfig(true)), false);
        assertNotNull(SetupWizard.selectProjectProfile(reader("maybe", "yes", "1", "1"),
                project, "passthrough").config());
    }

    @Test
    void saveCanBeDeclinedAndDuplicateReplacementRequiresYes() throws Exception {
        ChatConfig config = ChatProfilesTest.nativeConfig(true);
        SetupWizard.saveProjectProfile(reader("n"), project, config);
        assertFalse(Files.exists(ChatProfiles.path(project)));
        SetupWizard.saveProjectProfile(reader("yes", "work"), project, config);
        config.setThinking("low");
        SetupWizard.saveProjectProfile(reader("yes", "work", "n"), project, config);
        assertEquals("high", ChatProfiles.list(project, "passthrough").get(0).thinking());
        SetupWizard.saveProjectProfile(reader("yes", "work", "yes"), project, config);
        assertEquals("low", ChatProfiles.list(project, "passthrough").get(0).thinking());
    }

    @Test
    void cancellingSaveAbortsSetupWithoutWritingAProfile() {
        ChatConfig config = ChatProfilesTest.nativeConfig(true);
        assertFalse(SetupWizard.saveProjectProfile(reader(), project, config));
        assertFalse(SetupWizard.saveProjectProfile(reader("yes"), project, config));
        assertTrue(SetupWizard.saveProjectProfile(reader("yes", ""), project, config));
        assertFalse(Files.exists(ChatProfiles.path(project)));
    }

    @Test
    void nativeThinkingPickerOnlyOffersSupportedLaunchContracts() {
        for (boolean managed : List.of(true, false)) {
            assertTrue(SetupWizard.supportsPassthroughThinking("codex", managed));
            assertTrue(SetupWizard.supportsPassthroughThinking("claude", managed));
            assertFalse(SetupWizard.supportsPassthroughThinking("gemini", managed));
            assertFalse(SetupWizard.supportsPassthroughThinking("qwen", managed));
        }
        assertTrue(SetupWizard.supportsPassthroughThinking("opencode", true));
        assertFalse(SetupWizard.supportsPassthroughThinking("opencode", false));
    }

    @Test
    void nativeSelectionCanKeepDefaultsOrCancelWithoutLaunchingDiscovery() {
        ChatConfig config = ChatProfilesTest.nativeConfig(true);
        assertTrue(SetupWizard.configurePassthroughModel(reader("no"), config));
        assertFalse(SetupWizard.configurePassthroughModel(reader("cancel"), config));
    }

    @Test
    void destinationIsTransientAndProfileReuseOffersTheSameChoice() throws Exception {
        ChatConfig config = new ChatConfig("ollama", null, "model", null);
        ChatProfiles.save(project, ChatProfiles.capture("local", config), false);
        ChatConfig reused = SetupWizard.selectProjectProfile(reader("y", "1", "1"), project, "standard").config();
        for (ChatConfig candidate : List.of(config, reused)) {
            assertEquals(SetupWizard.Destination.TERMINAL,
                    SetupWizard.selectDestination(reader("1"), candidate, false, true));
            assertEquals(SetupWizard.Destination.BROWSER,
                    SetupWizard.selectDestination(reader("2"), candidate, false, true));
            assertEquals(SetupWizard.Destination.TERMINAL,
                    SetupWizard.selectDestination(reader(""), candidate, false, true));
            assertNull(SetupWizard.selectDestination(reader("q"), candidate, false, true));
            assertNull(SetupWizard.selectDestination(reader(), candidate, false, true));
        }
        assertFalse(Files.exists(ChatConfig.configPath(ChatConfig.Scope.PROJECT, project)));
        assertFalse(Files.readString(ChatProfiles.path(project)).contains("destination"));
    }

    @Test
    void explicitWebSkipsDestinationAndOtherModesStayTerminal() {
        ChatConfig config = new ChatConfig("ollama", null, "model", null);
        assertEquals(SetupWizard.Destination.BROWSER,
                SetupWizard.selectDestination(reader(), config, true, true));
        assertEquals(SetupWizard.Destination.TERMINAL,
                SetupWizard.selectDestination(reader(), config, false, false));
        for (String mode : List.of("passthrough", "resume", "resume-all")) {
            config.setChatMode(mode);
            assertEquals(SetupWizard.Destination.TERMINAL,
                    SetupWizard.selectDestination(reader(), config, false, true));
            assertNull(SetupWizard.selectDestination(reader(), config, true, true));
        }
        assertNull(SetupWizard.selectDestination(reader(),
                new ChatConfig("kompile", null, null, null), true, true));
    }

    @Test
    void browserRequiresSuccessfulPersistenceWhileTerminalRetainsFallback() throws Exception {
        ChatConfig config = new ChatConfig("ollama", null, "model", null);
        Path blocked = project.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        assertThrows(java.io.IOException.class, () -> SetupWizard.saveConfiguration(config,
                SetupWizard.Destination.BROWSER, ChatConfig.Scope.PROJECT, blocked));
        assertFalse(SetupWizard.saveConfiguration(config, SetupWizard.Destination.TERMINAL,
                ChatConfig.Scope.PROJECT, blocked));
        assertTrue(SetupWizard.saveConfiguration(config, SetupWizard.Destination.BROWSER,
                ChatConfig.Scope.PROJECT, project));
        String saved = Files.readString(ChatConfig.configPath(ChatConfig.Scope.PROJECT, project));
        assertFalse(saved.contains("destination"));
        assertEquals("model", ChatConfig.loadProject(project).getModel());
    }

    private static LineReader reader(String... answers) {
        ArrayDeque<String> input = new ArrayDeque<>(List.of(answers));
        return (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(), new Class<?>[]{LineReader.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("readLine")) {
                        if (input.isEmpty()) throw new EndOfFileException();
                        return input.removeFirst();
                    }
                    throw new AssertionError("Unexpected reader call: " + method);
                });
    }
}
