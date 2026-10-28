package ai.kompile.app.services.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayDeque;

/** Bounded, process-local event replay. A socket is a subscriber, not the owner of the run. */
final class HarnessReplayBuffer implements KompileCliHarnessClient.HarnessEventSink {
    /** Id of the out-of-band marker that says events were skipped; it never advances a client cursor. */
    static final long RESYNC_ID = 0;
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
    // Activity is state, not a delta: a caught-up cursor or an overflow must not hide running work.
    private Event latestActivity;
    private Event evictedEnding;
    private Connection connection;
    private final long heartbeatMillis;
    private Thread heartbeatThread;
    final long createdAt = System.currentTimeMillis();

    HarnessReplayBuffer(ObjectMapper mapper) { this(mapper, 1_048_576, 2000); }
    HarnessReplayBuffer(ObjectMapper mapper, int maxBytes, int maxEvents) {
        this(mapper, maxBytes, maxEvents, 15_000);
    }
    HarnessReplayBuffer(ObjectMapper mapper, int maxBytes, int maxEvents, long heartbeatMillis) {
        if (heartbeatMillis <= 0) throw new IllegalArgumentException("Heartbeat interval must be positive");
        this.mapper = mapper; this.maxBytes = maxBytes; this.maxEvents = maxEvents;
        this.heartbeatMillis = heartbeatMillis;
    }
    /** Liveness belongs to this subscriber, not the replay log or the underlying agent run. */
    private void startHeartbeat(Connection expected) {
        heartbeatThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(heartbeatMillis);
                    synchronized (this) {
                        if (terminal || connection != expected || Thread.currentThread().isInterrupted()) return;
                    }
                    // A liveness probe must never hold the replay/publish monitor during socket I/O.
                    // It is out of band: a late probe on an already detached socket changes no state.
                    try { expected.send(new Event(RESYNC_ID, "heartbeat", mapper.createObjectNode(), 0)); }
                    catch (IOException | RuntimeException disconnected) { detach(expected); return; }
                }
            } catch (InterruptedException detached) {
                Thread.currentThread().interrupt();
            }
        }, "chat-sse-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }
    private void stopHeartbeat() {
        if (heartbeatThread != null) { heartbeatThread.interrupt(); heartbeatThread = null; }
    }
    @Override public synchronized void send(String name, Object value) throws IOException {
        if (terminal) return;
        JsonNode data = mapper.valueToTree(value);
        Event event = new Event(++sequence, name, data, mapper.writeValueAsBytes(data).length + name.length() + 32);
        if (name.equals("activity")) latestActivity = event.bytes() <= maxBytes ? event : null;
        // A long final answer can evict itself. Its transcript is durable; retain only its ending.
        if (event.bytes() > maxBytes && java.util.Set.of("complete", "error", "cancelled").contains(name))
            evictedEnding = new Event(event.id(), name, mapper.valueToTree(name.equals("error")
                    ? "Run ended while the page was away; reload its saved transcript." : java.util.Map.of()), 0);
        events.addLast(event); bytes += event.bytes();
        while (bytes > maxBytes || events.size() > maxEvents) bytes -= events.removeFirst().bytes();
        deliver(event);
    }
    private void deliver(Event event) {
        if (connection == null) return;
        try { connection.send(event); }
        catch (IOException | RuntimeException disconnected) { detach(connection); }
    }
    /**
     * Cursor validation and replay share the publish lock: no gap between replay and live delivery.
     * A cursor older than the window (a phone asleep through a long turn) still follows the run: it
     * first gets a resync marker naming the new cursor, so the client knows to reload the transcript.
     */
    synchronized void attach(long after, Connection next) {
        long requestedAfter = after;
        long oldest = events.isEmpty() ? sequence + 1 : events.getFirst().id();
        if (after < 0 || after > sequence) throw new IllegalArgumentException("Invalid replay cursor");
        Connection previous = connection;
        stopHeartbeat();
        connection = next;
        if (previous != null && previous != next) previous.superseded();
        if (after < oldest - 1) {
            after = oldest - 1;
            JsonNode data = mapper.valueToTree(java.util.Map.of("after", after));
            deliver(new Event(RESYNC_ID, "resync", data, 0));
        }
        for (Event event : events) if (event.id() > after) deliver(event);
        if (evictedEnding != null && evictedEnding.id() > requestedAfter)
            deliver(new Event(RESYNC_ID, evictedEnding.name(), evictedEnding.data(), 0));
        // Out-of-band state does not advance the replay cursor. Send after deltas so old activity
        // in the window cannot overwrite the current snapshot, including when the cursor is caught up.
        if (!terminal && evictedEnding == null && latestActivity != null)
            deliver(new Event(RESYNC_ID, "activity", latestActivity.data(), 0));
        if (terminal && connection != null) {
            Connection done = connection; connection = null; done.complete();
        }
        if (!terminal && connection == next) startHeartbeat(next);
    }
    synchronized void detach(Connection expected) {
        if (connection == expected) { connection = null; stopHeartbeat(); }
    }
    synchronized boolean isTerminal() { return terminal; }
    @Override public synchronized void complete() {
        terminal = true;
        stopHeartbeat();
        Connection done = connection; connection = null;
        if (done != null) done.complete();
    }
}
