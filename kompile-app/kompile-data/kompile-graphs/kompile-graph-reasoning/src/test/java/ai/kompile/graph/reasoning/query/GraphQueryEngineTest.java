/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.query;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void searchesWithRetrievalOnlyScoresAndDeterministicPriority() {
        ReasoningGraph graph = retrievalOnlyGraph(
                GraphEntity.builder("alpha").type("PACKAGE").label("Package Alpha")
                        .weight(0.2).confidence(0.5).build(),
                GraphEntity.builder("gamma").type("PACKAGE").label("Package Gamma")
                        .weight(0.5).confidence(1.0).build(),
                GraphEntity.builder("beta").type("PACKAGE").label("Package Beta")
                        .weight(1.0).confidence(1.0).build());

        GraphQueryEngine.Result ranked = engine.query(graph, new GraphQueryEngine.Query(
                GraphQueryEngine.Intent.SEARCH, null, null, null, List.of(), null, 2,
                null, null, "package"));
        GraphQueryEngine.Result noMatch = engine.query(graph, GraphQueryEngine.Query.search("missing"));
        GraphQueryEngine.Result exactId = engine.query(graph, GraphQueryEngine.Query.search("beta"));
        GraphQueryEngine.Result exactName = engine.query(graph, GraphQueryEngine.Query.search("Package Beta"));

        assertEquals(List.of("beta", "gamma"), ranked.entities().stream()
                .map(GraphQueryEngine.EntityView::id).toList());
        assertEquals(2, ranked.entities().size());
        assertEquals("lexical + stored entity prior", ranked.data().get("scoreBasis"));
        assertEquals("clamp01(min(weight, confidence))", ranked.data().get("storedPrior"));
        assertEquals(false, ranked.data().get("inferenceInvoked"));
        assertTrue(ranked.summary().contains("retrieval-only"));
        assertTrue(ranked.trace().steps().stream()
                .anyMatch(step -> "lexical_entity_search".equals(step.operation())));
        assertTrue(ranked.trace().steps().stream()
                .noneMatch(step -> step.kind() == ai.kompile.graph.reasoning.explain.ReasoningTrace.StepKind.INFERENCE));

        assertTrue(noMatch.entities().isEmpty());
        assertEquals(List.of("beta"), exactId.entities().stream()
                .map(GraphQueryEngine.EntityView::id).toList());
        assertEquals(1.0, exactId.entities().get(0).score());
        assertEquals(List.of("beta", "gamma", "alpha"), exactName.entities().stream()
                .map(GraphQueryEngine.EntityView::id).toList());
        assertTrue(exactName.entities().get(0).score() >= 0.98);

        ReasoningGraph tied = retrievalOnlyGraph(
                GraphEntity.builder("z-id").type("NODE").label("same label").build(),
                GraphEntity.builder("a-id").type("NODE").label("same label").build());
        GraphQueryEngine.Result deterministic = engine.query(tied, GraphQueryEngine.Query.search("same label"));
        assertEquals(List.of("a-id", "z-id"), deterministic.entities().stream()
                .map(GraphQueryEngine.EntityView::id).toList());
    }

    @Test
    void naturalNameResolutionUsesOnlyEntityEvidenceWithoutGlobalRelations() {
        ReasoningGraph graph = retrievalOnlyGraph(
                GraphEntity.builder("beta").type("PACKAGE").label("Package Beta").build());

        GraphQueryEngine.Result result = engine.query(
                graph, GraphQueryEngine.Query.describe("Package Beta"));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(List.of("beta"), result.entities().stream()
                .map(GraphQueryEngine.EntityView::id).toList());
        assertTrue(result.relations().isEmpty());
        assertEquals("false", result.trace().steps().stream()
                .filter(step -> "automatic_entity_resolution".equals(step.operation()))
                .findFirst().orElseThrow().meta().get("inferenceInvoked"));
        assertTrue(result.trace().steps().stream()
                .filter(step -> "automatic_entity_resolution".equals(step.operation()))
                .findFirst().orElseThrow().meta().get("scoreBasis").contains("stored entity prior"));
    }

    @Test
    void retrievalLoopsPropagateCancellationWithoutClearingInterruptFlag() {
        boolean wasInterrupted = Thread.currentThread().isInterrupted();
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> engine.query(
                    retrievalOnlyGraph(GraphEntity.builder("one").type("NODE").label("one").build()),
                    GraphQueryEngine.Query.search("one")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            if (!wasInterrupted) {
                Thread.interrupted();
            }
        }
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
    void undirectedRelationsAreTraversableFromEitherEndpointInBothDirections() {
        MutableReasoningGraph graph = new MutableReasoningGraph()
                .addEntity("a", "NODE", "A")
                .addEntity("b", "NODE", "B")
                .addRelation(GraphRelation.builder("a-b", "a", "b")
                        .type("LINKED").directed(false).build());
        GraphQueryEngine.Query outgoingFromTarget = new GraphQueryEngine.Query(
                GraphQueryEngine.Intent.NEIGHBORS, "b", null,
                GraphQueryEngine.Direction.OUTGOING, List.of(), null, 10,
                null, null, null);
        GraphQueryEngine.Query incomingToSource = new GraphQueryEngine.Query(
                GraphQueryEngine.Intent.NEIGHBORS, "a", null,
                GraphQueryEngine.Direction.INCOMING, List.of(), null, 10,
                null, null, null);

        GraphQueryEngine.Result outgoing = engine.query(graph, outgoingFromTarget);
        GraphQueryEngine.Result incoming = engine.query(graph, incomingToSource);

        assertEquals(List.of("a"), outgoing.entities().stream()
                .map(GraphQueryEngine.EntityView::id).toList());
        assertEquals(List.of("b"), incoming.entities().stream()
                .map(GraphQueryEngine.EntityView::id).toList());
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
    void claimVerificationUsesIndexedOutgoingAdjacencyWithoutScanningAllRelations() {
        MutableReasoningGraph delegate = sampleGraph();
        ReasoningGraph storageBacked = new ReasoningGraph() {
            @Override public Collection<GraphEntity> entities() { return delegate.entities(); }
            @Override public Collection<GraphRelation> relations() {
                throw new AssertionError("claim verification must not request the full relation collection");
            }
            @Override public Optional<GraphEntity> entity(String id) { return delegate.entity(id); }
            @Override public List<GraphRelation> outgoing(String id) { return delegate.outgoing(id); }
            @Override public List<GraphRelation> incoming(String id) { return delegate.incoming(id); }
            @Override public List<GraphRelation> relationsOf(String id) { return delegate.relationsOf(id); }
        };

        GraphQueryEngine.Result result = engine.query(storageBacked,
                GraphQueryEngine.Query.claim(
                        GraphQueryEngine.Intent.VERIFY, "person", "SENT", "email"));

        assertEquals(GraphQueryEngine.Status.SUPPORTED, result.status());
        assertEquals(List.of("person-email"),
                result.relations().stream().map(GraphQueryEngine.RelationView::id).toList());
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
    void claimsAndRelationFiltersMatchStoredTypesInAnyCaseOrWordStyle() {
        MutableReasoningGraph graph = sampleGraph()
                .addRelation("person-attachment", "person", "attachment", "isOwnerOf", 0.8);

        for (String spelling : List.of("hasAttachment", "has_attachment", "Has-Attachment", "has attachment")) {
            GraphQueryEngine.Result verified = engine.query(graph, GraphQueryEngine.Query.claim(
                    GraphQueryEngine.Intent.VERIFY, "email", spelling, "attachment"));
            assertEquals(GraphQueryEngine.Status.SUPPORTED, verified.status(), spelling);
            assertEquals(List.of("email-attachment"),
                    verified.relations().stream().map(GraphQueryEngine.RelationView::id).toList(), spelling);
        }
        assertEquals(GraphQueryEngine.Status.SUPPORTED, engine.query(graph, GraphQueryEngine.Query.claim(
                GraphQueryEngine.Intent.VERIFY, "person", "IS_OWNER_OF", "attachment")).status());

        GraphQueryEngine.Result neighbors = engine.query(graph, new GraphQueryEngine.Query(
                GraphQueryEngine.Intent.NEIGHBORS, "person", null,
                GraphQueryEngine.Direction.OUTGOING, List.of("is owner of"), null, 10,
                null, null, null));
        assertEquals(List.of("person-attachment"),
                neighbors.relations().stream().map(GraphQueryEngine.RelationView::id).toList());
        // Canonical names decide what matches; trace premises keep the spelling the graph stores.
        assertTrue(neighbors.trace().contains("isOwnerOf(person,attachment)"),
                neighbors.trace().steps().stream().map(step -> step.conclusion()).toList().toString());
    }

    @Test
    void unknownClaimsNameExistingLinksAndClosestRelationTypesFromAdjacencyOnly() {
        MutableReasoningGraph delegate = sampleGraph();
        ReasoningGraph storageBacked = new ReasoningGraph() {
            @Override public Collection<GraphEntity> entities() { return delegate.entities(); }
            @Override public Collection<GraphRelation> relations() {
                throw new AssertionError("claim hints must not request the full relation collection");
            }
            @Override public Optional<GraphEntity> entity(String id) { return delegate.entity(id); }
            @Override public List<GraphRelation> outgoing(String id) { return delegate.outgoing(id); }
            @Override public List<GraphRelation> incoming(String id) { return delegate.incoming(id); }
            @Override public List<GraphRelation> relationsOf(String id) { return delegate.relationsOf(id); }
        };

        GraphQueryEngine.Result misspelled = engine.query(storageBacked, GraphQueryEngine.Query.claim(
                GraphQueryEngine.Intent.VERIFY, "person", "sends", "email"));
        GraphQueryEngine.Result reversed = engine.query(storageBacked, GraphQueryEngine.Query.claim(
                GraphQueryEngine.Intent.VERIFY, "email", "receivedFrom", "person"));
        GraphQueryEngine.Result supported = engine.query(storageBacked, GraphQueryEngine.Query.claim(
                GraphQueryEngine.Intent.VERIFY, "person", "sent", "email"));

        assertEquals(GraphQueryEngine.Status.UNKNOWN, misspelled.status());
        assertTrue(misspelled.guidance().contains("The graph links these entities as SENT(person,email); "
                        + "if one of those states the claim, verify that relation type and direction instead."),
                misspelled.guidance().toString());
        assertTrue(misspelled.guidance().contains(
                        "No SENDS relation leaves person; its closest relation types are SENT."),
                misspelled.guidance().toString());
        assertEquals(GraphQueryEngine.Status.UNKNOWN, reversed.status());
        assertTrue(reversed.guidance().stream().anyMatch(value -> value.contains("SENT(person,email)")),
                reversed.guidance().toString());
        assertTrue(reversed.guidance().contains("No RECEIVED_FROM relation leaves email; "
                        + "use SCHEMA to list the relation types this graph uses."),
                reversed.guidance().toString());
        assertEquals(GraphQueryEngine.Status.SUPPORTED, supported.status());
        assertTrue(supported.guidance().stream().noneMatch(value ->
                        value.contains("links these entities") || value.contains("relation leaves")),
                supported.guidance().toString());
    }

    @Test
    void recordedEvidenceDecidesClaimsAndTheStoredOpinionIsReportedAsALearnedScore() {
        GraphQueryEngine.Query claim = GraphQueryEngine.Query.claim(
                GraphQueryEngine.Intent.VERIFY, "person", "SENT", "email");

        // fromSoftTruth(p) keeps u = 1/11, so the learned score is (10p + 0.5) / 11. Whatever it
        // is, the SENT relation recorded at 0.9 decides the claim.
        for (double learnedTruth : new double[]{0.9, 0.2, 0.39}) {
            GraphQueryEngine.Result result = engine.query(opinionGraph(learnedTruth), claim);
            double learnedScore = (10.0 * learnedTruth + 0.5) / 11.0;

            assertEquals(GraphQueryEngine.Status.SUPPORTED, result.status(), result.summary());
            assertEquals(0.9, confidence(result), 1e-9);
            assertEquals("direct-evidence", result.data().get("verdictBasis"));
            assertEquals(learnedScore, ((Number) result.data().get("learnedScore")).doubleValue(), 1e-9);
            assertTrue(result.data().containsKey("relationOpinion"), result.data().toString());
            assertTrue(result.summary().contains("learned score"), result.summary());
            assertTrue(result.summary().contains("does not decide"), result.summary());
            assertEquals(0.9, result.trace().conclusion().confidence(), 1e-9);
            ReasoningTrace.Step verdict = traceStep(result, step -> "claim_verdict".equals(step.operation()));
            assertEquals(0.9, verdict.confidence(), 1e-9);
            assertNull(verdict.opinion());
            assertEquals(learnedScore, Double.parseDouble(verdict.meta().get("learnedScore")), 1e-3);
            // The relation's fact step carries its recorded confidence; the learned score is labelled
            // in meta instead of being attached as the fact's opinion.
            ReasoningTrace.Step fact = traceStep(result, step -> step.kind() == ReasoningTrace.StepKind.FACT
                    && "person-email".equals(step.source()));
            assertEquals(0.9, fact.confidence(), 1e-9);
            assertNull(fact.opinion());
            assertEquals(learnedScore, Double.parseDouble(fact.meta().get("learnedScore")), 1e-3);
        }
    }

    private static ReasoningTrace.Step traceStep(GraphQueryEngine.Result result,
                                                 Predicate<ReasoningTrace.Step> match) {
        return result.trace().steps().stream().filter(match).findFirst()
                .orElseThrow(() -> new AssertionError("no matching trace step in " + result.trace().steps()));
    }

    @Test
    void aWellEvidencedRelationBetweenLowRankedEntitiesStaysSupportedAfterLearning() {
        // Learning stores each relation's PSL/MEBN consensus target as its opinion. The target blends
        // the recorded confidence with the endpoints' hybrid rank, so a relation recorded at 0.6
        // between peripheral entities learns a low score. That score must not refute the relation.
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(GraphEntity.builder("acme").type("ORG").label("Acme").build())
                .addEntity(GraphEntity.builder("dana").type("PERSON").label("Dana").confidence(0.2).build())
                .addEntity(GraphEntity.builder("eve").type("PERSON").label("Eve").confidence(0.2).build());
        for (String employee : List.of("alice", "bob", "carol")) {
            graph.addEntity(GraphEntity.builder(employee).type("PERSON").label(employee).build())
                    .addRelation(GraphRelation.builder(employee + "-acme", employee, "acme")
                            .type("WORKS_AT").weight(1.0).confidence(1.0).build());
        }
        graph.addRelation(GraphRelation.builder("dana-eve", "dana", "eve")
                .type("KNOWS").weight(0.6).confidence(0.6).build());

        UnifiedGraphReasoningLifecycle.learn(graph, UnifiedGraphReasoningLifecycle.Config.defaults());
        Opinion learned = graph.relationOpinion("dana-eve");
        assertNotNull(learned, "learning should store an opinion on the KNOWS relation");
        assertTrue(learned.expectation() < 0.5,
                "the fixture should give the peripheral relation a low learned score, got " + learned.expectation());

        GraphQueryEngine.Result verify = engine.query(graph, GraphQueryEngine.Query.claim(
                GraphQueryEngine.Intent.VERIFY, "dana", "KNOWS", "eve"));
        assertEquals(GraphQueryEngine.Status.SUPPORTED, verify.status(), verify.summary());
        assertEquals(0.6, confidence(verify), 1e-9);
        assertEquals("direct-evidence", verify.data().get("verdictBasis"));
        assertEquals(learned.expectation(), ((Number) verify.data().get("learnedScore")).doubleValue(), 1e-9);

        GraphQueryEngine.Result whyNot = engine.query(graph, GraphQueryEngine.Query.claim(
                GraphQueryEngine.Intent.WHY_NOT, "dana", "KNOWS", "eve"));
        assertEquals(GraphQueryEngine.Status.SUPPORTED, whyNot.status(), whyNot.summary());
        assertTrue(whyNot.summary().contains("already supported"), whyNot.summary());
    }

    @Test
    void explicitCounterEvidenceRefutesAndTheLearnedScoreIsOnlyReported() {
        UnifiedGraph graph = opinionGraph(0.9)
                .addRelation(GraphRelation.builder("person-email-denied", "person", "email")
                        .type("NOT_SENT").weight(0.92).confidence(0.92).build());

        GraphQueryEngine.Result result = engine.query(graph, GraphQueryEngine.Query.claim(
                GraphQueryEngine.Intent.VERIFY, "person", "SENT", "email"));

        assertEquals(GraphQueryEngine.Status.REFUTED, result.status());
        assertEquals("negated-atom", result.data().get("verdictBasis"));
        assertEquals(0.92, confidence(result), 1e-9);
        assertTrue(result.summary().contains("NOT_SENT"), result.summary());
        assertTrue(result.summary().contains("does not decide"), result.summary());
    }

    @Test
    void staleGraphsReportNoOutdatedLearnedScore() {
        UnifiedGraph graph = opinionGraph(0.2)
                .meta(UnifiedGraphReasoningLifecycle.REASONING_STALE_META, true);

        GraphQueryEngine.Result result = engine.query(graph, GraphQueryEngine.Query.claim(
                GraphQueryEngine.Intent.VERIFY, "person", "SENT", "email"));

        assertEquals(GraphQueryEngine.Status.SUPPORTED, result.status());
        assertEquals("direct-evidence", result.data().get("verdictBasis"));
        assertEquals(0.9, confidence(result), 1e-9);
        assertEquals(Boolean.TRUE, result.data().get("opinionsStale"));
        assertFalse(result.data().containsKey("relationOpinion"));
        assertFalse(result.data().containsKey("learnedScore"));
        assertTrue(result.summary().contains("changed after the last learning pass"), result.summary());
    }

    @Test
    void entityStepsLabelTheStoredOpinionAsALearnedScoreUntilTheGraphChanges() {
        UnifiedGraph graph = opinionGraph(0.9).putEntityOpinion("person", Opinion.fromSoftTruth(0.8));
        Predicate<ReasoningTrace.Step> personLookup = step ->
                "person".equals(step.source()) && "graph_lookup".equals(step.operation());

        ReasoningTrace.Step fresh = traceStep(
                engine.query(graph, GraphQueryEngine.Query.describe("person")), personLookup);
        assertNull(fresh.opinion());
        assertEquals((10.0 * 0.8 + 0.5) / 11.0, Double.parseDouble(fresh.meta().get("learnedScore")), 1e-3);

        graph.meta(UnifiedGraphReasoningLifecycle.REASONING_STALE_META, true);
        ReasoningTrace.Step stale = traceStep(
                engine.query(graph, GraphQueryEngine.Query.describe("person")), personLookup);
        assertNull(stale.opinion());
        assertFalse(stale.meta().containsKey("learnedScore"), stale.meta().toString());
    }

    @Test
    void directEvidenceVerdictsReportTheirConfidenceInTheTrace() {
        MutableReasoningGraph graph = sampleGraph()
                .addRelation("weak-mention", "attachment", "person", "MENTIONS", 0.2);

        GraphQueryEngine.Result result = engine.query(graph, GraphQueryEngine.Query.claim(
                GraphQueryEngine.Intent.VERIFY, "attachment", "MENTIONS", "person"));

        assertEquals(GraphQueryEngine.Status.REFUTED, result.status());
        assertEquals("hard-false-fact", result.data().get("verdictBasis"));
        assertEquals(0.8, confidence(result), 1e-9);
        assertEquals(0.8, result.trace().conclusion().confidence(), 1e-9);
    }

    @Test
    void whyNotOverALowLearnedScoreSaysTheRecordedRelationAlreadySupportsTheClaim() {
        GraphQueryEngine.Result result = engine.query(opinionGraph(0.2), GraphQueryEngine.Query.claim(
                GraphQueryEngine.Intent.WHY_NOT, "person", "SENT", "email"));

        assertEquals(GraphQueryEngine.Status.SUPPORTED, result.status());
        assertEquals(List.of("person-email"),
                result.relations().stream().map(GraphQueryEngine.RelationView::id).toList());
        assertTrue(result.summary().contains("already supported"), result.summary());
        assertTrue(result.guidance().stream().noneMatch(value -> value.contains("Missing graph fact")),
                result.guidance().toString());
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

    private static ReasoningGraph retrievalOnlyGraph(GraphEntity... entities) {
        List<GraphEntity> values = List.of(entities);
        return new ReasoningGraph() {
            @Override
            public Collection<GraphEntity> entities() {
                return values;
            }

            @Override
            public Collection<GraphRelation> relations() {
                throw new AssertionError("retrieval-only query must not access global relations");
            }

            @Override
            public Optional<GraphEntity> entity(String id) {
                if (id == null) {
                    return Optional.empty();
                }
                return values.stream().filter(entity -> entity.id().equals(id)).findFirst();
            }

            @Override
            public List<GraphRelation> outgoing(String id) {
                return List.of();
            }

            @Override
            public List<GraphRelation> incoming(String id) {
                return List.of();
            }

            @Override
            public List<GraphRelation> relationsOf(String id) {
                return List.of();
            }
        };
    }

    /** The sample SENT claim as a UnifiedGraph whose relation carries a learned opinion. */
    private static UnifiedGraph opinionGraph(double learnedTruth) {
        return new UnifiedGraph()
                .addEntity("person", "PERSON", "Jordan Lee")
                .addEntity("email", "EMAIL", "Close package email")
                .addRelation(GraphRelation.builder("person-email", "person", "email")
                        .type("SENT").weight(0.95).confidence(0.9).build())
                .putRelationOpinion("person-email", Opinion.fromSoftTruth(learnedTruth));
    }

    private static double confidence(GraphQueryEngine.Result result) {
        return ((Number) result.data().get("confidence")).doubleValue();
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
