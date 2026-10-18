package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.activity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

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
}
