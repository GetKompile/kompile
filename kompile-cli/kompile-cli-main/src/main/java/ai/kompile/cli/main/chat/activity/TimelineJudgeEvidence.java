package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Recent authoritative judge evidence, projected without copying responses into the journal. */
public final class TimelineJudgeEvidence {
    private static final int MAX_BYTES = 64 * 1024;
    private final Path sessionsRoot;

    public TimelineJudgeEvidence(Path sessionsRoot) {
        this.sessionsRoot = sessionsRoot.toAbsolutePath().normalize();
    }

    public record Result(List<ActivityEvent> events, List<String> warnings) {
        public Result { events = List.copyOf(events); warnings = List.copyOf(warnings); }
    }

    /** Supplemental newest evidence only, not a cursor-paged or causally correlated history. */
    public Result recent(SessionTimelineQuery query) {
        if ((query.kind() != null && !query.kind().isBlank() && !query.kind().equals("judge"))
                || (query.runId() != null && !query.runId().isBlank())) {
            return new Result(List.of(), List.of());
        }
        List<ActivityEvent> events = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            String session = query.identity().conversationId();
            if (session.contains("/") || session.contains("\\") || session.equals(".") || session.equals(".."))
                throw new IOException("Invalid judge session identifier");
            Path file = ActivitySourceRef.resolve(session + "/judgements.jsonl", sessionsRoot);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                return new Result(List.of(), List.of("Judge timeline evidence unavailable for this exact session ID."));
            int budget = Math.min(MAX_BYTES, query.maxBytes());
            try (RandomAccessFile reader = new RandomAccessFile(file.toFile(), "r")) {
                long size = reader.length();
                long start = Math.max(0, size - budget);
                byte[] bytes = new byte[(int) (size - start)];
                reader.seek(start);
                reader.readFully(bytes);
                int lineStart = 0;
                if (start > 0) {
                    reader.seek(start - 1);
                    if (reader.read() != '\n') {
                        while (lineStart < bytes.length && bytes[lineStart++] != '\n') { }
                    }
                    warnings.add("Judge evidence is a bounded recent tail; older records are not included.");
                }
                for (int i = lineStart; i < bytes.length; i++) {
                    if (bytes[i] != '\n') continue;
                    if (i > lineStart) {
                        try {
                            JsonNode row = JsonUtils.standardMapper().readTree(
                                    new String(bytes, lineStart, i - lineStart, StandardCharsets.UTF_8));
                            if (!session.equals(row.path("sessionId").asText())) { lineStart = i + 1; continue; }
                            Instant at = Instant.parse(row.path("timestamp").asText());
                            String status = row.path("status").asText("unknown");
                            if ((query.from() == null || !at.isBefore(query.from()))
                                    && (query.to() == null || !at.isAfter(query.to()))
                                    && (query.outcome() == null || query.outcome().isBlank()
                                        || query.outcome().equals(status))) {
                                String id = "judge:" + session + ":" + (start + lineStart);
                                ActivityIdentity identity = ActivityIdentity.conversation(session,
                                        query.identity().projectDirectory().isBlank() ? null
                                                : Path.of(query.identity().projectDirectory()));
                                events.add(new ActivityEvent(ActivityEvent.CURRENT_SCHEMA_VERSION, id,
                                        identity, "", "judge", "", 0, at, "judge.recorded", "", List.of(),
                                        ActivitySourceRef.file("judge", id, "judgement", file, sessionsRoot),
                                        Map.of("phase", row.path("phase").asText("unknown"),
                                                "outcome", status, "timeBasis", "observed",
                                                "associationBasis", "exact-session-log",
                                                "correlation", "unknown",
                                                "tool", row.path("toolName").asText(""))));
                            }
                        } catch (Exception invalid) {
                            if (!warnings.contains("Skipped malformed or untimed judge evidence."))
                                warnings.add("Skipped malformed or untimed judge evidence.");
                        }
                    }
                    lineStart = i + 1;
                }
                if (lineStart < bytes.length) warnings.add("Incomplete final judge record deferred until newline arrives.");
            }
            events.sort(Comparator.comparing(ActivityEvent::timestamp).reversed()
                    .thenComparing(ActivityEvent::eventId));
            if (events.size() > query.limit()) {
                events = new ArrayList<>(events.subList(0, query.limit()));
                warnings.add("Judge evidence limited to " + query.limit() + " recent matching records.");
            }
            events.sort(Comparator.comparing(ActivityEvent::timestamp).thenComparing(ActivityEvent::eventId));
            warnings.add("Judge evidence has unknown run/tool correlation; it is not part of the journal cursor.");
        } catch (Exception failure) {
            warnings.add("Judge timeline evidence unavailable: " + ActivityToolText.boundedClean(failure.getMessage(), 160));
        }
        return new Result(events, warnings);
    }
}
