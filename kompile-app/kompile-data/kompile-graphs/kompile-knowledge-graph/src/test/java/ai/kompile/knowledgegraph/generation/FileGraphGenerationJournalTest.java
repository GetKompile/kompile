/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileGraphGenerationJournalTest {

    @TempDir
    Path tempDir;

    @Test
    void durableEntryReloadsAcrossJournalInstances() {
        FileGraphGenerationJournal first = journal();
        GraphGenerationJournal.Entry entry = building("factsheet_7", 0L);
        first.update("factsheet_7", ignored -> entry);

        FileGraphGenerationJournal restarted = journal();
        GraphGenerationJournal.Entry restored = restarted.get("factsheet_7").orElseThrow();

        assertEquals(entry, restored);
        assertEquals(1, restarted.list().size());
        try (var paths = Files.list(restarted.root())) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void twoInstancesSerializeRevisionCompareAndSwap() throws Exception {
        FileGraphGenerationJournal first = journal();
        FileGraphGenerationJournal second = journal();
        first.update("factsheet_7", ignored -> building("factsheet_7", 0L));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (FileGraphGenerationJournal candidate : new FileGraphGenerationJournal[]{first, second}) {
                executor.submit(() -> {
                    start.await();
                    try {
                        candidate.update("factsheet_7", current -> activateAtRevisionZero(current.orElseThrow()));
                        succeeded.incrementAndGet();
                    } catch (IllegalStateException conflict) {
                        conflicted.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, succeeded.get());
        assertEquals(1, conflicted.get());
        assertEquals(1L, first.get("factsheet_7").orElseThrow().pointer().revision());
    }

    @Test
    void updateCannotChangeLogicalScope() {
        FileGraphGenerationJournal journal = journal();
        assertThrows(IllegalArgumentException.class, () -> journal.update(
                "factsheet_7", ignored -> building("other", 0L)));
    }

    private FileGraphGenerationJournal journal() {
        return new FileGraphGenerationJournal(
                new ObjectMapper().findAndRegisterModules(), tempDir.resolve("journal"));
    }

    private static GraphGenerationJournal.Entry activateAtRevisionZero(
            GraphGenerationJournal.Entry existing) {
        if (existing.pointer().revision() != 0L) {
            throw new IllegalStateException("stale revision");
        }
        Instant now = Instant.parse("2026-01-01T00:01:00Z");
        GraphGeneration.Pointer pointer = new GraphGeneration.Pointer(
                7L, "factsheet_7", existing.generation().physicalGraphId(), "factsheet_7", 1L);
        GraphGeneration.Activation activation = new GraphGeneration.Activation(
                "factsheet_7", pointer.activePhysicalGraphId(), pointer.previousPhysicalGraphId(), 1L, now);
        return new GraphGenerationJournal.Entry(
                1, existing.generation(), pointer, GraphGenerationJournal.State.ACTIVE,
                existing.ownerJobId(), 0, new GraphGeneration.Validation(true, 0, 0, null), activation,
                existing.createdAt(), now, now.plusSeconds(60), null, "activate");
    }

    private static GraphGenerationJournal.Entry building(String logicalGraphId, long revision) {
        long factSheetId = "factsheet_7".equals(logicalGraphId) ? 7L : 8L;
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        GraphGeneration.Ref ref = new GraphGeneration.Ref(
                factSheetId, logicalGraphId, logicalGraphId + "~gen~g1", "g1",
                logicalGraphId, revision);
        GraphGeneration.Pointer pointer = new GraphGeneration.Pointer(
                factSheetId, logicalGraphId, logicalGraphId, null, revision);
        return new GraphGenerationJournal.Entry(
                1, ref, pointer, GraphGenerationJournal.State.BUILDING, "job-1", 0,
                null, null, now, now, now.plusSeconds(60), null, null);
    }
}
