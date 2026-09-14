package ai.kompile.app.services.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayDeque;

/** Bounded, process-local event replay. A socket is a subscriber, not the owner of the run. */
final class HarnessReplayBuffer implements KompileCliHarnessClient.HarnessEventSink {
    record Event(long id, String name, JsonNode data, int bytes) { }
    interface Connection {
        void send(Event event) throws IOException;
        void complete();
        default void superseded() { complete(); }
    }
    private final ObjectMapper mapper;
    private final int maxBytes;
    private final int maxEvents;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long sequence;
    private int bytes;
    private boolean terminal;
    private Connection connection;
    final long createdAt = System.currentTimeMillis();

    HarnessReplayBuffer(ObjectMapper mapper) { this(mapper, 1_048_576, 2000); }
    HarnessReplayBuffer(ObjectMapper mapper, int maxBytes, int maxEvents) {
        this.mapper = mapper; this.maxBytes = maxBytes; this.maxEvents = maxEvents;
    }
    @Override public synchronized void send(String name, Object value) throws IOException {
        if (terminal) return;
        JsonNode data = mapper.valueToTree(value);
        Event event = new Event(++sequence, name, data, mapper.writeValueAsBytes(data).length + name.length() + 32);
        events.addLast(event); bytes += event.bytes();
        while (bytes > maxBytes || events.size() > maxEvents) bytes -= events.removeFirst().bytes();
        deliver(event);
    }
    private void deliver(Event event) {
        if (connection == null) return;
        try { connection.send(event); }
        catch (IOException | RuntimeException disconnected) { detach(connection); }
    }
    /** Cursor validation and replay share the publish lock: no gap between replay and live delivery. */
    synchronized void attach(long after, Connection next) {
        long oldest = events.isEmpty() ? sequence + 1 : events.getFirst().id();
        if (after < 0 || after > sequence) throw new IllegalArgumentException("Invalid replay cursor");
        if (after < oldest - 1) throw new IllegalStateException("Replay window expired; reload the CLI transcript. Work was not restarted.");
        Connection previous = connection;
        connection = next;
        if (previous != null && previous != next) previous.superseded();
        for (Event event : events) if (event.id() > after) deliver(event);
        if (terminal && connection != null) {
            Connection done = connection; connection = null; done.complete();
        }
    }
    synchronized void detach(Connection expected) {
        if (connection == expected) connection = null;
    }
    synchronized boolean isTerminal() { return terminal; }
    @Override public synchronized void complete() {
        terminal = true;
        Connection done = connection; connection = null;
        if (done != null) done.complete();
    }
}
