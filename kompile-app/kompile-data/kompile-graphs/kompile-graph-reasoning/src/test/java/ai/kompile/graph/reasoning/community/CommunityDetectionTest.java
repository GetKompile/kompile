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
package ai.kompile.graph.reasoning.community;

import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LabelPropagationDetector} and {@link LouvainDetector}.
 *
 * <h3>Graph fixtures</h3>
 * <ul>
 *   <li><b>two-clique</b>: two dense cliques of 4 nodes each, joined by one weak bridge edge.
 *       Both detectors should find exactly 2 communities (one per clique).</li>
 *   <li><b>complete</b>: a complete graph K4 — every node is strongly connected to every other.
 *       Modularity-based methods should collapse this to 1 community.</li>
 *   <li><b>empty</b>: no nodes — result is an empty assignment.</li>
 *   <li><b>single-node</b>: one node, no edges — trivially 1 community.</li>
 * </ul>
 */
class CommunityDetectionTest {

    // ----- graph builders -----

    /**
     * Two cliques A={a1,a2,a3,a4} and B={b1,b2,b3,b4} connected by one weak bridge edge
     * a4–b1 with weight 0.05 (all intra-clique edges have weight 1.0).
     * Expected: exactly 2 communities (A and B).
     */
    private static ReasoningGraph twoCliqueGraph() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        // Clique A
        for (String n : List.of("a1", "a2", "a3", "a4")) g.addEntity(n, "NODE", n);
        // Clique B
        for (String n : List.of("b1", "b2", "b3", "b4")) g.addEntity(n, "NODE", n);

        // Intra-clique edges (undirected via addRelation with weight 1.0)
        String[][] aEdges = {{"a1","a2"},{"a1","a3"},{"a1","a4"},{"a2","a3"},{"a2","a4"},{"a3","a4"}};
        for (int i = 0; i < aEdges.length; i++) {
            g.addRelation("ae" + i, aEdges[i][0], aEdges[i][1], "LINK", 1.0);
            g.addRelation("ae" + i + "r", aEdges[i][1], aEdges[i][0], "LINK", 1.0);
        }
        String[][] bEdges = {{"b1","b2"},{"b1","b3"},{"b1","b4"},{"b2","b3"},{"b2","b4"},{"b3","b4"}};
        for (int i = 0; i < bEdges.length; i++) {
            g.addRelation("be" + i, bEdges[i][0], bEdges[i][1], "LINK", 1.0);
            g.addRelation("be" + i + "r", bEdges[i][1], bEdges[i][0], "LINK", 1.0);
        }

        // Bridge (very weak)
        g.addRelation("bridge1", "a4", "b1", "LINK", 0.05);
        g.addRelation("bridge2", "b1", "a4", "LINK", 0.05);

