package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.RandomAccessFile;
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
        long bytes = 0L;
        boolean truncated = false;
        try (RandomAccessFile reader = new RandomAccessFile(file.toFile(), "r")) {
            reader.seek(start);
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                long lineStart = consumed;
                long lineBytes = reader.getFilePointer() - consumed;
                if (bytes + lineBytes > budget.maxBytes()) {
                    truncated = true;
                    break;
                }
                bytes += lineBytes;
                consumed = reader.getFilePointer();
                String line = new String(rawLine.getBytes(StandardCharsets.ISO_8859_1),
                        StandardCharsets.UTF_8);
                if (line.isBlank()) continue;
                if (events.size() >= budget.maxRecords()) {
                    truncated = true;
                    break;
                }
                try {
                    ActivityEvent event = mapper.readValue(line, ActivityEvent.class);
                    if (event.identity() != null) events.add(event);
                    else malformed++;
                } catch (Exception malformedLine) {
                    malformed++;
                    if (warnings.size() < 8) warnings.add("Skipped malformed activity event");
                    if (reader.getFilePointer() >= size && finalLineHasNoNewline(reader, size)) {
                        truncated = true;
                        consumed = lineStart;
                    }
                }
            }
        }
        ActivityCoverage coverage = truncated || malformed > 0
                ? ActivityCoverage.PARTIAL : ActivityCoverage.COMPLETE;
        if (malformed > 8) warnings.add("Additional malformed activity events were suppressed");
        return new ReadResult(List.copyOf(events), malformed, truncated, consumed, coverage,
                warnings.stream().limit(16).toList());
    }

    private static boolean finalLineHasNoNewline(RandomAccessFile reader, long size) {
        if (size <= 0L) return false;
        try {
            long position = reader.getFilePointer();
            reader.seek(size - 1L);
            int last = reader.read();
            reader.seek(position);
            return last != '\n';
        } catch (IOException ignored) {
            return true;
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
