/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedGraphMutationJournalTest {

    @TempDir
    Path directory;

    @Test
    void overlaysAssertionsRetractionsTraversalAndGlobalRank() throws Exception {
        Path graphPath = baseGraph("overlay.kgraph");
        UnifiedGraphArchive.Link base;
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath);
             UnifiedGraphArchive.LinkCursor links = archive.openLinks()) {
            base = links.next();
        }
        GraphEntity c = SimpleGraphEntity.of("c", "PERSON", "C");
        UnifiedGraphArchive.Link asserted = link("r2", "b", "c", "KNOWS", 0.9);

        UnifiedGraphMutationJournal.AppendResult append =
                UnifiedGraphMutationJournal.appendAssertion(
                        graphPath, List.of(c), asserted, null);
        assertEquals(1, append.recordCount());
        assertFalse(append.compactionRecommended());

        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath)) {
            assertTrue(archive.hasJournalMutations());
            assertEquals(3, archive.entityCount());
            assertEquals(2, archive.linkCount());
            assertEquals(Set.of("r1", "r2"), archive.incidentLinks(
                    "b", GraphQueryEngine.Direction.BOTH, 10).stream()
                    .map(UnifiedGraphArchive.Link::id)
                    .collect(Collectors.toSet()));
            UnifiedGraph neighborhood = archive.materializeNeighborhood(
                    List.of("b"), List.of("b"), GraphQueryEngine.Direction.OUTGOING,
                    1, 10, 10);
            assertTrue(neighborhood.containsEntity("c"));
            assertTrue(neighborhood.relation("r2").isPresent());
            GraphQueryEngine.Result rank = new UnifiedGraphArchiveQueryEngine().query(
                    archive, new GraphQueryEngine.Query(GraphQueryEngine.Intent.RANK,
                            null, null, null, List.of(), null, 5, null,
                            HybridReasoner.Structural.PSL, null));
            assertTrue(rank.entities().stream().anyMatch(entity -> "c".equals(entity.id())));
        }
        assertTrue(UnifiedGraph.load(graphPath).relation("r2").isPresent());

        UnifiedGraphMutationJournal.appendRetractions(graphPath, List.of(base, asserted));
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath)) {
            assertEquals(3, archive.entityCount());
            assertEquals(0, archive.linkCount());
            assertTrue(archive.incidentLinks("b", GraphQueryEngine.Direction.BOTH, 10).isEmpty());
        }
        assertEquals(0, UnifiedGraph.load(graphPath).relationCount());
    }

    @Test
    void boundedNeighborhoodMarksLearningStaleWhileAJournalIsPending() throws Exception {
        Path graphPath = baseGraph("stale.kgraph");
        UnifiedGraphArchive.Link base;
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath);
             UnifiedGraphArchive.LinkCursor links = archive.openLinks()) {
            base = links.next();
            UnifiedGraph clean = archive.materializeNeighborhood(
                    List.of("a"), List.of("a"), GraphQueryEngine.Direction.BOTH, 1, 10, 10);
            assertFalse(Boolean.TRUE.equals(clean.meta().get("learning.reasoningStale")));
            assertFalse(Boolean.TRUE.equals(clean.meta().get("learning.kgeStale")));
        }

        UnifiedGraphMutationJournal.appendRetractions(graphPath, List.of(base));

        // Bounded reads must not report learned scores for the pre-retraction topology before compaction.
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath)) {
            UnifiedGraph neighborhood = archive.materializeNeighborhood(
                    List.of("a"), List.of("a"), GraphQueryEngine.Direction.BOTH, 1, 10, 10);
            assertEquals(true, neighborhood.meta().get("learning.reasoningStale"));
            assertEquals(true, neighborhood.meta().get("learning.kgeStale"));
        }
    }

    @Test
    void compactionPublishesOverlayAndRemovesSidecar() throws Exception {
        Path graphPath = baseGraph("compact.kgraph");
        GraphEntity c = SimpleGraphEntity.of("c", "PERSON", "C");
        UnifiedGraphArchive.Link asserted = link("r2", "b", "c", "KNOWS", 0.9);
        UnifiedGraphMutationJournal.appendAssertion(graphPath, List.of(c), asserted, null);

        assertTrue(UnifiedGraphMutationJournal.compact(graphPath));
        assertFalse(Files.exists(UnifiedGraphMutationJournal.pathFor(graphPath)));
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath)) {
            assertFalse(archive.hasJournalMutations());
            assertEquals(3, archive.entityCount());
            assertEquals(2, archive.linkCount());
            assertTrue(archive.hasAdjacencyIndex());
            archive.validateAdjacencyIndex();
        }
        assertTrue(UnifiedGraph.load(graphPath).relation("r2").isPresent());
    }

    @Test
    void replacementKeepsLogicalCountAndOverridesBaseTopology() throws Exception {
        Path graphPath = baseGraph("replace.kgraph");
        UnifiedGraphArchive.Link base;
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath);
             UnifiedGraphArchive.LinkCursor links = archive.openLinks()) {
            base = links.next();
        }
        UnifiedGraphArchive.Link replacement = link("r1", "b", "a", "REVERSED", 0.8);

        UnifiedGraphMutationJournal.appendAssertion(
                graphPath, List.of(), replacement, base);

        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath)) {
            assertEquals(1, archive.linkCount());
            assertEquals(List.of("REVERSED"), archive.incidentLinks(
                    "b", GraphQueryEngine.Direction.OUTGOING, 10).stream()
                    .map(UnifiedGraphArchive.Link::type).toList());
            assertTrue(new UnifiedGraphArchiveQueryEngine().query(
                    archive, new GraphQueryEngine.Query(GraphQueryEngine.Intent.RANK,
                            null, null, null, List.of(), null, 2, null,
                            HybridReasoner.Structural.PSL, null)).entities().size() == 2);
        }
        UnifiedGraph materialized = UnifiedGraph.load(graphPath);
        assertEquals("REVERSED", materialized.relation("r1").orElseThrow().type());
        assertTrue(materialized.vectorLayer("relation-analysis").contains("r1"));
    }

    @Test
    void compactionRemovesVectorsForTombstonedBaseRelations() throws Exception {
        Path graphPath = baseGraph("tombstone-vector.kgraph");
        UnifiedGraphArchive.Link base;
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath);
             UnifiedGraphArchive.LinkCursor links = archive.openLinks()) {
            base = links.next();
        }
        UnifiedGraphMutationJournal.appendRetractions(graphPath, List.of(base));

        assertTrue(UnifiedGraphMutationJournal.compact(graphPath));

        UnifiedGraph compacted = UnifiedGraph.load(graphPath);
        assertTrue(compacted.relation("r1").isEmpty());
        assertFalse(compacted.vectorLayer("relation-analysis").contains("r1"));
    }

    @Test
    void rejectsCorruptedTransactionInsteadOfIgnoringIt() throws Exception {
        Path graphPath = baseGraph("corrupt.kgraph");
        UnifiedGraphMutationJournal.appendAssertion(
                graphPath, List.of(), link("r2", "a", "b", "RELATED", 0.7), null);
        Path journal = UnifiedGraphMutationJournal.pathFor(graphPath);
        try (RandomAccessFile file = new RandomAccessFile(journal.toFile(), "rw")) {
            file.seek(file.length() - 1);
            int value = file.readUnsignedByte();
            file.seek(file.length() - 1);
            file.writeByte(value ^ 0x01);
        }
        assertThrows(IOException.class, () -> UnifiedGraphMutationJournal.load(graphPath));
        assertThrows(IOException.class, () -> UnifiedGraphArchive.open(graphPath));
    }

    @Test
    void migrationIncludesOverlayWithoutMutatingSourceAndDirectSaveClearsIt() throws Exception {
        Path source = baseGraph("migration-source.kgraph");
        UnifiedGraphMutationJournal.appendAssertion(
                source, List.of(), link("r2", "a", "b", "RELATED", 0.7), null);
        Path target = directory.resolve("migration-target.kgraph");

        GraphArchiveMigrator.migrate(source, target);

        assertTrue(UnifiedGraphMutationJournal.exists(source));
        assertFalse(UnifiedGraphMutationJournal.exists(target));
        assertTrue(UnifiedGraph.load(target).relation("r2").isPresent());
        UnifiedGraph.load(source).saveCompact(source);
        assertFalse(UnifiedGraphMutationJournal.exists(source));
        assertTrue(UnifiedGraph.load(source).relation("r2").isPresent());
    }

    @Test
    void assertionTombstonesTheRelationsItReplacesInTheSameTransaction() throws Exception {
        Path graphPath = baseGraph("supersede.kgraph");
        UnifiedGraphArchive.Link base;
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath);
             UnifiedGraphArchive.LinkCursor links = archive.openLinks()) {
            base = links.next();
        }
        UnifiedGraphArchive.Link respelled = link("r2", "a", "b", "Knows", 0.9);

        // Listing the assertion itself among the replaced links must not tombstone it.
        UnifiedGraphMutationJournal.AppendResult append = UnifiedGraphMutationJournal.appendAssertion(
                graphPath, List.of(), respelled, null, List.of(base, respelled));

        assertEquals(1, append.recordCount());
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath)) {
            assertEquals(1, archive.linkCount());
            assertEquals(List.of("r2"), archive.incidentLinks(
                    "a", GraphQueryEngine.Direction.OUTGOING, 10).stream()
                    .map(UnifiedGraphArchive.Link::id).toList());
        }
        UnifiedGraph materialized = UnifiedGraph.load(graphPath);
        assertTrue(materialized.relation("r1").isEmpty());
        assertEquals("Knows", materialized.relation("r2").orElseThrow().type());
    }

    @Test
    void retractedRelationTypesSurviveLoadAndCompaction() throws Exception {
        Path graphPath = baseGraph("retracted-vocabulary.kgraph");
        UnifiedGraphArchive.Link base;
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath);
             UnifiedGraphArchive.LinkCursor links = archive.openLinks()) {
            base = links.next();
        }

        UnifiedGraphMutationJournal.appendRetractions(graphPath, List.of(base));

        assertEquals(Set.of("KNOWS"), UnifiedGraphMutationJournal.load(graphPath).retractedRelationTypes());
        UnifiedGraph pending = UnifiedGraph.load(graphPath);
        assertEquals(0, pending.relationCount());
        assertEquals(Set.of("KNOWS"), pending.retractedRelationTypes());

        assertTrue(UnifiedGraphMutationJournal.compact(graphPath));
        assertFalse(UnifiedGraphMutationJournal.exists(graphPath));
        assertEquals(Set.of("KNOWS"), UnifiedGraph.load(graphPath).retractedRelationTypes());
    }

    private Path baseGraph(String name) throws Exception {
        Path path = directory.resolve(name);
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(SimpleGraphEntity.of("a", "PERSON", "A"))
                .addEntity(SimpleGraphEntity.of("b", "PERSON", "B"))
                .addRelation(SimpleGraphRelation.directed("r1", "a", "b", "KNOWS", 1.0));
        graph.putRelationVector("relation-analysis", "r1", new double[]{0.2, 0.8});
        graph.saveCompact(path);
        return path;
    }

    private static UnifiedGraphArchive.Link link(
            String id, String source, String target, String type, double confidence) {
        return new UnifiedGraphArchive.Link(-1, id, source, target, type,
                confidence, confidence, true, true, Set.of(), null,
                Map.of("asserted", true), null);
    }
}
