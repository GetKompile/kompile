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
package ai.kompile.graph.reasoning.e2e;

import ai.kompile.graph.reasoning.community.CommunityAssignment;
import ai.kompile.graph.reasoning.community.LabelPropagationDetector;
import ai.kompile.graph.reasoning.community.LouvainDetector;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.embedding.Embeddings;
import ai.kompile.graph.reasoning.embedding.kge.KgeTripleScorer;
import ai.kompile.graph.reasoning.embedding.kge.StubKgeTripleScorer;
import ai.kompile.graph.reasoning.sparse.SparsityMetrics;
import ai.kompile.graph.reasoning.subgraph.SubgraphMaterializer;
import ai.kompile.graph.reasoning.subgraph.SubgraphSpec;
import ai.kompile.graph.reasoning.subgraph.SubgraphView;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the graph-algorithm and embedding surface driven from a {@link UnifiedGraph}:
 * community detection, subgraph materialization, sparsity, semantic similarity, KGE scoring, and
 * answer synthesis. Assertions are structural/behavioral (co-assignment, ranking, bounds), not
 * brittle magic numbers.
 */
class GraphAlgorithmsAndEmbeddingE2ETest {

    /** Two internally-dense clusters (A = a0..a3, B = b0..b3) joined by one weak bridge edge. */
    private UnifiedGraph twoClusterGraph() {
        UnifiedGraph g = new UnifiedGraph();
        String[] a = {"a0", "a1", "a2", "a3"};
        String[] b = {"b0", "b1", "b2", "b3"};
        for (String id : a) g.addEntity(id, "PERSON", id);
        for (String id : b) g.addEntity(id, "PERSON", id);
        connectClique(g, a, "A");
        connectClique(g, b, "B");
        g.addRelation("bridge", "a0", "b0", "LINK", 0.1); // weak inter-cluster tie
        return g;
    }

    private void connectClique(UnifiedGraph g, String[] ids, String tag) {
        for (int i = 0; i < ids.length; i++) {
            for (int j = i + 1; j < ids.length; j++) {
                g.addRelation(tag + "-" + i + "-" + j, ids[i], ids[j], "COLLAB", 1.0);
            }
        }
    }

    // ── Community detection ──────────────────────────────────────────────────────

    @Test
    void louvainCoAssignsDenseClusterAndSeparatesClusters() {
        CommunityAssignment ca = new LouvainDetector().detect(twoClusterGraph(), 42L);
        assertEquals(ca.communityOf("a0"), ca.communityOf("a1"), "same cluster → same community");
        assertEquals(ca.communityOf("a0"), ca.communityOf("a2"));
        assertEquals(ca.communityOf("b0"), ca.communityOf("b1"));
        assertNotEquals(ca.communityOf("a0"), ca.communityOf("b0"), "dense clusters split apart");
        assertTrue(ca.communityCount() >= 2);
        assertTrue(ca.modularity() > 0.0, "structure present → positive modularity");
    }

    @Test
    void labelPropagationAlsoClustersDenseGroups() {
        CommunityAssignment ca = new LabelPropagationDetector().detect(twoClusterGraph(), 42L);
        assertEquals(ca.communityOf("a0"), ca.communityOf("a1"));
        assertEquals(ca.communityOf("b0"), ca.communityOf("b1"));
        assertTrue(ca.communityCount() >= 1);
    }

    @Test
    void louvainIsDeterministicForAFixedSeed() {
        UnifiedGraph g = twoClusterGraph();
        CommunityAssignment first = new LouvainDetector().detect(g, 7L);
        CommunityAssignment second = new LouvainDetector().detect(g, 7L);
        assertEquals(first.assignments(), second.assignments());
    }

    // ── Subgraph materialization ─────────────────────────────────────────────────

    @Test
    void subgraphMaterializerExtractsSeedNeighborhood() {
        SubgraphView view = SubgraphMaterializer.INSTANCE.materialize(
                twoClusterGraph(), SubgraphSpec.builder().seedId("a0").radius(1).build());
        assertTrue(view.graph().entity("a0").isPresent(), "seed present");
        assertTrue(view.graph().entity("a1").isPresent(), "radius-1 neighbor present");
        assertTrue(view.provenance().resolvedSeedIds().contains("a0"));
        assertTrue(view.graph().entityCount() <= 8);
    }

    @Test
    void subgraphRadiusZeroIsSeedsOnly() {
        SubgraphView view = SubgraphMaterializer.INSTANCE.materialize(
                twoClusterGraph(), SubgraphSpec.builder().seedId("a0").radius(0).build());
        assertEquals(1, view.graph().entityCount());
        assertTrue(view.graph().entity("a0").isPresent());
    }

