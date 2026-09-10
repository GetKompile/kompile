package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.main.chat.enforcer.JudgementRecord;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.Instant;

/** Read-only, bounded adapter for layered enforcer/judge records. */
public final class JudgementActivityReader implements ActivityEvidenceReader {
    private final ObjectMapper mapper;
    private final Path file;
    private final Path permittedRoot;

    public JudgementActivityReader(Path file, Path permittedRoot) {
        this(JsonUtils.standardMapper(), file, permittedRoot);
    }

    JudgementActivityReader(ObjectMapper mapper, Path file, Path permittedRoot) {
        this.mapper = mapper;
        this.file = file;
        this.permittedRoot = permittedRoot == null ? null : permittedRoot.toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return "judgement";
    }

    @Override
    public ActivityEvidence read(ActivityIdentity identity, ActivityReadBudget budget) {
        if (identity == null) return ActivityEvidence.unavailable(id(), "Judge identity is unavailable");
        budget = budget == null ? ActivityReadBudget.DEFAULT : budget;
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return ActivityEvidence.unavailable(id(), "Judge records are not retained");
        }
        int blocked = 0;
        int errors = 0;
        int malformed = 0;
        long bytes = 0L;
        Instant first = null;
        Instant last = null;
        List<String> warnings = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                long lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1L;
                if (bytes + lineBytes > budget.maxBytes() || count >= budget.maxRecords()) {
                    warnings.add("Judge records were bounded before the file ended");
                    break;
                }
                bytes += lineBytes;
                if (line.isBlank()) continue;
                count++;
                try {
                    JudgementRecord record = mapper.readValue(line, JudgementRecord.class);
                    if (!identity.conversationId().equals(record.getSessionId())) continue;
                    if ("RESULT".equalsIgnoreCase(record.getPhase())) {
                        if ("BLOCKED".equalsIgnoreCase(record.getStatus())) blocked++;
                        if ("ERROR".equalsIgnoreCase(record.getStatus())
                                || "UNAVAILABLE".equalsIgnoreCase(record.getStatus())) errors++;
                    }
                    Instant timestamp = parse(record.getTimestamp());
                    if (timestamp != null) {
                        if (first == null || timestamp.isBefore(first)) first = timestamp;
                        if (last == null || timestamp.isAfter(last)) last = timestamp;
                    }
                } catch (Exception malformedLine) {
                    malformed++;
                }
            }
        } catch (Exception failure) {
            return ActivityEvidence.unavailable(id(), "Could not read judge records: " + failure.getMessage());
        }
        if (malformed > 0) warnings.add("Skipped " + malformed + " malformed judge record(s)");
        ActivityCoverage coverage = malformed > 0 || !warnings.isEmpty()
                ? ActivityCoverage.PARTIAL : ActivityCoverage.COMPLETE;
        ActivitySourceRef source = ActivitySourceRef.file("kompile", file.getFileName().toString(),
                "judgement", file, permittedRoot == null ? file.getParent() : permittedRoot);
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("actualBlocked", Integer.toString(blocked));
        attributes.put("judgeErrors", Integer.toString(errors));
        return new ActivityEvidence(id(), coverage, null, null,
                ActivityMetric.unknown(id()), null,
                ActivityMetric.observed((long) blocked + errors, id()), first, last,
                List.of(source), List.of(), attributes, warnings);
    }

    private static Instant parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
