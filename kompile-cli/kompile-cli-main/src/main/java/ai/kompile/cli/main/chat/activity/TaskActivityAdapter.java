package ai.kompile.cli.main.chat.activity;

import java.util.List;
import java.util.function.Supplier;

/** Adapter for task/child records; launch count is not confused with token totals. */
public final class TaskActivityAdapter implements ActivityEvidenceReader {
    private final Supplier<List<TaskObservation>> source;

    public TaskActivityAdapter(Supplier<List<TaskObservation>> source) {
        this.source = source;
    }

    @Override
    public String id() {
        return "tasks";
    }

    @Override
    public ActivityEvidence read(ActivityIdentity identity, ActivityReadBudget budget) {
        if (identity == null) return ActivityEvidence.unavailable(id(), "Task identity is unavailable");
        budget = budget == null ? ActivityReadBudget.DEFAULT : budget;
        if (source == null) return ActivityEvidence.unavailable(id(), "Task records are unavailable");
        List<TaskObservation> observations;
        try {
            observations = source.get();
        } catch (RuntimeException failure) {
            return ActivityEvidence.unavailable(id(), "Task records read failed: " + failure.getMessage());
        }
        int launches = 0;
        int failures = 0;
        for (TaskObservation observation : observations == null ? List.<TaskObservation>of() : observations) {
            if (observation == null || !identity.conversationId().equals(observation.conversationId())) continue;
            launches++;
            if (observation.failed()) failures++;
        }
        return new ActivityEvidence(id(), ActivityCoverage.COMPLETE, null, null,
                ActivityMetric.unknown(id()), null, ActivityMetric.observed(failures, id()),
                null, null, List.of(ActivitySourceRef.logical("kompile", identity.conversationId(), "tasks")),
                List.of(), java.util.Map.of("launches", Integer.toString(launches)), List.of());
    }

    public record TaskObservation(String conversationId, String taskId, boolean failed) {
    }
}
