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
