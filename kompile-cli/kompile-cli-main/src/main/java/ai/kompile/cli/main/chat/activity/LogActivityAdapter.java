package ai.kompile.cli.main.chat.activity;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** Adapter for retained agent/subprocess log metadata; log payloads remain on demand. */
public final class LogActivityAdapter implements ActivityEvidenceReader {
    private final Supplier<List<LogObservation>> source;

    public LogActivityAdapter(Supplier<List<LogObservation>> source) {
        this.source = source;
    }

    @Override
    public String id() {
        return "logs";
    }

    @Override
    public ActivityEvidence read(ActivityIdentity identity, ActivityReadBudget budget) {
        if (identity == null) return ActivityEvidence.unavailable(id(), "Log identity is unavailable");
        if (source == null) return ActivityEvidence.unavailable(id(), "Log metadata is unavailable");
        List<LogObservation> observations;
        try {
            observations = source.get();
        } catch (RuntimeException failure) {
            return ActivityEvidence.unavailable(id(), "Log metadata read failed: " + failure.getMessage());
        }
        Instant first = null;
        Instant last = null;
        int errors = 0;
        for (LogObservation observation : observations == null ? List.<LogObservation>of() : observations) {
            if (observation == null || !identity.conversationId().equals(observation.conversationId())) continue;
            if (observation.startedAt() != null && (first == null || observation.startedAt().isBefore(first))) first = observation.startedAt();
            if (observation.endedAt() != null && (last == null || observation.endedAt().isAfter(last))) last = observation.endedAt();
            if (observation.error()) errors++;
        }
        ActivityMetric elapsed = first != null && last != null && !last.isBefore(first)
                ? ActivityMetric.observed(last.toEpochMilli() - first.toEpochMilli(), id())
                : ActivityMetric.unknown(id());
        return new ActivityEvidence(id(), ActivityCoverage.COMPLETE, elapsed, null,
                ActivityMetric.unknown(id()), null, ActivityMetric.observed(errors, id()),
                first, last, List.of(ActivitySourceRef.logical("kompile", identity.conversationId(), "logs")),
                List.of(), java.util.Map.of(), List.of());
    }

    public record LogObservation(String conversationId, String runId, Instant startedAt,
                                 Instant endedAt, boolean error) {
    }
}
