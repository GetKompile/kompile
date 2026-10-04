package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.main.chat.enforcer.JudgementRecord;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.Instant;

/**
 * Read-only, bounded adapter for layered enforcer/judge records. The budget is spent on the
 * newest records: a long session's judge log runs to megabytes, and its oldest verdicts say the
 * least about how the session went.
 */
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
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return ActivityEvidence.unavailable(id(), "Judge records are not retained");
        }
        Tally tally;
        try {
            tally = tally(identity, budget == null ? ActivityReadBudget.DEFAULT : budget);
        } catch (Exception failure) {
            return ActivityEvidence.unavailable(id(), "Could not read judge records: " + failure.getMessage());
        }
        List<String> warnings = new ArrayList<>();
        if (tally.olderRecordsSkipped()) {
            warnings.add("Read only the newest " + tally.read() + " judge record(s); older records are not counted");
        }
        if (tally.malformed() > 0) warnings.add("Skipped " + tally.malformed() + " malformed judge record(s)");
        ActivityCoverage coverage = warnings.isEmpty() ? ActivityCoverage.COMPLETE : ActivityCoverage.PARTIAL;
        ActivitySourceRef source = ActivitySourceRef.file("kompile", file.getFileName().toString(),
                "judgement", file, permittedRoot == null ? file.getParent() : permittedRoot);
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("actualBlocked", Integer.toString(tally.blocked()));
        attributes.put("judgeErrors", Integer.toString(tally.errors()));
        attributes.put("judgeVerdicts", Integer.toString(tally.verdicts()));
        attributes.put("judgeStops", Integer.toString(tally.stops()));
        attributes.put("judgeCorrections", Integer.toString(tally.corrections()));
        attributes.put("overridden", Integer.toString(tally.overridden()));
        attributes.put("approvalsUsed", Integer.toString(tally.approvalsUsed()));
        attributes.put("controlChanges", Integer.toString(tally.controlChanges()));
        // Only a RESULT record reports a final enforcement outcome; chat-lane verdicts are
        // recommendations a user may override, so without one the count is unknown, not zero.
        ActivityMetric issues = tally.results() > 0
                ? ActivityMetric.observed((long) tally.blocked() + tally.errors(), id())
                : ActivityMetric.unknown(id());
        return new ActivityEvidence(id(), coverage, null, null,
                ActivityMetric.unknown(id()), null, issues, tally.first(), tally.last(),
                List.of(source), List.of(), attributes, warnings);
    }

    @Override
    public List<String> details(ActivityIdentity identity, ActivityReadBudget budget) {
        if (identity == null || file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Tally tally;
        try {
            tally = tally(identity, budget == null ? ActivityReadBudget.DEFAULT : budget);
        } catch (Exception failure) {
            return List.of();
        }
        if (tally.read() == 0) return List.of();
        StringBuilder line = new StringBuilder("Judge: ").append(tally.verdicts()).append(" verdict(s), ")
                .append(tally.stops()).append(" stopped, ").append(tally.corrections()).append(" corrected");
        if (tally.overridden() + tally.approvalsUsed() + tally.controlChanges() > 0) {
            line.append("; user: ").append(tally.overridden()).append(" overridden, ")
                    .append(tally.approvalsUsed()).append(" approval(s) used, ")
                    .append(tally.controlChanges()).append(" control change(s)");
        }
        if (tally.results() > 0) {
            line.append("; final outcomes: ").append(tally.blocked()).append(" blocked, ")
                    .append(tally.errors()).append(" judge error(s)");
        }
        line.append(tally.olderRecordsSkipped()
                ? " (newest " + tally.read() + " records read)" : " (" + tally.read() + " records read)");
        return List.of(line.toString());
    }

    private Tally tally(ActivityIdentity identity, ActivityReadBudget budget) throws IOException {
        Window window = newestLines(budget.maxBytes());
        boolean skipped = window.startsMidFile();
        int read = 0;
        int malformed = 0;
        int results = 0;
        int blocked = 0;
        int errors = 0;
        int verdicts = 0;
        int stops = 0;
        int corrections = 0;
        int overridden = 0;
        int approvalsUsed = 0;
        int controlChanges = 0;
        Instant first = null;
        Instant last = null;
        List<String> lines = window.lines();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            if (read >= budget.maxRecords()) {
                skipped = true;
                break;
            }
            read++;
            JudgementRecord record;
            try {
                record = mapper.readValue(line, JudgementRecord.class);
            } catch (Exception malformedLine) {
                malformed++;
                continue;
            }
            if (!identity.conversationId().equals(record.getSessionId())) continue;
            String phase = record.getPhase() == null ? "" : record.getPhase().toUpperCase();
            String status = record.getStatus() == null ? "" : record.getStatus().toUpperCase();
            if (phase.equals("RESULT")) {
                results++;
                if (status.equals("BLOCKED")) blocked++;
                if (status.equals("ERROR") || status.equals("UNAVAILABLE")) errors++;
            } else if (phase.equals("ATTEMPT") || (phase.startsWith("JUDGE_") && !phase.equals("JUDGE_CHAT"))) {
                verdicts++;
                if (record.isStop()) stops++;
                else if (!record.isCompliant()) corrections++;
            } else if (phase.equals("OVERRIDE")) {
                if (status.equals("OVERRIDDEN")) overridden++;
                if (status.equals("APPROVED")) approvalsUsed++;
            } else if (phase.equals("CONTROL")) {
                controlChanges++;
            }
            Instant timestamp = parse(record.getTimestamp());
            if (timestamp != null) {
                if (first == null || timestamp.isBefore(first)) first = timestamp;
                if (last == null || timestamp.isAfter(last)) last = timestamp;
            }
        }
        return new Tally(read, malformed, skipped, results, blocked, errors, verdicts, stops, corrections,
                overridden, approvalsUsed, controlChanges, first, last);
    }

    /** The complete lines in the last {@code maxBytes} of the file, oldest first. */
    private Window newestLines(int maxBytes) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            long start = Math.max(0L, size - maxBytes);
            // One byte before the window shows whether the window begins on a record boundary.
            long from = start == 0L ? 0L : start - 1L;
            ByteBuffer buffer = ByteBuffer.allocate((int) (size - from));
            channel.position(from);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // A read may return fewer bytes than asked; records appended after open wait for the next read.
            }
            byte[] bytes = buffer.array();
            int length = buffer.position();
            int offset = 0;
            if (start > 0L) {
                while (offset < length && bytes[offset] != '\n') offset++;
                offset = Math.min(length, offset + 1);
            }
            String text = new String(bytes, offset, length - offset, StandardCharsets.UTF_8);
            return new Window(List.of(text.split("\n")), start > 0L);
        }
    }

    private static Instant parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record Window(List<String> lines, boolean startsMidFile) {
    }

    private record Tally(int read, int malformed, boolean olderRecordsSkipped, int results, int blocked,
                         int errors, int verdicts, int stops, int corrections, int overridden,
                         int approvalsUsed, int controlChanges, Instant first, Instant last) {
    }
}