        return g;
    }

    /**
     * Complete graph K4: all 4 nodes fully connected.
     * In a K_n every partition has the same modularity (0 for n>1), so
     * any detector is free to produce 1 community.
     */
    private static ReasoningGraph completeK4() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        for (String n : List.of("n1", "n2", "n3", "n4")) g.addEntity(n, "NODE", n);
        String[] nodes = {"n1","n2","n3","n4"};
        int edgeId = 0;
        for (int i = 0; i < nodes.length; i++) {
            for (int j = i + 1; j < nodes.length; j++) {
                g.addRelation("e" + edgeId++, nodes[i], nodes[j], "LINK", 1.0);
                g.addRelation("e" + edgeId++, nodes[j], nodes[i], "LINK", 1.0);
            }
        }
        return g;
    }

    private static ReasoningGraph emptyGraph() {
        return new MutableReasoningGraph();
    }

    private static ReasoningGraph singleNodeGraph() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity("only", "NODE", "only");
        return g;
    }

    /** Factory method for parameterized tests: provides both detector implementations. */
    static Stream<CommunityDetector> detectors() {
        return Stream.of(
                new LabelPropagationDetector(),
                new LouvainDetector()
        );
    }

    // ----- tests -----

    @Nested
    @DisplayName("Two-clique graph (structural separation)")
    class TwoCliqueTests {

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.kompile.graph.reasoning.community.CommunityDetectionTest#detectors")
        @DisplayName("Finds exactly 2 communities")
        void twoCliques_findsTwoCommunities(CommunityDetector detector) {
            CommunityAssignment result = detector.detect(twoCliqueGraph(), 42L);

            assertEquals(2, result.communityCount(),
                    "Expected 2 communities for two dense cliques with one bridge; got "
                    + result.communityCount() + " (" + detector.getClass().getSimpleName() + ")");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.kompile.graph.reasoning.community.CommunityDetectionTest#detectors")
        @DisplayName("Clique A nodes are in one community, clique B in the other")
        void twoCliques_correctMembership(CommunityDetector detector) {
            CommunityAssignment result = detector.detect(twoCliqueGraph(), 42L);

            // All A-nodes must share a community id
            int commA1 = result.communityOf("a1");
            int commA2 = result.communityOf("a2");
            int commA3 = result.communityOf("a3");
            int commA4 = result.communityOf("a4");
            assertAll("Clique A members in one community",
                    () -> assertEquals(commA1, commA2, "a1 and a2 must be in same community"),
                    () -> assertEquals(commA1, commA3, "a1 and a3 must be in same community"),
                    () -> assertEquals(commA1, commA4, "a1 and a4 must be in same community")
            );

            // All B-nodes must share a (different) community id
            int commB1 = result.communityOf("b1");
            int commB2 = result.communityOf("b2");
            int commB3 = result.communityOf("b3");
            int commB4 = result.communityOf("b4");
            assertAll("Clique B members in one community",
                    () -> assertEquals(commB1, commB2, "b1 and b2 must be in same community"),
                    () -> assertEquals(commB1, commB3, "b1 and b3 must be in same community"),
                    () -> assertEquals(commB1, commB4, "b1 and b4 must be in same community")
            );

            // A and B must be in different communities
            assertNotEquals(commA1, commB1, "Clique A and clique B must be in different communities");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.kompile.graph.reasoning.community.CommunityDetectionTest#detectors")
        @DisplayName("Modularity is positive (meaningful structure)")
        void twoCliques_positiveModularity(CommunityDetector detector) {
            CommunityAssignment result = detector.detect(twoCliqueGraph(), 42L);
            assertTrue(result.modularity() > 0.0,
                    "Two-clique graph should have positive modularity, got " + result.modularity());
        }
    }

    @Nested
    @DisplayName("Complete graph K4 (no community structure)")
    class CompleteGraphTests {

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.kompile.graph.reasoning.community.CommunityDetectionTest#detectors")
        @DisplayName("Produces at most a few communities (all equivalent partitions)")
        void completeGraph_fewCommunities(CommunityDetector detector) {
            CommunityAssignment result = detector.detect(completeK4(), 42L);
            // In a complete graph modularity is the same for any partition (Q=0 for K_n when γ=1).
            // A reasonable detector may produce 1 community (all together) or occasionally a small number.
            // The important contract: no more than n communities, and modularity ≈ 0.
            assertTrue(result.communityCount() >= 1, "Should have at least 1 community");
            assertTrue(result.communityCount() <= 4, "Should not produce more communities than nodes");
            // Modularity in K_n with any partition is 0 (null-model exactly cancels actual edges)
            assertEquals(0.0, result.modularity(), 1e-6,
                    "Complete graph should have modularity ≈ 0 for any partition");
        }
    }

    @Nested
    @DisplayName("Modularity comparison")
    class ModularityComparisonTests {

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.kompile.graph.reasoning.community.CommunityDetectionTest#detectors")
        @DisplayName("Detected partition has higher modularity than trivial all-in-one")
        void detected_beats_trivialPartition(CommunityDetector detector) {
            ReasoningGraph graph = twoCliqueGraph();
            CommunityAssignment detected = detector.detect(graph, 42L);

            // Trivial partition: all nodes in community 0
            Map<String, Integer> trivialMap = new java.util.HashMap<>();
            for (var entity : graph.entities()) trivialMap.put(entity.id(), 0);
            CommunityAssignment trivial = new CommunityAssignment(trivialMap, 0.0);

            assertTrue(detected.modularity() > trivial.modularity(),
                    "Detected partition modularity " + detected.modularity()
                    + " should exceed trivial single-community modularity " + trivial.modularity());
        }
    }

    @Nested
    @DisplayName("Determinism")
    class DeterminismTests {

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.kompile.graph.reasoning.community.CommunityDetectionTest#detectors")
        @DisplayName("Same graph + same seed → identical assignment (two calls)")
        void sameSeed_identicalResult(CommunityDetector detector) {
            ReasoningGraph graph = twoCliqueGraph();
            CommunityAssignment first  = detector.detect(graph, 99L);
            CommunityAssignment second = detector.detect(graph, 99L);

            assertEquals(first.communityCount(), second.communityCount(),
                    "Community count must be the same across two calls with the same seed");
            assertEquals(first.modularity(), second.modularity(), 1e-12,
                    "Modularity must be identical across two calls with the same seed");
            assertEquals(first.assignments(), second.assignments(),
                    "Full assignment map must be identical across two calls with the same seed");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.kompile.graph.reasoning.community.CommunityDetectionTest#detectors")
        @DisplayName("Different seeds may produce different assignments (non-trivial graph)")
        void differentSeeds_mayDifferOnTwoClique(CommunityDetector detector) {
            // On the two-clique graph with a very strong partition signal, LabelPropagation may
            // always find the same communities regardless of seed (which is fine — stable is good).
            // What we test here is that the API at least runs without error for various seeds.
            for (long seed : new long[]{0L, 1L, 12345L, Long.MAX_VALUE}) {
                CommunityAssignment result = detector.detect(twoCliqueGraph(), seed);
                assertNotNull(result);
                assertTrue(result.communityCount() >= 1);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.kompile.graph.reasoning.community.CommunityDetectionTest#detectors")
        @DisplayName("Default seed (detect(graph)) equals detect(graph, defaultSeed())")
        void defaultSeed_matchesExplicitDefaultSeed(CommunityDetector detector) {
            ReasoningGraph graph = twoCliqueGraph();
            CommunityAssignment withDefault   = detector.detect(graph);
            CommunityAssignment withExplicit  = detector.detect(graph, detector.defaultSeed());
            assertEquals(withDefault.assignments(), withExplicit.assignments(),
                    "detect(graph) must equal detect(graph, defaultSeed())");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.kompile.graph.reasoning.community.CommunityDetectionTest#detectors")
        @DisplayName("Empty graph returns empty assignment")
        void emptyGraph_emptyAssignment(CommunityDetector detector) {
            CommunityAssignment result = detector.detect(emptyGraph(), 42L);
            assertNotNull(result);
            assertEquals(0, result.assignments().size());
            assertEquals(0.0, result.modularity(), 0.0);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.kompile.graph.reasoning.community.CommunityDetectionTest#detectors")
        @DisplayName("Single-node graph returns 1 community")
        void singleNode_oneCommunity(CommunityDetector detector) {
            CommunityAssignment result = detector.detect(singleNodeGraph(), 42L);
            assertNotNull(result);
            assertEquals(1, result.communityCount());
            assertEquals(0, result.communityOf("only"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.kompile.graph.reasoning.community.CommunityDetectionTest#detectors")
        @DisplayName("Null graph throws IllegalArgumentException")
        void nullGraph_throws(CommunityDetector detector) {
            assertThrows(IllegalArgumentException.class, () -> detector.detect(null, 42L));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.kompile.graph.reasoning.community.CommunityDetectionTest#detectors")
        @DisplayName("All entity ids are present in the assignment")
        void allEntitiesPresent(CommunityDetector detector) {
            ReasoningGraph graph = twoCliqueGraph();
            CommunityAssignment result = detector.detect(graph, 42L);
            for (var entity : graph.entities()) {
                int comm = result.communityOf(entity.id());
                assertTrue(comm >= 0, "Entity " + entity.id() + " missing from assignment (got -1)");
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.kompile.graph.reasoning.community.CommunityDetectionTest#detectors")
        @DisplayName("Community ids are dense (no gaps) starting at 0")
        void communityIdsDense(CommunityDetector detector) {
            CommunityAssignment result = detector.detect(twoCliqueGraph(), 42L);
            Set<Integer> ids = new java.util.HashSet<>(result.assignments().values());
            for (int i = 0; i < result.communityCount(); i++) {
                assertTrue(ids.contains(i), "Community id " + i + " missing; found: " + ids);
            }
        }
    }

    @Nested
    @DisplayName("LabelPropagation-specific")
    class LabelPropagationSpecificTests {

        @Test
        @DisplayName("maxIterations=1 still produces a valid assignment")
        void oneIteration_valid() {
            LabelPropagationDetector detector = new LabelPropagationDetector(1);
            CommunityAssignment result = detector.detect(twoCliqueGraph(), 42L);
            assertNotNull(result);
            assertTrue(result.communityCount() >= 1);
        }

        @Test
        @DisplayName("Invalid maxIterations throws")
        void invalidMaxIterations_throws() {
            assertThrows(IllegalArgumentException.class, () -> new LabelPropagationDetector(0));
            assertThrows(IllegalArgumentException.class, () -> new LabelPropagationDetector(-5));
        }
    }

    @Nested
    @DisplayName("Louvain-specific")
    class LouvainSpecificTests {

        @Test
        @DisplayName("Single-level mode (multilevel=false) still finds 2 communities on two-clique")
        void singleLevel_twoClique() {
            LouvainDetector detector = new LouvainDetector(1.0, 50, false);
            CommunityAssignment result = detector.detect(twoCliqueGraph(), 42L);
            assertEquals(2, result.communityCount(),
                    "Single-level Louvain should find 2 communities on two-clique graph");
        }

        @Test
        @DisplayName("High resolution (γ=2.0) may split into more communities")
        void highResolution_moreOrEqualCommunities() {
            LouvainDetector lowRes  = new LouvainDetector(0.5, 50, true);
            LouvainDetector highRes = new LouvainDetector(2.0, 50, true);
            CommunityAssignment rLow  = lowRes.detect(twoCliqueGraph(), 42L);
            CommunityAssignment rHigh = highRes.detect(twoCliqueGraph(), 42L);
            // High resolution tends to produce >= communities compared to low resolution
            assertTrue(rHigh.communityCount() >= rLow.communityCount(),
                    "Higher resolution should produce >= communities; low=" + rLow.communityCount()
                    + " high=" + rHigh.communityCount());
        }

        @Test
        @DisplayName("Invalid constructor arguments throw")
        void invalidConstructor_throws() {
            assertThrows(IllegalArgumentException.class, () -> new LouvainDetector(0.0, 50, true));
            assertThrows(IllegalArgumentException.class, () -> new LouvainDetector(-1.0, 50, true));
            assertThrows(IllegalArgumentException.class, () -> new LouvainDetector(1.0, 0, true));
        }
    }

    @Nested
    @DisplayName("CommunityAssignment API")
    class CommunityAssignmentTests {

        @Test
        @DisplayName("membersOf returns the correct node set")
        void membersOf_correct() {
            CommunityAssignment result = new LabelPropagationDetector().detect(twoCliqueGraph(), 42L);
            int commA = result.communityOf("a1");
            Set<String> membersA = result.membersOf(commA);
            assertTrue(membersA.contains("a1"));
            assertTrue(membersA.contains("a2"));
            assertTrue(membersA.contains("a3"));
            assertTrue(membersA.contains("a4"));
            assertFalse(membersA.contains("b1"));
        }

        @Test
        @DisplayName("communityOf returns -1 for unknown entity id")
        void unknownEntity_minusOne() {
            CommunityAssignment result = new LabelPropagationDetector().detect(twoCliqueGraph(), 42L);
            assertEquals(-1, result.communityOf("nonexistent_xyz"));
        }

        @Test
        @DisplayName("assignments() view is unmodifiable")
        void assignments_unmodifiable() {
            CommunityAssignment result = new LabelPropagationDetector().detect(twoCliqueGraph(), 42L);
            assertThrows(UnsupportedOperationException.class,
                    () -> result.assignments().put("hack", 999));
        }
    }
}
