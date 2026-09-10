package ai.kompile.cli.main.chat.activity;

import java.time.Instant;
import java.util.List;

/** User/producer annotation; it records evidence and never rewrites prior claims. */
public record ActivityAnnotation(
        String annotationId,
        String kind,
        String text,
        String status,
        Instant observedAt,
        String actorId,
        String evaluatedRevision,
        List<ActivitySourceRef> evidence) {

    public ActivityAnnotation {
        annotationId = clean(annotationId, "annotation", 160);
        kind = clean(kind, "note", 64);
        text = clean(text, "", 2_000);
        status = clean(status, "", 64);
        observedAt = observedAt == null ? Instant.EPOCH : observedAt;
        actorId = clean(actorId, "", 200);
        evaluatedRevision = clean(evaluatedRevision, "", 240);
        evidence = evidence == null ? List.of() : evidence.stream()
                .filter(java.util.Objects::nonNull).limit(128).toList();
    }

    public static ActivityAnnotation goal(String id, String text, String actorId,
                                          Instant observedAt) {
        return new ActivityAnnotation(id, "goal", text, "declared", observedAt, actorId, "", List.of());
    }

    public static ActivityAnnotation outcome(String id, String status, String text,
                                             String actorId, Instant observedAt,
                                             List<ActivitySourceRef> evidence) {
        return new ActivityAnnotation(id, "outcome", text, status, observedAt, actorId, "", evidence);
    }

    /** A typed operator confirmation; agent/producer outcome claims use kind {@code outcome}. */
    public static ActivityAnnotation confirmedOutcome(String id, String status, String text,
                                                      String actorId, Instant observedAt,
                                                      List<ActivitySourceRef> evidence) {
        return new ActivityAnnotation(id, "user_confirmation", text, status, observedAt,
                actorId, "", evidence);
    }

    public static ActivityAnnotation commit(String id, String repository, String commitHash,
                                            String basis, String actorId, Instant observedAt,
                                            ActivitySourceRef evidence) {
        String text = (repository == null ? "" : repository) + "@"
                + (commitHash == null ? "" : commitHash);
        return new ActivityAnnotation(id, "commit", text, basis, observedAt, actorId,
                commitHash, evidence == null ? List.of() : List.of(evidence));
    }

    private static String clean(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.replace('\n', ' ').replace('\r', ' ').strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
    }
}
