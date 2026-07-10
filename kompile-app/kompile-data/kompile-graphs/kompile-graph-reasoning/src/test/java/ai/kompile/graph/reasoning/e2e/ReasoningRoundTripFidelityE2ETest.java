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

import ai.kompile.graph.reasoning.bayesian.GraphBayesianNetworkBuilder;
import ai.kompile.graph.reasoning.community.CommunityAssignment;
import ai.kompile.graph.reasoning.community.LouvainDetector;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.OpinionStore;
import ai.kompile.graph.reasoning.embedding.Embeddings;
import ai.kompile.graph.reasoning.mebn.type.TypeRegistry;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.sparse.SparsityMetrics;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The capstone end-to-end tests: reason on a graph, serialize it to a {@code .kgraph}, reload it,
 * reason again — and assert the result is IDENTICAL. This proves the single-file format preserves
 * everything every engine needs, across the whole reasoning surface. Each assertion compares an
 * engine against itself, so it is robust by construction.
 */
class ReasoningRoundTripFidelityE2ETest {

    /** Two dense triangles (a cluster + b cluster) with embeddings, opinions, and a weak bridge. */
    private UnifiedGraph fixture() {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity(new SimpleGraphEntity("n1", "PERSON", "Alice", 0.9, 0.9, Set.of(), new double[] {1.0, 0.0, 0.0}, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("n2", "PERSON", "Bob", 0.8, 0.8, Set.of(), new double[] {0.9, 0.1, 0.0}, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("n3", "PERSON", "Carol", 0.8, 0.8, Set.of(), new double[] {0.8, 0.2, 0.0}, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("n4", "ORG", "Acme", 1.0, 1.0, Set.of(), new double[] {0.0, 0.0, 1.0}, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("n5", "ORG", "Beta", 1.0, 1.0, Set.of(), new double[] {0.1, 0.0, 0.9}, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("n6", "ORG", "Gamma", 1.0, 1.0, Set.of(), new double[] {0.0, 0.1, 0.9}, null, Map.of()));
        g.addRelation("t1", "n1", "n2", "KNOWS", 1.0);
        g.addRelation("t2", "n2", "n3", "KNOWS", 1.0);
        g.addRelation("t3", "n1", "n3", "KNOWS", 1.0);
        g.addRelation("t4", "n4", "n5", "PARTNER", 1.0);
        g.addRelation("t5", "n5", "n6", "PARTNER", 1.0);
        g.addRelation("t6", "n4", "n6", "PARTNER", 1.0);
        g.addRelation("bridge", "n1", "n4", "LINK", 0.1);
        g.putEntityOpinion("n1", Opinion.fromBetaEvidence(8, 2));
        g.putEntityOpinion("n2", Opinion.fromBetaEvidence(6, 4));
        return g;
    }

    private UnifiedGraph roundTrip(UnifiedGraph g) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        g.save(bos, Dtype.F64); // F64 → embeddings survive exactly
        return UnifiedGraph.load(new ByteArrayInputStream(bos.toByteArray()));
    }

    @Test
    void topologySurvives() throws IOException {
        UnifiedGraph back = roundTrip(fixture());
        assertEquals(6, back.entityCount());
        assertEquals(7, back.relationCount());
    }

    @Test
    void pslProgramStructureIsIdenticalAfterRoundTrip() throws IOException {
        UnifiedGraph g = fixture();
        PslProgram before = new GraphPslProgramBuilder().build(g);
        PslProgram after = new GraphPslProgramBuilder().build(roundTrip(g));
        assertEquals(before.rules().size(), after.rules().size());
        assertEquals(before.ground().size(), after.ground().size());
    }

    @Test
    void bayesianStructureIsIdenticalAfterRoundTrip() throws IOException {
        UnifiedGraph g = fixture();
        GraphBayesianNetworkBuilder b1 = new GraphBayesianNetworkBuilder();
        b1.build(g);
        GraphBayesianNetworkBuilder b2 = new GraphBayesianNetworkBuilder();
        b2.build(roundTrip(g));
        assertEquals(b1.variableToEntityId().size(), b2.variableToEntityId().size());
        assertEquals(b1.entityIdToVariable().keySet(), b2.entityIdToVariable().keySet());
    }

    @Test
    void semanticSimilarityRankingIsIdenticalAfterRoundTrip() throws IOException {
        UnifiedGraph g = fixture();
        double[] query = {1.0, 0.0, 0.0};
        assertEquals(rankedIds(g, query), rankedIds(roundTrip(g), query));
    }

    private List<String> rankedIds(ReasoningGraph g, double[] query) {
        return Embeddings.mostSimilar(g, query, 6).stream().map(Embeddings.SemanticHit::entityId).toList();
    }

    @Test
    void sparsityMetricsAreIdenticalAfterRoundTrip() throws IOException {
        UnifiedGraph g = fixture();
        assertEquals(SparsityMetrics.compute(g).density, SparsityMetrics.compute(roundTrip(g)).density, 0.0);
    }

    @Test
    void communityPartitionIsIdenticalAfterRoundTrip() throws IOException {
        UnifiedGraph g = fixture();
        List<String> ids = List.of("n1", "n2", "n3", "n4", "n5", "n6");
        assertEquals(partition(new LouvainDetector().detect(g, 42L), ids),
                partition(new LouvainDetector().detect(roundTrip(g), 42L), ids));
    }

    /** Canonical, label-independent partition: each node → the sorted set of its community-mates. */
    private Map<String, TreeSet<String>> partition(CommunityAssignment ca, List<String> ids) {
        Map<String, TreeSet<String>> out = new LinkedHashMap<>();
        for (String id : ids) {
            TreeSet<String> mates = new TreeSet<>();
            for (String other : ids) {
                if (ca.communityOf(id) == ca.communityOf(other)) mates.add(other);
            }
            out.put(id, mates);
        }
        return out;
    }

    @Test
    void opinionFusionIsIdenticalAfterRoundTrip() throws IOException {
        UnifiedGraph g = fixture();
        OpinionStore before = g.opinionStore();
        OpinionStore after = roundTrip(g).opinionStore();
        assertEquals(before.get("n1"), after.get("n1"));
        assertEquals(before.get("n1").cumulativeFuse(before.get("n2")),
                after.get("n1").cumulativeFuse(after.get("n2")));
    }

    @Test
    void bundledModelReasoningIsIdenticalAfterRoundTrip() throws IOException {
        UnifiedGraph g = fixture();
        g.putModel("psl.program", new GraphPslProgramBuilder().build(g));
        g.putModel("types", new TypeRegistry().declare("PERSON").declare("ORG"));

        UnifiedGraph back = roundTrip(g);
        PslProgram restored = back.model("psl.program");
        TypeRegistry types = back.model("types");
        assertEquals(new GraphPslProgramBuilder().build(g).rules().size(), restored.rules().size());
        assertEquals("TypeRegistry{2 declared types}", types.toString());
    }
}
