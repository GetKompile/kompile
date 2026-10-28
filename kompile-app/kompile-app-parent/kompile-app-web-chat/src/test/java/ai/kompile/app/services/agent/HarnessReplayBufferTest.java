package ai.kompile.app.services.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HarnessReplayBufferTest {
    static class Connection implements HarnessReplayBuffer.Connection {
        final List<HarnessReplayBuffer.Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        boolean completed;
        public void send(HarnessReplayBuffer.Event event) throws IOException { events.add(event); }
        public void complete() { completed = true; }
    }
    @Test void heartbeatIsOutOfBandAndStopsWhenItsSubscriberDetaches() throws Exception {
        var buffer = new HarnessReplayBuffer(new ObjectMapper(), 1024, 2, 10);
        var received = new java.util.concurrent.LinkedBlockingQueue<HarnessReplayBuffer.Event>();
        var view = new Connection() {
            public void send(HarnessReplayBuffer.Event event) { received.add(event); }
        };
        buffer.attach(0, view);
        var heartbeat = received.poll(2, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(heartbeat);
        assertEquals("heartbeat", heartbeat.name());
        assertEquals(0, heartbeat.id());
        assertTrue(heartbeat.data().isObject());
        buffer.detach(view);
        received.clear();
        assertNull(received.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS));
        // It neither consumes the bounded replay window nor changes its cursor.
        buffer.send("chunk", "answer");
        buffer.complete();
        var replay = new Connection(); buffer.attach(0, replay);
        assertEquals(List.of("chunk"), replay.events.stream().map(HarnessReplayBuffer.Event::name).toList());
        assertEquals(1, replay.events.get(0).id());
    }

    @Test void heartbeatFollowsReplacementAndStopsAfterCompletion() throws Exception {
        var buffer = new HarnessReplayBuffer(new ObjectMapper(), 1024, 2, 10);
        var oldEvents = new java.util.concurrent.LinkedBlockingQueue<HarnessReplayBuffer.Event>();
        var newEvents = new java.util.concurrent.LinkedBlockingQueue<HarnessReplayBuffer.Event>();
        var old = new Connection() { public void send(HarnessReplayBuffer.Event event) { oldEvents.add(event); } };
        var next = new Connection() { public void send(HarnessReplayBuffer.Event event) { newEvents.add(event); } };
        buffer.attach(0, old);
        assertNotNull(oldEvents.poll(2, java.util.concurrent.TimeUnit.SECONDS));
        buffer.attach(0, next);
        buffer.detach(old); // A stale callback must not stop the replacement's heartbeat.
        oldEvents.clear();
        assertNotNull(newEvents.poll(2, java.util.concurrent.TimeUnit.SECONDS));
        assertNull(oldEvents.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS));
        buffer.complete();
        newEvents.clear();
        assertNull(newEvents.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertTrue(next.completed);
    }

    @Test void failedHeartbeatDetachesOnlyTheSocketAndStopsItsTimer() throws Exception {
        var buffer = new HarnessReplayBuffer(new ObjectMapper(), 1024, 2, 10);
        var calls = new java.util.concurrent.LinkedBlockingQueue<String>();
        var broken = new Connection() {
            public void send(HarnessReplayBuffer.Event event) throws IOException {
                calls.add(event.name()); throw new IOException("dead socket");
            }
        };
        buffer.attach(0, broken);
        assertEquals("heartbeat", calls.poll(2, java.util.concurrent.TimeUnit.SECONDS));
        assertNull(calls.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertFalse(buffer.isTerminal());
        buffer.send("chunk", "work continues"); buffer.complete();
        var replay = new Connection(); buffer.attach(0, replay);
        assertEquals("work continues", replay.events.get(0).data().asText());
    }

    @Test void blockedHeartbeatDoesNotHoldTheReplayMonitorOrDetachANewerSubscriber() throws Exception {
        var buffer = new HarnessReplayBuffer(new ObjectMapper(), 1024, 2, 10);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var exited = new java.util.concurrent.CountDownLatch(1);
        var old = new Connection() {
            public void send(HarnessReplayBuffer.Event event) throws IOException {
                if (!event.name().equals("heartbeat")) { super.send(event); return; }
                entered.countDown();
                // Model a non-interruptible socket write; replacement must not await this probe.
                boolean interrupted = false;
                try {
                    while (true) {
                        try { release.await(); break; }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                    throw new IOException("old socket failed after replacement");
                } finally { if (interrupted) Thread.currentThread().interrupt(); exited.countDown(); }
            }
        };
        buffer.attach(0, old);
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        var next = new Connection();
        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
                buffer.attach(0, next);
                buffer.send("chunk", "still publishing");
            });
        } finally { release.countDown(); }
        assertTrue(exited.await(2, java.util.concurrent.TimeUnit.SECONDS));
        buffer.send("complete", Map.of("content", "done")); buffer.complete();
        assertTrue(next.events.stream().anyMatch(event -> event.name().equals("complete")));
        assertTrue(next.completed);
    }

    @Test void disconnectedSocketDoesNotDiscardWorkAndReplayHasNoGap() throws Exception {
        var buffer = new HarnessReplayBuffer(new ObjectMapper());
        var dead = new Connection() {
            public void send(HarnessReplayBuffer.Event event) throws IOException { throw new IOException("lost socket"); }
        };
        buffer.attach(0, dead);
        buffer.send("chunk", "first");
        buffer.send("turn_complete", Map.of("turnId", 1));
        var next = new Connection();
        buffer.attach(1, next);
        buffer.send("chunk", "second");
        assertEquals(List.of(2L, 3L), next.events.stream().map(HarnessReplayBuffer.Event::id).toList());
        assertFalse(buffer.isTerminal());
    }
    @Test void staleSocketCallbackCannotDetachReplacementAndTerminalReplaysOnce() throws Exception {
        var buffer = new HarnessReplayBuffer(new ObjectMapper());
        var old = new Connection(); var current = new Connection();
        buffer.attach(0, old);
        buffer.send("queued", Map.of("processId", "harness-1"));
        buffer.attach(1, current);
        assertTrue(old.completed);
        buffer.detach(old);
        buffer.send("complete", Map.of("content", "finished"));
        buffer.complete();
        buffer.send("chunk", "late");
        assertEquals(1, current.events.size()); assertTrue(current.completed);
        var replay = new Connection(); buffer.attach(1, replay);
        assertEquals("complete", replay.events.get(0).name()); assertTrue(replay.completed);
        var caughtUp = new Connection(); buffer.attach(2, caughtUp);
        assertTrue(caughtUp.events.isEmpty()); assertTrue(caughtUp.completed);
    }
    @Test void cursorOlderThanTheWindowResyncsAndKeepsFollowingTheRun() throws Exception {
        var buffer = new HarnessReplayBuffer(new ObjectMapper(), 1024, 2);
        for (int i = 0; i < 3; i++) buffer.send("chunk", "text");
        assertThrows(IllegalArgumentException.class, () -> buffer.attach(4, new Connection()));
        assertThrows(IllegalArgumentException.class, () -> buffer.attach(-1, new Connection()));
        var asleep = new Connection(); buffer.attach(0, asleep);
        assertEquals(List.of("resync", "chunk", "chunk"), asleep.events.stream().map(HarnessReplayBuffer.Event::name).toList());
        assertEquals(HarnessReplayBuffer.RESYNC_ID, asleep.events.get(0).id());
        assertEquals(1, asleep.events.get(0).data().path("after").asLong());
        buffer.send("chunk", "live");
        assertEquals(4L, asleep.events.get(3).id());
        var valid = new Connection(); buffer.attach(2, valid);
        assertEquals(List.of(3L, 4L), valid.events.stream().map(HarnessReplayBuffer.Event::id).toList());
        // An event larger than the whole window empties it: the resync names the newest id.
        buffer.send("chunk", "x".repeat(2000));
        var behind = new Connection(); buffer.attach(3, behind);
        assertEquals(1, behind.events.size());
        assertEquals(5, behind.events.get(0).data().path("after").asLong());
    }
    @Test void currentActivityIsRestoredAtACaughtUpCursorAndAfterOverflow() throws Exception {
        var buffer = new HarnessReplayBuffer(new ObjectMapper(), 1024, 2);
        buffer.send("activity", Map.of("turnActive", false, "processes", List.of(Map.of("id", "build", "state", "RUNNING")),
                "tasks", List.of()));
        buffer.send("chunk", "text");
        buffer.send("chunk", "more"); // activity has left the replay window
        var caughtUp = new Connection();
        buffer.attach(3, caughtUp);
        assertEquals(List.of("activity"), caughtUp.events.stream().map(HarnessReplayBuffer.Event::name).toList());
        assertEquals(0, caughtUp.events.get(0).id()); // snapshot never advances the cursor
        assertEquals("build", caughtUp.events.get(0).data().path("processes").get(0).path("id").asText());
        var asleep = new Connection();
        buffer.attach(0, asleep);
        assertEquals(List.of("resync", "chunk", "chunk", "activity"),
                asleep.events.stream().map(HarnessReplayBuffer.Event::name).toList());
        buffer.send("activity", Map.of("turnActive", false, "processes", List.of(), "tasks", List.of()));
        var latest = new Connection(); buffer.attach(4, latest);
        assertTrue(latest.events.get(0).data().path("processes").isEmpty());
        buffer.send("complete", Map.of("content", "done")); buffer.complete();
        var finished = new Connection(); buffer.attach(5, finished);
        assertTrue(finished.events.isEmpty()); // no stale running snapshot after completion
        assertTrue(finished.completed);
    }

    @Test void oversizedFinalAnswerStillReplaysATerminalMarkerAndResyncsTheTranscript() throws Exception {
        var buffer = new HarnessReplayBuffer(new ObjectMapper(), 1024, 2);
        buffer.send("chunk", "partial");
        buffer.send("complete", Map.of("content", "x".repeat(2000)));
        // Reconnect in the publication gap before complete(): the ending must already be replayable.
        var returned = new Connection(); buffer.attach(1, returned);
        buffer.complete();
        assertEquals(List.of("resync", "complete"), returned.events.stream().map(HarnessReplayBuffer.Event::name).toList());
        assertEquals(2, returned.events.get(0).data().path("after").asLong());
        assertEquals(0, returned.events.get(1).id());
        assertTrue(returned.completed);
    }

    @Test void finishedRunPastTheWindowResyncsThenDeliversItsEnding() throws Exception {
        var buffer = new HarnessReplayBuffer(new ObjectMapper(), 1024, 2);
        for (int i = 0; i < 3; i++) buffer.send("chunk", "text");
        buffer.send("complete", Map.of("content", "done"));
        buffer.complete();
        var woke = new Connection(); buffer.attach(0, woke);
        assertEquals(List.of("resync", "chunk", "complete"), woke.events.stream().map(HarnessReplayBuffer.Event::name).toList());
        assertTrue(woke.completed);
    }
}
