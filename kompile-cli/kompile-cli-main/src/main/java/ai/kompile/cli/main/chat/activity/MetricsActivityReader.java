package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Reads the existing per-session ChatSessionMetrics JSON without creating a second metrics store. */
public final class MetricsActivityReader implements ActivityEvidenceReader {
    private final ObjectMapper mapper;
    private final Path file;
    private final Path permittedRoot;

    public MetricsActivityReader(Path file, Path permittedRoot) {
        this(JsonUtils.standardMapper(), file, permittedRoot);
    }

    MetricsActivityReader(ObjectMapper mapper, Path file, Path permittedRoot) {
        this.mapper = mapper;
        this.file = file;
        this.permittedRoot = permittedRoot == null ? null : permittedRoot.toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return "metrics";
    }

    @Override
    public ActivityEvidence read(ActivityIdentity identity, ActivityReadBudget budget) {
        if (identity == null) return ActivityEvidence.unavailable(id(), "Metrics identity is unavailable");
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return ActivityEvidence.unavailable(id(), "Session metrics are not recorded");
        }
        try {
            JsonNode root = mapper.readTree(file.toFile());
            JsonNode session = root.path("session");
            String recordedId = session.path("sessionId").asText("");
            if (!recordedId.isBlank() && !recordedId.equals(identity.conversationId())) {
                return ActivityEvidence.unavailable(id(), "Metrics belong to a different conversation");
            }
            JsonNode tokens = root.path("tokens");
            ActivityMetric tokenMetric = tokens.has("total")
                    ? ActivityMetric.observed(tokens.path("total").asLong(), id())
                    : tokens.has("estimatedTotal")
                    ? ActivityMetric.estimated(tokens.path("estimatedTotal").asLong(), id())
                    : ActivityMetric.unknown(id());
            ActivityMetric elapsed = session.has("durationSeconds")
                    ? ActivityMetric.observed(session.path("durationSeconds").asLong() * 1_000L, id())
                    : ActivityMetric.unknown(id());
            int toolErrors = root.path("tools").path("totalErrors").asInt(0);
            int escapes = root.path("escapes").path("totalEscapes").asInt(0);
            ActivityMetric issues = root.has("tools") || root.has("escapes")
                    ? ActivityMetric.observed((long) toolErrors + escapes, id())
                    : ActivityMetric.unknown(id());
            List<ActivityAnnotation> annotations = new ArrayList<>();
            JsonNode outcome = root.path("outcome");
            if (outcome.isObject() && outcome.has("outcome")) {
                String status = outcome.path("outcome").asText("UNVERIFIED");
                String reason = outcome.path("reason").asText("");
                annotations.add(ActivityAnnotation.outcome(identity.key() + ":metrics-outcome",
                        status, reason, session.path("agent").asText(""), parseInstant(session.path("ended").asText(null)), List.of()));
                if (outcome.has("taskPrompt")) {
                    annotations.add(ActivityAnnotation.goal(identity.key() + ":metrics-goal",
                            outcome.path("taskPrompt").asText(""), session.path("agent").asText(""),
                            parseInstant(session.path("started").asText(null))));
                }
            }
            ActivitySourceRef source = ActivitySourceRef.file("kompile", file.getFileName().toString(),
                    "metrics", file, permittedRoot == null ? file.getParent() : permittedRoot);
            ActivityCoverage coverage = root.has("session") && root.has("tokens")
                    ? ActivityCoverage.COMPLETE : ActivityCoverage.PARTIAL;
            java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
            String executionState = session.path("executionState").asText("");
            if (!executionState.isBlank()) attributes.put("executionState", executionState);
            if (session.has("cleanlyEnded") && session.path("cleanlyEnded").asBoolean(false)) {
                attributes.put("executionState", "CLEANLY_ENDED");
            }
            return new ActivityEvidence(id(), coverage, elapsed, null, tokenMetric, null, issues,
                    parseInstant(session.path("started").asText(null)),
                    parseInstant(session.path("ended").asText(null)),
                    List.of(source), annotations, attributes, List.of());
        } catch (Exception failure) {
            return ActivityEvidence.unavailable(id(), "Could not read session metrics: " + failure.getMessage());
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
