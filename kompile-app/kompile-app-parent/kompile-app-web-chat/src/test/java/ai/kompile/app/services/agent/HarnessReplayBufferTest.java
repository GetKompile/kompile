package ai.kompile.app.services.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HarnessReplayBufferTest {
    static class Connection implements HarnessReplayBuffer.Connection {
        final List<HarnessReplayBuffer.Event> events = new ArrayList<>();
        boolean completed;
        public void send(HarnessReplayBuffer.Event event) throws IOException { events.add(event); }
        public void complete() { completed = true; }
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
