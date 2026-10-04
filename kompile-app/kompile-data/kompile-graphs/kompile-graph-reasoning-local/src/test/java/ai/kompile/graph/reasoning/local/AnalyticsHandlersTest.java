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

import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphKgeLifecycle;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AnalyticsHandlers}: graph_centrality and graph_embeddings tools.
 *
 * <p>All tests build graphs programmatically using {@link LocalReasoningSession#of(UnifiedGraph)}
 * and dispatch via {@link LocalToolDispatcher} — the same pattern used in the sibling
 * test classes in this module.</p>
 *
 * <h3>Graph fixture: hub-and-spoke</h3>
 * <p>Five entities: one hub (H) connected to four spokes (A, B, C, D).
 * Hub has the highest degree and should be top-ranked by both degree and pagerank.</p>
 */
class AnalyticsHandlersTest {

    /** Vector layer name used in embedding tests. */
    private static final String LAYER = "kge";

    // ── Hub-and-spoke graph (centrality tests) ────────────────────────────────

    private static LocalToolDispatcher dispatcher;

    /** Hub-and-spoke session: H→A, H→B, H→C, H→D. No embedding vectors. */
    private static LocalReasoningSession hubSession;

    /** Session with vectors in a named layer. */
    private static LocalReasoningSession embeddingSession;

    /** Session with NO embedding layers (to test the error path). */
    private static LocalReasoningSession noEmbedSession;

    @BeforeAll
    static void buildFixtures() {
        dispatcher = LocalToolDispatcher.create();

        // ── Hub-and-spoke graph ───────────────────────────────────────────────
        UnifiedGraph hub = new UnifiedGraph();
        hub.addEntity("H", "NODE", "Hub");
        hub.addEntity("A", "NODE", "SpokeA");
        hub.addEntity("B", "NODE", "SpokeB");
        hub.addEntity("C", "NODE", "SpokeC");
        hub.addEntity("D", "NODE", "SpokeD");
        hub.addRelation("r1", "H", "A", "LINKS", 1.0);
        hub.addRelation("r2", "H", "B", "LINKS", 1.0);
        hub.addRelation("r3", "H", "C", "LINKS", 1.0);
        hub.addRelation("r4", "H", "D", "LINKS", 1.0);
        hubSession = LocalReasoningSession.of(hub);

        // ── Embedding graph: two tight clusters + outlier ─────────────────────
        // alice and bob share a nearly identical vector; charlie is orthogonal
        //   alice:   [1.0, 0.0, 0.0]
        //   bob:     [0.99, 0.14, 0.0]  (close to alice)
        //   charlie: [0.0, 0.0, 1.0]    (orthogonal)
        UnifiedGraph embedGraph = new UnifiedGraph();
        embedGraph.addEntity("alice",   "PERSON", "Alice");
        embedGraph.addEntity("bob",     "PERSON", "Bob");
        embedGraph.addEntity("charlie", "PERSON", "Charlie");
        embedGraph.addRelation("r1", "alice", "bob",     "KNOWS",    1.0);
        embedGraph.addRelation("r2", "alice", "charlie", "KNOWS",    0.5);
        embedGraph.addRelation("r3", "bob",   "charlie", "PARTNER",  0.3);

        embedGraph.putEntityVector(LAYER, "alice",   new double[]{1.0, 0.0, 0.0});
        embedGraph.putEntityVector(LAYER, "bob",     new double[]{0.99, 0.14, 0.0});
        embedGraph.putEntityVector(LAYER, "charlie", new double[]{0.0, 0.0, 1.0});
        embeddingSession = LocalReasoningSession.of(embedGraph);

        // ── No-embedding graph ────────────────────────────────────────────────
        UnifiedGraph plain = new UnifiedGraph();
        plain.addEntity("x", "NODE", "X");
        plain.addEntity("y", "NODE", "Y");
        plain.addRelation("rx", "x", "y", "EDGE", 1.0);
        noEmbedSession = LocalReasoningSession.of(plain);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // graph_centrality — degree
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void degreeCentralityHubRanksFirst() {
        String json = dispatcher.dispatch(hubSession, "graph_centrality",
                "{\"algorithm\":\"degree\",\"degree_type\":\"both\",\"top_k\":5}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"), "Expected OK: " + json);

        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) res.get("scores");
        assertNotNull(scores, "scores must be present");

        // Hub H should have the highest score (most edges)
        String topId = scores.keySet().iterator().next(); // LinkedHashMap insertion order = sorted desc
        assertEquals("H", topId, "Hub must rank first in degree centrality. scores=" + scores);
    }

    @Test
    void degreeOutCentralityHubHasHighestOut() {
        String json = dispatcher.dispatch(hubSession, "graph_centrality",
                "{\"algorithm\":\"degree\",\"degree_type\":\"out\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) res.get("scores");
        // Hub is the only node with out-edges
        String top = scores.keySet().iterator().next();
        assertEquals("H", top, "Hub must have highest out-degree. scores=" + scores);
    }

    @Test
    void degreeInCentralitySpokeHasHighestIn() {
        String json = dispatcher.dispatch(hubSession, "graph_centrality",
                "{\"algorithm\":\"degree\",\"degree_type\":\"in\",\"top_k\":20}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) res.get("scores");
        // Spokes A,B,C,D each have in-degree=1; hub has in-degree=0
        // The first entry must NOT be the hub (hub has 0 in-degree)
        String top = scores.keySet().iterator().next();
        assertNotEquals("H", top, "Hub has 0 in-edges; a spoke must rank first. scores=" + scores);
    }

    @Test
    void degreeMissingAlgorithmReturnsError() {
        String json = dispatcher.dispatch(hubSession, "graph_centrality", "{}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("ERROR", res.get("status"));
    }

    @Test
    void degreeUnknownAlgorithmReturnsError() {
        String json = dispatcher.dispatch(hubSession, "graph_centrality",
                "{\"algorithm\":\"foobar\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("ERROR", res.get("status"));
        assertTrue(res.get("message").toString().contains("foobar"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // graph_centrality — pagerank
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void pageRankHubRanksFirst() {
        String json = dispatcher.dispatch(hubSession, "graph_centrality",
                "{\"algorithm\":\"pagerank\",\"top_k\":5}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"), "Expected OK: " + json);

        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) res.get("scores");
        String topId = scores.keySet().iterator().next();
        assertEquals("H", topId, "Hub must rank first in PageRank. scores=" + scores);
    }

    @Test
    void pageRankScoresSumToApproximately1() {
        String json = dispatcher.dispatch(hubSession, "graph_centrality",
                "{\"algorithm\":\"pagerank\",\"top_k\":10}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) res.get("scores");
        double sum = scores.values().stream()
                .mapToDouble(v -> ((Number) v).doubleValue())
                .sum();
        // With all 5 nodes, sum should be close to 1.0 (PageRank conserves mass)
        assertEquals(1.0, sum, 0.01, "PageRank scores should sum to ~1.0, got " + sum);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // graph_centrality — betweenness
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void betweennessHubRanksFirst() {
        String json = dispatcher.dispatch(hubSession, "graph_centrality",
                "{\"algorithm\":\"betweenness\",\"top_k\":5}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"), "Expected OK: " + json);

        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) res.get("scores");
        String topId = scores.keySet().iterator().next();
        assertEquals("H", topId, "Hub must rank first in betweenness. scores=" + scores);
    }

    @Test
    void centralityTopKLimitsResults() {
        String json = dispatcher.dispatch(hubSession, "graph_centrality",
                "{\"algorithm\":\"pagerank\",\"top_k\":3}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) res.get("scores");
        assertTrue(scores.size() <= 3, "top_k=3 must yield at most 3 results, got " + scores.size());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // graph_embeddings — no-embed error path
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void noEmbedSimilarReturnsError() {
        String json = dispatcher.dispatch(noEmbedSession, "graph_embeddings",
                "{\"action\":\"similar\",\"entity_name\":\"X\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("ERROR", res.get("status"), "No-embed graph must return ERROR. response=" + json);
        assertTrue(res.get("message").toString().contains("no embedding layers"),
                "Message should mention no embedding layers: " + res.get("message"));
    }

    @Test
    void noEmbedScoreReturnsError() {
        String json = dispatcher.dispatch(noEmbedSession, "graph_embeddings",
                "{\"action\":\"score\",\"head\":\"x\",\"tail\":\"y\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("ERROR", res.get("status"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // graph_embeddings — similar (cosine)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void similarAliceReturnsBobFirst() {
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"similar\",\"entity_name\":\"alice\",\"layer\":\"" + LAYER + "\",\"top_k\":2}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"), "Expected OK: " + json);

        @SuppressWarnings("unchecked")
        List<Object> results = (List<Object>) res.get("results");
        assertNotNull(results);
        assertFalse(results.isEmpty(), "similar must return at least one result");

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) results.get(0);
        assertEquals("bob", first.get("entityId"),
                "Alice's nearest neighbour by cosine must be Bob. results=" + results);
        assertTrue(first.containsKey("similarity"), "Each result must have 'similarity'");
    }

    @Test
    void similarByLabelResolution() {
        // "Alice" (label) should resolve to "alice" (id)
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"similar\",\"entity_name\":\"Alice\",\"layer\":\"" + LAYER + "\",\"top_k\":2}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"), "Expected OK: " + json);
        assertEquals("alice", res.get("queryEntity"), "queryEntity should be the resolved id");
    }

    @Test
    void similarUnknownEntityReturnsError() {
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"similar\",\"entity_name\":\"nobody\",\"layer\":\"" + LAYER + "\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("ERROR", res.get("status"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // graph_embeddings — score
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void vectorsWithoutModelNeverPretendToBeTrainedKge() {
        for (String action : List.of("score", "predict_tails", "predict_heads", "predict_relations")) {
            Map<String, Object> res = embeddingResult(embeddingSession.graph(), Map.of(
                    "action", action, "head", "alice", "tail", "bob", "relation", "KNOWS"));
            assertEquals("ERROR", res.get("status"), action + ": " + res);
            assertTrue(res.get("message").toString().contains("No trained KGE model"));
            assertFalse(res.containsKey("score"));
            assertFalse(res.containsKey("predictions"));
        }
    }

    @Test
    void transeScoreUsesRelationTranslationNotCosine() {
        UnifiedGraph graph = trainedGraph("TRANSE");
        // Alice and Bob are orthogonal. The learned translation makes KNOWS a perfect triple.
        assertEquals(1.0, score(graph, "Alice", "knows", "Bob"), 1e-12);
        assertEquals(0.5, score(graph, "alice", "PARTNER", "bob"), 1e-12);
        assertEquals(1.0 / (1.0 + Math.sqrt(5.0)), score(graph, "bob", "PARTNER", "alice"), 1e-12);
    }

    @Test
    void rotateScoreUsesComplexRotationNotCosineOrTranslation() {
        UnifiedGraph graph = trainedGraph("ROTATE");
        assertEquals(1.0, score(graph, "Alice", "knows", "Bob"), 1e-12);
        assertEquals(1.0 / 3.0, score(graph, "alice", "PARTNER", "bob"), 1e-12);
        assertEquals(1.0 / (1.0 + 2.0 * Math.sqrt(2.0)),
                score(graph, "bob", "KNOWS", "alice"), 1e-12);
    }

    @Test
    void scoreMissingTailReturnsError() {
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"score\",\"head\":\"alice\",\"layer\":\"" + LAYER + "\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("ERROR", res.get("status"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // graph_embeddings — predict_tails / predict_heads
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void predictTailsReturnsRankedList() {
        String json = dispatcher.dispatch(LocalReasoningSession.of(trainedGraph("TRANSE")), "graph_embeddings",
                "{\"action\":\"predict_tails\",\"head\":\"alice\",\"relation\":\"KNOWS\",\"top_k\":2}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"), "Expected OK: " + json);
        @SuppressWarnings("unchecked")
        List<Object> predictions = (List<Object>) res.get("predictions");
        assertNotNull(predictions);
        assertFalse(predictions.isEmpty());
        // First prediction should be bob (perfect learned relation translation).
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) predictions.get(0);
        assertEquals("bob", first.get("entityId"),
                "predict_tails for alice must rank bob first. predictions=" + predictions);
    }

    @Test
    void predictHeadsReturnsRankedList() {
        String json = dispatcher.dispatch(LocalReasoningSession.of(trainedGraph("TRANSE")), "graph_embeddings",
                "{\"action\":\"predict_heads\",\"tail\":\"bob\",\"relation\":\"KNOWS\",\"top_k\":2}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"), "Expected OK: " + json);
        @SuppressWarnings("unchecked")
        List<Object> predictions = (List<Object>) res.get("predictions");
        assertFalse(predictions.isEmpty());
    }

    @Test
    void predictTailsMissingHeadReturnsError() {
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"predict_tails\",\"layer\":\"" + LAYER + "\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("ERROR", res.get("status"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // graph_embeddings — predict_relations
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void predictRelationsReturnsList() {
        String json = dispatcher.dispatch(LocalReasoningSession.of(trainedGraph("TRANSE")), "graph_embeddings",
                "{\"action\":\"predict_relations\",\"head\":\"alice\",\"tail\":\"bob\",\"top_k\":5}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"), "Expected OK: " + json);
        @SuppressWarnings("unchecked")
        List<Object> predictions = (List<Object>) res.get("predictions");
        assertNotNull(predictions);
        assertFalse(predictions.isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) predictions.get(0);
        assertTrue(first.containsKey("relation"), "Each prediction must have 'relation'");
        assertTrue(first.containsKey("score"),    "Each prediction must have 'score'");
    }

    @Test
    void bothAlgorithmsRankAllPredictionTargetsByTrainedScore() {
        for (String algorithm : List.of("TRANSE", "ROTATE")) {
            UnifiedGraph graph = trainedGraph(algorithm);
            Map<String, Object> tails = embeddingResult(graph, Map.of("action", "predict_tails",
                    "head", "Alice", "relation", "knows", "top_k", 1));
            assertEquals(algorithm, tails.get("algorithm"));
            assertEquals("bob", firstPrediction(tails).get("entityId"));
            assertEquals(1.0, ((Number) firstPrediction(tails).get("score")).doubleValue(), 1e-12);
            assertEquals(1, ((List<?>) tails.get("predictions")).size());
            Map<String, Object> heads = embeddingResult(graph, Map.of("action", "predict_heads",
                    "tail", "Bob", "relation", "KNOWS", "top_k", 1));
            assertEquals("alice", firstPrediction(heads).get("entityId"));
            assertEquals(1.0, ((Number) firstPrediction(heads).get("score")).doubleValue(), 1e-12);
            Map<String, Object> relations = embeddingResult(graph, Map.of("action", "predict_relations",
                    "head", "alice", "tail", "bob", "top_k", 2));
            assertEquals("KNOWS", firstPrediction(relations).get("relation"));
            assertFalse(relations.containsKey("pairCosine"));
            // Changing only the relation changes the best tail, including self for identity rotation.
            Map<String, Object> other = embeddingResult(graph, Map.of("action", "predict_tails",
                    "head", "alice", "relation", "PARTNER", "top_k", 1));
            assertEquals("ROTATE".equals(algorithm) ? "alice" : "charlie",
                    firstPrediction(other).get("entityId"));
        }
    }

    @Test
    void requiredIdentifiersAndUnknownModelCoverageReturnErrors() {
        UnifiedGraph graph = trainedGraph("TRANSE");
        for (Map<String, Object> args : List.<Map<String, Object>>of(
                Map.of("action", "score", "head", "alice", "tail", "bob"),
                Map.of("action", "score", "head", "alice", "tail", "bob", "relation", ""),
                Map.of("action", "score", "head", "alice", "relation", "KNOWS"),
                Map.of("action", "predict_tails", "relation", "KNOWS"),
                Map.of("action", "predict_heads", "tail", "bob"),
                Map.of("action", "predict_relations", "head", "alice"),
                Map.of("action", "score", "head", "alice", "tail", "nobody", "relation", "KNOWS"),
                Map.of("action", "predict_tails", "head", "alice", "relation", "UNKNOWN"),
                Map.of("action", "score", "head", "alice", "tail", "bob", "relation", "KNOWS", "layer", "sentence"))) {
            assertEquals("ERROR", embeddingResult(graph, args).get("status"), args.toString());
        }
        graph.vectorLayer(LAYER).remove("bob");
        assertKgeError(graph, "no trained KGE vector");
        Map<String, Object> result = embeddingResult(graph, Map.of("action", "predict_tails",
                "head", "alice", "relation", "KNOWS"));
        assertEquals("OK", result.get("status"));
        assertTrue(((List<?>) result.get("predictions")).stream()
                .noneMatch(row -> "bob".equals(((Map<?, ?>) row).get("entityId"))));
    }

    @Test
    void staleFlagAndMissingOrMismatchedCodeReceiptsRejectKgeButNotSimilarity() {
        for (String algorithm : List.of("TRANSE", "ROTATE")) {
            UnifiedGraph graph = trainedGraph(algorithm);
            graph.meta("learning.kgeStale", true);
            for (String action : List.of("score", "predict_tails", "predict_heads", "predict_relations")) {
                Map<String, Object> res = embeddingResult(graph, Map.of("action", action,
                        "head", "alice", "tail", "bob", "relation", "KNOWS"));
                assertEquals("ERROR", res.get("status"));
                assertTrue(res.get("message").toString().contains("stale"));
            }
            assertEquals("OK", embeddingResult(graph, Map.of("action", "similar", "entity_name", "alice")).get("status"));
            graph.meta("learning.kgeStale", false).meta("codeIndexGeneration.p", "g2");
            assertKgeError(graph, "generation receipt");
            graph.meta("codeKgeGeneration.p", "g1");
            assertKgeError(graph, "generation receipt");
            graph.meta("codeKgeGeneration.p", "g2");
            assertEquals(1.0, score(graph, "alice", "KNOWS", "bob"), 1e-12);
        }
    }

    @Test
    void malformedMetadataDoesNotDefaultToTranseOrGuessDimensions() {
        for (String artifact : List.of("{", "[]", "null", "{}",
                "{\"algorithm\":\"DISTMULT\",\"embeddingDim\":2}",
                "{\"algorithm\":\"TRANSE\"}",
                "{\"algorithm\":\"TRANSE\",\"embeddingDim\":\"2\"}",
                "{\"algorithm\":\"TRANSE\",\"embeddingDim\":0}",
                "{\"algorithm\":\"TRANSE\",\"embeddingDim\":-1}",
                "{\"algorithm\":\"TRANSE\",\"embeddingDim\":1.5}",
                "{\"algorithm\":\"TRANSE\",\"embeddingDim\":257}",
                "{\"algorithm\":\"TRANSE\",\"embeddingDim\":2} trailing")) {
            UnifiedGraph graph = trainedGraph("TRANSE");
            graph.putArtifactText(UnifiedGraphKgeLifecycle.MODEL_ARTIFACT, artifact);
            assertEquals("ERROR", embeddingResult(graph, Map.of("action", "score",
                    "head", "alice", "tail", "bob", "relation", "KNOWS")).get("status"), artifact);
        }
    }

    @Test
    void relationPredictionsUseTrainedRowsAndDeterministicTies() {
        for (String algorithm : List.of("TRANSE", "ROTATE")) {
            UnifiedGraph graph = trainedGraph(algorithm);
            // A learned relation need not have a currently observed edge.
            graph.vectorLayer("kge-relations").put("A_LEARNED",
                    graph.vectorLayer("kge-relations").get("KNOWS").clone());
            Map<String, Object> result = embeddingResult(graph, Map.of("action", "predict_relations",
                    "head", "alice", "tail", "bob", "top_k", 0));
            assertEquals("A_LEARNED", firstPrediction(result).get("relation"));
            assertEquals(1, ((List<?>) result.get("predictions")).size());
        }
    }

    @Test
    void missingWrongTargetAndWrongDimensionLayersAreErrors() {
        UnifiedGraph missing = new UnifiedGraph();
        missing.addEntity("alice", "PERSON", "Alice");
        missing.addEntity("bob", "PERSON", "Bob");
        missing.putArtifactText(UnifiedGraphKgeLifecycle.MODEL_ARTIFACT,
                "{\"algorithm\":\"TRANSE\",\"embeddingDim\":2}");
        assertKgeError(missing, "Missing trained KGE");
        UnifiedGraph graph = trainedGraph("TRANSE");
        graph.putVectorLayer(new VectorLayer("kge-relations", VectorLayer.Target.GLOBAL, 2, Dtype.F64));
        assertKgeError(graph, "Missing trained KGE");
        for (VectorLayer.Target target : List.of(VectorLayer.Target.ENTITY, VectorLayer.Target.RELATION)) {
            graph = trainedGraph("TRANSE");
            graph.putVectorLayer(new VectorLayer("kge-relations", target, 2, Dtype.F64)
                    .put("KNOWS", new double[]{-1, 1}));
            assertKgeError(graph, "target or dimensions");
        }
        graph = trainedGraph("ROTATE");
        graph.putVectorLayer(new VectorLayer(LAYER, VectorLayer.Target.ENTITY, 2, Dtype.F64)
                .put("alice", new double[]{1, 0}).put("bob", new double[]{0, 1}));
        assertKgeError(graph, "target or dimensions");
        graph = trainedGraph("TRANSE");
        graph.putVectorLayer(new VectorLayer("kge-relations", VectorLayer.Target.GLOBAL, 1, Dtype.F64)
                .put("KNOWS", new double[]{1}));
        assertKgeError(graph, "target or dimensions");
    }

    @Test
    void nonFiniteEntityAndRelationVectorsAndOverflowNeverBecomeScores() {
        for (String algorithm : List.of("TRANSE", "ROTATE")) {
            for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                for (String layer : List.of(LAYER, "kge-relations")) {
                    UnifiedGraph graph = trainedGraph(algorithm);
                    graph.vectorLayer(layer).get(LAYER.equals(layer) ? "alice" : "KNOWS")[0] = value;
                    assertKgeError(graph, "Non-finite trained KGE vector");
                }
            }
        }
        UnifiedGraph graph = trainedGraph("TRANSE");
        graph.vectorLayer(LAYER).get("alice")[0] = Double.MAX_VALUE;
        assertKgeError(graph, "Non-finite trained KGE distance");
    }

    private static UnifiedGraph trainedGraph(String algorithm) {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity("alice", "PERSON", "Alice");
        graph.addEntity("bob", "PERSON", "Bob");
        graph.addEntity("charlie", "PERSON", "Charlie");
        graph.addRelation("r1", "alice", "bob", "KNOWS", 1.0);
        // PARTNER is more frequent, so frequency-based relation proxies would rank it incorrectly.
        graph.addRelation("r2", "bob", "charlie", "PARTNER", 0.5);
        graph.addRelation("r3", "alice", "charlie", "PARTNER", 0.5);
        boolean rotate = "ROTATE".equals(algorithm);
        VectorLayer entities = new VectorLayer(LAYER, VectorLayer.Target.ENTITY, rotate ? 4 : 2, Dtype.F64);
        entities.put("alice", rotate ? new double[]{1, 0, 0, 1} : new double[]{1, 0});
        entities.put("bob", rotate ? new double[]{0, -1, 1, 0} : new double[]{0, 1});
        entities.put("charlie", rotate ? new double[]{-1, 0, 0, -1} : new double[]{1, 1});
        VectorLayer relations = new VectorLayer("kge-relations", VectorLayer.Target.GLOBAL, 2, Dtype.F64);
        relations.put("KNOWS", rotate ? new double[]{Math.PI / 2, Math.PI / 2} : new double[]{-1, 1});
        relations.put("PARTNER", rotate ? new double[]{0, 0} : new double[]{0, 1});
        graph.putVectorLayer(entities).putVectorLayer(relations);
        graph.putArtifactText(UnifiedGraphKgeLifecycle.MODEL_ARTIFACT,
                MiniJson.write(Map.of("algorithm", algorithm, "embeddingDim", 2)));
        return graph;
    }

    private static Map<String, Object> embeddingResult(UnifiedGraph graph, Map<String, Object> args) {
        return MiniJson.parseObject(dispatcher.dispatch(LocalReasoningSession.of(graph),
                "graph_embeddings", MiniJson.write(args)));
    }

    private static double score(UnifiedGraph graph, String head, String relation, String tail) {
        Map<String, Object> result = embeddingResult(graph, Map.of("action", "score",
                "head", head, "relation", relation, "tail", tail));
        assertEquals("OK", result.get("status"), result.toString());
        return ((Number) result.get("score")).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstPrediction(Map<String, Object> result) {
        assertEquals("OK", result.get("status"), result.toString());
        return (Map<String, Object>) ((List<?>) result.get("predictions")).get(0);
    }

    private static void assertKgeError(UnifiedGraph graph, String message) {
        Map<String, Object> result = embeddingResult(graph, Map.of("action", "score",
                "head", "alice", "relation", "KNOWS", "tail", "bob"));
        assertEquals("ERROR", result.get("status"), result.toString());
        assertTrue(result.get("message").toString().contains(message), result.toString());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // graph_embeddings — algorithms
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void algorithmsListsAvailableLayers() {
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"algorithms\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"), "Expected OK: " + json);
        @SuppressWarnings("unchecked")
        List<Object> layers = (List<Object>) res.get("layers");
        assertNotNull(layers);
        assertFalse(layers.isEmpty(), "Should list at least the '" + LAYER + "' layer");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) layers.get(0);
        assertEquals(LAYER, first.get("layer"), "Layer name should be '" + LAYER + "'");
    }

    @Test
    void algorithmsOnNoEmbedGraphReturnsOkWithEmptyList() {
        String json = dispatcher.dispatch(noEmbedSession, "graph_embeddings",
                "{\"action\":\"algorithms\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"));
        @SuppressWarnings("unchecked")
        List<Object> layers = (List<Object>) res.get("layers");
        assertTrue(layers.isEmpty(), "No layers should be listed for a plain graph");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // graph_embeddings — UNSUPPORTED training actions
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void trainActionReturnsUnsupported() {
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"train\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("UNSUPPORTED", res.get("status"),
                "Train action must return UNSUPPORTED. response=" + json);
        assertTrue(res.get("message").toString().contains("training is server-side"),
                "Message should state server-side training: " + res.get("message"));
    }

    @Test
    void jobsActionReturnsUnsupported() {
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"jobs\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("UNSUPPORTED", res.get("status"));
    }

    @Test
    void cancelActionReturnsUnsupported() {
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"cancel\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("UNSUPPORTED", res.get("status"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Catalog parity — analytics tools must appear in dispatcher catalog
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void analyticsToolsAppearsInCatalog() {
        List<String> known = dispatcher.knownToolsList();
        assertTrue(known.contains("graph_centrality"),
                "graph_centrality must be registered. known=" + known);
        assertTrue(known.contains("graph_embeddings"),
                "graph_embeddings must be registered. known=" + known);
    }

    @Test
    void catalogEntriesHaveRequiredFields() {
        String catalogJson = dispatcher.catalog().toJson();
        Object parsed = MiniJson.parse(catalogJson);
        assertInstanceOf(List.class, parsed);
        @SuppressWarnings("unchecked")
        List<Object> entries = (List<Object>) parsed;

        boolean hasCentrality = false;
        boolean hasEmbeddings = false;
        for (Object obj : entries) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) obj;
            String name = (String) entry.get("name");
            assertTrue(entry.containsKey("description"), "Entry '" + name + "' needs description");
            assertTrue(entry.containsKey("parameters"),  "Entry '" + name + "' needs parameters");
            if ("graph_centrality".equals(name)) hasCentrality = true;
            if ("graph_embeddings".equals(name)) hasEmbeddings = true;
        }
        assertTrue(hasCentrality, "Catalog must contain graph_centrality");
        assertTrue(hasEmbeddings, "Catalog must contain graph_embeddings");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // factSheetId / graph_id ignored contract
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void centralityIgnoresFactSheetId() {
        // factSheetId should be silently ignored — call must still succeed
        String json = dispatcher.dispatch(hubSession, "graph_centrality",
                "{\"algorithm\":\"degree\",\"factSheetId\":99,\"graph_id\":\"foo\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"), "factSheetId/graph_id must be silently ignored. response=" + json);
    }

    @Test
    void embeddingsSimilarIgnoresGraphId() {
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"similar\",\"entity_name\":\"alice\",\"graph_id\":\"ignored\",\"layer\":\"" + LAYER + "\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"), "graph_id must be silently ignored. response=" + json);
    }
}
