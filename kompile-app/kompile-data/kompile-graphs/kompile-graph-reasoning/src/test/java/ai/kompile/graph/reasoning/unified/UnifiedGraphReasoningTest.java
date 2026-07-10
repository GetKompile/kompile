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
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.bayesian.GraphBayesianNetworkBuilder;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.OpinionStore;
import ai.kompile.graph.reasoning.embedding.Embeddings;
import ai.kompile.graph.reasoning.embedding.kge.KgeEmbeddingTableBridge;
import ai.kompile.graph.reasoning.embedding.learn.EmbeddingTable;
import ai.kompile.graph.reasoning.embedding.learn.EmbeddingTableIO;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves a graph imported <em>on its own</em> (pure library, no infra) is immediately reasonable
 * upon — topology, opinions, and any weight-vector layer are all reachable by the engines with no
 * manual bridging.
 */
class UnifiedGraphReasoningTest {

    private UnifiedGraph sampleGraph() {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity(new SimpleGraphEntity("n1", "PERSON", "Alice", 0.9, 0.9, Set.of(),
                new double[] {0.1, 0.2}, null, Map.of()));   // primary (sentence) embedding
        g.addEntity(new SimpleGraphEntity("n2", "ORG", "Acme", 1.0, 1.0, Set.of(),
                new double[] {0.3, 0.4}, null, Map.of()));
        g.addRelation("r1", "n1", "n2", "WORKS_AT", 0.8);
        g.putEntityOpinion("n1", Opinion.fromBetaEvidence(8, 2));
        // A distinct KGE layer over the same nodes (values are F32-exact).
        g.putEntityVector("kge", "n1", new double[] {1.0, 0.0, 0.0});
        g.putEntityVector("kge", "n2", new double[] {0.0, 1.0, 0.0});
        g.factSheetId(42L);
        return g;
    }

    @Test
    void importedGraphReasonsWithNoBridging() throws IOException {
        // Round-trip through the single-file format, then reason on the FRESHLY IMPORTED graph.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        sampleGraph().save(bos, Dtype.F64);
        UnifiedGraph g = UnifiedGraph.load(new ByteArrayInputStream(bos.toByteArray()));

        // 1. It IS a ReasoningGraph — an engine consumes it directly.
        BayesianNetwork net = new GraphBayesianNetworkBuilder().build(g);
        assertNotNull(net);

        // 2. Opinions are reasoning-ready as an OpinionStore (no side-map plumbing).
        OpinionStore opinions = g.opinionStore();
        assertTrue(opinions.has("n1"));
        assertEquals(Opinion.fromBetaEvidence(8, 2), opinions.get("n1"));

        // 3. Any vector layer is reasoning-ready as an EmbeddingTable (KGE scorers, PSL evidence).
        EmbeddingTable kge = g.embeddingTable("kge");
        assertNotNull(kge);
        assertArrayEquals(new double[] {1.0, 0.0, 0.0}, kge.vector("n1"), 0.0);
        assertNull(g.embeddingTable("does-not-exist"));

        // 4. Similarity can run over ANY layer, not just the primary embedding.
        ReasoningGraph kgeView = g.withEmbeddingLayer("kge");
        assertArrayEquals(new double[] {1.0, 0.0, 0.0},
                kgeView.entity("n1").orElseThrow().embedding(), 0.0);
        List<Embeddings.SemanticHit> hits =
                Embeddings.mostSimilar(kgeView, new double[] {1.0, 0.0, 0.0}, 2);
        assertEquals("n1", hits.get(0).entityId()); // n1's KGE vector equals the query

        // The primary (sentence) embedding is untouched by the layer view.
        assertArrayEquals(new double[] {0.1, 0.2}, g.entity("n1").orElseThrow().embedding(), 0.0);
    }

    @Test
    void neighborhoodPreservesAnalysisBundleForReasoning() {
        UnifiedGraph g = sampleGraph();
        g.addEntity(new SimpleGraphEntity("n3", "RISK", "Risk", 0.3, 0.3, Set.of(),
                new double[] {0.8, 0.9}, null, Map.of()));
        g.addRelation("r2", "n2", "n3", "CAUSES", 0.4);
        g.putRelationOpinion("r1", Opinion.fromBetaEvidence(3, 1));
        g.putRelationOpinion("r2", Opinion.fromBetaEvidence(1, 3));
        g.putEntityOpinion("n3", Opinion.fromBetaEvidence(1, 4));
        g.putEntityVector("kge", "n3", new double[] {0.0, 0.0, 1.0});
        g.putWeightMap("pslWeights", Map.of("WORKS_AT", 0.8));
        g.putArtifactText("model.json", "{\"ok\":true}");

        UnifiedGraph neighborhood = g.neighborhood(List.of("n1"), 1);

        assertEquals(2, neighborhood.entityCount());
        assertEquals(1, neighborhood.relationCount());
        assertTrue(neighborhood.containsEntity("n1"));
        assertTrue(neighborhood.containsEntity("n2"));
        assertTrue(neighborhood.entity("n3").isEmpty());
        assertEquals(2, neighborhood.vectorLayer("kge").size());
        assertArrayEquals(new double[] {1.0, 0.0, 0.0},
                neighborhood.vectorLayer("kge").get("n1"), 0.0);
        assertNull(neighborhood.vectorLayer("kge").get("n3"));
        assertNotNull(neighborhood.entityOpinion("n1"));
        assertNull(neighborhood.entityOpinion("n3"));
        assertNotNull(neighborhood.relationOpinion("r1"));
        assertNull(neighborhood.relationOpinion("r2"));
        assertEquals(0.8, neighborhood.weightMap("pslWeights").get("WORKS_AT"), 1e-9);
        assertEquals("{\"ok\":true}", neighborhood.artifactText("model.json"));
        assertEquals(42L, neighborhood.factSheetId());
    }

    @Test
    void bundlesAndRestoresARealModelArtifactViaItsOwnSerializer() throws IOException {
        // A real model (an EmbeddingTable) serialized with its own IO rides inside the .kgraph and
        // comes back usable — the archive is the single container for the whole reasoning bundle.
        EmbeddingTable table = KgeEmbeddingTableBridge.fromVectorMap(
                Map.of("a", new double[] {1.0, 2.0}, "b", new double[] {3.0, 4.0}));

        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("a", "T", "A");
        g.putArtifactText("embeddings.json", EmbeddingTableIO.toJson(table));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        g.save(bos);
        UnifiedGraph back = UnifiedGraph.load(new ByteArrayInputStream(bos.toByteArray()));

        EmbeddingTable restored = EmbeddingTableIO.fromJson(back.artifactText("embeddings.json"));
        assertArrayEquals(new double[] {1.0, 2.0}, restored.vector("a"), 1e-9);
    }
}
