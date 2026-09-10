package ai.kompile.cli.main.chat.activity;

import java.util.List;
import java.util.Map;

/** Bounded contribution from one existing producer/store. */
public record ActivityEvidence(
        String readerId,
        ActivityCoverage coverage,
        ActivityMetric elapsed,
        ActivityMetric blocking,
        ActivityMetric tokens,
        ActivityMetric edits,
        ActivityMetric issues,
        java.time.Instant startedAt,
        java.time.Instant endedAt,
        List<ActivitySourceRef> sources,
        List<ActivityAnnotation> annotations,
        Map<String, String> attributes,
        List<String> warnings) {

    public ActivityEvidence {
        readerId = readerId == null ? "unknown" : readerId;
        coverage = coverage == null ? ActivityCoverage.UNAVAILABLE : coverage;
        elapsed = elapsed == null ? ActivityMetric.unknown(readerId) : elapsed;
        blocking = blocking == null ? ActivityMetric.unknown(readerId) : blocking;
        tokens = tokens == null ? ActivityMetric.unknown(readerId) : tokens;
        edits = edits == null ? ActivityMetric.unknown(readerId) : edits;
        issues = issues == null ? ActivityMetric.unknown(readerId) : issues;
        sources = bounded(sources, 128);
        annotations = bounded(annotations, 128);
        attributes = boundedAttributes(attributes);
        warnings = bounded(warnings, 64);
    }

    public static ActivityEvidence unavailable(String readerId, String warning) {
        return new ActivityEvidence(readerId, ActivityCoverage.UNAVAILABLE, null, null, null, null,
                null, null, null, List.of(), List.of(), Map.of(),
                warning == null ? List.of() : List.of(warning));
    }

    private static <T> List<T> bounded(List<T> values, int limit) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(java.util.Objects::nonNull).limit(limit).toList();
    }

    private static Map<String, String> boundedAttributes(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, String> result = new java.util.LinkedHashMap<>();
        values.entrySet().stream().limit(64).forEach(entry -> {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(ActivityToolText.boundedClean(entry.getKey(), 80),
                        ActivityToolText.summary(entry.getValue()));
            }
        });
        return Map.copyOf(result);
    }
}
