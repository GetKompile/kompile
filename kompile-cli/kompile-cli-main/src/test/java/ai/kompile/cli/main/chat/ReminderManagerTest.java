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
}
