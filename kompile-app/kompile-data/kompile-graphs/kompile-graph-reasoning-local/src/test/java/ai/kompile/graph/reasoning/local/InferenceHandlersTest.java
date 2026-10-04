/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.fol.MebnInferenceService;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryArtifactCodec;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for InferenceHandlers via dispatcher.dispatch().
 *
 * <p>Builds a programmatic graph with meaningful entity weights (NOT all 1.0) and typed entities
 * so that Bayesian posteriors are non-degenerate, claim dossiers have direct-edge signals, and
 * synthesize returns typed candidates.</p>
 *
 * <p>Graph topology:
 * <pre>
 *   alice  (PERSON, weight=0.8) --WORKS_AT(0.9)--> acme   (ORG, weight=0.7)
 *   bob    (PERSON, weight=0.6) --WORKS_AT(0.7)--> acme
 *   alice  (PERSON, weight=0.8) --KNOWS(0.5)   --> bob
 *   london (LOCATION, weight=0.9)                  (no relations to alice — used for absent-claim test)
 * </pre>
 * </p>
 */
class InferenceHandlersTest {

    private LocalToolDispatcher dispatcher;
    private LocalReasoningSession session;
    @TempDir Path tempDir;
    private static final String THEORY_ARTIFACT = "reasoning/mebn-theory.v1.json";

    @BeforeEach
    void setUp() {
        dispatcher = LocalToolDispatcher.create();
        UnifiedGraph graph = buildGraph();
        storeLearnedTheory(graph, 0.75);
        session = LocalReasoningSession.of(graph);
    }

    // ── ask_graph_mebn ────────────────────────────────────────────────────────

    @Test
    void mebn_returnsPosteriorsForReachableNodes() {
        String json = dispatcher.dispatch(session, "ask_graph_mebn",
                MiniJson.write(Map.of("nodeId", "alice", "maxDepth", 2)));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);

        @SuppressWarnings("unchecked")
        Map<String, Object> posteriors = (Map<String, Object>) r.get("posteriors");
        assertNotNull(posteriors, "posteriors must be present: " + json);
        assertTrue(posteriors.containsKey("isRelevant(alice)"), "learned relevance variable: " + posteriors);
        assertTrue(posteriors.containsKey("WORKS_AT(alice,acme)"), "learned relationship variable: " + posteriors);
        assertFalse(posteriors.containsKey("alice"), "must not alias learned variables to entity ids");
        assertFalse(posteriors.containsKey("isRelevant(london)"), "unrelated entity must be excluded");
        assertEquals(true, r.get("learnedTheory"));
        assertEquals("mobile-learned", r.get("mTheory"));
        // posteriors are probabilities in [0,1]
        for (Object v : posteriors.values()) {
            double p = ((Number) v).doubleValue();
            assertTrue(p >= 0.0 && p <= 1.0, "posterior out of range: " + p);
        }

