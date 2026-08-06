package ai.kompile.core.evaluation.graph;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Receives optional graph decision diagnostics without coupling callers to their storage. */
@FunctionalInterface
public interface GraphDecisionTraceSink {

    /** Records one decision event. */
    void accept(GraphDecisionTraceEvent event);

    /** Alias that reads naturally at instrumentation call sites. */
    default void trace(GraphDecisionTraceEvent event) {
        accept(event);
    }

    /** A sink suitable for production paths where tracing is disabled. */
    static GraphDecisionTraceSink noop() {
        return event -> { };
    }

    /** Creates a thread-safe in-memory collector. */
    static Collector collector() {
        return new Collector();
    }

    /** Thread-safe collector whose reads are immutable snapshots. */
    final class Collector implements GraphDecisionTraceSink {
        private final CopyOnWriteArrayList<GraphDecisionTraceEvent> events =
                new CopyOnWriteArrayList<>();

        @Override
        public void accept(GraphDecisionTraceEvent event) {
            if (event != null) {
                events.add(event);
            }
        }

        /** Returns the events in acceptance order. */
        public List<GraphDecisionTraceEvent> events() {
            return List.copyOf(events);
        }

        /** Alias emphasizing that the returned value cannot change underneath its caller. */
        public List<GraphDecisionTraceEvent> snapshot() {
            return events();
        }

        public void clear() {
            events.clear();
        }
    }
}
