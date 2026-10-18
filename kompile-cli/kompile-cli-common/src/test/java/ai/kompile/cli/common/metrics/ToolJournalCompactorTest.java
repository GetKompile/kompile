package ai.kompile.cli.common.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ToolJournalCompactorTest {
    @TempDir Path root;

    private Path journal(String name, String content) throws Exception {
        Path directory = Files.createDirectories(root.resolve("tool-calls"));
        return Files.writeString(directory.resolve(name), content);
    }
    private void purged(String id) throws Exception {
        Files.writeString(Files.createDirectories(root.resolve(".purged")).resolve(id), "receipt");
    }

    @Test void dryRunHasNoWritesAndPreservesMalformedAndPartialRecords() throws Exception {
        purged("gone");
        String removed = "{\"sessionId\":\"gone\",\"futureField\":2}\n";
        String keep = "{\"sessionId\":\"live\"}\r\nmalformed\n{\"sessionId\":\"gone\"}";
        Path catalog = journal("all-tool-calls.jsonl", removed + keep);
        var preview = new ToolJournalCompactor(root).compact(true);
        assertEquals(1, preview.removedRecords());
        assertEquals(removed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, preview.reclaimableBytes());
        assertEquals(removed + keep, Files.readString(catalog));
        assertFalse(Files.exists(root.resolve(".retention.lock")));
        assertFalse(Files.exists(root.resolve("tool-calls/.all-tool-calls.jsonl.lock")));
        var actual = new ToolJournalCompactor(root).compact(false);
        assertEquals(preview.reclaimableBytes(), actual.reclaimableBytes());
        assertEquals(keep, Files.readString(catalog));
    }

    @Test void merelyArchivedPinnedAndReappearingSessionsRemainInBothJournals() throws Exception {
        purged("gone"); purged("pinned"); purged("revived");
        Files.writeString(root.resolve("pinned.pinned"), "pin");
        Files.writeString(root.resolve("revived.context.json"), "{}");
        Files.writeString(Files.createDirectories(root.resolve(".retired")).resolve("archived"), ".trash/archived/run");
        String text = "{\"sessionId\":\"gone\"}\n{\"sessionId\":\"pinned\"}\n{\"sessionId\":\"revived\"}\n{\"sessionId\":\"archived\"}\n";
        Path calls = journal("all-tool-calls.jsonl", text);
        Path usage = journal("tool-usage.jsonl", text);
        var result = new ToolJournalCompactor(root).compact(false);
        assertEquals(2, result.removedRecords());
        String expected = text.substring(text.indexOf('\n') + 1);
        assertEquals(expected, Files.readString(calls));
        assertEquals(expected, Files.readString(usage));
        assertEquals(0, new ToolJournalCompactor(root).compact(false).removedRecords());
    }

    @Test void reappearingSessionJournalAndInvocationDetailsPreventRemoval() throws Exception {
        purged("journal-only"); purged("details-only");
        String text = "{\"sessionId\":\"journal-only\"}\n{\"sessionId\":\"details-only\"}\n";
        Path calls = journal("all-tool-calls.jsonl", text);
        journal("journal-only.jsonl", "{}\n");
        Files.createDirectories(root.resolve("tool-calls/details/details-only"));
        assertEquals(0, new ToolJournalCompactor(root).compact(false).removedRecords());
        assertEquals(text, Files.readString(calls));
    }

    @Test void oversizedRecordFailsWithoutCommittingAndMalformedTrailingTokensArePreserved() throws Exception {
        purged("gone");
        String ambiguous = "{\"sessionId\":\"gone\"} {\"other\":1}\n";
        Path calls = journal("all-tool-calls.jsonl", ambiguous);
        assertEquals(0, new ToolJournalCompactor(root).compact(false).removedRecords());
        assertEquals(ambiguous, Files.readString(calls));
        String tooLarge = "x".repeat(8 * 1024 * 1024 + 1) + "\n";
        Files.writeString(calls, tooLarge);
        assertThrows(java.io.IOException.class, () -> new ToolJournalCompactor(root).compact(false));
        assertEquals(tooLarge, Files.readString(calls));
        try (var files = Files.list(root.resolve("tool-calls"))) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".compact-")));
        }
    }

    @Test void noPurgedReceiptMeansNoJournalRewrite() throws Exception {
        Path calls = journal("all-tool-calls.jsonl", "{\"sessionId\":\"one\"}\n");
        var before = Files.readAttributes(calls, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        assertTrue(new ToolJournalCompactor(root).compact(false).files().isEmpty());
        assertEquals(before, Files.readAttributes(calls, java.nio.file.attribute.BasicFileAttributes.class).fileKey());
    }

    @Test void catalogWritersAndCompactorCoordinateAcrossThreadsWithoutLosingLiveCalls() throws Exception {
        purged("gone"); journal("all-tool-calls.jsonl", "{\"sessionId\":\"gone\"}\n");
        var pool = Executors.newFixedThreadPool(3);
        try {
            var first = pool.submit(() -> { for (int i = 0; i < 100; i++) ToolJournalFiles.appendCatalog(root.resolve("tool-calls"), "live", "{\"sessionId\":\"live\",\"i\":" + i + "}\n"); return null; });
            var second = pool.submit(() -> { for (int i = 100; i < 200; i++) ToolJournalFiles.appendCatalog(root.resolve("tool-calls"), "live", "{\"sessionId\":\"live\",\"i\":" + i + "}\n"); return null; });
            var compact = pool.submit(() -> new ToolJournalCompactor(root).compact(false));
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS); compact.get(10, TimeUnit.SECONDS);
            assertEquals(200, Files.readAllLines(root.resolve("tool-calls/all-tool-calls.jsonl")).size());
            assertEquals(200, Files.readAllLines(root.resolve("tool-calls/live.jsonl")).size());
            assertThrows(java.io.IOException.class, () -> ToolJournalFiles.appendCatalog(root.resolve("tool-calls"), "gone", "{}\n"));
        } finally { pool.shutdownNow(); }
    }

    @Test void symlinkedJournalOrReceiptRootFailsClosed() throws Exception {
        Path outside = Files.writeString(root.resolve("outside"), "keep");
        purged("gone");
        Files.createDirectories(root.resolve("tool-calls"));
        Files.createSymbolicLink(root.resolve("tool-calls/all-tool-calls.jsonl"), outside);
        assertThrows(java.io.IOException.class, () -> new ToolJournalCompactor(root).compact(false));
        assertEquals("keep", Files.readString(outside));
    }

    @Test void usageWritersShareStableLeaseAcrossDifferentInstances() throws Exception {
        Path file = journal("tool-usage.jsonl", "");
        var mapper = ai.kompile.cli.common.util.JsonUtils.standardMapper();
        var a = new ToolCallUsageJournal(file, mapper);
        var b = new ToolCallUsageJournal(file, mapper);
        var usage = new ToolCallUsage("inv", "session", "read", "read", 1L, 2L, 1L,
                ToolCallUsage.ExecutionOutcome.EXECUTED, ToolCallUsage.ResponseDisposition.DELIVERED,
                false, null, null, null, List.of(), false, null);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> a.record(usage, 1));
            var two = pool.submit(() -> b.record(usage, 1));
            assertFalse(one.get(5, TimeUnit.SECONDS) instanceof ToolCallUsageJournal.WriteResult.Failed);
            assertFalse(two.get(5, TimeUnit.SECONDS) instanceof ToolCallUsageJournal.WriteResult.Failed);
            assertEquals(1, Files.readAllLines(file).size());
        } finally { pool.shutdownNow(); }
    }
}
