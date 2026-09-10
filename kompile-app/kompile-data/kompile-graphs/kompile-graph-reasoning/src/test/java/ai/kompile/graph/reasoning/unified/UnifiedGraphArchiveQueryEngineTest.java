/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedGraphArchiveQueryEngineTest {

    @TempDir
    Path directory;

    @Test
    void executesGlobalQueriesWithoutMaterializingRelationCollections() throws Exception {
        Path path = directory.resolve("streaming.kgraph");
        graph().saveCompact(path);

        UnifiedGraphArchiveQueryEngine engine = new UnifiedGraphArchiveQueryEngine();
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(path)) {
            assertTrue(archive.hasAdjacencyIndex());

            GraphQueryEngine.Result overview = engine.query(archive, query(GraphQueryEngine.Intent.OVERVIEW, null));
            assertEquals(3, overview.data().get("entityCount"));
            assertEquals(2, overview.data().get("relationCount"));
            assertEquals(true, overview.data().get("mmapAdjacency"));

            GraphQueryEngine.Result schema = engine.query(archive, query(GraphQueryEngine.Intent.SCHEMA, null));
            assertTrue(((Map<?, ?>) schema.data().get("entityTypes")).containsKey("PERSON"));
            assertTrue(((Map<?, ?>) schema.data().get("relationTypes")).containsKey("KNOWS"));

            GraphQueryEngine.Result search = engine.query(archive,
                    query(GraphQueryEngine.Intent.SEARCH, "Alpha"));
            assertEquals("alpha", search.entities().get(0).id());

            GraphQueryEngine.Result relations = engine.query(archive,
                    query(GraphQueryEngine.Intent.RELATIONS, null));
            assertEquals(2, relations.relations().size());
            assertEquals("Alpha", relations.relations().get(0).sourceLabel());

            GraphQueryEngine.Result timeline = engine.query(archive,
                    query(GraphQueryEngine.Intent.TIMELINE, null));
            assertEquals(List.of("works"), timeline.relations().stream().map(
                    GraphQueryEngine.RelationView::id).toList());

            GraphQueryEngine.Result facts = engine.query(archive,
                    query(GraphQueryEngine.Intent.FACTS, null));
            assertFalse(((List<?>) facts.data().get("facts")).isEmpty());

            GraphQueryEngine.Query similarQuery = new GraphQueryEngine.Query(
                    GraphQueryEngine.Intent.SIMILAR, "alpha", null, null, List.of(), null, 2,
                    null, HybridReasoner.Structural.PSL, null);
            GraphQueryEngine.Result similar = engine.query(archive, similarQuery);
            assertEquals("beta", similar.entities().get(0).id());
            assertEquals("MMAP_PERSONALIZED_PAGERANK", similar.data().get("structuralEngine"));

            GraphQueryEngine.Result rank = engine.query(archive, query(GraphQueryEngine.Intent.RANK, null));
            assertFalse(rank.entities().isEmpty());
            assertTrue(rank.summary().contains("mmap PageRank"));

            GraphQueryEngine.Result assets = engine.query(archive, query(GraphQueryEngine.Intent.ASSETS, null));
            assertFalse(((List<?>) assets.data().get("vectorLayers")).isEmpty());
            assertFalse(((List<?>) assets.data().get("artifacts")).isEmpty());

            GraphQueryEngine.Result artifact = engine.query(archive,
                    query(GraphQueryEngine.Intent.ARTIFACT, "notes/readme.txt"));
            assertEquals("archive query", artifact.data().get("text"));
        }
    }

    @Test
    void explicitStructuralRankUsesCoreReasonerAfterCompactRoundTrip() throws Exception {
        Path path = directory.resolve("explicit-structural.kgraph");
        graph().saveCompact(path);

        UnifiedGraphArchiveQueryEngine engine = new UnifiedGraphArchiveQueryEngine();
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(path)) {
            for (HybridReasoner.Structural structural : HybridReasoner.Structural.values()) {
                GraphQueryEngine.Query request = query(GraphQueryEngine.Intent.RANK, null, structural);
                GraphQueryEngine.Result actual = engine.query(archive, request);
                GraphQueryEngine.Result expected = new GraphQueryEngine().query(
                        UnifiedGraph.load(path), request);

                assertEquals(expected.entities().stream().map(GraphQueryEngine.EntityView::id).toList(),
                        actual.entities().stream().map(GraphQueryEngine.EntityView::id).toList(), structural.name());
                assertEquals(expected.entities().stream().map(GraphQueryEngine.EntityView::structuralScore).toList(),
                        actual.entities().stream().map(GraphQueryEngine.EntityView::structuralScore).toList(), structural.name());
                assertTrue(actual.summary().contains(structural.name()), actual.summary());
                assertFalse(actual.summary().contains("mmap PageRank"), actual.summary());
            }
        }
    }

    @Test
    void explicitStructuralRankDoesNotSilentlyFallBackWhenBudgetIsExceeded() throws Exception {
        Path path = directory.resolve("explicit-structural-budget.kgraph");
        graph().saveCompact(path);
        String previous = System.getProperty("kompile.graph.archiveReasoningMaxEntities");
        System.setProperty("kompile.graph.archiveReasoningMaxEntities", "2");
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(path)) {
            GraphQueryEngine.Result result = new UnifiedGraphArchiveQueryEngine().query(
                    archive, query(GraphQueryEngine.Intent.RANK, null, HybridReasoner.Structural.BAYESIAN));
            assertEquals(GraphQueryEngine.Status.INVALID, result.status());
            assertTrue(result.summary().contains("budget"), result.summary());
            assertEquals("BAYESIAN", result.data().get("requestedStructural"));
        } finally {
            if (previous == null) {
                System.clearProperty("kompile.graph.archiveReasoningMaxEntities");
            } else {
                System.setProperty("kompile.graph.archiveReasoningMaxEntities", previous);
            }
        }
    }

    @Test
    void exposesBoundedEntityAndVectorCursors() throws Exception {
        Path path = directory.resolve("cursor.kgraph");
        graph().saveCompact(path);
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(path);
             UnifiedGraphArchive.EntityCursor entities = archive.openEntities();
             VectorBlobCodec.RowCursor vectors = archive.openVectorRows(
                     UnifiedGraphFormat.PRIMARY_ENTITY_VECTORS)) {
            int entityRows = 0;
            while (entities.next() != null) entityRows++;
            int vectorRows = 0;
            VectorBlobCodec.VectorRow row;
            while ((row = vectors.next()) != null) {
                assertEquals(2, row.values().length);
                vectorRows++;
            }
            assertEquals(3, entityRows);
            assertEquals(3, vectorRows);
            assertEquals(VectorLayer.Target.ENTITY, vectors.target());
        }
    }

    private static GraphQueryEngine.Query query(GraphQueryEngine.Intent intent, String text) {
        return query(intent, text, null);
    }

    private static GraphQueryEngine.Query query(
            GraphQueryEngine.Intent intent, String text, HybridReasoner.Structural structural) {
        return new GraphQueryEngine.Query(intent, null, null, null, List.of(), null, 10,
                null, structural, text);
    }

    private static UnifiedGraph graph() {
        UnifiedGraph graph = new UnifiedGraph().graphId("archive-query");
        graph.addEntity(new SimpleGraphEntity("alpha", "PERSON", "Alpha", 1.0, 0.95,
                Set.of("lead"), new double[]{1.0, 0.0}, Instant.parse("2025-01-01T00:00:00Z"),
                Map.of("team", "north")));
        graph.addEntity(new SimpleGraphEntity("beta", "PERSON", "Beta", 0.9, 0.9,
                Set.of(), new double[]{0.9, 0.1}, null, Map.of()));
        graph.addEntity(new SimpleGraphEntity("gamma", "COMPANY", "Gamma", 0.8, 0.85,
                Set.of(), new double[]{0.0, 1.0}, null, Map.of()));
        graph.addRelation(new SimpleGraphRelation("knows", "alpha", "beta", "KNOWS",
                0.9, 0.9, true, Set.of(), null, null, Map.of()));
        graph.addRelation(new SimpleGraphRelation("works", "beta", "gamma", "WORKS_AT",
                0.8, 0.8, true, Set.of("employment"), null,
                Instant.parse("2025-02-01T00:00:00Z"), Map.of("role", "engineer")));
        graph.putArtifactText("notes/readme.txt", "archive query");
        return graph;
    }
}
