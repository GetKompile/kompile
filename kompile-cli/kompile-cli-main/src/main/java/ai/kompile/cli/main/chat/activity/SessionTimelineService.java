package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Bounded recent-first journal inspection. No second store, source execution or outcome inference. */
public final class SessionTimelineService {
    private final ActivityStorage storage;
    private final ObjectMapper mapper = JsonUtils.standardMapper();

    public SessionTimelineService(ActivityStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public SessionTimelinePage page(SessionTimelineQuery query) throws IOException {
        return page(query, null);
    }

    public SessionTimelinePage page(SessionTimelineQuery query, SessionTimelineCursor cursor)
            throws IOException {
        Objects.requireNonNull(query, "query");
        if (cursor != null && !query.equals(cursor.query()))
            throw new IllegalArgumentException("Timeline cursor does not match conversation/filter/budget");
        Path file = storage.eventsPath(query.identity());
        storage.ensureSafe(file);
        ActivityJournalReader.ensureSafePath(file);
        List<String> warnings = new ArrayList<>();
        warnings.add("Journal-only coverage: transcripts, tools, judge and diff stores are not projected here");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            if (cursor != null) warnings.add("Timeline cursor reset: journal is unavailable");
            return new SessionTimelinePage(List.of(), null, ActivityCoverage.UNAVAILABLE,
                    false, cursor != null, warnings);
        }
        try (FileChannel input = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = input.size();
            long snapshot = cursor == null ? size : cursor.snapshotSize();
            boolean reset = false;
            String generation;
            if (size < snapshot) {
                reset = true;
                snapshot = size;
            }
            generation = generation(file, input, snapshot);
            if (cursor != null && !generation.equals(cursor.generation())) {
                reset = true;
                snapshot = size;
                generation = generation(file, input, snapshot);
            }
            if (reset) warnings.add("Timeline cursor reset: journal generation changed; showing recent evidence");
            long end = cursor == null || reset ? snapshot : cursor.beforeOffset();
            long start = Math.max(0L, end - query.maxBytes());
            // The budget covers loaded bytes, including malformed/filtered records.
            byte[] data = load(input, start, (int) (end - start));
            // One boundary byte distinguishes a full exactly-budget-sized record from
            // a leading fragment. Generation checks read only fixed-size metadata probes.
            boolean alignedStart = start == 0L || load(input, start - 1L, 1)[0] == '\n';
            int right = data.length;
            long next = end;
            boolean partial = reset;
            List<ActivityEvent> events = new ArrayList<>();
            if (right > 0 && data[right - 1] != '\n') {
                // A snapshot can end in a writer's unfinished record. Do not parse it.
                int newline = right - 1;
                while (newline >= 0 && data[newline] != '\n') newline--;
                right = newline + 1;
                next = start + right;
                partial = true;
                warnings.add("Incomplete final activity record omitted from this snapshot");
            }
            while (right > 0 && events.size() < query.limit()) {
                int delimiter = right - 2;
                while (delimiter >= 0 && data[delimiter] != '\n') delimiter--;
                int left = delimiter + 1;
                if (delimiter < 0 && !alignedStart) {
                    // Leave the leading fragment to the next window, whose end is this
                    // record's end. If the whole window is one oversized record, skip
                    // a bounded fragment visibly rather than loop forever.
                    if (right == data.length) {
                        next = start;
                        warnings.add("Oversized activity record exceeds byte budget; fragment skipped");
                    }
                    partial = true;
                    break;
                }
                int length = right - left - 1;
                next = start + left;
                right = left;
                try {
                    String line = ActivityJournalReader.decodeUtf8(data, left, length);
                    if (line.isBlank()) continue;
                    JsonNode raw = mapper.readTree(line);
                    if (!raw.isObject() || !raw.hasNonNull("identity") || !raw.hasNonNull("eventId"))
                        throw new IOException("Missing event envelope");
                    ActivityEvent event = mapper.treeToValue(raw, ActivityEvent.class);
                    boolean knownTime = raw.hasNonNull("timestamp");
                    if (matches(query, event, knownTime)) {
                        events.add(event);
                        if (!knownTime) {
                            partial = true;
                            if (warnings.size() < 12) warnings.add("Unknown timestamp for event "
                                    + event.eventId() + "; epoch sentinel is not observed time");
                        }
                    }
                } catch (Exception invalid) {
                    partial = true;
                    if (warnings.size() < 12) warnings.add("Skipped malformed activity record at byte " + next);
                }
            }
            boolean truncated = next > 0;
            if (truncated) warnings.add("Older journal evidence remains; continue with nextCursor");
            if (input.size() < snapshot || !generation.equals(generation(file, input, snapshot)))
                throw new IOException("Activity journal changed during timeline read; retry recent query");
            events.sort(Comparator.comparing(ActivityEvent::timestamp)
                    .thenComparingLong(ActivityEvent::sequence).thenComparing(ActivityEvent::eventId));
            SessionTimelineCursor continuation = truncated
                    ? new SessionTimelineCursor(query, generation, snapshot, next) : null;
            return new SessionTimelinePage(events, continuation,
                    partial || truncated ? ActivityCoverage.PARTIAL : ActivityCoverage.COMPLETE,
                    truncated, reset, warnings);
        }
    }

    private static boolean matches(SessionTimelineQuery query, ActivityEvent event, boolean knownTime) {
        if (!query.identity().key().equals(event.identity().key())) return false;
        if (query.runId() != null && !query.runId().equals(event.runId())) return false;
        if (query.kind() != null) {
            String type = event.eventType();
            int separator = type.indexOf('.');
            String family = separator < 0 ? type : type.substring(0, separator);
            if (!query.kind().equals(type) && !query.kind().equals(family)) return false;
        }
        // Only explicit outcome metadata is evidence; event names are not outcomes.
        if (query.outcome() != null && !query.outcome().equals(event.attributes().get("outcome"))) return false;
        if ((query.from() != null || query.to() != null) && !knownTime) return false;
        return (query.from() == null || !event.timestamp().isBefore(query.from()))
                && (query.to() == null || !event.timestamp().isAfter(query.to()));
    }

    private static byte[] load(FileChannel input, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        input.position(offset);
        while (buffer.hasRemaining()) {
            int read = input.read(buffer);
            if (read < 0) throw new IOException("Activity journal truncated during read");
            if (read == 0) throw new IOException("Activity journal read made no progress");
        }
        return buffer.array();
    }

    private static String generation(Path file, FileChannel input, long snapshot) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((String.valueOf(attributes.fileKey()) + ":" + attributes.creationTime())
                    .getBytes(StandardCharsets.UTF_8));
            int length = (int) Math.min(snapshot, 256L);
            digest.update(load(input, 0L, length));
            digest.update(load(input, snapshot - length, length));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
