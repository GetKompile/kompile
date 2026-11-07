package ai.kompile.cli.main.chat.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SubagentIdleWatchdogTest {

    private final AtomicLong clock = new AtomicLong();
    private final List<String> ids = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();

    private SubagentIdleWatchdog watchdog(Duration threshold) {
        return new SubagentIdleWatchdog(threshold, clock::get, (id, notice) -> {
            ids.add(id);
            notices.add(notice);
        });
    }

    private void advance(Duration duration) {
        clock.addAndGet(duration.toNanos());
    }

    @Test
    void alertsOnceWhenARunningSubagentStaysQuietPastTheThreshold() {
        SubagentIdleWatchdog watchdog = watchdog(Duration.ofMinutes(5));
        watchdog.onStart("sa-1", "explore-quick", "Find the config loader");
        watchdog.onStatus("sa-1", "thinking");
        watchdog.onActivity("sa-1", "bash: mvn install");

        advance(Duration.ofMinutes(4));
        assertEquals(0, watchdog.scan());

        advance(Duration.ofMinutes(1));
        assertEquals(1, watchdog.scan());
        assertEquals(List.of("sa-1"), ids);
        String notice = notices.get(0);
        assertTrue(notice.startsWith("[System subagent watchdog]"), notice);
        assertTrue(notice.contains("sa-1 (explore-quick) \"Find the config loader\""), notice);
        assertTrue(notice.contains("no activity for 5m 0s"), notice);
        assertTrue(notice.contains("Last status: thinking."), notice);
        assertTrue(notice.contains("Last activity: bash: mvn install."), notice);
        assertTrue(notice.contains("subagent_resume with action=cancel and session_id=sa-1"), notice);

        // One alert per quiet stretch, no matter how long it lasts.
        advance(Duration.ofMinutes(30));
        assertEquals(0, watchdog.scan());
    }

    @Test
    void anyEventResetsTheQuietStretchAndRearmsTheAlert() {
        SubagentIdleWatchdog watchdog = watchdog(Duration.ofSeconds(60));
        watchdog.onStart("sa-1", "general", "Refactor");

        advance(Duration.ofSeconds(50));
        watchdog.onProgress("sa-1");
        advance(Duration.ofSeconds(50));
        assertEquals(0, watchdog.scan(), "streamed output proves the child is alive");

        advance(Duration.ofSeconds(10));
        assertEquals(1, watchdog.scan());

        watchdog.onActivity("sa-1", "edit: Foo.java");
        advance(Duration.ofSeconds(60));
        assertEquals(1, watchdog.scan(), "a new quiet stretch alerts again");
        assertEquals(2, notices.size());
    }

    @Test
    void endedSubagentsAndLateEventsNeverAlert() {
        SubagentIdleWatchdog watchdog = watchdog(Duration.ofSeconds(30));
        watchdog.onStart("sa-1", "general", "Done quickly");
        watchdog.onEnd("sa-1");
        // Events that arrive after the run ended must not resurrect tracking.
        watchdog.onStatus("sa-1", "completed");
        watchdog.onProgress("unknown");

        advance(Duration.ofMinutes(10));
        assertEquals(0, watchdog.scan());
        assertTrue(notices.isEmpty());
    }

    @Test
    void followUpRunStartsAFreshQuietStretch() {
        SubagentIdleWatchdog watchdog = watchdog(Duration.ofSeconds(30));
        watchdog.onStart("sa-1", "general", "First run");
        advance(Duration.ofSeconds(30));
        assertEquals(1, watchdog.scan());

        watchdog.onEnd("sa-1");
        watchdog.onStart("sa-1", "general", "Interactive follow-up");
        assertEquals(0, watchdog.scan());
        advance(Duration.ofSeconds(30));
        assertEquals(1, watchdog.scan());
        assertTrue(notices.get(1).contains("\"Interactive follow-up\""), notices.get(1));
    }

    @Test
    void alertsWaitWhileTheParentIsBlockedInAForegroundToolCall() {
        SubagentIdleWatchdog watchdog = watchdog(Duration.ofSeconds(60));
        AtomicBoolean parentBlocked = new AtomicBoolean(true);
        watchdog.setParentBlocked(parentBlocked::get);
        watchdog.onStart("sync", "general", "Awaited synchronously");
        watchdog.onStart("background", "general", "Detached");

        advance(Duration.ofMinutes(5));
        assertEquals(0, watchdog.scan(), "the parent cannot act while blocked");

        // The synchronously awaited child ends before the parent is free: never reported stale.
        watchdog.onEnd("sync");
        parentBlocked.set(false);
        assertEquals(1, watchdog.scan());
        assertEquals(List.of("background"), ids);
        assertTrue(notices.get(0).contains("no activity for 5m 0s"), "the deferred alert reports the full quiet time");
    }

    @Test
    void zeroThresholdDisablesTheWatchdog() {
        SubagentIdleWatchdog watchdog = watchdog(Duration.ZERO);
        assertFalse(watchdog.isEnabled());
        watchdog.onStart("sa-1", "general", "Never alerted");
        advance(Duration.ofHours(2));
        assertEquals(0, watchdog.scan());
        watchdog.start();
        watchdog.close();
        assertTrue(notices.isEmpty());
    }

    @Test
    void formatsDurationsForTheNotice() {
        assertEquals("45s", SubagentIdleWatchdog.formatDuration(Duration.ofSeconds(45)));
        assertEquals("2m 5s", SubagentIdleWatchdog.formatDuration(Duration.ofSeconds(125)));
        assertEquals("1h 30m", SubagentIdleWatchdog.formatDuration(Duration.ofMinutes(90)));
    }
}
