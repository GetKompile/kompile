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
import ai.kompile.graph.reasoning.embedding.learn.EmbeddingConfig;
import ai.kompile.graph.reasoning.embedding.learn.EmbeddingTable;
import ai.kompile.graph.reasoning.embedding.learn.Node2VecLearner;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.mebn.type.TypeRegistry;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.sparse.SparsityMetrics;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a broad slice of the reasoning library from a SINGLE {@link UnifiedGraph} fixture — the
 * pattern for a library-wide test suite. Every capability here is reached from the one graph object:
 * directly (it IS a {@link ReasoningGraph}), via {@link UnifiedGraph#mutableGraph()}, via a model
 * built from it, or via its accessors ({@code opinionStore}, {@code embeddingTable},
 * {@code withEmbeddingLayer}, {@code putModel}/{@code model}).
 */
class UnifiedGraphLibraryIntegrationTest {

    /** One fixture that feeds every engine: topology, primary embeddings, a KGE layer, an opinion. */
    private UnifiedGraph fixture() {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity(new SimpleGraphEntity("n1", "PERSON", "Alice", 0.9, 0.9, Set.of(),
                new double[] {0.1, 0.2}, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("n2", "ORG", "Acme", 1.0, 1.0, Set.of(),
                new double[] {0.3, 0.4}, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("n3", "PERSON", "Bob", 0.8, 0.8, Set.of(),
                new double[] {0.5, 0.6}, null, Map.of()));
        g.addRelation("r1", "n1", "n2", "WORKS_AT", 0.8);
        g.addRelation("r2", "n3", "n2", "WORKS_AT", 0.7);
        g.addRelation("r3", "n1", "n3", "KNOWS", 0.6);
        g.putEntityOpinion("n1", Opinion.fromBetaEvidence(8, 2));
        g.putEntityVector("kge", "n1", new double[] {1.0, 0.0, 0.0});
        g.putEntityVector("kge", "n2", new double[] {0.0, 1.0, 0.0});
        g.putEntityVector("kge", "n3", new double[] {0.0, 0.0, 1.0});
        return g;
    }

    // ── Bucket A: engines that take a ReasoningGraph — the graph IS one ──────────

    @Test
    void bayesianNetworkFromGraph() {
        BayesianNetwork net = new GraphBayesianNetworkBuilder().build(fixture());
        assertNotNull(net);
    }

    @Test
    void pslProgramFromGraph() {
        PslProgram program = new GraphPslProgramBuilder().build(fixture());
        assertNotNull(program);
        assertNotNull(program.rules());
    }

    @Test
    void portableReasoningLifecycleLearnsDirectlyFromGroundedGraphFacts() {
        UnifiedGraph graph = fixture();

        UnifiedGraphReasoningLifecycle.Summary summary = UnifiedGraphReasoningLifecycle.learn(
                graph, new UnifiedGraphReasoningLifecycle.Config(true, 1, 1, 1, 0.35, 2));

        assertTrue(summary.enabled());
        assertTrue(summary.mebnLearned());
        assertEquals(6, summary.observedTargetCount());
        assertNotNull(graph.model(UnifiedGraphReasoningLifecycle.MEBN_THEORY_ARTIFACT));
    }

    @Test
    void nodeEmbeddingsFromGraph() {
        EmbeddingTable table = new Node2VecLearner().learn(fixture(), EmbeddingConfig.defaults());
        assertNotNull(table);
        assertNotNull(table.vector("n1"));
    }

    @Test
    void semanticSimilarityOverAnyLayer() {
        UnifiedGraph g = fixture();
        List<Embeddings.SemanticHit> hits =
                Embeddings.mostSimilar(g.withEmbeddingLayer("kge"), new double[] {1.0, 0.0, 0.0}, 3);
        assertEquals("n1", hits.get(0).entityId()); // n1's KGE vector equals the query
    }

    @Test
    void sparsityMetricsFromGraph() {
        double density = SparsityMetrics.compute(fixture()).density;
        assertTrue(density >= 0.0 && density <= 1.0);
    }

    @Test
    void typeHierarchyBuiltFromGraph() {
        assertNotNull(new TypeRegistry().declare("PERSON").declare("ORG").buildFor(fixture()));
    }

    // ── Bucket B: engines that need a MutableReasoningGraph — via mutableGraph() ──

    @Test
    void learnEmbeddingsIntoTheGraphInPlace() {
        UnifiedGraph g = fixture();
        new Node2VecLearner().learnInto(g.mutableGraph(), EmbeddingConfig.defaults());
        assertTrue(g.entity("n1").orElseThrow().hasEmbedding());
    }

    // ── Accessors: opinions, vector layers as reasoning inputs ───────────────────

    @Test
    void opinionsAsAReasoningStore() {
        OpinionStore opinions = fixture().opinionStore();
        assertTrue(opinions.has("n1"));
        assertEquals(Opinion.fromBetaEvidence(8, 2), opinions.get("n1"));
    }

    @Test
    void vectorLayerAsAnEmbeddingTable() {
        EmbeddingTable kge = fixture().embeddingTable("kge");
        assertNotNull(kge);
        assertArrayEquals(new double[] {1.0, 0.0, 0.0}, kge.vector("n1"), 0.0);
    }

    // ── Bucket C: build a model FROM the graph, bundle it INTO the graph, reload ──

    @Test
    void buildAModelFromTheGraphThenBundleAndReloadIt() throws IOException {
        UnifiedGraph g = fixture();
        PslProgram program = new GraphPslProgramBuilder().build(g);
        g.putModel("psl.program", program);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        g.save(bos);
        UnifiedGraph back = UnifiedGraph.load(new ByteArrayInputStream(bos.toByteArray()));

        PslProgram restored = back.model("psl.program");
        assertNotNull(restored);
        assertEquals(program.rules().size(), restored.rules().size());
    }
}
