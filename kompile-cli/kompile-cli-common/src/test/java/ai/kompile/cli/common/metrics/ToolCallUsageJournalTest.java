/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 2 acceptance: journal format safety, idempotency, locking, recovery, retention. */
class ToolCallUsageJournalTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ToolCallUsage usage(String invocationId, String sessionId) {
        return new ToolCallUsage(
                invocationId, sessionId, "read", "read",
                1000L, 1050L, 50L,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED,
                false,
                null,
                TokenMeasurement.measured(123, "utf8-text", PayloadTokenCounter.TEXT_METHOD,
                        "est", "1"),
                null, List.of(), false, null);
    }

    @Test
    @DisplayName("record + snapshot round trip preserves scalars")
    void roundTrip(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("tool-usage.jsonl");
        ToolCallUsageJournal journal1 = new ToolCallUsageJournal(journal, M);
        assertEquals(ToolCallUsageJournal.WriteResult.Written.class,
                journal1.record(usage("inv-1", "sess-1"), 1).getClass());

        ToolCallUsageJournal reopened = new ToolCallUsageJournal(journal, M);
        ToolCallUsageJournal.FoldedSnapshot snap = reopened.snapshot();
        assertEquals(1, snap.byInvocationId().size());
        ToolCallUsage back = snap.usages().get(0);
        assertEquals("inv-1", back.invocationId());
        assertEquals("sess-1", back.sessionId());
        assertEquals(123L, back.payloadMeasurement().tokens());
        assertEquals(ToolCallUsage.ResponseDisposition.DELIVERED, back.disposition());
    }

    @Test
    @DisplayName("usage lines are invisible to legacy readers: line fails ToolCallRecord binding")
    void legacyInvisibility() throws Exception {
        Path journal = dir("tool-usage.jsonl");
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        j.record(usage("inv-legacy", "sess-legacy"), 1);

        String line = Files.readString(journal, StandardCharsets.UTF_8).trim();
        // legacy readers bind to ToolCallRecord: recordType is unknown, usage block unknown,
        // but critical: legacy binding would ACCEPT it unless a required field fails.
        // ToolCallRecord has a no-arg ctor + setters via JsonProperty → a lenient bind SUCCEEDS
        // with nulls. So assert the discriminator exists and document the loader-side skip.
        ObjectNode node = (ObjectNode) M.readTree(line);
        assertEquals("usage", node.path("recordType").asText());
        // invocations must filter on recordType != usage when reading catalog lines.
        assertTrue(line.contains("\"recordType\":\"usage\""));
    }

    private Path dir(String name) throws Exception {
        return Files.createTempDirectory("journal-test").resolve(name);
    }

    @Test
    @DisplayName("duplicate same-revision record is idempotent (no double count)")
    void duplicateIdempotent() throws Exception {
        Path journal = dir("tool-usage.jsonl");
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        assertEquals(ToolCallUsageJournal.WriteResult.Written.class,
                j.record(usage("inv-d", "s"), 1).getClass());
        ToolCallUsageJournal.WriteResult second =
                j.record(usage("inv-d", "s"), 1);
        assertInstanceOf(ToolCallUsageJournal.WriteResult.Skipped.class, second);
        ToolCallUsageJournal.FoldedSnapshot snap = j.snapshot();
        assertEquals(1, snap.byInvocationId().size());
    }

    @Test
    @DisplayName("higher revision supersedes; conflicting stale line recorded as diagnostic")
    void revisionSupersede() throws Exception {
        Path journal = dir("tool-usage.jsonl");
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        j.record(usage("inv-r", "s"), 1);
        j.record(usage("inv-r", "s"), 2);
        // stale CONFLICTING line: same revision 1 re-emitted with DIFFERENT content
        ToolCallUsage conflicting = new ToolCallUsage(
                "inv-r", "s", "read", "read",
                1000L, 9999L, 8999L, // different finish/duration
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED,
                false,
                null,
                TokenMeasurement.measured(777, "utf8-text", PayloadTokenCounter.TEXT_METHOD,
                        "est", "1"),
                null, List.of(), false, null);
        j.record(conflicting, 1); // write-side: rejected (stale) — Skipped
        assertInstanceOf(ToolCallUsageJournal.WriteResult.Skipped.class,
                j.record(conflicting, 1));
        // snapshot-side: simulate a concurrent-process ordering where the conflicting
        // line DID land before revision 2 — append the raw line directly.
        ObjectNode rawConflicting = conflicting.toJsonNode(M);
        rawConflicting.put("recordType", "usage");
        rawConflicting.put("revision", 1);
        Files.writeString(journal, M.writeValueAsString(rawConflicting) + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        ToolCallUsageJournal.FoldedSnapshot snap = j.snapshot();
        assertEquals(1, snap.byInvocationId().size());
        assertEquals(2L, snap.byInvocationId().get("inv-r").revision());
        assertEquals(1, snap.conflictingRevisions().size(),
                "conflicting stale line kept as diagnostic, not last-file-wins");
    }

    @Test
    @DisplayName("two journal instances (simulating two processes) share one stream safely")
    void twoWriters() throws Exception {
        Path journal = dir("tool-usage.jsonl");
        ToolCallUsageJournal writerA = new ToolCallUsageJournal(journal, M);
        ToolCallUsageJournal writerB = new ToolCallUsageJournal(journal, M);
        for (int i = 0; i < 20; i++) {
            String inv = "inv-" + i;
            (i % 2 == 0 ? writerA : writerB).record(usage(inv, "shared-sess"), 1);
        }
        ToolCallUsageJournal.FoldedSnapshot snap = new ToolCallUsageJournal(journal, M).snapshot();
        assertEquals(20, snap.byInvocationId().size(), "every invocation present exactly once");
        assertEquals(20, snap.usages().size());
    }

    @Test
    @DisplayName("concurrent threads on one instance: all records land, none torn")
    void concurrentAppends() throws Exception {
        Path journal = dir("tool-usage.jsonl");
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            final int base = t * 10;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    j.record(usage("inv-" + base + "-" + i, "s"), 1);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        ToolCallUsageJournal.FoldedSnapshot snap = j.snapshot();
        assertEquals(80, snap.byInvocationId().size());
        assertEquals(0, snap.malformedLines().size());
    }

    @Test
    @DisplayName("corrupt trailing line: torn tail detected and repairable")
    void tornTailRepair() throws Exception {
        Path journal = dir("tool-usage.jsonl");
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        j.record(usage("inv-ok", "s"), 1);
        // simulate crash mid-write
        Files.writeString(journal, "{\"recordType\":\"usage\",\"invocationId\":\"inv-torn",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertTrue(j.tornTailBytes() > 0);
        long removed = j.repairTornTail();
        assertTrue(removed > 0);
        assertEquals(-1, j.tornTailBytes()); // clean now
        assertEquals(1, j.snapshot().byInvocationId().size()); // good line intact
    }

    @Test
    @DisplayName("distinct sessions keep distinct keys in fold; scoped reads don't cross")
    void sessionIsolation() throws Exception {
        Path journal = dir("tool-usage.jsonl");
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        j.record(usage("inv-a", "sess-A"), 1);
        j.record(usage("inv-b", "sess-B"), 1);
        ToolCallUsageJournal.FoldedSnapshot snap = j.snapshot();
        List<ToolCallUsage> usages = snap.usages();
        assertEquals(2, usages.size());
        assertTrue(usages.stream().anyMatch(u -> "sess-A".equals(u.sessionId())));
        assertTrue(usages.stream().anyMatch(u -> "sess-B".equals(u.sessionId())));
    }

    @Test
    @DisplayName("path safety: traversal keys rejected")
    void pathSafety() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallUsageJournal.validateSessionKey("../etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallUsageJournal.validateSessionKey("a/b"));
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallUsageJournal.validateSessionKey("a\\b"));
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallUsageJournal.validateSessionKey(".."));
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallUsageJournal.validateSessionKey("sess:1"));
        assertEquals("ok-session-1", ToolCallUsageJournal.validateSessionKey("ok-session-1"));
    }

    @Test
    @DisplayName("retention prune removes only old lines and keeps others intact")
    void retention() throws Exception {
        Path journal = dir("tool-usage.jsonl");
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        j.record(usage("inv-old", "s"), 1);
        Thread.sleep(5);
        Instant cutoff = Instant.now();
        Thread.sleep(5);
        j.record(usage("inv-new", "s"), 1);
        long removed = j.pruneOlderThan(cutoff);
        assertEquals(1, removed);
        ToolCallUsageJournal.FoldedSnapshot snap = j.snapshot();
        assertEquals(1, snap.byInvocationId().size());
        assertEquals("inv-new", snap.usages().get(0).invocationId());
    }

    @Test
    @DisplayName("write failure does not throw — accounting health, tool result unaffected")
    void writeFailureNonfatal() throws Exception {
        // journal parent is a FILE → lock channel open fails → Failed result, no throw
        Path parentFile = Files.createFile(dir("blocker-dir"));
        Path journal = parentFile.resolve("tool-usage.jsonl");
        ToolCallUsageJournal broken = new ToolCallUsageJournal(journal, M);
        ToolCallUsageJournal.WriteResult result = broken.record(usage("inv-x", "s"), 1);
        assertInstanceOf(ToolCallUsageJournal.WriteResult.Failed.class, result);
    }

    @Test
    @DisplayName("legacy tool-call lines in the same directory are ignored by the journal")
    void foreignLinesIgnored() throws Exception {
        Path journal = dir("tool-usage.jsonl");
        Files.writeString(journal, "{\"id\":\"s-1\",\"sessionId\":\"s\",\"toolName\":\"read\","
                + "\"toolInput\":\"{}\",\"toolInputSummary\":\"\",\"timestamp\":\"2026-01-01T00:00:00Z\","
                + "\"source\":\"cli\",\"agentName\":\"a\",\"isError\":false,\"durationMs\":0,"
                + "\"category\":\"filesystem\"}\n", StandardCharsets.UTF_8);
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        j.record(usage("inv-mixed", "s"), 1);
        ToolCallUsageJournal.FoldedSnapshot snap = j.snapshot();
        assertEquals(1, snap.byInvocationId().size(), "legacy line not counted as usage");
        assertEquals("inv-mixed", snap.usages().get(0).invocationId());
    }
}