    @Test
    void subgraphSeedsByEntityType() {
        UnifiedGraph g = twoClusterGraph();
        g.addEntity("org1", "ORG", "Acme");
        SubgraphView view = SubgraphMaterializer.INSTANCE.materialize(
                g, SubgraphSpec.builder().seedEntityType("ORG").radius(0).build());
        assertTrue(view.graph().entity("org1").isPresent());
        assertEquals(1, view.graph().entityCount());
    }

    // ── Sparsity ─────────────────────────────────────────────────────────────────

    @Test
    void sparsityMetricsReflectDensity() {
        SparsityMetrics dense = SparsityMetrics.compute(cliqueOnly());
        SparsityMetrics sparse = SparsityMetrics.compute(chainGraph());
        assertTrue(dense.density > sparse.density, "clique denser than chain");
        assertTrue(dense.density >= 0.0 && dense.density <= 1.0);
        assertTrue(sparse.isLikelySparse());
        assertEquals(4, dense.nodeCount);
    }

    private UnifiedGraph cliqueOnly() {
        UnifiedGraph g = new UnifiedGraph();
        String[] a = {"a0", "a1", "a2", "a3"};
        for (String id : a) g.addEntity(id, "N", id);
        connectClique(g, a, "A");
        return g;
    }

    private UnifiedGraph chainGraph() {
        // A long chain: density = 1/N, so N=20 gives density 0.05 < the 0.10 sparse threshold.
        UnifiedGraph g = new UnifiedGraph();
        for (int i = 0; i < 20; i++) g.addEntity("c" + i, "N", "c" + i);
        for (int i = 0; i < 19; i++) g.addRelation("e" + i, "c" + i, "c" + (i + 1), "NEXT", 1.0);
        return g;
    }

    // ── Semantic similarity over embeddings ──────────────────────────────────────

    @Test
    void semanticSimilarityRanksTheNearestEntityFirst() {
        UnifiedGraph g = new UnifiedGraph();
        g.putEntityVector("emb", "n1", new double[] {1.0, 0.0});   // put via layer, then view
        g.putEntityVector("emb", "n2", new double[] {0.9, 0.1});   // near n1
        g.putEntityVector("emb", "n3", new double[] {0.0, 1.0});   // far from n1
        g.addEntity("n1", "N", "n1");
        g.addEntity("n2", "N", "n2");
        g.addEntity("n3", "N", "n3");

        List<Embeddings.SemanticHit> hits =
                Embeddings.mostSimilar(g.withEmbeddingLayer("emb"), new double[] {1.0, 0.0}, 3);
        assertEquals("n1", hits.get(0).entityId());
        assertEquals("n2", hits.get(1).entityId(), "n2 is nearer to the query than n3");
    }

    // ── KGE triple scoring (pure-Java stub — exercises the scorer contract) ───────

    @Test
    void kgeTripleScorerRanksPlausibleTripleHigher() {
        KgeTripleScorer scorer = StubKgeTripleScorer.builder()
                .withScore("alice", "KNOWS", "bob", 0.92)
                .withScore("alice", "KNOWS", "carol", 0.20)
                .withKnownEntity("alice").withKnownEntity("bob").withKnownEntity("carol")
                .withKnownRelation("KNOWS")
                .build();
        assertTrue(scorer.scoreTriple("alice", "KNOWS", "bob")
                > scorer.scoreTriple("alice", "KNOWS", "carol"));
        assertEquals(0.0, scorer.scoreTriple("alice", "KNOWS", "nobody"), 0.0);
        assertTrue(scorer.knows("alice", "KNOWS", "bob"));
    }

    // ── Answer synthesis (opinion fusion over candidate signals) ─────────────────

    @Test
    void answerSynthesisRanksTheStrongerCandidateFirst() {
        Opinion strong = Opinion.fromBetaEvidence(9, 1);
        Opinion weak = Opinion.fromBetaEvidence(4, 6);
        List<AnswerSynthesizer.Candidate> candidates = List.of(
                new AnswerSynthesizer.Candidate("A", List.of(
                        AnswerSynthesizer.CandidateSignal.of(AnswerSynthesizer.SignalGroup.RETRIEVAL, "ann", strong),
                        AnswerSynthesizer.CandidateSignal.of(AnswerSynthesizer.SignalGroup.ENGINE, "psl", strong))),
                new AnswerSynthesizer.Candidate("B", List.of(
                        AnswerSynthesizer.CandidateSignal.of(AnswerSynthesizer.SignalGroup.RETRIEVAL, "ann", weak))));

        List<AnswerSynthesizer.SynthesizedAnswer> answers = AnswerSynthesizer.synthesize(candidates);
        assertEquals("A", answers.get(0).entityId());
        assertTrue(answers.get(0).likelihood() > answers.get(1).likelihood());
        assertNotNull(answers.get(0).trace());
    }
}
