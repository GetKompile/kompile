package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Bounded, read-only inspection of a Kompile or imported transcript file. */
public final class TranscriptActivityReader implements ActivityEvidenceReader {
    private final ObjectMapper mapper;
    private final Path file;
    private final Path permittedRoot;

    public TranscriptActivityReader(Path file, Path permittedRoot) {
        this(JsonUtils.standardMapper(), file, permittedRoot);
    }

    TranscriptActivityReader(ObjectMapper mapper, Path file, Path permittedRoot) {
        this.mapper = mapper;
        this.file = file;
        this.permittedRoot = permittedRoot == null ? null : permittedRoot.toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return "transcript";
    }

    @Override
    public ActivityEvidence read(ActivityIdentity identity, ActivityReadBudget budget) {
        if (identity == null) return ActivityEvidence.unavailable(id(), "Transcript identity is unavailable");
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return ActivityEvidence.unavailable(id(), "Transcript is unavailable");
        }
        TranscriptInspection inspection = inspect(budget);
        ActivitySourceRef source = ActivitySourceRef.file("kompile", file.getFileName().toString(),
                "transcript", file, permittedRoot == null ? file.getParent() : permittedRoot);
        List<ActivityAnnotation> annotations = new ArrayList<>();
        if (!inspection.title().isBlank()) {
            annotations.add(ActivityAnnotation.goal(identity.key() + ":transcript-goal",
                    inspection.title(), inspection.agent(), inspection.startedAt()));
        }
        ActivityCoverage coverage = inspection.truncated()
                ? ActivityCoverage.PARTIAL : ActivityCoverage.COMPLETE;
        List<String> warnings = inspection.truncated()
                ? List.of("Transcript inspection stopped at the configured read bound") : List.of();
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("turns", Integer.toString(inspection.turns().size()));
        if (!inspection.workingDirectory().isBlank()) {
            attributes.put("workingDirectory", inspection.workingDirectory());
        }
        return new ActivityEvidence(id(), coverage, null, null, ActivityMetric.unknown(id()),
                null, ActivityMetric.unknown(id()), inspection.startedAt(), null,
                List.of(source), annotations, attributes, warnings);
    }

    public TranscriptInspection inspect(ActivityReadBudget budget) {
        budget = budget == null ? ActivityReadBudget.DEFAULT : budget;
        List<TranscriptTurn> turns = new ArrayList<>();
        String startedText = null;
        String agent = "";
        String cwd = "";
        String title = "";
        String role = null;
        StringBuilder content = new StringBuilder();
        long bytes = 0L;
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                long lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1L;
                if (bytes + lineBytes > budget.maxBytes() || turns.size() >= budget.maxTurns()) {
                    truncated = true;
                    break;
                }
                bytes += lineBytes;
                if (line.startsWith("Started:") && startedText == null) {
                    startedText = line.substring("Started:".length()).strip();
                    continue;
                }
                if (line.startsWith("Agent:") && agent.isBlank()) {
                    agent = line.substring("Agent:".length()).strip();
                    continue;
                }
                if (line.startsWith("CWD:") && cwd.isBlank()) {
                    cwd = line.substring("CWD:".length()).strip();
                    continue;
                }
                if (line.startsWith("> ")) {
                    if (role != null) flush(turns, role, content);
                    role = "user";
                    content.setLength(0);
                    content.append(line.substring(2));
                    if (title.isBlank()) title = content.toString().strip();
                } else if (line.startsWith("< ")) {
                    if (role != null && !"assistant".equals(role)) flush(turns, role, content);
                    if (!"assistant".equals(role)) {
                        role = "assistant";
                        content.setLength(0);
                    } else if (content.length() > 0) {
                        content.append('\n');
                    }
                    content.append(line.substring(2));
                } else if (line.isBlank()) {
                    if ("user".equals(role)) flush(turns, role, content);
                } else if (role != null && !isMetadata(line)) {
                    content.append(content.length() == 0 ? "" : "\n").append(line);
                }
            }
            if (role != null) flush(turns, role, content);
        } catch (Exception failure) {
            truncated = true;
        }
        Instant started = parse(startedText);
        return new TranscriptInspection(title, agent, cwd, started, turns, bytes, truncated);
    }

    private static void flush(List<TranscriptTurn> turns, String role, StringBuilder content) {
        String value = content.toString().strip();
        if (!value.isBlank()) turns.add(new TranscriptTurn(role, value));
        content.setLength(0);
    }

    private static boolean isMetadata(String line) {
        return line.startsWith("────") || line.startsWith("Server:") || line.startsWith("RAG:")
                || line.startsWith("[system]") || line.startsWith("[tool:")
                || line.startsWith("[subagent:") || line.startsWith("[todo:")
                || line.startsWith("[harvested:") || line.startsWith("[resumed")
                || line.startsWith("  [");
    }

    private static Instant parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public record TranscriptTurn(String role, String content) {
        public TranscriptTurn {
            role = role == null ? "unknown" : role;
            content = content == null ? "" : content;
        }
    }

    public record TranscriptInspection(String title, String agent, String workingDirectory,
                                       Instant startedAt, List<TranscriptTurn> turns,
                                       long bytesRead, boolean truncated) {
        public TranscriptInspection {
            title = title == null ? "" : title;
            agent = agent == null ? "" : agent;
            workingDirectory = workingDirectory == null ? "" : workingDirectory;
            turns = turns == null ? List.of() : List.copyOf(turns);
            bytesRead = Math.max(0L, bytesRead);
        }
    }
}
