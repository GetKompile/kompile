package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class SessionTimelineFoundationTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = JsonUtils.standardMapper();

    private ActivityIdentity identity() {
        return ActivityIdentity.conversation("timeline", temp);
    }

    private ActivityEvent event(int sequence) {
        return new ActivityEvent(1, "event-" + sequence, identity(), "run-1", "actor", "",
                sequence, Instant.parse("2026-09-01T00:00:00Z").plusSeconds(sequence),
                "tool.end", "op-" + sequence, List.of(), null,
                Map.of("outcome", "success", "text", "日本語 café"));
    }

    private Path journal(ActivityStorage storage, int count) throws Exception {
        Path path = storage.eventsPath(identity());
        Files.createDirectories(path.getParent());
        StringBuilder data = new StringBuilder();
        for (int i = 1; i <= count; i++) data.append(line(i));
        Files.writeString(path, data);
        return path;
    }

    private String line(int sequence) throws Exception {
        return mapper.writeValueAsString(event(sequence)) + "\n";
    }

    @Test void readerRecordLimitNeverConsumesFirstUnreturnedRecord() throws Exception {
        ActivityStorage storage = new ActivityStorage(temp);
        Path path = journal(storage, 3);
        ActivityJournalReader reader = new ActivityJournalReader(path);
        long offset = 0;
        for (int i = 1; i <= 3; i++) {
            var page = reader.readFrom(offset, new ActivityReadBudget(16384, 1));
            assertEquals(List.of((long) i), page.events().stream().map(ActivityEvent::sequence).toList());
            offset += line(i).getBytes(StandardCharsets.UTF_8).length;
            assertEquals(offset, page.nextOffset());
            assertEquals(i < 3, page.truncated());
        }
    }

    @Test void readerByteBoundaryUtf8AndPartialFinalRecordsAreRetryable() throws Exception {
        ActivityStorage storage = new ActivityStorage(temp);
        Path path = journal(storage, 2);
        ActivityJournalReader reader = new ActivityJournalReader(path);
        int firstBytes = line(1).getBytes(StandardCharsets.UTF_8).length;
        var shortPage = reader.read(new ActivityReadBudget(firstBytes - 1, 10));
        assertTrue(shortPage.events().isEmpty());
        assertEquals(0, shortPage.nextOffset());
        var exact = reader.read(new ActivityReadBudget(firstBytes, 10));
        assertEquals(1, exact.events().size());
        assertEquals(firstBytes, exact.nextOffset());
        assertEquals("日本語 café", exact.events().get(0).attributes().get("text"));
        long complete = Files.size(path);
        String third = line(3);
        Files.writeString(path, third.substring(0, third.length() - 1), StandardOpenOption.APPEND);
        var partial = reader.readFrom(complete, new ActivityReadBudget(16384, 10));
        assertTrue(partial.events().isEmpty());
        assertEquals(complete, partial.nextOffset());
        assertTrue(partial.truncated());
        Files.writeString(path, "\n", StandardOpenOption.APPEND);
        assertEquals(3, reader.readFrom(complete, new ActivityReadBudget(16384, 10)).events().get(0).sequence());
    }

    @Test void readerBoundsOversizedLinesAndReportsMalformedCompleteLines() throws Exception {
        ActivityStorage storage = new ActivityStorage(temp);
        Path path = journal(storage, 0);
        Files.writeString(path, "{bad}\n" + line(1) + "x".repeat(1024 * 1024));
        var malformed = new ActivityJournalReader(path).read(new ActivityReadBudget(16384, 10));
        assertEquals(1, malformed.events().size());
        assertEquals(1, malformed.malformedRecords());
        assertTrue(malformed.truncated());
        assertEquals(("{bad}\n" + line(1)).getBytes(StandardCharsets.UTF_8).length, malformed.nextOffset());
        var blocked = new ActivityJournalReader(path).readFrom(malformed.nextOffset(), new ActivityReadBudget(16, 10));
        assertEquals(malformed.nextOffset(), blocked.nextOffset());
        assertTrue(blocked.warnings().stream().anyMatch(w -> w.contains("budget")));
    }

    @Test void concurrentWritersAndRestartRecoverPersistedTail() throws Exception {
        ActivityStorage storage = new ActivityStorage(temp);
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> writes = new ArrayList<>();
            for (int writer = 0; writer < 4; writer++) {
                writes.add(pool.submit(() -> {
                    ActivityRecorder recorder = new ActivityRecorder(storage);
                    for (int i = 0; i < 20; i++) if (!recorder.append(event(0))) return false;
                    return recorder.warnings().isEmpty();
                }));
            }
            for (Future<Boolean> write : writes) assertTrue(write.get());
        } finally {
            pool.shutdownNow();
        }
        Files.writeString(storage.eventsPath(identity()), "{partial", StandardOpenOption.APPEND);
        assertTrue(new ActivityRecorder(storage).append(event(0)));
        var events = new ActivityJournalReader(storage.eventsPath(identity()))
                .read(new ActivityReadBudget(1024 * 1024, 100)).events();
        assertEquals(81, events.size());
        for (int i = 0; i < events.size(); i++) assertEquals(i + 1L, events.get(i).sequence());
    }

    @Test void boundedTailRecoveryWarnsRatherThanInventingSequence() throws Exception {
        ActivityStorage storage = new ActivityStorage(temp);
        Path path = journal(storage, 1);
        Files.writeString(path, "x".repeat(ActivityRecorder.SEQUENCE_TAIL_BYTES + 1), StandardOpenOption.APPEND);
        long size = Files.size(path);
        ActivityRecorder recorder = new ActivityRecorder(storage);
        assertFalse(recorder.append(event(0)));
        assertEquals(size, Files.size(path));
        assertTrue(recorder.warnings().stream().anyMatch(w -> w.contains("Cannot determine activity sequence")));
    }

    @Test void longHistoryRecentPageAndRestartUseBoundedTail() throws Exception {
        ActivityStorage storage = new ActivityStorage(temp);
        Path path = journal(storage, 4000);
        assertTrue(Files.size(path) > ActivityRecorder.SEQUENCE_TAIL_BYTES);
        assertTrue(new ActivityRecorder(storage).append(event(0)));
        var query = new SessionTimelineQuery(identity(), null, null, null, null, null, 5, 4096);
        var page = new SessionTimelineService(storage).page(query);
        assertEquals(List.of(3997L, 3998L, 3999L, 4000L, 4001L),
                page.events().stream().map(ActivityEvent::sequence).sorted().toList());
        assertNotNull(page.nextCursor());
        assertTrue(page.truncated());
        assertTrue(page.warnings().get(0).contains("Journal-only"));
    }

    @Test void continuationHasNoDropsAndIgnoresNewAppends() throws Exception {
        ActivityStorage storage = new ActivityStorage(temp);
        Path path = journal(storage, 7);
        var service = new SessionTimelineService(storage);
        var query = new SessionTimelineQuery(identity(), null, null, null, null, null, 2, 4096);
        var page = service.page(query);
        assertEquals(List.of(6L, 7L), page.events().stream().map(ActivityEvent::sequence).toList());
        Files.writeString(path, line(8), StandardOpenOption.APPEND);
        List<Long> seen = new ArrayList<>(page.events().stream().map(ActivityEvent::sequence).toList());
        while (page.nextCursor() != null) {
            page = service.page(query, page.nextCursor());
            assertFalse(page.reset());
            seen.addAll(page.events().stream().map(ActivityEvent::sequence).toList());
        }
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), seen.stream().sorted().toList());
    }

    @Test void byteWindowsPreserveLeadingFragmentsForNextPage() throws Exception {
        ActivityStorage storage = new ActivityStorage(temp);
        journal(storage, 9);
        var query = new SessionTimelineQuery(identity(), null, null, null, null, null, 50,
                line(9).getBytes(StandardCharsets.UTF_8).length * 2 + 20);
        var service = new SessionTimelineService(storage);
        var page = service.page(query);
        List<Long> seen = new ArrayList<>();
        int pages = 0;
        do {
            assertTrue(++pages < 12, "continuation must make progress");
            seen.addAll(page.events().stream().map(ActivityEvent::sequence).toList());
            if (page.nextCursor() == null) break;
            page = service.page(query, page.nextCursor());
        } while (true);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), seen.stream().sorted().toList());
    }

    @Test void exactFiltersRejectUnknownTimeOutcomeAndCursorMismatch() throws Exception {
        ActivityStorage storage = new ActivityStorage(temp);
        Path path = journal(storage, 3);
        var unknown = mapper.valueToTree(event(4));
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown).remove("timestamp");
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown).putObject("attributes");
        Files.writeString(path, mapper.writeValueAsString(unknown) + "\n", StandardOpenOption.APPEND);
        var service = new SessionTimelineService(storage);
        var query = new SessionTimelineQuery(identity(), "run-1", "tool.end", "success",
                event(2).timestamp(), event(3).timestamp(), 1, 4096);
        var page = service.page(query);
        assertEquals(List.of(3L), page.events().stream().map(ActivityEvent::sequence).toList());
        var continuation = service.page(query, page.nextCursor());
        assertEquals(List.of(2L), continuation.events().stream().map(ActivityEvent::sequence).toList());
        var changed = new SessionTimelineQuery(identity(), "run-2", "tool.end", "success",
                event(2).timestamp(), event(3).timestamp(), 1, 4096);
        assertThrows(IllegalArgumentException.class, () -> service.page(changed, page.nextCursor()));
        assertThrows(IllegalArgumentException.class, () -> service.page(
                SessionTimelineQuery.recent(ActivityIdentity.conversation("other", temp)), page.nextCursor()));
        var all = service.page(SessionTimelineQuery.recent(identity()));
        assertTrue(all.warnings().stream().anyMatch(w -> w.contains("Unknown timestamp")));
        assertThrows(IllegalArgumentException.class, () -> new SessionTimelineQuery(identity(),
                null, null, null, event(3).timestamp(), event(2).timestamp(), 5, 4096));
    }

    @Test void kindSelectsExactFamilyOrExactEventTypeNotSubstring() throws Exception {
        ActivityStorage storage = new ActivityStorage(temp);
        journal(storage, 3);
        var service = new SessionTimelineService(storage);
        for (String kind : List.of("tool", "tool.end")) {
            var query = new SessionTimelineQuery(identity(), null, kind, null, null, null, 50, 4096);
            assertEquals(3, service.page(query).events().size());
        }
        for (String kind : List.of("too", "TOOL", "tool.*", "tool.start")) {
            var query = new SessionTimelineQuery(identity(), null, kind, null, null, null, 50, 4096);
            assertTrue(service.page(query).events().isEmpty());
        }
    }

    @Test void exactTimelineByteBoundaryDoesNotDropWholeRecord() throws Exception {
        ActivityStorage storage = new ActivityStorage(temp);
        journal(storage, 3);
        var service = new SessionTimelineService(storage);
        var query = new SessionTimelineQuery(identity(), null, null, null, null, null, 50,
                line(3).getBytes(StandardCharsets.UTF_8).length);
        var page = service.page(query);
        assertEquals(List.of(3L), page.events().stream().map(ActivityEvent::sequence).toList());
        assertEquals(List.of(2L), service.page(query, page.nextCursor()).events().stream()
                .map(ActivityEvent::sequence).toList());
    }

    @Test void timelineMalformedUtf8AndPartialSuffixRemainVisible() throws Exception {
        ActivityStorage storage = new ActivityStorage(temp);
        Path path = journal(storage, 2);
        Files.write(path, new byte[] {(byte) 0xc3, (byte) 0x28, '\n'}, StandardOpenOption.APPEND);
        Files.writeString(path, "{partial", StandardOpenOption.APPEND);
        var page = new SessionTimelineService(storage).page(SessionTimelineQuery.recent(identity()));
        assertEquals(List.of(1L, 2L), page.events().stream().map(ActivityEvent::sequence).toList());
        assertEquals(ActivityCoverage.PARTIAL, page.coverage());
        assertTrue(page.warnings().stream().anyMatch(w -> w.contains("Incomplete")));
        assertTrue(page.warnings().stream().anyMatch(w -> w.contains("malformed")));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionTimelineCursor(SessionTimelineQuery.recent(identity()), "bad", 1, 2));
    }

    @Test void truncationReplacementAndUnsafePathsAreVisible() throws Exception {
        ActivityStorage storage = new ActivityStorage(temp);
        Path path = journal(storage, 5);
        var query = new SessionTimelineQuery(identity(), null, null, null, null, null, 1, 4096);
        var service = new SessionTimelineService(storage);
        var cursor = service.page(query).nextCursor();
        Files.writeString(path, line(1));
        var reset = service.page(query, cursor);
        assertTrue(reset.reset());
        assertTrue(reset.warnings().stream().anyMatch(w -> w.contains("reset")));
        journal(storage, 5);
        cursor = service.page(query).nextCursor();
        Path replacement = path.resolveSibling("replacement.jsonl");
        Files.writeString(replacement, line(7).repeat(6));
        Files.move(replacement, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertTrue(service.page(query, cursor).reset());
        Path linked = temp.resolve("linked");
        Files.createSymbolicLink(linked, path.getParent());
        assertThrows(java.io.IOException.class, () -> new ActivityJournalReader(
                linked.resolve(path.getFileName())).read(ActivityReadBudget.DEFAULT));
    }
}
