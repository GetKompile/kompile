package ai.kompile.cli.main.chat.config;

import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
class ChatUiWizardTest {
    @TempDir Path project;
    private String previousHome;

    @org.junit.jupiter.api.BeforeEach void isolateUserPreferences() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", project.resolve("home").toString());
    }
    @org.junit.jupiter.api.AfterEach void restoreHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    private LineReader numbers(String... choices) {
        LineReader reader = mock(LineReader.class);
        java.util.ArrayDeque<String> input = new java.util.ArrayDeque<>(List.of(choices));
        when(reader.readLine(anyString())).thenAnswer(call -> {
            assertEquals("Choice (number): ", call.getArgument(0));
            assertFalse(input.isEmpty(), "Wizard requested an unexpected additional input");
            return input.removeFirst();
        });
        return reader;
    }

    @Test void successfulSaveTerminatesForChatAndStandaloneWithoutAnotherPrompt() throws Exception {
        for (boolean inChat : List.of(false, true)) {
            Path target = project.resolve(inChat ? "chat" : "standalone");
            LineReader reader = numbers("1", "2", "6", inChat ? "2" : "1");
            List<String> pages = new ArrayList<>();
            java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
            ChatUiWizard.run(reader, ChatUiConfig.classic(), ignored -> { }, (title, lines) -> pages.add(title),
                    closes::incrementAndGet, target, inChat ? "ui-test" : null);
            assertEquals(List.of("Chat display", "Choose a preset", "Chat display", "Save preferences"), pages);
            verify(reader, times(4)).readLine(anyString());
            assertEquals(1, closes.get(), "Completion closes the wizard exactly once");
            assertEquals("focused", ChatUiConfig.load(List.of(ChatUiConfig.projectPath(target))).getPreset());
        }
    }

    @Test void numberedPresetTwoSelectsFocusedAndAllPagesAreNumbered() throws Exception {
        LineReader reader = numbers("1", "2", "6", "2");
        List<ChatUiConfig> live = new ArrayList<>();
        List<List<String>> pages = new ArrayList<>();
        AtomicBoolean closed = new AtomicBoolean();
        ChatUiWizard.run(reader, ChatUiConfig.classic(), live::add, (title, lines) -> pages.add(lines), () -> closed.set(true), project, "ui-test");
        assertTrue(closed.get()); assertEquals(2, live.size());
        assertEquals("focused", live.get(0).getPreset());
        assertEquals("focused", ChatUiConfig.load(List.of(ChatUiConfig.projectPath(project))).getPreset());
        assertTrue(pages.get(0).get(0).contains("1. Preset"));
        assertTrue(pages.get(1).get(1).contains("2. Focused"));
        assertTrue(pages.get(1).get(0).contains("* current"));
        for (List<String> page : pages) {
            assertTrue(page.stream().anyMatch(line -> line.matches(" *0\\. .*")));
            assertTrue(page.stream().noneMatch(line -> line.contains("inline=") || line.contains("setting path") || line.contains("preset =")));
            assertTrue(page.stream().filter(line -> line.contains("Back") || line.contains("Cancel") || line.contains("Done")).allMatch(line -> line.matches(" *[0-9]+\\. .*")));
        }
    }

    @Test void contentAndSectionPlacementRequireOnlyNumberSelections() throws Exception {
        // Tool calls -> chat summary, then diffs -> expanded sidebar.
        LineReader reader = numbers("3", "3", "1", "2", "11", "2", "3", "0", "0");
        List<ChatUiConfig> live = new ArrayList<>();
        ChatUiWizard.run(reader, ChatUiConfig.classic(), live::add, (title, lines) -> { }, () -> { }, project, "ui-test");
        ChatUiConfig result = live.get(live.size() - 1);
        assertEquals("summary", result.policy("tool", "read").inline());
        assertEquals("expanded", result.sectionPolicy("diff", "read", result.policy("tool", "read")).sidebar());
        ChatUiConfig restored = ChatUiConfig.loadEffective(project, "ui-test");
        assertEquals("summary", restored.policy("tool", "read").inline());
        assertEquals("expanded", restored.sectionPolicy("diff", "read", restored.policy("tool", "read")).sidebar());
        assertTrue(Files.exists(ChatUiConfig.globalPath()), "Done must save without a separate Save action");
        assertFalse(Files.exists(ChatUiConfig.projectPath(project)));
    }

    @Test void sidebarRetentionChoicesPreviewAndSaveInBothHosts() throws Exception {
        for (boolean inChat : List.of(false, true)) {
            List<ChatUiConfig> live = new ArrayList<>();
            ChatUiWizard.run(numbers("4", "6", "4", "7", "4", "0", "0"),
                    ChatUiConfig.classic(), live::add, (title, lines) -> { }, () -> { }, project, inChat ? "ui-test" : null);
            assertEquals(3, live.size(), "Two previews and the final saved configuration");
            ChatUiConfig result = live.get(live.size() - 1);
            assertEquals(512, result.getSidebarMaxItems());
            assertEquals(4194304, result.getSidebarMaxChars());
            ChatUiConfig restored = ChatUiConfig.loadEffective(project, inChat ? "ui-test" : null);
            assertEquals(512, restored.getSidebarMaxItems());
            assertEquals(4194304, restored.getSidebarMaxChars());
        }
    }

    @Test void selectingSidebarWidthLeavesTheValuePageWithoutRequiringBack() throws Exception {
        LineReader reader = numbers("4", "1", "2", "0", "0");
        List<String> pages = new ArrayList<>(); List<ChatUiConfig> live = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        ChatUiWizard.run(reader, ChatUiConfig.classic(), live::add, (title, lines) -> pages.add(title),
                closes::incrementAndGet, project, "ui-test");
        assertEquals(List.of("Chat display", "Sidebar", "Sidebar width", "Sidebar", "Chat display"), pages);
        assertEquals(25, live.get(0).getSidebarWidthPercent());
        assertEquals(1, closes.get());
        verify(reader, times(5)).readLine(anyString());
    }

    @Test void everyWidthAndMinimumChatValueAcceptsOnceInBothHosts() throws Exception {
        for (boolean inChat : List.of(false, true)) for (boolean percent : List.of(false, true)) {
            int count = percent ? 10 : 19, step = percent ? 5 : 10;
            for (int number = 1; number <= count; number++) {
                LineReader reader = numbers("4", percent ? "1" : "2", String.valueOf(number), "0", "0");
                List<String> pages = new ArrayList<>(); List<ChatUiConfig> live = new ArrayList<>();
                ChatUiWizard.run(reader, ChatUiConfig.classic(), live::add, (title, lines) -> pages.add(title),
                        () -> { }, project, inChat ? "ui-test" : null);
                assertEquals(List.of("Chat display", "Sidebar", percent ? "Sidebar width" : "Minimum chat width", "Sidebar", "Chat display"), pages);
                assertEquals(20 + (number - 1) * step, percent ? live.get(0).getSidebarWidthPercent() : live.get(0).getMinChatColumns());
                verify(reader, times(5)).readLine(anyString());
            }
        }
    }

    @Test void eachContentTypeAndPlacementValueFinishesItsDialogInsteadOfRepeatingIt() throws Exception {
        List<String> labels = List.of("Assistant messages", "Thinking", "Tool calls", "Errors", "Processes", "Subagents", "Plans and todos", "Sources", "Custom panels", "Tool arguments", "Diffs", "File content", "Command output", "Metadata");
        List<String> kinds = List.of("assistant", "thinking", "tool", "error", "process", "subagent", "todos", "sources", "custom", "arguments", "diff", "content", "output", "metadata");
        for (int kind = 0; kind < kinds.size(); kind++) for (int destination = 1; destination <= 2; destination++) for (int value = 1; value <= 3; value++) {
            LineReader reader = numbers("3", String.valueOf(kind + 1), String.valueOf(destination), String.valueOf(value), "0", "0");
            List<String> pages = new ArrayList<>(); List<ChatUiConfig> live = new ArrayList<>();
            ChatUiWizard.run(reader, ChatUiConfig.classic(), live::add, (title, lines) -> pages.add(title), () -> { }, project, "ui-test");
            assertEquals(List.of("Chat display", "Choose content to configure", labels.get(kind), labels.get(kind) + (destination == 1 ? " in chat" : " in sidebar"), "Choose content to configure", "Chat display"), pages);
            ChatUiConfig.Policy policy = live.get(0).policy(kinds.get(kind), null);
            assertEquals((destination == 1 ? List.of("none", "summary", "full") : List.of("off", "collapsed", "expanded")).get(value - 1), destination == 1 ? policy.inline() : policy.sidebar());
            verify(reader, times(6)).readLine(anyString());
        }
    }

    @Test void everyPresetLayoutWrappingAndFollowChoiceLeavesItsValuePage() throws Exception {
        List<String> presets = List.of("classic", "focused", "verbose"), layouts = List.of("inline", "split", "auto");
        for (int number = 1; number <= 3; number++) for (int menu = 1; menu <= 2; menu++) {
            List<String> pages = new ArrayList<>(); List<ChatUiConfig> live = new ArrayList<>();
            ChatUiWizard.run(numbers(String.valueOf(menu), String.valueOf(number), "0"), ChatUiConfig.classic(), live::add,
                    (title, lines) -> pages.add(title), () -> { }, project, "ui-test");
            assertEquals(List.of("Chat display", menu == 1 ? "Choose a preset" : "Choose a layout", "Chat display"), pages);
            assertEquals((menu == 1 ? presets : layouts).get(number - 1), menu == 1 ? live.get(0).getPreset() : live.get(0).getLayoutMode());
        }
        for (int number = 1; number <= 2; number++) for (int menu = 3; menu <= 4; menu++) {
            List<String> pages = new ArrayList<>(); List<ChatUiConfig> live = new ArrayList<>();
            ChatUiWizard.run(numbers("4", String.valueOf(menu), String.valueOf(number), "0", "0"), ChatUiConfig.classic(), live::add,
                    (title, lines) -> pages.add(title), () -> { }, project, null);
            assertEquals(List.of("Chat display", "Sidebar", menu == 3 ? "Line wrapping" : "Follow new output", "Sidebar", "Chat display"), pages);
            assertEquals(menu == 3 ? String.valueOf(number == 1) : number == 1 ? "when-at-bottom" : "manual",
                    menu == 3 ? String.valueOf(live.get(0).isSidebarWrap()) : live.get(0).getSidebarFollow());
        }
    }

    @Test void everySectionActionReturnsToSectionListRatherThanRepeatingItsActionDialog() throws Exception {
        List<String> groups = List.of("tools", "processes", "subagents", "todos", "sources", "custom");
        for (int group = 0; group < groups.size(); group++) for (int action = 1; action <= (group == 0 || group == groups.size() - 1 ? 2 : 3); action++) {
            List<String> pages = new ArrayList<>(); List<ChatUiConfig> live = new ArrayList<>();
            ChatUiWizard.run(numbers("4", "5", String.valueOf(group + 1), String.valueOf(action), "0", "0", "0"), ChatUiConfig.classic(), live::add,
                    (title, lines) -> pages.add(title), () -> { }, project, "ui-test");
            assertEquals(List.of("Chat display", "Sidebar", "Sidebar sections", "Section: " + groups.get(group), "Sidebar sections", "Sidebar", "Chat display"), pages);
            if (action == 1) assertFalse(live.get(0).getSidebarSections().contains(groups.get(group)));
            else {
                int direction = group == 0 || action == 3 ? 1 : -1;
                assertEquals(group + direction, live.get(0).getSidebarSections().indexOf(groups.get(group)));
            }
        }
    }

    @Test void bothShortcutActionsAcceptEachOfferedKeyOnce() throws Exception {
        for (int action = 1; action <= 2; action++) {
            List<String> available = new ArrayList<>();
            for (char key = 'a'; key <= 'z'; key++) {
                String binding = "ctrl-x " + key;
                if (!List.of("ctrl-x a", "ctrl-x p", "ctrl-x t", action == 1 ? "ctrl-x r" : "ctrl-x s").contains(binding)) available.add(binding);
            }
            for (int choice = 1; choice <= available.size(); choice++) {
                List<String> pages = new ArrayList<>(); List<ChatUiConfig> live = new ArrayList<>();
                ChatUiWizard.run(numbers("5", String.valueOf(action), String.valueOf(choice), "0", "0"), ChatUiConfig.classic(), live::add,
                        (title, lines) -> pages.add(title), () -> { }, project, "ui-test");
                assertEquals(List.of("Chat display", "Keyboard shortcuts", action == 1 ? "Choose toggle shortcut" : "Choose focus shortcut", "Keyboard shortcuts", "Chat display"), pages);
                assertEquals(available.get(choice - 1), live.get(0).getBinding(action == 1 ? "toggle" : "focus"));
            }
        }
    }

    @Test void sizePickerPreservesUnusualCurrentValueAndBackDoesNotApply() throws Exception {
        ChatUiConfig config = ChatUiConfig.classic(); config.set("layout.sidebarWidthPercent", "37");
        List<String> displayed = new ArrayList<>();
        ChatUiWizard.run(numbers("4", "1", "0", "0", "0"), config, ignored -> fail("Back must not edit a value"), (title, lines) -> {
            if (title.equals("Sidebar width")) displayed.addAll(lines);
        }, () -> { }, project, "ui-test");
        assertTrue(displayed.stream().anyMatch(line -> line.contains("37% * current")));
        assertTrue(displayed.stream().anyMatch(line -> line.contains("35% — default")));
        assertTrue(displayed.stream().noneMatch(line -> line.contains("Increase") || line.contains("Decrease")));
    }

    @Test void layoutAndEverySidebarSettingUseNumberedPickers() throws Exception {
        LineReader reader = numbers(
                "2", "2",                         // split layout
                "4", "1", "5",                  // select sidebar width 40%
                "2", "4",                        // select minimum chat width 50 columns
                "3", "2",                        // clip long lines
                "4", "2",                        // manual follow
                "5", "1", "1",                  // hide tools
                "2", "2",                        // move processes later (earlier is unavailable)
                "0", "0", "0");                 // sections back, sidebar back, done
        List<ChatUiConfig> live = new ArrayList<>();
        ChatUiWizard.run(reader, ChatUiConfig.classic(), live::add, (title, lines) -> { }, () -> { }, project, null);
        ChatUiConfig result = live.get(live.size() - 1);
        assertEquals("split", result.getLayoutMode());
        assertEquals(40, result.getSidebarWidthPercent());
        assertEquals(50, result.getMinChatColumns());
        assertFalse(result.isSidebarWrap());
        assertEquals("manual", result.getSidebarFollow());
        assertFalse(result.getSidebarSections().contains("tools"));
        assertEquals(List.of("subagents", "processes", "todos", "sources", "custom"), result.getSidebarSections());
    }

    @Test void shortcutsAndToolOverridesUseListsNotTypedPaths() throws Exception {
        LineReader reader = numbers("5", "1", "1", "0", "3", "15", "1", "1", "2", "0", "0");
        List<ChatUiConfig> live = new ArrayList<>();
        ChatUiWizard.run(reader, ChatUiConfig.classic(), live::add, (title, lines) -> { }, () -> { }, project, "ui-test", List.of("bash", "read"));
        ChatUiConfig result = live.get(live.size() - 1);
        assertEquals("ctrl-x b", result.getBinding("toggle"));
        assertEquals("summary", result.policy("tool", "bash").inline());
        assertEquals("full", result.policy("tool", "read").inline());
    }

    @Test void shortcutPickerOmitsBindingsAlreadyOwnedByOtherWidgets() throws Exception {
        LineReader reader = numbers("5", "1", "0", "0", "0");
        org.jline.keymap.KeyMap<org.jline.reader.Binding> map = new org.jline.keymap.KeyMap<>();
        map.bind(new org.jline.reader.Reference("existing-widget"), org.jline.keymap.KeyMap.ctrl('X') + "q");
        when(reader.getKeyMaps()).thenReturn(java.util.Map.of(LineReader.EMACS, map));
        List<String> shortcuts = new ArrayList<>();
        ChatUiWizard.run(reader, ChatUiConfig.classic(), ignored -> fail("Back must not change settings"), (title, lines) -> {
            if (title.equals("Choose toggle shortcut")) shortcuts.addAll(lines);
        }, () -> { }, project, "ui-test");
        assertFalse(shortcuts.stream().anyMatch(line -> line.contains("ctrl-x q")));
        assertFalse(shortcuts.stream().anyMatch(line -> line.contains("ctrl-x r")));
        assertFalse(shortcuts.stream().anyMatch(line -> line.contains("ctrl-x p")));
        assertTrue(shortcuts.stream().anyMatch(line -> line.contains("ctrl-x s") && line.contains("* current")));
    }

    @Test void rejectedLiveEditCannotBeSaved() throws Exception {
        LineReader reader = numbers("2", "2", "6", "2");
        ChatUiWizard.run(reader, ChatUiConfig.classic(), candidate -> { throw new IllegalArgumentException("Rejected layout"); },
                (title, lines) -> { }, () -> { }, project, "ui-test");
        assertEquals("inline", ChatUiConfig.load(List.of(ChatUiConfig.projectPath(project))).getLayoutMode());
        assertFalse(Files.readString(ChatUiConfig.projectPath(project)).contains("split"));
    }

    @Test void invalidNumbersAndRawValuesRepromptWithoutEnteringConfig() throws Exception {
        LineReader reader = numbers("1", "focused", "99", "2", "0");
        List<ChatUiConfig> live = new ArrayList<>(); List<String> output = new ArrayList<>();
        ChatUiWizard.run(reader, ChatUiConfig.classic(), live::add, (title, lines) -> output.addAll(lines), () -> { }, project, "ui-test");
        assertEquals(2, live.size()); assertEquals("focused", live.get(0).getPreset());
        assertEquals("focused", ChatUiConfig.loadEffective(project, "ui-test").getPreset());
        assertTrue(output.contains("Choose a number from 0 to 4."));
        assertTrue(output.stream().noneMatch(line -> line.contains("Invalid UI setting")));
    }

    @Test void backAndNumberedCancelDoNotCommitUnsavedChanges() throws Exception {
        LineReader reader = numbers("1", "0", "2", "2", "7");
        List<ChatUiConfig> live = new ArrayList<>(); AtomicBoolean closed = new AtomicBoolean();
        ChatUiWizard.run(reader, ChatUiConfig.classic(), live::add, (title, lines) -> { }, () -> closed.set(true), project, "ui-test");
        assertTrue(closed.get()); assertEquals(2, live.size());
        assertEquals("split", live.get(0).getLayoutMode());
        assertEquals("inline", live.get(1).getLayoutMode());
        assertFalse(Files.exists(ChatUiConfig.projectPath(project)));
        assertFalse(Files.exists(ChatUiConfig.globalPath()));
        assertFalse(Files.exists(ChatUiConfig.sessionPath("ui-test")));
    }

    @Test void cancelRestoresPreviouslySavedPreferencesWithoutReopeningWizard() throws Exception {
        ChatUiConfig saved = ChatUiConfig.classic(); saved.applyPreset("focused");
        saved.saveTo(ChatUiConfig.projectPath(project));
        List<ChatUiConfig> live = new ArrayList<>();
        LineReader reader = numbers("2", "1", "7");
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        ChatUiWizard.run(reader, saved, live::add, (title, lines) -> { }, closes::incrementAndGet, project, "ui-test");
        assertEquals("inline", live.get(0).getLayoutMode());
        assertEquals("focused", live.get(1).getPreset());
        assertEquals("auto", live.get(1).getLayoutMode());
        assertEquals(1, closes.get()); verify(reader, times(3)).readLine(anyString());
        assertEquals("auto", ChatUiConfig.load(List.of(ChatUiConfig.projectPath(project))).getLayoutMode());
        assertFalse(Files.exists(ChatUiConfig.globalPath()));
    }

    @Test void doneAndCancelTerminateOnceInBothContexts() throws Exception {
        for (boolean inChat : List.of(false, true)) for (String choice : List.of("0", "7")) {
            LineReader reader = numbers(choice);
            java.util.concurrent.atomic.AtomicInteger renders = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
            ChatUiWizard.run(reader, ChatUiConfig.classic(), ignored -> fail("No edits were made"),
                    (title, lines) -> renders.incrementAndGet(), closes::incrementAndGet, project, inChat ? "ui-test" : null);
            assertEquals(1, renders.get()); assertEquals(1, closes.get());
            verify(reader, times(1)).readLine(anyString());
        }
    }

    @Test void backingOutOfSaveScopeThenDoneSavesAtDefaultScope() throws Exception {
        LineReader reader = numbers("6", "0", "2", "2", "0");
        List<ChatUiConfig> live = new ArrayList<>(); List<String> pages = new ArrayList<>();
        ChatUiWizard.run(reader, ChatUiConfig.classic(), live::add, (title, lines) -> pages.add(title), () -> { }, project, "ui-test");
        assertEquals("split", live.get(0).getLayoutMode());
        assertEquals(List.of("Chat display", "Save preferences", "Chat display", "Choose a layout", "Chat display"), pages);
        assertFalse(Files.exists(ChatUiConfig.projectPath(project)));
        assertEquals("split", ChatUiConfig.loadEffective(project, "ui-test").getLayoutMode());
    }

    @Test void donePersistsAtExistingScopeInBothHosts() throws Exception {
        for (boolean inChat : List.of(false, true)) {
            Path target = ChatUiConfig.projectPath(project.resolve(inChat ? "chat" : "standalone"));
            ChatUiConfig initial = ChatUiConfig.load(List.of(target));
            initial.applyPreset("focused"); initial.saveDefault();
            List<ChatUiConfig> live = new ArrayList<>();
            ChatUiWizard.run(numbers("2", "2", "0"), ChatUiConfig.load(List.of(target)), live::add,
                    (title, lines) -> { }, () -> { }, project, inChat ? "ui-test" : null);
            assertEquals("split", ChatUiConfig.load(List.of(target)).getLayoutMode());
            assertEquals(target.toAbsolutePath(), live.get(live.size() - 1).persistencePath());
        }
        assertFalse(Files.exists(ChatUiConfig.globalPath()));
    }

    @Test void failedAutomaticSaveShowsErrorAndCancelRestoresPreview() throws Exception {
        Path blocked = project.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        Path target = blocked.resolve("ui.json");
        List<String> pages = new ArrayList<>(), lines = new ArrayList<>();
        List<ChatUiConfig> live = new ArrayList<>();
        ChatUiWizard.run(numbers("2", "2", "0", "7"), ChatUiConfig.load(List.of(target)), live::add,
                (title, content) -> { pages.add(title); lines.addAll(content); }, () -> { }, project, null);
        assertEquals(List.of("Chat display", "Choose a layout", "Chat display", "Chat display"), pages);
        assertTrue(lines.stream().anyMatch(line -> line.contains(blocked.toString())));
        assertEquals("inline", live.get(live.size() - 1).getLayoutMode());
        assertFalse(Files.exists(target));
    }

    @Test void failedSaveReportsErrorAndLeavesWizardUsable() throws Exception {
        Files.writeString(project.resolve(".kompile"), "blocked directory");
        LineReader reader = numbers("6", "2", "0");
        List<String> pages = new ArrayList<>(), lines = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        ChatUiWizard.run(reader, ChatUiConfig.classic(), ignored -> { }, (title, content) -> {
            pages.add(title); lines.addAll(content);
        }, closes::incrementAndGet, project, "ui-test");
        assertEquals(List.of("Chat display", "Save preferences", "Chat display"), pages);
        assertTrue(lines.stream().anyMatch(line -> line.contains(project.resolve(".kompile").toString())));
        assertEquals(1, closes.get());
        assertEquals("blocked directory", Files.readString(project.resolve(".kompile")));
    }

    @Test void existingToolRulesAreNumberedOutsideAnActiveSessionToo() throws Exception {
        ChatUiConfig config = ChatUiConfig.classic(); config.set("toolOverrides.read.inline", "summary");
        List<ChatUiConfig> live = new ArrayList<>();
        ChatUiWizard.run(numbers("3", "15", "1", "1", "3", "0", "0"), config, live::add,
                (title, lines) -> { }, () -> { }, project, null);
        assertEquals("full", live.get(0).policy("tool", "read").inline());
    }

    @Test void standaloneAndChatUseTheSameMenuAndNumberedSaveFlow() throws Exception {
        List<List<String>> standalone = new ArrayList<>(), chat = new ArrayList<>();
        ChatUiWizard.run(numbers("6", "1"), ChatUiConfig.classic(), ignored -> { }, (title, lines) -> standalone.add(lines), () -> { }, project, null);
        ChatUiWizard.run(numbers("6", "2"), ChatUiConfig.classic(), ignored -> { }, (title, lines) -> chat.add(lines), () -> { }, project, "ui-test");
        assertEquals(standalone.get(0), chat.get(0));
        assertTrue(standalone.get(1).get(0).contains("1. This project"));
        assertTrue(chat.get(1).get(0).contains("1. This conversation"));
        assertTrue(chat.get(1).get(1).contains("2. This project"));
    }

    @Test void escapeClosesPageAndDoesNotWriteSettings() throws Exception {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenThrow(new UserInterruptException(""));
        AtomicBoolean closed = new AtomicBoolean();
        ChatUiWizard.run(reader, ChatUiConfig.classic(), ignored -> fail("no changes expected"), (title, lines) -> { }, () -> closed.set(true), project, null);
        assertTrue(closed.get()); assertFalse(Files.exists(ChatUiConfig.projectPath(project)));
    }
}
