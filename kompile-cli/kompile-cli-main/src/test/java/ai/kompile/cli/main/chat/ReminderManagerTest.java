package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsBothScopesAndPrependsProjectBeforeSession() throws Exception {
        Path sessionFile = tempDir.resolve("sessions/chat-1.reminders.json");
        Path projectFile = tempDir.resolve("project/.kompile/chat-reminders.json");
        ReminderManager manager = ReminderManager.forStorage(
                JsonUtils.standardMapper(), sessionFile, projectFile);

        assertTrue(manager.handleCommand(ReminderManager.Scope.PROJECT,
                "Keep generated files unchanged").startsWith("Added"));
        assertTrue(manager.handleCommand(ReminderManager.Scope.SESSION,
                "add Run focused tests").startsWith("Added"));
        assertTrue(manager.handleCommand(ReminderManager.Scope.SESSION,
                "Keep the answer concise").startsWith("Added"));

        String prompt = manager.prependTo("Explain the change");
        assertTrue(prompt.startsWith("<kompile_reminders>"));
        assertTrue(prompt.endsWith("Explain the change"));
        assertTrue(prompt.indexOf("[project] Keep generated files unchanged")
                < prompt.indexOf("[session] Run focused tests"));
        assertTrue(prompt.indexOf("[session] Run focused tests")
                < prompt.indexOf("[session] Keep the answer concise"));

        ReminderManager reloaded = ReminderManager.forStorage(
                JsonUtils.standardMapper(), sessionFile, projectFile);
        assertEquals(List.of("Keep generated files unchanged"),
                reloaded.list(ReminderManager.Scope.PROJECT));
        assertEquals(List.of("Run focused tests", "Keep the answer concise"),
                reloaded.list(ReminderManager.Scope.SESSION));

        assertEquals("Cleared 2 session reminders.",
                reloaded.handleCommand(ReminderManager.Scope.SESSION, "clear"));
        String projectOnly = reloaded.prependTo("Continue");
        assertTrue(projectOnly.contains("[project] Keep generated files unchanged"));
        assertFalse(projectOnly.contains("[session]"));
    }

    @Test
    void commandGrammarListsRejectsDuplicatesAndSupportsExplicitAdd() {
        ReminderManager manager = ReminderManager.inMemory(List.of(), List.of());

        assertEquals("No session reminders configured.",
                manager.handleCommand(ReminderManager.Scope.SESSION, ""));
        assertEquals("Added session reminder.",
                manager.handleCommand(ReminderManager.Scope.SESSION, "Only change listed files"));
        assertEquals("That session reminder is already configured.",
                manager.handleCommand(ReminderManager.Scope.SESSION,
                        "add Only change listed files"));
        assertTrue(manager.handleCommand(ReminderManager.Scope.SESSION, "list")
                .contains("1. Only change listed files"));
        assertTrue(manager.handleCommand(ReminderManager.Scope.PROJECT, "add")
                .startsWith("Usage: /reminder-global"));
    }

    @Test
    void emptyOrUnreadableStorageNeverBlocksPromptDispatch() throws Exception {
        Path sessionFile = tempDir.resolve("broken-session.json");
        Path projectFile = tempDir.resolve("broken-project.json");
        ReminderManager manager = ReminderManager.forStorage(
                JsonUtils.standardMapper(), sessionFile, projectFile);

        assertEquals("unchanged", manager.prependTo("unchanged"));
        assertEquals("", manager.prependTo(""));
        assertNull(manager.prependTo(null));

        Files.writeString(projectFile, "");
        assertEquals("empty file ignored", manager.prependTo("empty file ignored"));
        Files.writeString(projectFile, "not-json");
        assertEquals("still sent", manager.prependTo("still sent"));
    }

    @Test
    void resumedSessionCanInheritRemindersIntoItsNewTranscriptId() throws Exception {
        ReminderManager original = ReminderManager.forStorage(
                JsonUtils.standardMapper(), tempDir.resolve("original.json"),
                tempDir.resolve("project.json"));
        ReminderManager resumed = ReminderManager.forStorage(
                JsonUtils.standardMapper(), tempDir.resolve("resumed.json"),
                tempDir.resolve("project.json"));
        original.add(ReminderManager.Scope.SESSION, "Retain this instruction");

        assertEquals(1, resumed.inheritSessionReminders(original));
        assertEquals(List.of("Retain this instruction"),
                resumed.list(ReminderManager.Scope.SESSION));
        assertEquals(0, resumed.inheritSessionReminders(original),
                "repeated resume setup must not duplicate reminders");
    }

    @Test
    void persistenceIgnoresFixedTempSymlinksAndRejectsUnsafeState() throws Exception {
        Path stateDir = tempDir.resolve("safe-state");
        Files.createDirectories(stateDir);
        Path sessionFile = stateDir.resolve("session.json");
        Path projectFile = stateDir.resolve("project.json");
        Path victim = tempDir.resolve("victim.txt");
        Files.writeString(victim, "unchanged");
        Files.createSymbolicLink(stateDir.resolve("session.json.tmp"), victim);
        ReminderManager manager = ReminderManager.forStorage(
                JsonUtils.standardMapper(), sessionFile, projectFile);

        assertTrue(manager.add(ReminderManager.Scope.SESSION, "Safe reminder").added());
        assertEquals("unchanged", Files.readString(victim));
        assertFalse(manager.add(ReminderManager.Scope.SESSION, "unsafe\u001bcontrol").added());

        Files.delete(sessionFile);
        Files.createSymbolicLink(sessionFile, victim);
        assertEquals("prompt still sent", manager.prependTo("prompt still sent"));
        assertTrue(manager.handleCommand(ReminderManager.Scope.SESSION, "list")
                .startsWith("Could not update session reminders:"));
    }

    @Test
    void listingEscapesAllowedMultilineFormatting() throws Exception {
        ReminderManager manager = ReminderManager.inMemory(List.of(), List.of());

        assertTrue(manager.add(ReminderManager.Scope.SESSION, "first line\nsecond\tcolumn").added());
        String listing = manager.handleCommand(ReminderManager.Scope.SESSION, "list");

        assertTrue(listing.contains("first line\\nsecond\\tcolumn"));
        assertFalse(listing.contains("first line\nsecond"));
    }

    @Test
    void projectStorageResolvesToNearestKompileProjectRoot() throws Exception {
        Path project = tempDir.resolve("project");
        Path nested = project.resolve("src/main");
        Files.createDirectories(nested);
        Files.writeString(project.resolve("kompile.project.json"), "{}");

        ReminderManager manager = new ReminderManager(
                JsonUtils.standardMapper(), "session-1", nested);

        assertEquals(project.resolve(".kompile/chat-reminders.json")
                        .toAbsolutePath().normalize(),
                manager.projectFile());
    }

    @Test
    void reminderBlockContentExtractsTheInjectedBodyForRenderers() {
        ReminderManager manager = ReminderManager.inMemory(
                List.of("Keep project context"), List.of("Be explicit"));

        assertNull(ReminderManager.reminderBlockContent(null));
        assertNull(ReminderManager.reminderBlockContent("plain prompt"));

        String decorated = manager.prependTo("Inspect the build");
        String content = ReminderManager.reminderBlockContent(decorated);
        assertTrue(content.contains("[project] Keep project context"));
        assertTrue(content.contains("[session] Be explicit"));
        assertFalse(content.contains("Inspect the build"),
                "the user prompt must never appear in the reminder section");
    }

    @Test
    void enforcementConstraintsStayActiveBetweenInjectionIntervalsAndRespectOff() {
        ReminderManager manager = ReminderManager.inMemory(
                List.of("Plan before making changes"), List.of("Run focused tests"));
        manager.handleCommand(ReminderManager.Scope.PROJECT, "interval 3");

        assertTrue(manager.enforcementConstraints().contains(
                "1. [project] Plan before making changes"));
        assertTrue(manager.enforcementConstraints().contains(
                "2. [session] Run focused tests"));

        manager.decorateUserTurn("first");
        assertEquals("second", manager.decorateUserTurn("second"),
                "the reminder block is not repeated on an interval-skipped turn");
        assertTrue(manager.enforcementConstraints().contains("Run focused tests"),
                "the active judge must still enforce reminders between prompt injections");

        manager.handleCommand(ReminderManager.Scope.SESSION, "interval off");
        assertEquals("", manager.enforcementConstraints(),
                "the effective interval-off setting is an explicit enforcement opt-out");
    }

    @Test
    void intervalInjectsOnlyOnEveryNthUserTurn() {
        ReminderManager manager = ReminderManager.inMemory(
                List.of("Stay on plan"), List.of());
        manager.handleCommand(ReminderManager.Scope.PROJECT, "interval 3");

        assertTrue(manager.decorateUserTurn("first").startsWith("<kompile_reminders>"),
                "the first user message is always due");
        assertEquals("second", manager.decorateUserTurn("second"));
        assertEquals("third", manager.decorateUserTurn("third"));
        assertTrue(manager.decorateUserTurn("fourth").startsWith("<kompile_reminders>"),
                "injection repeats every 3 user messages after the first");
        assertEquals("fifth", manager.decorateUserTurn("fifth"));
        assertEquals("sixth", manager.decorateUserTurn("sixth"));
        assertTrue(manager.decorateUserTurn("seventh").startsWith("<kompile_reminders>"));
    }

    @Test
    void intervalOffSuppressesInjectionAndResetRestoresDefault() {
        ReminderManager manager = ReminderManager.inMemory(
                List.of("Stay on plan"), List.of());

        assertTrue(manager.handleCommand(ReminderManager.Scope.PROJECT, "interval off")
                .contains("will not be injected"));
        assertEquals("quiet", manager.decorateUserTurn("quiet"));

        assertTrue(manager.handleCommand(ReminderManager.Scope.PROJECT, "interval reset")
                .contains("falls back"));
        assertTrue(manager.decorateUserTurn("again").startsWith("<kompile_reminders>"));
    }

    @Test
    void resumedSessionNoticeIsPreviewedThenConsumedExactlyOnceEvenWhenIntervalsAreOff() {
        ReminderManager manager = ReminderManager.inMemory(
                List.of("Configured reminder stays disabled"), List.of());
        manager.handleCommand(ReminderManager.Scope.PROJECT, "interval off");
        manager.scheduleSessionResumeReminder();

        String preview = manager.previewUserTurn("Continue the work");
        assertTrue(preview.contains("[system] " + ReminderManager.SESSION_RESUMED_REMINDER));
        assertFalse(preview.contains("[project] Configured reminder stays disabled"));
        assertTrue(manager.previewUserTurn("Continue the work").contains("[system]"),
                "previewing must not consume the one-shot notice");

        String first = manager.decorateUserTurn("Continue the work");
        assertTrue(first.contains("[system] " + ReminderManager.SESSION_RESUMED_REMINDER));
        assertTrue(first.endsWith("Continue the work"));
        assertEquals("Next turn", manager.decorateUserTurn("Next turn"),
                "the automatic resume notice must be emitted only once");
    }

    @Test
    void prependIsIdempotentAndPreviewDoesNotTick() {
        ReminderManager manager = ReminderManager.inMemory(
                List.of("Stay on plan"), List.of());

        String decorated = manager.decorateUserTurn("task");
        assertEquals(decorated, manager.prependTo(decorated));
        assertEquals(decorated, manager.decorateUserTurn(decorated));
        assertTrue(manager.decorateUserTurn("next").startsWith("<kompile_reminders>"),
                "interval 1 injects on every turn");

        manager.handleCommand(ReminderManager.Scope.PROJECT, "interval 2");
        // Counter is at 2; the next real turn is tick 3, which interval 2 injects on
        // (odd ticks), so preview must show the decorated prompt without ticking.
        assertTrue(manager.previewUserTurn("due").startsWith("<kompile_reminders>"),
                "preview must not tick the counter");
        assertTrue(manager.decorateUserTurn("sent").startsWith("<kompile_reminders>"));
        assertEquals("skipped", manager.previewUserTurn("skipped"),
                "tick 4 is undue; preview must report the raw prompt");
        assertEquals("skipped", manager.previewUserTurn("skipped"),
                "previewing twice in a row must report the same decoration");
        assertEquals("real", manager.decorateUserTurn("real"),
                "the real send must observe the same skipped turn the preview showed");
        assertTrue(manager.decorateUserTurn("due-now").startsWith("<kompile_reminders>"));
    }

    @Test
    void intervalCommandGrammarAndPersistence() throws Exception {
        Path sessionFile = tempDir.resolve("interval-session.reminders.json");
        Path projectFile = tempDir.resolve("interval-project.json");
        ReminderManager manager = ReminderManager.forStorage(
                JsonUtils.standardMapper(), sessionFile, projectFile);

        assertTrue(manager.handleCommand(ReminderManager.Scope.SESSION, "interval every 4")
                .contains("every 4 user messages"));
        assertTrue(manager.handleCommand(ReminderManager.Scope.SESSION, "interval")
                .contains("Reminder interval: every 4 user messages [source: session]"));

        ReminderManager reloaded = ReminderManager.forStorage(
                JsonUtils.standardMapper(), sessionFile, projectFile);
        assertTrue(reloaded.handleCommand(ReminderManager.Scope.SESSION, "interval")
                .contains("every 4"), "interval must survive a session restart");
        assertTrue(reloaded.handleCommand(ReminderManager.Scope.SESSION, "interval nonsense")
                .startsWith("Usage:"));

        assertTrue(reloaded.handleCommand(ReminderManager.Scope.PROJECT, "interval 0")
                .contains("will not be injected"));
        assertTrue(reloaded.handleCommand(ReminderManager.Scope.SESSION, "interval off")
                .contains("will not be injected"));
        assertEquals("quiet", reloaded.decorateUserTurn("quiet"));
    }
}
