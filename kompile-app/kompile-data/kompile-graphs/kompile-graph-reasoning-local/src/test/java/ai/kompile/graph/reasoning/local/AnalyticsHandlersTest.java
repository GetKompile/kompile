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

import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
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
    void scoreReturnsNumberBetweenMinusOneAndOne() {
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"score\",\"head\":\"alice\",\"tail\":\"bob\",\"layer\":\"" + LAYER + "\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"), "Expected OK: " + json);
        assertTrue(res.containsKey("score"), "Response must contain 'score'");
        double score = ((Number) res.get("score")).doubleValue();
        assertTrue(score >= -1.0 && score <= 1.0,
                "Cosine score must be in [-1,1], got " + score);
    }

    @Test
    void scoreAliceVsBobIsHigherThanAliceVsCharlie() {
        String jsonClose = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"score\",\"head\":\"alice\",\"tail\":\"bob\",\"layer\":\"" + LAYER + "\"}");
        String jsonFar = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"score\",\"head\":\"alice\",\"tail\":\"charlie\",\"layer\":\"" + LAYER + "\"}");

        @SuppressWarnings("unchecked")
        double close = ((Number) ((Map<String, Object>) MiniJson.parse(jsonClose)).get("score")).doubleValue();
        @SuppressWarnings("unchecked")
        double far   = ((Number) ((Map<String, Object>) MiniJson.parse(jsonFar)).get("score")).doubleValue();

        assertTrue(close > far,
                "alice-bob cosine (" + close + ") must be higher than alice-charlie (" + far + ")");
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
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"predict_tails\",\"head\":\"alice\",\"layer\":\"" + LAYER + "\",\"top_k\":2}");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) MiniJson.parse(json);
        assertEquals("OK", res.get("status"), "Expected OK: " + json);
        @SuppressWarnings("unchecked")
        List<Object> predictions = (List<Object>) res.get("predictions");
        assertNotNull(predictions);
        assertFalse(predictions.isEmpty());
        // First prediction should be bob (closest to alice)
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) predictions.get(0);
        assertEquals("bob", first.get("entityId"),
                "predict_tails for alice must rank bob first. predictions=" + predictions);
    }

    @Test
    void predictHeadsReturnsRankedList() {
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"predict_heads\",\"tail\":\"alice\",\"layer\":\"" + LAYER + "\",\"top_k\":2}");
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
        String json = dispatcher.dispatch(embeddingSession, "graph_embeddings",
                "{\"action\":\"predict_relations\",\"head\":\"alice\",\"tail\":\"bob\",\"layer\":\"" + LAYER + "\",\"top_k\":5}");
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