        // Match the CLI's grounded-variable title and MEBN metadata contract.
        @SuppressWarnings("unchecked")
        Map<String, Object> titles = (Map<String, Object>) r.get("variableToTitle");
        assertNotNull(titles);
        assertEquals("isRelevant(alice)", titles.get("isRelevant(alice)"));
        Map<?, ?> meta = (Map<?, ?>) ((Map<?, ?>) r.get("variableToMebnMeta")).get("WORKS_AT(alice,acme)");
        assertEquals("WORKS_AT", meta.get("mfragName"));
        assertEquals("WORKS_AT", meta.get("randomVariable"));
        assertEquals(true, meta.get("learned"));
    }

    @Test
    void mebn_missingNodeIdReturnsError() {
        String json = dispatcher.dispatch(session, "ask_graph_mebn", "{}");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("ERROR", r.get("status"), "Expected ERROR for missing nodeId");
    }

    @Test
    void mebn_unknownNodeIdReturnsError() {
        String json = dispatcher.dispatch(session, "ask_graph_mebn",
                MiniJson.write(Map.of("nodeId", "nonexistent_xyz")));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("ERROR", r.get("status"), "Expected ERROR for unknown entity");
    }

    @Test
    void mebn_evidenceConditionsThePosteriorsWhilePriorsStayTheMarginals() {
        Map<String, Object> base = call("ask_graph_mebn", Map.of("nodeId", "alice", "maxDepth", 2));
        Map<String, Object> observed = call("ask_graph_mebn", Map.of("nodeId", "alice", "maxDepth", 2,
                "evidence", Map.of("isRelevant(alice)", false)));
        assertNoError(base, base.toString());
        assertNoError(observed, observed.toString());

        assertEquals(false, base.get("evidenceApplied"));
        assertEquals(true, observed.get("evidenceApplied"));
        assertEquals(base.get("posteriors"), base.get("priors"), "without evidence the priors are the posteriors");
        assertEquals(base.get("priors"), observed.get("priors"), "evidence does not move the priors");
        assertEquals(0.0, probability(observed, "posteriors", "isRelevant(alice)"));
        assertTrue(probability(observed, "posteriors", "WORKS_AT(alice,acme)")
                        < probability(base, "posteriors", "WORKS_AT(alice,acme)"),
                "learned relevance FALSE must lower the relationship posterior: " + observed);
    }

    @Test
    void evidenceOutsideTheNetworkOrWithABadStateIsAnErrorNotSkipped() {
        // Unknown grounded name, out-of-neighborhood name, and structural id aliases all fail.
        for (String name : List.of("missing(alice)", "isRelevant(london)", "alice", "isRelevant( alice )")) {
            Map<String, Object> unknown = call("ask_graph_mebn", Map.of("nodeId", "alice", "maxDepth", 2,
                    "evidence", Map.of(name, true)));
            assertEquals("ERROR", unknown.get("status"), unknown.toString());
            assertTrue(unknown.get("message").toString().contains(name), unknown.toString());
            assertTrue(unknown.get("message").toString().contains("isRelevant(alice)"),
                    "error should show valid exact names: " + unknown);
        }
        for (Object state : List.of("maybe", "true", "1", 2, 0.5)) {
            Map<String, Object> badState = call("ask_graph_mebn", Map.of("nodeId", "alice",
                    "evidence", Map.of("isRelevant(alice)", state)));
            assertEquals("ERROR", badState.get("status"), badState.toString());
        }
        Map<String, Object> badShape = call("ask_graph_mebn", Map.of("nodeId", "alice", "evidence", List.of(true)));
        assertEquals("ERROR", badShape.get("status"), badShape.toString());

        Map<String, Object> bayes = call("graph_bayes", Map.of("action", "query", "node_id", "alice",
                "max_depth", 2, "evidence", Map.of("london", 1)));
        assertEquals("ERROR", bayes.get("status"), bayes.toString());
    }

    @Test
    void mebn_acceptsBooleanAndNumericEvidenceWithExactLearnedNames() {
        for (Object state : List.of(true, false, 1, 0)) {
            Map<String, Object> result = call("ask_graph_mebn", Map.of("nodeId", "alice",
                    "evidence", Map.of("isRelevant(alice)", state)));
            assertNoError(result, result.toString());
            double expected = Boolean.TRUE.equals(state) || Integer.valueOf(1).equals(state) ? 1.0 : 0.0;
            assertEquals(expected, probability(result, "posteriors", "isRelevant(alice)"));
            assertEquals(true, result.get("evidenceApplied"));
        }
    }

    @Test
    void mebn_savedCanonicalTheoryPreservesLearnedStrengthAndMatchesJavaInference() throws Exception {
        Path file = tempDir.resolve("learned.kgraph");
        session.save(file);
        String canonical = session.graph().artifactText(THEORY_ARTIFACT);
        session.close();
        session = LocalReasoningSession.open(file);
        assertEquals(canonical, session.graph().artifactText(THEORY_ARTIFACT));
        MTheory restored = RelationalMTheoryArtifactCodec.fromJson(canonical);
        assertEquals(0.75, restored.getMFrag("WORKS_AT").getEdgeStrength("isRelevant", "WORKS_AT"));

        Map<String, Object> result = call("ask_graph_mebn", Map.of("nodeId", "alice",
                "evidence", Map.of("isRelevant(alice)", true)));
        assertNoError(result, result.toString());
        MTheory restricted = RelationalMTheoryArtifactCodec.restrictToEntityIds(restored, List.of("alice", "bob", "acme"));
        Map<String, Double> expected = new MebnInferenceService().infer(session.graph(), restricted,
                Map.of("isRelevant(alice)", 1));
        assertEquals(expected.keySet(), ((Map<?, ?>) result.get("posteriors")).keySet());
        expected.forEach((name, p) -> assertEquals(p, probability(result, "posteriors", name), 1e-12));
    }

    @Test
    void mebn_learnedStrengthChangesPosteriorWithoutChangingGraphTopology() {
        Map<String, Object> args = Map.of("nodeId", "alice", "evidence", Map.of("isRelevant(alice)", true));
        storeLearnedTheory(session.graph(), 0.15);
        Map<String, Object> weak = call("ask_graph_mebn", args);
        storeLearnedTheory(session.graph(), 0.95);
        Map<String, Object> strong = call("ask_graph_mebn", args);
        assertNoError(weak, weak.toString());
        assertNoError(strong, strong.toString());
        assertTrue(probability(strong, "posteriors", "WORKS_AT(alice,acme)")
                        > probability(weak, "posteriors", "WORKS_AT(alice,acme)") + 0.1,
                "must consume learned strength, not rebuild from unchanged edge confidences");
    }

    @Test
    void mebn_neighborhoodCapsTheoryBeforeGroundingAndKeepsAnchor() {
        String original = session.graph().artifactText(THEORY_ARTIFACT);
        Map<String, Object> result = call("ask_graph_mebn", Map.of("nodeId", "bob", "maxDepth", 2, "maxNodes", 1));
        assertNoError(result, result.toString());
        assertEquals(1L, result.get("scopedEntityCount"));
        assertEquals(Set.of("isRelevant(bob)"), ((Map<?, ?>) result.get("posteriors")).keySet());
        assertEquals(original, session.graph().artifactText(THEORY_ARTIFACT), "query must not mutate saved theory");
        Map<String, Object> outside = call("ask_graph_mebn", Map.of("nodeId", "bob", "maxNodes", 1,
                "evidence", Map.of("isRelevant(alice)", true)));
        assertEquals("ERROR", outside.get("status"));
        for (int cap : List.of(0, -1, 1001)) {
            assertEquals("ERROR", call("ask_graph_mebn", Map.of("nodeId", "alice", "maxNodes", cap)).get("status"));
        }
    }

    @Test
    void mebn_missingCanonicalArtifactErrorsEvenWithLegacyArtifactsAndBayesStillWorks() {
        session.close();
        UnifiedGraph graph = buildGraph();
        session = LocalReasoningSession.of(graph);
        Map<String, Object> absent = call("ask_graph_mebn", Map.of("nodeId", "alice"));
        assertEquals("ERROR", absent.get("status"));
        assertTrue(absent.get("message").toString().contains("No learned canonical MEBN theory"));
        session.close();
        graph.putArtifactText("reasoning/mebn-strengths.json", "{}");
        graph.putArtifact("reasoning/mebn-theory.bin", new byte[]{1, 2, 3});
        session = LocalReasoningSession.of(graph);
        Map<String, Object> missing = call("ask_graph_mebn", Map.of("nodeId", "alice"));
        assertEquals("ERROR", missing.get("status"));
        assertTrue(missing.get("message").toString().contains("No learned canonical MEBN theory"));
        assertNoError(call("graph_bayes", Map.of("action", "query")), "structural Bayes must remain independent");
    }

    @Test
    void mebn_corruptAndUnsupportedCanonicalArtifactsNeverFallBack() {
        session.graph().putArtifactText("reasoning/mebn-strengths.json", "{}");
        String valid = session.graph().artifactText(THEORY_ARTIFACT);
        for (String corrupt : List.of("", "not JSON", "{}", valid.replace("\"version\":1", "\"version\":99"))) {
            session.graph().putArtifactText(THEORY_ARTIFACT, corrupt);
            Map<String, Object> result = call("ask_graph_mebn", Map.of("nodeId", "alice"));
            assertEquals("ERROR", result.get("status"), result.toString());
            assertFalse(result.containsKey("posteriors"));
        }
    }

    @Test
    void mebn_staleTheoryErrorsAfterSaveReloadWithoutRebuilding() throws Exception {
        session.graph().meta("learning.reasoningStale", true);
        Path file = tempDir.resolve("stale.kgraph");
        session.save(file);
        session.close();
        session = LocalReasoningSession.open(file);
        Map<String, Object> result = call("ask_graph_mebn", Map.of("nodeId", "alice"));
        assertEquals("ERROR", result.get("status"));
        assertTrue(result.get("message").toString().contains("stale"));
        assertNoError(call("graph_bayes", Map.of("action", "query")), "structural Bayes does not use learned theory");
    }

    @Test
    void mebnCodeGenerationReceiptsMatchExecutionAndInventory() {
        session.graph().meta("codeIndexGeneration.project", 7L);
        for (long receipt : List.of(-1L, 6L, 7L)) {
            if (receipt >= 0) session.graph().meta("codeLearningGeneration.project", receipt);
            Map<String, Object> result = call("ask_graph_mebn", Map.of("nodeId", "alice"));
            Map<String, Object> activation = InferenceHandlers.mebnActivation(session.graph());
            if (receipt == 7) {
                assertNoError(result, result.toString());
                assertEquals("ACTIVE", activation.get("status"));
            } else {
                assertEquals("ERROR", result.get("status"));
                assertTrue(result.get("message").toString().contains("generation receipt"));
                assertEquals("INVALID", activation.get("status"));
            }
        }
    }

    @Test
    void mebnRejectsCartesianGroundingExplosionBeforeInference() {
        UnifiedGraph graph = new UnifiedGraph();
        List<String> ids = java.util.stream.IntStream.range(0, 101).mapToObj(i -> "n" + i).toList();
        ids.forEach(id -> graph.addEntity(id, "PERSON", id));
        for (int i = 1; i < ids.size(); i++) graph.addRelation("e" + i, "n0", ids.get(i), "LINK", 1.0);
        MTheory theory = RelationalMTheoryBuilder.build("wide", List.of(
                new RelationalMTheoryBuilder.RelationDescriptor("LINK", "PERSON", "PERSON", 0.7, ids, ids)));
        graph.putArtifactText(THEORY_ARTIFACT, RelationalMTheoryArtifactCodec.toJson(theory));
        try (LocalReasoningSession large = LocalReasoningSession.of(graph)) {
            Map<String, Object> result = MiniJson.parseObject(dispatcher.dispatch(large, "ask_graph_mebn",
                    MiniJson.write(Map.of("nodeId", "n0", "maxNodes", 101))));
            assertEquals("ERROR", result.get("status"), result.toString());
            assertTrue(result.get("message").toString().contains("grounding work exceeds"), result.toString());
        }
    }

    @Test
    void mutationInvalidatesMebnThroughSaveReload() throws Exception {
        assertEquals("ACTIVE", InferenceHandlers.mebnActivation(session.graph()).get("status"));
        assertNotEquals("ERROR", call("ask_graph_retract", Map.of("atomKey", "WORKS_AT(alice, acme)")).get("status"));
        assertEquals("ERROR", call("ask_graph_mebn", Map.of("nodeId", "alice")).get("status"));
        Path file = tempDir.resolve("mutated.kgraph");
        session.save(file);
        session.close();
        session = LocalReasoningSession.open(file);
        assertEquals("ERROR", call("ask_graph_mebn", Map.of("nodeId", "alice")).get("status"));
        assertEquals("INVALID", InferenceHandlers.mebnActivation(session.graph()).get("status"));
    }

    @Test
    void mebn_savedTheoryRunsWithOnlyJavaReasoningAndLoggingOnClasspath() throws Exception {
        Path file = tempDir.resolve("java-only.kgraph");
        session.save(file);
        // A platform-parent loader cannot see test/runtime native dependencies. Only the local
        // facade, reasoning library and logging API are available; no lifecycle learner is called.
        URL[] classpath = {
                LocalToolDispatcher.class.getProtectionDomain().getCodeSource().getLocation(),
                UnifiedGraph.class.getProtectionDomain().getCodeSource().getLocation(),
                org.slf4j.LoggerFactory.class.getProtectionDomain().getCodeSource().getLocation()
        };
        try (URLClassLoader loader = new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass("org.eclipse.deeplearning4j.linalg.factory.Nd4j"));
            Class<?> sessionType = loader.loadClass(LocalReasoningSession.class.getName());
            Class<?> dispatcherType = loader.loadClass(LocalToolDispatcher.class.getName());
            Object isolatedSession = sessionType.getMethod("open", Path.class).invoke(null, file);
            try {
                Object isolatedDispatcher = dispatcherType.getMethod("create").invoke(null);
                String json = (String) dispatcherType.getMethod("dispatch", sessionType, String.class, String.class)
                        .invoke(isolatedDispatcher, isolatedSession, "ask_graph_mebn",
                                MiniJson.write(Map.of("nodeId", "alice", "evidence", Map.of("isRelevant(alice)", true))));
                Map<?, ?> result = (Map<?, ?>) MiniJson.parse(json);
                assertNotEquals("ERROR", result.get("status"), json);
                assertEquals(true, result.get("learnedTheory"));
                assertEquals(1.0, ((Number) ((Map<?, ?>) result.get("posteriors")).get("isRelevant(alice)")).doubleValue());
            } finally {
                sessionType.getMethod("close").invoke(isolatedSession);
            }
        }
    }

    // ── graph_bayes ───────────────────────────────────────────────────────────

    @Test
    void bayes_queryNoEvidence_returnsPosteriors() {
        String json = dispatcher.dispatch(session, "graph_bayes",
                MiniJson.write(Map.of("action", "query", "node_id", "alice", "max_depth", 2)));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);
        assertEquals("query", r.get("action"));

        @SuppressWarnings("unchecked")
        Map<String, Object> posteriors = (Map<String, Object>) r.get("posteriors");
        assertNotNull(posteriors, "posteriors required: " + json);
        assertTrue(posteriors.containsKey("alice"));
        assertTrue(posteriors.containsKey("acme"));
    }

    @Test
    void bayes_queryWithEvidence_shiftsPosteriorsVsNoEvidence() {
        // Without evidence
        String jsonBase = dispatcher.dispatch(session, "graph_bayes",
                MiniJson.write(Map.of("action", "query", "node_id", "alice", "max_depth", 2)));
        @SuppressWarnings("unchecked")
        Map<String, Object> base = (Map<String, Object>) MiniJson.parse(jsonBase);
        @SuppressWarnings("unchecked")
        Map<String, Object> basePost = (Map<String, Object>) base.get("posteriors");
        double baseAcme = ((Number) basePost.get("acme")).doubleValue();

        // With alice observed TRUE (1) as evidence
        Map<String, Object> evidence = Map.of("alice", 1);
        String jsonEv = dispatcher.dispatch(session, "graph_bayes",
                MiniJson.write(Map.of("action", "query", "node_id", "alice",
                        "max_depth", 2, "evidence", evidence)));
        @SuppressWarnings("unchecked")
        Map<String, Object> ev = (Map<String, Object>) MiniJson.parse(jsonEv);
        assertNoError(ev, jsonEv);
        @SuppressWarnings("unchecked")
        Map<String, Object> evPost = (Map<String, Object>) ev.get("posteriors");
        double evAcme = ((Number) evPost.get("acme")).doubleValue();

        // Observing alice=TRUE should increase acme's posterior (noisy-OR)
        assertTrue(evAcme >= baseAcme - 0.001,
                "Evidence alice=TRUE should not decrease acme's posterior: base=" + baseAcme + " ev=" + evAcme);
    }

    @Test
    void bayes_mpe_returnsAssignment() {
        String json = dispatcher.dispatch(session, "graph_bayes",
                MiniJson.write(Map.of("action", "mpe", "node_id", "alice", "max_depth", 2)));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);
        assertEquals("mpe", r.get("action"));

        @SuppressWarnings("unchecked")
        Map<String, Object> assignment = (Map<String, Object>) r.get("assignment");
        assertNotNull(assignment, "mpe must return assignment: " + json);
        assertFalse(assignment.isEmpty(), "assignment must be non-empty");

        // All values must be TRUE or FALSE
        for (Object v : assignment.values()) {
            String sv = v.toString();
            assertTrue("TRUE".equals(sv) || "FALSE".equals(sv),
                    "MPE value must be TRUE or FALSE, got: " + sv);
        }
    }

    @Test
    void bayes_whatif_returnsDeltaMap() {
        Map<String, Object> hypEvidence = Map.of("alice", 1);
        String json = dispatcher.dispatch(session, "graph_bayes",
                MiniJson.write(Map.of("action", "whatif",
                        "node_id", "alice", "max_depth", 2,
                        "hypothetical_evidence", hypEvidence)));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);
        assertEquals("whatif", r.get("action"));

        @SuppressWarnings("unchecked")
        Map<String, Object> deltas = (Map<String, Object>) r.get("deltas");
        assertNotNull(deltas, "deltas must be present: " + json);
        // Each delta entry has baseline, hypothetical, delta keys
        for (Map.Entry<String, Object> e : deltas.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) e.getValue();
            assertTrue(item.containsKey("baseline"),    "delta item missing baseline: " + item);
            assertTrue(item.containsKey("hypothetical"), "delta item missing hypothetical: " + item);
            assertTrue(item.containsKey("delta"),       "delta item missing delta: " + item);
        }
    }

    @Test
    void bayes_stats_returnsNetworkCounts() {
        String json = dispatcher.dispatch(session, "graph_bayes",
                MiniJson.write(Map.of("action", "stats")));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);
        assertEquals("stats", r.get("action"));

        Number nodes = (Number) r.get("nodes");
        Number edges = (Number) r.get("edges");
        assertNotNull(nodes, "nodes required");
        assertNotNull(edges, "edges required");
        assertTrue(nodes.intValue() > 0, "must have at least 1 node");
        assertTrue(edges.intValue() >= 0, "edges must be non-negative");
        assertNotNull(r.get("maxDepthReached"), "maxDepthReached required");
    }

    @Test
    void bayes_unknownAction_returnsError() {
        String json = dispatcher.dispatch(session, "graph_bayes",
                MiniJson.write(Map.of("action", "bogus")));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("ERROR", r.get("status"));
    }

    // ── ask_graph_claim ───────────────────────────────────────────────────────

    @Test
    void claim_existingEdge_likelyTrue() {
        // alice -WORKS_AT-> acme exists in the graph
        String json = dispatcher.dispatch(session, "ask_graph_claim",
                MiniJson.write(Map.of("subject", "alice", "predicate", "WORKS_AT", "object", "acme")));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);

        assertEquals("LIKELY_TRUE", r.get("verdict"),
                "Direct edge should produce LIKELY_TRUE: " + json);

        double score = ((Number) r.get("fusedScore")).doubleValue();
        assertTrue(score > 0.5, "fusedScore should be > 0.5 for direct edge: " + score);

        @SuppressWarnings("unchecked")
        List<Object> supporting = (List<Object>) r.get("supporting");
        assertNotNull(supporting);
        assertFalse(supporting.isEmpty(), "direct edge must produce at least one supporting signal");

        // Verify at least one supporting item has DIRECT_EDGE signal
        boolean hasDirectEdge = false;
        for (Object s : supporting) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sig = (Map<String, Object>) s;
            if ("DIRECT_EDGE".equals(sig.get("signal"))) {
                hasDirectEdge = true;
                break;
            }
        }
        assertTrue(hasDirectEdge, "Direct edge signal expected in supporting: " + supporting);
    }

    @Test
    void claim_absentEdge_notLikelyTrue() {
        // alice -WORKS_AT-> london does NOT exist; london has no relations
        String json = dispatcher.dispatch(session, "ask_graph_claim",
                MiniJson.write(Map.of("subject", "alice", "predicate", "WORKS_AT", "object", "london")));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);

        // Should NOT be LIKELY_TRUE since there is no direct edge
        assertNotEquals("LIKELY_TRUE", r.get("verdict"),
                "Absent edge should not be LIKELY_TRUE: " + json);
    }

    @Test
    void claim_missingSubject_returnsError() {
        String json = dispatcher.dispatch(session, "ask_graph_claim",
                MiniJson.write(Map.of("predicate", "WORKS_AT", "object", "acme")));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("ERROR", r.get("status"));
    }

    @Test
    void claim_hasClaimAtom() {
        String json = dispatcher.dispatch(session, "ask_graph_claim",
                MiniJson.write(Map.of("subject", "alice", "predicate", "WORKS_AT", "object", "acme")));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);
        String atom = (String) r.get("claimAtom");
        assertNotNull(atom, "claimAtom must be present");
        assertTrue(atom.contains("alice"), "claimAtom must mention subject: " + atom);
        assertTrue(atom.contains("acme"),  "claimAtom must mention object: " + atom);
    }

    // ── ask_graph_claim with KGE embeddings ──────────────────────────────────

    @Test
    void claim_withKgeVectors_surfacesEmbeddingSignalInSupporting() {
        // Build a graph WITH entity vectors so the KGE channel fires
        UnifiedGraph g = buildGraph();
        // Give alice and acme distinct embeddings that are highly similar
        g.putEntityVector("kge", "alice", new double[]{1.0, 0.0, 0.0});
        g.putEntityVector("kge", "acme",  new double[]{0.9, 0.1, 0.0}); // high cosine with alice
        g.putEntityVector("kge", "bob",   new double[]{0.0, 1.0, 0.0}); // orthogonal to alice
        g.putEntityVector("kge", "london",new double[]{0.0, 0.0, 1.0}); // orthogonal

        LocalReasoningSession kgeSession = LocalReasoningSession.of(g);
        LocalToolDispatcher kgeDispatcher = LocalToolDispatcher.create();

        // alice -WORKS_AT-> acme has direct edge + high embedding cosine -> LIKELY_TRUE
        String json = kgeDispatcher.dispatch(kgeSession, "ask_graph_claim",
                MiniJson.write(Map.of("subject", "alice", "predicate", "WORKS_AT", "object", "acme")));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);

        @SuppressWarnings("unchecked")
        List<Object> supporting = (List<Object>) r.get("supporting");
        assertNotNull(supporting, "supporting required: " + json);

        // At least one supporting item must be of kind KGE
        boolean hasKge = supporting.stream().anyMatch(item -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) item;
            String kind = String.valueOf(m.get("kind"));
            String desc = String.valueOf(m.getOrDefault("description", ""));
            return "KGE".equals(kind) || desc.contains("KGE") || desc.contains("calibrated");
        });
        assertTrue(hasKge, "Supporting items must include a KGE signal when vectors present: " + json);
    }

    @Test
    void claim_withoutKgeVectors_behavesExactlyAsBefore() {
        // Standard session (no vectors) — must still return without error
        String json = dispatcher.dispatch(session, "ask_graph_claim",
                MiniJson.write(Map.of("subject", "alice", "predicate", "WORKS_AT", "object", "acme")));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);
        assertNotNull(r.get("verdict"),    "verdict required: " + json);
        assertNotNull(r.get("fusedScore"), "fusedScore required: " + json);
    }

    @Test
    void claim_kgeVectorsAbsentForOneEntity_skipsKgeChannel() {
        // Only alice has a vector — object (london) does NOT
        UnifiedGraph g = buildGraph();
        g.putEntityVector("kge", "alice", new double[]{1.0, 0.0, 0.0});
        // london intentionally NOT added to the vector layer

        LocalReasoningSession partialSession = LocalReasoningSession.of(g);
        LocalToolDispatcher partialDispatcher = LocalToolDispatcher.create();

        // Should NOT crash and should return a valid dossier (KGE channel skipped cleanly)
        String json = partialDispatcher.dispatch(partialSession, "ask_graph_claim",
                MiniJson.write(Map.of("subject", "alice", "predicate", "WORKS_AT", "object", "london")));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);
        assertNotNull(r.get("verdict"), "verdict required even without KGE: " + json);
    }

    // ── ask_graph_synthesize ──────────────────────────────────────────────────

    @Test
    void synthesize_returnsTypedCandidate() {
        // Query for PERSON entities
        String json = dispatcher.dispatch(session, "ask_graph_synthesize",
                MiniJson.write(Map.of("query", "Alice", "expectedType", "PERSON", "maxCandidates", 10)));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);

        @SuppressWarnings("unchecked")
        List<Object> answers = (List<Object>) r.get("answers");
        assertNotNull(answers, "answers required: " + json);
        assertFalse(answers.isEmpty(), "Should find at least one PERSON entity");

        // Verify answer fields
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) answers.get(0);
        assertNotNull(first.get("entityId"),   "entityId required");
        assertNotNull(first.get("answer"),     "answer (label) required");
        assertNotNull(first.get("likelihood"), "likelihood required");
        assertNotNull(first.get("belief"),     "belief required");
        assertNotNull(first.get("uncertainty"), "uncertainty required");

        double likelihood = ((Number) first.get("likelihood")).doubleValue();
        assertTrue(likelihood >= 0.0 && likelihood <= 1.0,
                "likelihood must be in [0,1]: " + likelihood);
    }

    @Test
    void synthesize_textMatchBoostsCandidates() {
        // Query matching alice's label
        String json = dispatcher.dispatch(session, "ask_graph_synthesize",
                MiniJson.write(Map.of("query", "alice")));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);

        @SuppressWarnings("unchecked")
        List<Object> answers = (List<Object>) r.get("answers");
        assertFalse(answers.isEmpty());

        // alice should appear and should be highly ranked (text match + high weight)
        boolean aliceFound = false;
        for (Object a : answers) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) a;
            if ("alice".equals(item.get("entityId"))) {
                aliceFound = true;
                break;
            }
        }
        assertTrue(aliceFound, "alice should appear in synthesize results: " + json);
    }

    @Test
    void synthesize_missingQuery_returnsError() {
        String json = dispatcher.dispatch(session, "ask_graph_synthesize", "{}");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("ERROR", r.get("status"));
    }

    @Test
    void synthesize_answerCountMatchesListSize() {
        String json = dispatcher.dispatch(session, "ask_graph_synthesize",
                MiniJson.write(Map.of("query", "org")));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNoError(r, json);
        @SuppressWarnings("unchecked")
        List<Object> answers = (List<Object>) r.get("answers");
        Number answerCount = (Number) r.get("answerCount");
        assertEquals(answers.size(), answerCount.intValue(),
                "answerCount must match answers array size");
    }

    // ── Catalog registration ──────────────────────────────────────────────────

    @Test
    void inferenceToolsAppearInCatalog() {
        String json = dispatcher.dispatch(session, "tools_catalog", "{}");
        @SuppressWarnings("unchecked")
        List<Object> catalog = (List<Object>) MiniJson.parse(json);
        assertNotNull(catalog);

        List<String> names = catalog.stream()
                .map(e -> (String) ((Map<?, ?>) e).get("name"))
                .toList();

        assertTrue(names.contains("ask_graph_mebn"),     "ask_graph_mebn must be in catalog");
        assertTrue(names.contains("graph_bayes"),        "graph_bayes must be in catalog");
        assertTrue(names.contains("ask_graph_claim"),    "ask_graph_claim must be in catalog");
        assertTrue(names.contains("ask_graph_synthesize"), "ask_graph_synthesize must be in catalog");
    }

    // ── Graph fixture ─────────────────────────────────────────────────────────

    private static void storeLearnedTheory(UnifiedGraph graph, double worksAtStrength) {
        MTheory theory = RelationalMTheoryBuilder.build("mobile-learned", List.of(
                new RelationalMTheoryBuilder.RelationDescriptor("WORKS_AT", "PERSON", "ORG",
                        worksAtStrength, List.of("alice", "bob"), List.of("acme")),
                new RelationalMTheoryBuilder.RelationDescriptor("KNOWS", "PERSON", "PERSON",
                        0.35, List.of("alice"), List.of("bob"))));
        graph.putArtifactText(THEORY_ARTIFACT, RelationalMTheoryArtifactCodec.toJson(theory));
    }

    /**
     * Build a graph with meaningful weights (not all 1.0) and typed entities.
     * Weights are intentionally non-degenerate so Bayesian inference produces
     * non-trivial posteriors.
     */
    private static UnifiedGraph buildGraph() {
        UnifiedGraph g = new UnifiedGraph();

        // PERSON entities with varying weights
        g.addEntity(new SimpleGraphEntity("alice",  "PERSON",   "Alice",  0.8, 0.8, Set.of(),
                null, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("bob",    "PERSON",   "Bob",    0.6, 0.6, Set.of(),
                null, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("acme",   "ORG",      "Acme",   0.7, 0.7, Set.of(),
                null, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("london", "LOCATION", "London", 0.9, 0.9, Set.of(),
                null, null, Map.of()));

        // Relations with meaningful weights
        g.addRelation("r1", "alice", "acme",   "WORKS_AT", 0.9);
        g.addRelation("r2", "bob",   "acme",   "WORKS_AT", 0.7);
        g.addRelation("r3", "alice", "bob",    "KNOWS",    0.5);

        return g;
    }

    // ── Assertion helper ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String tool, Map<String, Object> args) {
        return (Map<String, Object>) MiniJson.parse(dispatcher.dispatch(session, tool, MiniJson.write(args)));
    }

    private static double probability(Map<String, Object> r, String field, String entityId) {
        return ((Number) ((Map<?, ?>) r.get(field)).get(entityId)).doubleValue();
    }

    private static void assertNoError(Map<String, Object> r, String json) {
        String status = (String) r.get("status");
        assertNotEquals("ERROR", status, "Unexpected ERROR response: " + json);
    }
}
