package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.activity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DashboardTimelineCommandsTest {
    @TempDir Path root;

    private DashboardTimelineCommands commands(ActivityStorage storage, ActivityIdentity identity) {
        return new DashboardTimelineCommands(storage, () -> identity, ignored -> { });
    }

    @Test void defaultsToCurrentSessionAndPreservesCaseAndCoverage() {
        ActivityStorage storage = new ActivityStorage(root.resolve("conversations"));
        ActivityIdentity identity = ActivityIdentity.conversation("SessionCase", root);
        new ActivityRecorder(storage).record(identity, "tool.finished", "main", "tool-1", null,
                Map.of("outcome", "succeeded", "tool", "read"));
        try (var commands = commands(storage, identity)) {
            String report = commands.command("");
            assertTrue(report.contains("SessionCase"));
            assertTrue(report.contains("tool.finished"));
            assertTrue(report.contains("coverage"));
            assertTrue(report.contains("/timeline inspect:EVENT_ID"));
        }
    }

    @Test void nextPageDoesNotLoseRowsAndNewSelectionResetsCursor() {
        ActivityStorage storage = new ActivityStorage(root.resolve("conversations"));
        ActivityIdentity identity = ActivityIdentity.conversation("same", root);
        ActivityRecorder recorder = new ActivityRecorder(storage);
        recorder.record(identity, "tool.started", "main", "op", null, Map.of());
        recorder.record(identity, "tool.finished", "main", "op", null, Map.of("outcome", "succeeded"));
        try (var commands = commands(storage, identity)) {
            assertTrue(commands.command("limit:1").contains("tool.finished"));
            String previous = commands.command("next");
            assertTrue(previous.contains("tool.started"));
            assertFalse(previous.contains("tool.finished ·"));
            assertTrue(commands.command("next").contains("No older"));
            assertTrue(commands.command("session:missing").contains("No matching retained events"));
        }
    }

    @Test void duplicateAndUnknownSelectorsDoNotSilentlyChangeScope() {
        var storage = new ActivityStorage(root.resolve("conversations"));
        var identity = ActivityIdentity.conversation("current", root);
        try (var commands = commands(storage, identity)) {
            assertTrue(commands.command("session:A session:B").contains("Duplicate selector"));
            assertTrue(commands.command("unknown:A").contains("Unknown selector"));
            assertTrue(commands.command("limit:101").contains("limit must"));
            assertTrue(commands.command("from:tomorrow").contains("Timeline unavailable"));
        }
    }

    @Test void inspectionNeverExecutesOrPrintsRawToolPayload() {
        var storage = new ActivityStorage(root.resolve("conversations"));
        var identity = ActivityIdentity.conversation("current", root);
        ActivityEvent event = new ActivityRecorder(storage).record(identity, "tool.finished", "main", "op", null,
                Map.of("outcome", "succeeded", "rawInput", "secret payload"));
        try (var commands = commands(storage, identity)) {
            assertFalse(commands.command("").contains("secret payload"));
            assertTrue(commands.command("inspect:" + event.eventId()).contains("No retained source reference"));
            assertTrue(commands.command("inspect:not-on-page").contains("not in the displayed page"));
        }
    }

    @Test void relativeTimeWindowAndConflictsAreValidated() {
        var storage = new ActivityStorage(root.resolve("conversations"));
        var identity = ActivityIdentity.conversation("current", root);
        new ActivityRecorder(storage).record(identity, "tool.finished", "main", "op", null, Map.of());
        try (var commands = commands(storage, identity)) {
            assertTrue(commands.command("kind:tool last 2 hours").contains("tool.finished"));
            assertTrue(commands.command("from:2026-01-01T00:00:00Z last 2 hours").contains("either from:"));
            assertTrue(commands.command("last 0 hours").contains("positive"));
        }
    }

    @Test void recordingWarningsAndReadPermissionAreNotHidden() {
        var storage = new ActivityStorage(root.resolve("conversations"));
        var identity = ActivityIdentity.conversation("current", root);
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var commands = new DashboardTimelineCommands(storage, () -> identity, ignored -> { },
                () -> java.util.List.of("Recorder failed to persist lifecycle evidence"), () -> {
                    if (!allowed.get()) throw new IllegalStateException("Read permission revoked");
                })) {
            assertTrue(commands.command("").contains("Recorder failed"));
            allowed.set(false);
            assertTrue(commands.command("").contains("Read permission revoked"));
            assertEquals("Timeline follow stopped.", commands.command("stop"));
        }
    }

    @Test void followIsExplicitAndStopsOnNavigationAndClose() {
        var storage = new ActivityStorage(root.resolve("conversations"));
        var identity = ActivityIdentity.conversation("current", root);
        var commands = commands(storage, identity);
        assertFalse(commands.isFollowing());
        assertTrue(commands.command("follow").contains("Following"));
        assertTrue(commands.isFollowing());
        commands.command("");
        assertFalse(commands.isFollowing());
        commands.command("follow");
        commands.command("stop");
        assertFalse(commands.isFollowing());
        commands.command("follow");
        commands.close();
        assertFalse(commands.isFollowing());
        assertEquals("Timeline view is closed.", commands.command("follow"));
    }

    /** Four in-project requests over three chats (one 10 days old) plus one from another project. */
    private ActivityStorage tokenJournals(Instant now) throws Exception {
        Path conversations = root.resolve("conversations");
        String project = root.toString();
        Path chatA = TokenUsageJournal.pathFor(conversations, "chat-a");
        TokenUsageJournal.append(chatA, TokenUsageJournalTest.entry(now.minus(Duration.ofDays(1)), "chat-a", project, "model-a"));
        TokenUsageJournal.append(chatA, TokenUsageJournalTest.entry(now.minus(Duration.ofHours(3)), "chat-a", project, "model-a"));
        Path chatB = TokenUsageJournal.pathFor(conversations, "chat-b");
        TokenUsageJournal.append(chatB, TokenUsageJournalTest.entry(now.minus(Duration.ofDays(2)), "chat-b", project, "model-b"));
        TokenUsageJournal.append(chatB, TokenUsageJournalTest.entry(now.minus(Duration.ofHours(1)), "chat-b",
                root.resolve("elsewhere").toString(), "model-b"));
        TokenUsageJournal.append(TokenUsageJournal.pathFor(conversations, "chat-c"),
                TokenUsageJournalTest.entry(now.minus(Duration.ofDays(10)), "chat-c", project, "model-a"));
        return new ActivityStorage(conversations);
    }

    @Test void tokensDefaultsToTheLastWeekAcrossEveryProjectChatWithAPerModelBreakdown() throws Exception {
        ActivityStorage storage = tokenJournals(Instant.now());
        try (var commands = commands(storage, ActivityIdentity.conversation("chat-a", root))) {
            String report = commands.command("tokens");
            assertTrue(report.contains("by day"), report);
            assertTrue(report.contains("3 requests across 2 chats"), report);
            assertTrue(Pattern.compile("model-a\\s+anthropic\\s+2\\s+1\\s").matcher(report).find(), report);
            assertTrue(Pattern.compile("model-b\\s+anthropic\\s+1\\s+1\\s").matcher(report).find(), report);
            assertTrue(Pattern.compile("TOTAL\\s+3\\s+2\\s").matcher(report).find(), report);
        }
    }

    @Test void tokensHonorsRelativeAndExplicitWindows() throws Exception {
        Instant now = Instant.now();
        ActivityStorage storage = tokenJournals(now);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate oldDay = now.minus(Duration.ofDays(10)).atZone(zone).toLocalDate();
        try (var commands = commands(storage, ActivityIdentity.conversation("chat-a", root))) {
            assertTrue(commands.command("tokens last 30 days").contains("4 requests across 3 chats"));
            String hourly = commands.command("tokens last 24 hours");
            assertTrue(hourly.contains("by hour"), hourly);
            assertTrue(hourly.contains("1 requests across 1 chats"), hourly);
            // A bare to: date includes that whole day.
            String explicit = commands.command("tokens from:" + oldDay + " to:" + oldDay);
            assertTrue(explicit.contains("1 requests across 1 chats"), explicit);
        }
    }

    @Test void tokensRejectsBadWindowsAndChatsWithoutAProject() throws Exception {
        ActivityStorage storage = tokenJournals(Instant.now());
        try (var commands = commands(storage, ActivityIdentity.conversation("chat-a", root))) {
            assertTrue(commands.command("tokens from:2026-11-05 to:2026-11-01").contains("from: must be before to:"));
            assertTrue(commands.command("tokens from:2026-01-01 last 2 days").contains("not both"));
            assertTrue(commands.command("tokens model:x").contains("Unknown selector"));
        }
        try (var commands = commands(storage, ActivityIdentity.conversation("detached", null))) {
            assertTrue(commands.command("tokens").contains("no project directory"));
        }
    }

    @Test void projectTotalsSumEveryProjectChatInTheWindowWithModelShares() throws Exception {
        Instant now = Instant.now();
        tokenJournals(now);
        var scan = TokenUsageJournal.read(root.resolve("conversations"), root.toString(), now.minus(Duration.ofDays(7)), now);
        String totals = TokenUsageTimelineReport.projectTotals(scan, root.toString(), now.minus(Duration.ofDays(7)), now,
                ZoneId.systemDefault());
        // Three in-window project requests of 1,250 tokens each; the 10-day-old and other-project rows are excluded.
        assertTrue(totals.contains("Total 3,750 tokens"), totals);
        assertTrue(totals.contains("3 requests across 2 chats"), totals);
        assertTrue(totals.contains("model-a") && totals.contains("(66.7%)"), totals);
        assertTrue(totals.contains("model-b") && totals.contains("(33.3%)"), totals);

        var empty = TokenUsageJournal.read(root.resolve("conversations"), root.toString(),
                now.minus(Duration.ofDays(60)), now.minus(Duration.ofDays(30)));
        assertTrue(TokenUsageTimelineReport.projectTotals(empty, root.toString(), now.minus(Duration.ofDays(60)),
                now.minus(Duration.ofDays(30)), ZoneId.systemDefault()).contains("No token usage recorded"));
    }

    @Test void relativeWindowIsFoundAmongOtherSelectors() {
        var window = DashboardTimelineCommands.relativeWindow("session:abc last 3 hours tool:read");
        assertNotNull(window.from());
        assertTrue(Duration.between(window.from(), Instant.now()).minus(Duration.ofHours(3)).abs().getSeconds() < 5);
        assertEquals("session:abc tool:read", window.rest().replaceAll("\\s+", " "));
        assertNull(DashboardTimelineCommands.relativeWindow("session:abc").from());
    }
}
