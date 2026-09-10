package ai.kompile.cli.main.chat.activity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, list-view-safe activity summary. */
public record ConversationActivitySummary(
        int schemaVersion,
        ActivityIdentity identity,
        String title,
        String goal,
        String outcome,
        String executionState,
        String evidenceBasis,
        Instant startedAt,
        Instant endedAt,
        ActivityMetric elapsed,
        ActivityMetric blocking,
        ActivityMetric tokens,
        ActivityMetric edits,
        ActivityMetric issues,
        Map<String, ActivityCoverage> coverage,
        List<ActivityAnnotation> annotations,
        List<ActivitySourceRef> evidence,
        List<String> warnings) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ConversationActivitySummary {
        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
        identity = identity == null ? ActivityIdentity.conversation("unknown", null) : identity;
        title = clean(title, "(untitled)", 160);
        goal = clean(goal, "", 2_000);
        outcome = clean(outcome, "UNVERIFIED", 40);
        executionState = clean(executionState, "UNKNOWN", 40);
        evidenceBasis = clean(evidenceBasis, "NONE", 64);
        elapsed = elapsed == null ? ActivityMetric.unknown("activity") : elapsed;
        blocking = blocking == null ? ActivityMetric.unknown("activity") : blocking;
        tokens = tokens == null ? ActivityMetric.unknown("activity") : tokens;
        edits = edits == null ? ActivityMetric.unknown("activity") : edits;
        issues = issues == null ? ActivityMetric.unknown("activity") : issues;
        coverage = boundedCoverage(coverage);
        annotations = bounded(annotations, 256);
        evidence = bounded(evidence, 256);
        warnings = bounded(warnings, 64);
    }

    public static ConversationActivitySummary empty(ActivityIdentity identity) {
        return new ConversationActivitySummary(CURRENT_SCHEMA_VERSION, identity, "(untitled)", "",
                "UNVERIFIED", "UNKNOWN", "NONE", null, null, null, null, null, null, null,
                Map.of("summary", ActivityCoverage.UNAVAILABLE), List.of(), List.of(), List.of());
    }

    public ConversationActivitySummary withAnnotations(List<ActivityAnnotation> next) {
        return new ConversationActivitySummary(schemaVersion, identity, title, goal, outcome,
                executionState, evidenceBasis, startedAt, endedAt, elapsed, blocking, tokens, edits,
                issues, coverage, next, evidence, warnings);
    }

    public ConversationActivitySummary withGoal(String nextGoal) {
        return new ConversationActivitySummary(schemaVersion, identity, title, nextGoal, outcome,
                executionState, evidenceBasis, startedAt, endedAt, elapsed, blocking, tokens, edits,
                issues, coverage, annotations, evidence, warnings);
    }

    public ConversationActivitySummary merge(ActivityEvidence contribution) {
        if (contribution == null) return this;
        Map<String, ActivityCoverage> nextCoverage = new LinkedHashMap<>(coverage);
        nextCoverage.put(contribution.readerId(), contribution.coverage());
        List<ActivityAnnotation> nextAnnotations = new ArrayList<>(annotations);
        contribution.annotations().forEach(annotation -> {
            if (annotation != null && nextAnnotations.stream().noneMatch(existing ->
                    existing.annotationId().equals(annotation.annotationId()))) nextAnnotations.add(annotation);
        });
        List<ActivitySourceRef> nextEvidence = new ArrayList<>(evidence);
        contribution.sources().forEach(ref -> {
            if (ref != null && !nextEvidence.contains(ref)) nextEvidence.add(ref);
        });
        List<String> nextWarnings = new ArrayList<>(warnings);
        contribution.warnings().forEach(warning -> {
            if (warning != null && !warning.isBlank() && !nextWarnings.contains(warning)) nextWarnings.add(warning);
        });
        return new ConversationActivitySummary(schemaVersion, identity, title, goal, outcome,
                executionState, evidenceBasis, startedAt, endedAt,
                elapsed.prefer(contribution.elapsed()), blocking.prefer(contribution.blocking()),
                tokens.prefer(contribution.tokens()), edits.prefer(contribution.edits()),
                issues.prefer(contribution.issues()), nextCoverage, nextAnnotations,
                nextEvidence, nextWarnings);
    }

    private static <T> List<T> bounded(List<T> values, int limit) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(java.util.Objects::nonNull).limit(limit).toList();
    }

    private static Map<String, ActivityCoverage> boundedCoverage(
            Map<String, ActivityCoverage> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, ActivityCoverage> result = new LinkedHashMap<>();
        values.entrySet().stream().limit(64).forEach(entry -> {
            if (entry.getKey() != null && entry.getValue() != null) result.put(entry.getKey(), entry.getValue());
        });
        return Map.copyOf(result);
    }

    private static String clean(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.replace('\n', ' ').replace('\r', ' ').strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
    }
}
