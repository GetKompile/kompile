package ai.kompile.cli.main.chat.activity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Append-only lifecycle evidence envelope. Full payloads stay in their primary stores. */
public record ActivityEvent(
        int schemaVersion,
        String eventId,
        ActivityIdentity identity,
        String runId,
        String actorId,
        String parentActorId,
        long sequence,
        Instant timestamp,
        String eventType,
        String operationId,
        List<String> relatedIds,
        ActivitySourceRef source,
        Map<String, String> attributes) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ActivityEvent {
        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
        eventId = clean(eventId, 160);
        identity = identity == null ? ActivityIdentity.conversation("unknown", null) : identity;
        runId = clean(runId, 240);
        actorId = clean(actorId, 200);
        parentActorId = clean(parentActorId, 200);
        sequence = Math.max(0L, sequence);
        timestamp = timestamp == null ? Instant.EPOCH : timestamp;
        eventType = clean(eventType, 80);
        operationId = clean(operationId, 200);
        relatedIds = relatedIds == null ? List.of() : relatedIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> clean(value, 200)).limit(32).toList();
        attributes = attributes == null ? Map.of() : boundedAttributes(attributes);
    }

    public ActivityEvent withSequence(long nextSequence) {
        return new ActivityEvent(schemaVersion, eventId, identity, runId, actorId,
                parentActorId, nextSequence, timestamp, eventType, operationId,
                relatedIds, source, attributes);
    }

    private static Map<String, String> boundedAttributes(Map<String, String> input) {
        Map<String, String> result = new LinkedHashMap<>();
        input.entrySet().stream().limit(32).forEach(entry -> {
            String key = clean(entry.getKey(), 80);
            if (!key.isBlank()) result.put(key, ActivityToolText.summary(entry.getValue()));
        });
        return Map.copyOf(result);
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String normalized = value.replace('\n', ' ').replace('\r', ' ').strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
    }
}
