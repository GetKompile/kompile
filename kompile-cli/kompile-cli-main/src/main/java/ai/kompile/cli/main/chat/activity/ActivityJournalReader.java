package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Bounded JSONL reader that ignores only malformed/truncated records, never earlier valid events. */
public final class ActivityJournalReader {
    private final ObjectMapper mapper;
    private final Path file;

    public ActivityJournalReader(Path file) {
        this(JsonUtils.standardMapper(), file);
    }

    ActivityJournalReader(ObjectMapper mapper, Path file) {
        this.mapper = mapper;
        this.file = file;
    }

    public ReadResult read(ActivityReadBudget budget) throws IOException {
        return readFrom(0L, budget);
    }

    public ReadResult readFrom(long offset, ActivityReadBudget budget) throws IOException {
        budget = budget == null ? ActivityReadBudget.DEFAULT : budget;
        if (file != null) ensureSafePath(file);
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return new ReadResult(List.of(), 0, false, Math.max(0L, offset),
                    ActivityCoverage.UNAVAILABLE, List.of());
        }
        long start = Math.max(0L, offset);
        long size = Files.size(file);
        if (start > size) start = 0L;
        List<ActivityEvent> events = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int malformed = 0;
        long consumed = start;
        boolean truncated = false;
        ensureSafePath(file);
        // Bound allocation BEFORE loading a line. readLine() may allocate an arbitrarily
        // large malformed record before a caller's byte budget can be checked.
        try (FileChannel reader = FileChannel.open(file, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(budget.maxBytes(), size - start));
            reader.position(start);
            while (buffer.hasRemaining() && reader.read(buffer) > 0) { }
            byte[] data = buffer.array();
            int loaded = buffer.position();
            int begin = 0;
            for (int i = 0; i < loaded; i++) {
                if (data[i] != '\n') continue;
                if (events.size() >= budget.maxRecords()) break;
                int lineBegin = begin;
                consumed = start + i + 1L;
                begin = i + 1;
                try {
                    String line = decodeUtf8(data, lineBegin, i - lineBegin);
                    if (line.isBlank()) continue;
                    var raw = mapper.readTree(line);
                    if (!raw.isObject() || !raw.hasNonNull("identity") || !raw.hasNonNull("eventId"))
                        throw new IOException("Missing event envelope");
                    events.add(mapper.treeToValue(raw, ActivityEvent.class));
                } catch (Exception malformedLine) {
                    malformed++;
                    if (warnings.size() < 8) warnings.add("Skipped malformed activity event");
                }
            }
            // Unterminated final records remain unconsumed, even if currently valid JSON:
            // the writer may still be appending to that record.
            truncated = consumed < size;
            if (begin < loaded && events.size() < budget.maxRecords()
                    && loaded == size - start && data[loaded - 1] != '\n') {
                malformed++;
                warnings.add("Incomplete final activity record retained for retry");
            } else if (truncated) warnings.add("Activity read budget reached; continue at nextOffset");
            if (offset > size) warnings.add("Activity read offset reset after journal truncation");
        }
        ActivityCoverage coverage = truncated || malformed > 0
                ? ActivityCoverage.PARTIAL : ActivityCoverage.COMPLETE;
        if (malformed > 8) warnings.add("Additional malformed activity events were suppressed");
        return new ReadResult(List.copyOf(events), malformed, truncated, consumed, coverage,
                warnings.stream().limit(16).toList());
    }

    static String decodeUtf8(byte[] data, int offset, int length) throws IOException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, offset, length)).toString();
    }

    static void ensureSafePath(Path file) throws IOException {
        for (Path path = file.toAbsolutePath().normalize(); path != null; path = path.getParent()) {
            if (Files.isSymbolicLink(path))
                throw new IOException("Activity journal path must not traverse symbolic links");
        }
    }

    public record ReadResult(List<ActivityEvent> events, int malformedRecords,
                             boolean truncated, long nextOffset, ActivityCoverage coverage,
                             List<String> warnings) {
        public ReadResult {
            events = events == null ? List.of() : List.copyOf(events);
            malformedRecords = Math.max(0, malformedRecords);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
