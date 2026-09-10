/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.generation;

import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.InMemoryMatrixGraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphGenerationCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    void validatesActivatesIdempotentlyAndRollsBack() {
        InMemoryMatrixGraphStore store = new InMemoryMatrixGraphStore();
        store.createGraph("factsheet_7", 7L);
        store.addNode("factsheet_7", node("old"));
        GraphGenerationCoordinator coordinator = coordinator(store, at("2026-01-01T00:00:00Z"));

        GraphGeneration.Ref ref = coordinator.begin(7L, "factsheet_7", "g1", "job-1");
        store.addNode(ref.physicalGraphId(), node("new"));
        assertTrue(store.getNode("factsheet_7", "old").isPresent());
        assertTrue(coordinator.validate(ref).valid());

        GraphGeneration.Activation activated = coordinator.activate(ref, "activate-1");
        GraphGeneration.Activation retried = coordinator.activate(ref, "activate-retry");
        assertEquals(activated, retried);
        assertTrue(store.getNode("factsheet_7", "new").isPresent());

        GraphGeneration.Activation rolledBack = coordinator.rollback(
                7L, "factsheet_7", activated.revision(), "rollback-1");
        assertEquals(activated.revision() + 1L, rolledBack.revision());
        assertTrue(store.getNode("factsheet_7", "old").isPresent());
    }

    @Test
    void validationFailureNeverActivatesAndAbortPreservesActiveGraph() {
        InMemoryMatrixGraphStore store = new InMemoryMatrixGraphStore();
        store.createGraph("factsheet_7", 7L);
        store.addNode("factsheet_7", node("old"));
        GraphGenerationCoordinator coordinator = coordinator(store, at("2026-01-01T00:00:00Z"));
        GraphGeneration.Ref ref = coordinator.begin(7L, "factsheet_7", "g1", "job-1");
        store.addEdge(ref.physicalGraphId(), "missing-a", "missing-b", 1.0, "RELATED", false);

        assertFalse(coordinator.validate(ref).valid());
        assertThrows(IllegalStateException.class, () -> coordinator.activate(ref, "activate-1"));
        coordinator.abort(ref, new IllegalStateException("crawl failed"));

        assertEquals(GraphGenerationJournal.State.ABORTED,
                coordinator.status("factsheet_7").orElseThrow().state());
        assertTrue(store.getNode("factsheet_7", "old").isPresent());
        assertTrue(store.loadGraph(ref.physicalGraphId()).isEmpty());
    }

    @Test
    void staleLeaseIsAbortedDuringRestartRecovery() {
        InMemoryMatrixGraphStore store = new InMemoryMatrixGraphStore();
        store.createGraph("factsheet_7", 7L);
        GraphGenerationCoordinator first = coordinator(store, at("2026-01-01T00:00:00Z"));
        GraphGeneration.Ref ref = first.begin(7L, "factsheet_7", "g1", "job-1");
        first.acquire(ref.target(), "job-1"); // simulates a subprocess crash before request release

        GraphGenerationCoordinator restarted = coordinator(store, at("2026-01-01T01:00:00Z"));
        restarted.recover();

        assertEquals(GraphGenerationJournal.State.ABORTED,
                restarted.status("factsheet_7").orElseThrow().state());
        assertTrue(store.loadGraph(ref.physicalGraphId()).isEmpty());
    }

    @Test
    void differentJobCannotTakeOverLiveGeneration() {
        InMemoryMatrixGraphStore store = new InMemoryMatrixGraphStore();
        store.createGraph("factsheet_7", 7L);
        GraphGenerationCoordinator coordinator = coordinator(store, at("2026-01-01T00:00:00Z"));
        coordinator.begin(7L, "factsheet_7", "g1", "job-1");

        assertThrows(IllegalStateException.class,
                () -> coordinator.begin(7L, "factsheet_7", "g1", "job-2"));
        assertThrows(IllegalStateException.class,
                () -> coordinator.begin(7L, "factsheet_7", "g2", "job-2"));
    }

    @Test
    void validationSealsNewWritersAndWaitsForAdmittedWriter() throws Exception {
        InMemoryMatrixGraphStore store = new InMemoryMatrixGraphStore();
        store.createGraph("factsheet_7", 7L);
        GraphGenerationCoordinator coordinator = coordinator(store, at("2026-01-01T00:00:00Z"));
        GraphGeneration.Ref ref = coordinator.begin(7L, "factsheet_7", "g1", "job-1");
        store.addNode(ref.physicalGraphId(), node("new"));
        coordinator.acquire(ref.target(), "job-1");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<GraphGeneration.Validation> validation = executor.submit(() -> coordinator.validate(ref));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (coordinator.status("factsheet_7").orElseThrow().state()
                    != GraphGenerationJournal.State.SEALING && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            assertEquals(GraphGenerationJournal.State.SEALING,
                    coordinator.status("factsheet_7").orElseThrow().state());
            assertThrows(IllegalStateException.class,
                    () -> coordinator.acquire(ref.target(), "job-1"));
            assertThrows(IllegalStateException.class,
                    () -> coordinator.begin(7L, "factsheet_7", "g2", "job-2"));
            assertThrows(IllegalStateException.class,
                    () -> coordinator.begin(7L, "factsheet_7", "g1", "job-1"));
            assertFalse(validation.isDone());

            coordinator.release(ref.target(), "job-1");
            assertTrue(validation.get(5, TimeUnit.SECONDS).valid());
        } finally {
            executor.shutdownNow();
        }
    }

    private GraphGenerationCoordinator coordinator(InMemoryMatrixGraphStore store, Clock clock) {
        FileGraphGenerationJournal journal = new FileGraphGenerationJournal(
                new ObjectMapper().findAndRegisterModules(), tempDir.resolve("journal"));
        return new GraphGenerationCoordinator(store, journal, clock, Duration.ofMinutes(30));
    }

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static MatrixGraphNode node(String id) {
        return MatrixGraphNode.builder().nodeId(id).nodeType("ENTITY").title(id).factSheetId(7L).build();
    }
}
