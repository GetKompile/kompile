/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.query;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphQueryEngineTest {

    private final GraphQueryEngine engine = new GraphQueryEngine();

    @Test
    void advertisesAConstrainedSelfDescribingContract() {
        GraphQueryEngine.Result result = engine.query(
                new MutableReasoningGraph(), GraphQueryEngine.Query.capabilities());

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(List.of("CAPABILITIES", "OVERVIEW", "SCHEMA", "SEARCH", "RELATIONS",
                        "DESCRIBE", "NEIGHBORS", "PATH", "TIMELINE", "FACTS", "SIMILAR",
                        "VERIFY", "WHY", "WHY_NOT", "RANK", "ASSETS", "ARTIFACT",
                        "MODELS", "CALCULATE", "SCENARIO", "SOLVE_TARGET"),
                result.capabilities().stream().map(GraphQueryEngine.Capability::intent).toList());
        assertEquals(GraphQueryEngine.capabilityContract(), result.capabilities());
        assertEquals(List.of("entityId", "targetId"),
                result.capabilities().get(7).requiredFields());
        assertTrue(result.capabilities().stream()
                .filter(capability -> "FACTS".equals(capability.intent()))
                .findFirst().orElseThrow().purpose().contains("first-order"));
        assertTrue(result.capabilities().stream()
                .filter(capability -> "SIMILAR".equals(capability.intent()))
                .findFirst().orElseThrow().purpose().contains("stored embeddings"));
        assertTrue(result.capabilities().stream()
                .filter(capability -> "RANK".equals(capability.intent()))
                .findFirst().orElseThrow().purpose().contains("PSL/Bayesian"));
        assertNotNull(result.trace());
    }

    @Test
    void resolvesNaturalPhrasesToStableEntityIds() {
        GraphQueryEngine.Result result = engine.query(
                sampleGraph(), GraphQueryEngine.Query.search("close package email"));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals("email", result.entities().get(0).id());
        assertTrue(result.entities().get(0).score() > 0.0);
    }

    @Test
    void describesEntitiesWithReadableRelationEvidence() {
        MutableReasoningGraph graph = sampleGraph();

        GraphQueryEngine.Result result = engine.query(
                graph, GraphQueryEngine.Query.describe("email"));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(List.of("Close package email"),
                result.entities().stream().map(GraphQueryEngine.EntityView::label).toList());
        assertEquals(Set.of("SENT", "HAS_ATTACHMENT"),
                result.relations().stream().map(GraphQueryEngine.RelationView::type).collect(Collectors.toSet()));
        assertFalse(result.relations().get(0).sourceLabel().isBlank());
        assertFalse(result.relations().get(0).targetLabel().isBlank());
    }

    @Test
    void filtersNeighborsByDirectionAndRelationType() {
        MutableReasoningGraph graph = sampleGraph();
        GraphQueryEngine.Query query = new GraphQueryEngine.Query(
                GraphQueryEngine.Intent.NEIGHBORS,
                "email",
                null,
                GraphQueryEngine.Direction.OUTGOING,
                List.of("has_attachment"),
                null,
                10,
                null,
                null,
                null);

        GraphQueryEngine.Result result = engine.query(graph, query);

        assertEquals(List.of("attachment"),
                result.entities().stream().map(GraphQueryEngine.EntityView::id).toList());
        assertEquals(List.of("email-attachment"),
                result.relations().stream().map(GraphQueryEngine.RelationView::id).toList());
    }

    @Test
    void findsShortestEvidencePath() {
        MutableReasoningGraph graph = sampleGraph();

        GraphQueryEngine.Result result = engine.query(
                graph, GraphQueryEngine.Query.path("person", "attachment"));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(List.of("person", "email", "attachment"),
                result.path().stream().map(step -> step.entity().id()).toList());
        assertNull(result.path().get(0).via());
        assertEquals("SENT", result.path().get(1).via().type());
        assertEquals("HAS_ATTACHMENT", result.path().get(2).via().type());
    }

    @Test
    void verifiesAndExplainsSupportedGraphClaims() {
        GraphQueryEngine.Result verified = engine.query(sampleGraph(),
                GraphQueryEngine.Query.claim(
                        GraphQueryEngine.Intent.VERIFY, "person", "sent", "email"));
        GraphQueryEngine.Result explained = engine.query(sampleGraph(),
                GraphQueryEngine.Query.claim(
                        GraphQueryEngine.Intent.WHY, "person", "sent", "email"));

        assertEquals(GraphQueryEngine.Status.SUPPORTED, verified.status());
        assertTrue(verified.summary().contains("SUPPORTED"));
        assertEquals(List.of("person-email"),
                explained.relations().stream().map(GraphQueryEngine.RelationView::id).toList());
    }

    @Test
    void distinguishesRefutedAndUnknownClaims() {
        MutableReasoningGraph graph = sampleGraph()
                .addEntity("other", "PERSON", "Alex Kim")
                .addRelation("not-sent", "other", "email", "NOT_SENT", 0.92);

        GraphQueryEngine.Result refuted = engine.query(graph,
                GraphQueryEngine.Query.claim(
                        GraphQueryEngine.Intent.VERIFY, "other", "SENT", "email"));
        GraphQueryEngine.Result unknown = engine.query(graph,
                GraphQueryEngine.Query.claim(
                        GraphQueryEngine.Intent.WHY_NOT, "person", "SENT", "attachment"));

        assertEquals(GraphQueryEngine.Status.REFUTED, refuted.status());
        assertEquals(List.of("not-sent"),
                refuted.relations().stream().map(GraphQueryEngine.RelationView::id).toList());
        assertEquals(GraphQueryEngine.Status.UNKNOWN, unknown.status());
        assertEquals(List.of("person", "email", "attachment"),
                unknown.path().stream().map(step -> step.entity().id()).toList());
        assertTrue(unknown.guidance().stream().anyMatch(value -> value.contains("Missing graph fact")));
    }

    @Test
    void returnsActionableGuidanceInsteadOfThrowingForBadQueries() {
        GraphQueryEngine.Result result = engine.query(
                sampleGraph(), GraphQueryEngine.Query.describe("missing"));

        assertEquals(GraphQueryEngine.Status.NOT_FOUND, result.status());
        assertTrue(result.summary().contains("missing"));
        assertFalse(result.guidance().isEmpty());
    }

    @Test
    void automaticallyResolvesNamesAcrossTopologyAndEntailmentQueries() {
        MutableReasoningGraph graph = sampleGraph();

        GraphQueryEngine.Result described = engine.query(
                graph, GraphQueryEngine.Query.describe("Jordan Lee"));
        GraphQueryEngine.Result path = engine.query(
                graph, GraphQueryEngine.Query.path("Jordan Lee", "regional-close.xlsx"));
        GraphQueryEngine.Result verified = engine.query(graph,
                GraphQueryEngine.Query.claim(
                        GraphQueryEngine.Intent.VERIFY, "Jordan Lee", "sent", "Close package email"));

        assertEquals("person", described.entities().get(0).id());
        assertEquals("person", described.resolutions().get(0).resolvedId());
        assertEquals(List.of("person", "email", "attachment"),
                path.path().stream().map(step -> step.entity().id()).toList());
        assertEquals(2, path.resolutions().size());
        assertEquals(GraphQueryEngine.Status.SUPPORTED, verified.status());
        assertNotNull(described.trace());
        assertTrue(described.trace().contains("entityId Jordan Lee -> person"));
        assertNotNull(path.trace());
        assertNotNull(verified.trace());
    }

    @Test
    void leavesAmbiguousEntityPhrasesUnresolvedWithRankedCandidates() {
        MutableReasoningGraph graph = new MutableReasoningGraph()
                .addEntity("first", "PERSON", "Jordan Lee")
                .addEntity("second", "PERSON", "Alex Kim");

        GraphQueryEngine.Result result = engine.query(
                graph, GraphQueryEngine.Query.describe("person profile"));

        assertEquals(GraphQueryEngine.Status.NOT_FOUND, result.status());
        assertNull(result.resolutions().get(0).resolvedId());
        assertTrue(result.resolutions().get(0).candidates().size() >= 2);
        assertTrue(result.resolutions().get(0).score() > 0.0);
        assertNotNull(result.trace());
    }

    @Test
    void queriesOverviewSchemaRelationsFactsAndTimelineWithRankedTraces() {
        MutableReasoningGraph graph = sampleGraph();
        graph.addRelation(GraphRelation.builder("dated", "person", "email")
                .type("REVIEWED")
                .weight(0.8)
                .confidence(0.85)
                .timestamp(Instant.parse("2025-03-01T10:15:30Z"))
                .build());

        GraphQueryEngine.Result overview = engine.query(graph, GraphQueryEngine.Query.overview());
        GraphQueryEngine.Result schema = engine.query(graph, GraphQueryEngine.Query.schema());
        GraphQueryEngine.Result relations = engine.query(
                graph, GraphQueryEngine.Query.relations("Jordan Lee", "sent"));
        GraphQueryEngine.Result facts = engine.query(graph, new GraphQueryEngine.Query(
                GraphQueryEngine.Intent.FACTS, "Close package email", null, null,
                List.of("has attachment"), null, 10, null, null, null));
        GraphQueryEngine.Result timeline = engine.query(
                graph, GraphQueryEngine.Query.timeline("Jordan Lee"));

        assertEquals(3, overview.data().get("entityCount"));
        assertTrue(((Map<?, ?>) schema.data().get("entityTypes")).containsKey("PERSON"));
        assertEquals(List.of("person-email"),
                relations.relations().stream().map(GraphQueryEngine.RelationView::id).toList());
        assertEquals("email", facts.data().get("scopeEntityId"));
        assertEquals(List.of("dated"),
                timeline.relations().stream().map(GraphQueryEngine.RelationView::id).toList());
        assertTrue(List.of(overview, schema, relations, facts, timeline).stream()
                .allMatch(result -> result.trace() != null));
    }

    @Test
    void exposesUnifiedVectorsOpinionsWeightsArtifactsAndCompleteFields() {
        Instant timestamp = Instant.parse("2025-03-01T10:15:30Z");
        UnifiedGraph graph = new UnifiedGraph()
                .graphId("query-test")
                .factSheetId(42L)
                .addEntity(GraphEntity.builder("person")
                        .type("PERSON")
                        .label("Jordan Lee")
                        .weight(0.8)
                        .confidence(0.9)
                        .tag("owner")
                        .embedding(new double[]{1.0, 0.0})
                        .timestamp(timestamp)
                        .attribute("validFrom", "2025-01-01T00:00:00Z")
                        .build())
                .addEntity(GraphEntity.builder("email")
                        .type("EMAIL")
                        .label("Close package email")
                        .embedding(new double[]{0.9, 0.1})
                        .build())
                .addRelation(GraphRelation.builder("person-email", "person", "email")
                        .type("SENT")
                        .weight(0.95)
                        .confidence(0.9)
                        .tag("event")
                        .embedding(new double[]{0.2, 0.8})
                        .timestamp(timestamp)
                        .build())
                .putVectorLayer(new VectorLayer("semantic", VectorLayer.Target.ENTITY, 2, Dtype.F32)
                        .put("person", new double[]{1.0, 0.0})
                        .put("email", new double[]{0.9, 0.1}))
                .putVectorLayer(new VectorLayer("relation-types", VectorLayer.Target.GLOBAL, 2, Dtype.F32)
                        .put("SENT", new double[]{0.2, 0.8}))
                .putEntityOpinion("person", Opinion.fromBetaEvidence(8, 2))
                .putRelationOpinion("person-email", Opinion.fromBetaEvidence(7, 1))
                .putWeightMap("importance", Map.of("person", 0.9, "person-email", 0.8))
                .putArtifactText("notes.txt", "close process evidence");

        GraphQueryEngine.Result described = engine.query(
                graph, GraphQueryEngine.Query.describe("Jordan Lee"));
        GraphQueryEngine.Result assets = engine.query(
                graph, GraphQueryEngine.Query.assets("Jordan Lee", "semantic"));
        GraphQueryEngine.Result relationAssets = engine.query(
                graph, GraphQueryEngine.Query.assets(null, "person-email"));
        GraphQueryEngine.Result globalAssets = engine.query(
                graph, GraphQueryEngine.Query.assets(null, "SENT"));
        GraphQueryEngine.Result artifact = engine.query(
                graph, GraphQueryEngine.Query.artifact("notes.txt"));
        GraphQueryEngine.Result similar = engine.query(graph, new GraphQueryEngine.Query(
                GraphQueryEngine.Intent.SIMILAR, "Jordan Lee", null, null, List.of(),
                null, 5, null, null, "semantic"));

        GraphQueryEngine.EntityView entity = described.entities().get(0);
        GraphQueryEngine.RelationView relation = described.relations().get(0);
        assertEquals(Set.of("PERSON"), entity.typeMemberships());
        assertEquals(Set.of("owner"), entity.tags());
        assertEquals(2, entity.embeddingDimension());
        assertEquals(timestamp.toString(), entity.timestamp());
        assertEquals("2025-01-01T00:00:00Z", entity.validFrom());
        assertEquals(Set.of("event"), relation.tags());
        assertEquals(2, relation.embeddingDimension());
        assertEquals(timestamp.toString(), relation.timestamp());
        assertTrue(assets.data().containsKey("selectedEntity"));
        assertTrue(assets.data().containsKey("selectedVectorLayer"));
        assertTrue(relationAssets.data().containsKey("selectedRelation"));
        assertTrue(relationAssets.data().containsKey("selectedKeyWeights"));
        assertTrue(globalAssets.data().containsKey("selectedGlobalVectors"));
        assertEquals("close process evidence", artifact.data().get("text"));
        assertEquals(List.of("email"),
                similar.entities().stream().map(GraphQueryEngine.EntityView::id).toList());
        assertTrue(List.of(described, assets, relationAssets, globalAssets, artifact, similar).stream()
                .allMatch(result -> result.trace() != null));
    }

    @Test
    void exposesHybridReasoningWithDeterministicDefaults() {
        GraphQueryEngine.Result result = engine.query(
                sampleGraph(), GraphQueryEngine.Query.rank(2));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(2, result.entities().size());
        for (GraphQueryEngine.EntityView entity : result.entities()) {
            assertNotNull(entity.structuralScore());
            assertEquals(0.0, entity.semanticScore());
        }
        assertTrue(result.summary().contains("PSL"));
        assertTrue(result.summary().contains("structural"));
    }

    @Test
    void similarAutomaticallyResolvesSparseSourceVectorWithTraceableOrigin() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("source", "NODE", "Sparse source");
        graph.addEntity(GraphEntity.builder("anchor").type("NODE").label("Semantic anchor")
                .embedding(new double[]{1.0, 0.0}).build());
        graph.addEntity(GraphEntity.builder("match").type("NODE").label("Semantic match")
                .embedding(new double[]{1.0, 0.0}).build());
        graph.addEntity(GraphEntity.builder("other").type("NODE").label("Other")
                .embedding(new double[]{0.0, 1.0}).build());
        graph.addRelation("source-anchor", "source", "anchor", "RELATED", 1.0);

        GraphQueryEngine.Result result = engine.query(graph, new GraphQueryEngine.Query(
                GraphQueryEngine.Intent.SIMILAR, "source", null, null, List.of(),
                null, 3, null, null, null));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals("NEIGHBORHOOD", result.data().get("semanticVectorOrigin"));
        assertEquals(1, result.data().get("semanticVectorSupport"));
        assertEquals(1, result.data().get("semanticVectorHops"));
        assertTrue(result.entities().stream().anyMatch(entity -> entity.semanticScore() > 0.99));
        assertNotNull(result.trace());
    }

    private static MutableReasoningGraph sampleGraph() {
        return new MutableReasoningGraph()
                .addEntity("person", "PERSON", "Jordan Lee")
                .addEntity("email", "EMAIL", "Close package email")
                .addEntity("attachment", "DOCUMENT", "regional-close.xlsx")
                .addRelation("person-email", "person", "email", "SENT", 0.95)
                .addRelation("email-attachment", "email", "attachment", "HAS_ATTACHMENT", 0.9);
    }
}
