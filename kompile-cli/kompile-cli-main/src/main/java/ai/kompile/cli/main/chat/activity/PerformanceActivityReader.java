package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.main.chat.harness.ModelPerformanceRecord;
import ai.kompile.cli.main.chat.harness.ModelPerformanceStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Reuses the existing cross-session performance store without summing duplicate parent/child rows. */
public final class PerformanceActivityReader implements ActivityEvidenceReader {
    private final ModelPerformanceStore store;

    public PerformanceActivityReader(ModelPerformanceStore store) {
        this.store = store;
    }

    @Override
    public String id() {
        return "performance";
    }

    @Override
    public ActivityEvidence read(ActivityIdentity identity, ActivityReadBudget budget) {
        if (identity == null) return ActivityEvidence.unavailable(id(), "Performance identity is unavailable");
        budget = budget == null ? ActivityReadBudget.DEFAULT : budget;
        if (store == null) return ActivityEvidence.unavailable(id(), "Performance records are unavailable");
        List<ModelPerformanceRecord> records;
        try {
            records = store.getSessionRecords(identity.conversationId());
        } catch (RuntimeException failure) {
            return ActivityEvidence.unavailable(id(), "Performance records read failed: " + failure.getMessage());
        }
        if (records == null || records.isEmpty()) return ActivityEvidence.unavailable(id(), "No performance record for session");
        long tokens = 0L;
        long errors = 0L;
        Instant first = null;
        Instant last = null;
        List<ActivityAnnotation> annotations = new ArrayList<>();
        int count = 0;
        for (ModelPerformanceRecord record : records) {
            if (record == null || count++ >= budget.maxItems()) break;
            tokens += Math.max(0L, record.getInputTokens()) + Math.max(0L, record.getOutputTokens());
            errors += Math.max(0, record.getToolCallErrors()) + (record.isHadApiError() ? 1 : 0)
                    + (record.isHadRateLimit() ? 1 : 0);
            if (record.isHadEscape()) errors++;
            Instant timestamp = record.getTimestamp();
            if (timestamp != null && (first == null || timestamp.isBefore(first))) first = timestamp;
            if (timestamp != null && (last == null || timestamp.isAfter(last))) last = timestamp;
            if (record.getTaskOutcome() != null && !record.getTaskOutcome().isBlank()) {
                annotations.add(ActivityAnnotation.outcome(identity.key() + ":performance-" + count,
                        record.getTaskOutcome(), record.getOutcomeReason(), record.getAgentName(),
                        timestamp, List.of()));
            }
        }
        return new ActivityEvidence(id(), count < records.size() ? ActivityCoverage.PARTIAL : ActivityCoverage.COMPLETE,
                null, null, ActivityMetric.observed(tokens, id()), null,
                ActivityMetric.observed(errors, id()), first, last,
                List.of(ActivitySourceRef.logical("kompile", identity.conversationId(), "performance")),
                annotations, java.util.Map.of("records", Integer.toString(count)), List.of());
    }
}
