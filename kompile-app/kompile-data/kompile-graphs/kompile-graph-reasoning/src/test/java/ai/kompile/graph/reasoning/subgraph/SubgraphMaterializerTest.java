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
package ai.kompile.graph.reasoning.subgraph;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SubgraphMaterializer}.
 *
 * <p>Uses a small, hand-built 7-node graph so every test is deterministic and fast:
 *
 * <pre>
 *  A --CAUSES(1.0)--> B --CAUSES(1.0)--> D
 *  A --CAUSES(1.0)--> C --SUPPORTS(1.0)-> E
 *  B --WEAK(0.2)----> F
 *  G (isolated)
 * </pre>
 *
 * Node types: A, B, C = "SOURCE"; D, E = "TARGET"; F = "PERIPHERAL"; G = "ISOLATED".
 * Edge WEAK(B→F) has confidence 0.2 to test the confidence filter.
 * Edge SUPPORTS(C→E) has type "SUPPORTS" to test the predicate filter.
 * </p>
 */
class SubgraphMaterializerTest {

    // Graph is rebuilt fresh for each test to keep tests independent
    private MutableReasoningGraph source;
    private SubgraphMaterializer  materializer;

    @BeforeEach
    void buildGraph() {
        source = new MutableReasoningGraph();

        // Nodes
        source.addEntity(SimpleGraphEntity.of("A", "SOURCE", "Node A"));
        source.addEntity(SimpleGraphEntity.of("B", "SOURCE", "Node B"));
        source.addEntity(SimpleGraphEntity.of("C", "SOURCE", "Node C"));
        source.addEntity(SimpleGraphEntity.of("D", "TARGET", "Node D"));
        source.addEntity(SimpleGraphEntity.of("E", "TARGET", "Node E"));
        source.addEntity(SimpleGraphEntity.of("F", "PERIPHERAL", "Node F"));
        source.addEntity(SimpleGraphEntity.of("G", "ISOLATED", "Node G"));  // no edges

        // A→B  CAUSES  confidence 1.0
        source.addRelation(SimpleGraphRelation.directed("r-AB", "A", "B", "CAUSES", 1.0));
        // A→C  CAUSES  confidence 1.0
        source.addRelation(SimpleGraphRelation.directed("r-AC", "A", "C", "CAUSES", 1.0));
        // B→D  CAUSES  confidence 1.0
        source.addRelation(SimpleGraphRelation.directed("r-BD", "B", "D", "CAUSES", 1.0));
        // C→E  SUPPORTS  confidence 1.0
        source.addRelation(new SimpleGraphRelation("r-CE", "C", "E", "SUPPORTS", 1.0, 1.0, true,
                java.util.Set.of(), null, null, java.util.Map.of()));
        // B→F  WEAK  confidence 0.2
        source.addRelation(new SimpleGraphRelation("r-BF", "B", "F", "WEAK", 0.2, 0.2, true,
                java.util.Set.of(), null, null, java.util.Map.of()));

        materializer = SubgraphMaterializer.INSTANCE;
    }

    // -----------------------------------------------------------------------
    // Basic seed tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Seed by id, radius=1 (direct neighbours only)")
    class SeedByIdRadius1 {

        @Test
        @DisplayName("Seed A → A + B + C (outgoing); no D/E/F because they are 2 hops away")
        void seedA_radius1() {
            SubgraphSpec spec = SubgraphSpec.builder().seedId("A").radius(1).build();
            SubgraphView view = materializer.materialize(source, spec);
            MutableReasoningGraph g = view.graph();

            // A (seed) + B, C (1-hop via outgoing from A)
            assertEquals(3, g.entityCount(), "Expected A, B, C");
            assertTrue(g.containsEntity("A"));
            assertTrue(g.containsEntity("B"));
            assertTrue(g.containsEntity("C"));

            assertFalse(g.containsEntity("D"), "D is 2 hops from A");
            assertFalse(g.containsEntity("E"), "E is 2 hops from A");
            assertFalse(g.containsEntity("F"), "F is 2 hops from A");
            assertFalse(g.containsEntity("G"), "G is isolated");

            // Edges: A→B and A→C should be present; B→F and B→D are not (F/D not in view)
            assertEquals(2, g.relationCount(), "r-AB and r-AC only");
        }

        @Test
        @DisplayName("Seed B → B + A + D + F (incoming A; outgoing D, F)")
        void seedB_radius1() {
            SubgraphSpec spec = SubgraphSpec.builder().seedId("B").radius(1).build();
            SubgraphView view = materializer.materialize(source, spec);
            MutableReasoningGraph g = view.graph();

            // B(seed) + A(incoming) + D(outgoing CAUSES) + F(outgoing WEAK)
            assertEquals(4, g.entityCount(), "Expected B, A, D, F");
            assertTrue(g.containsEntity("B"));
            assertTrue(g.containsEntity("A"));
            assertTrue(g.containsEntity("D"));
            assertTrue(g.containsEntity("F"));

            assertFalse(g.containsEntity("C"));
            assertFalse(g.containsEntity("E"));
            assertFalse(g.containsEntity("G"));
        }

        @Test
        @DisplayName("Isolated seed G → only G, no edges")
        void seedG_isolated() {
            SubgraphSpec spec = SubgraphSpec.builder().seedId("G").radius(1).build();
            SubgraphView view = materializer.materialize(source, spec);

            assertEquals(1, view.graph().entityCount());
            assertTrue(view.graph().containsEntity("G"));
            assertEquals(0, view.graph().relationCount());
        }
    }

    // -----------------------------------------------------------------------
    // Radius 2 (2-hop expansion)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Radius=2 (two-hop expansion)")
    class Radius2 {

        @Test
        @DisplayName("Seed A, radius=2 → A+B+C+D+E+F (all reachable nodes except isolated G)")
        void seedA_radius2() {
            SubgraphSpec spec = SubgraphSpec.builder().seedId("A").radius(2).build();
            SubgraphView view = materializer.materialize(source, spec);
            MutableReasoningGraph g = view.graph();

            assertEquals(6, g.entityCount(), "All nodes except G");
            assertTrue(g.containsEntity("A"));
            assertTrue(g.containsEntity("B"));
            assertTrue(g.containsEntity("C"));
            assertTrue(g.containsEntity("D"));
            assertTrue(g.containsEntity("E"));
            assertTrue(g.containsEntity("F"));
            assertFalse(g.containsEntity("G"), "G is isolated");

            // All 5 edges of the connected component should be present
            assertEquals(5, g.relationCount());
        }

        @Test
        @DisplayName("Seed D, radius=2 → D + B (hop1) + A + F (hop2)")
        void seedD_radius2() {
            SubgraphSpec spec = SubgraphSpec.builder().seedId("D").radius(2).build();
            SubgraphView view = materializer.materialize(source, spec);
            MutableReasoningGraph g = view.graph();

            // D→(B via incoming r-BD)→(A via incoming r-AB, F via outgoing r-BF)
            assertTrue(g.containsEntity("D"), "seed");
            assertTrue(g.containsEntity("B"), "1-hop");
            assertTrue(g.containsEntity("A"), "2-hop via B←A");
            assertTrue(g.containsEntity("F"), "2-hop via B→F");

            // C and E are NOT reachable from D within 2 hops following the graph direction
            // (A is reached at hop 2; A→C would be hop 3)
            assertFalse(g.containsEntity("C"), "3 hops from D");
            assertFalse(g.containsEntity("E"), "not reachable within 2 hops");
        }
    }

    // -----------------------------------------------------------------------
    // Predicate / relation-type filter
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Predicate (relation type) allow-list filter")
    class PredicateFilter {

        @Test
        @DisplayName("Allow only CAUSES: seed A, radius=2 → A+B+C+D but not E (SUPPORTS) or F (WEAK)")
        void causesOnlyFilter() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedId("A")
                    .radius(2)
                    .allowedRelationType("CAUSES")
                    .build();
            SubgraphView view = materializer.materialize(source, spec);
            MutableReasoningGraph g = view.graph();

            assertTrue(g.containsEntity("A"));
            assertTrue(g.containsEntity("B"));
            assertTrue(g.containsEntity("C"));
            assertTrue(g.containsEntity("D")); // reached via A→B→D (all CAUSES)

            // E is only reachable via C→E which is SUPPORTS (blocked)
            assertFalse(g.containsEntity("E"), "C→E is SUPPORTS, blocked");
            // F is only reachable via B→F which is WEAK (blocked)
            assertFalse(g.containsEntity("F"), "B→F is WEAK, blocked");
        }

        @Test
        @DisplayName("Allow only SUPPORTS: seed A, radius=2 → only A and C (A→C is CAUSES, blocked; C→E is SUPPORTS)")
        void supportsOnlyFilter_seedA() {
            // A has no outgoing SUPPORTS edges, so radius=1 from A yields only A.
            // To reach E we need to go through C, but A→C is CAUSES (blocked).
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedId("A")
                    .radius(2)
                    .allowedRelationType("SUPPORTS")
                    .build();
            SubgraphView view = materializer.materialize(source, spec);
            MutableReasoningGraph g = view.graph();

            // Only A (no SUPPORTS edges from A to traverse)
            assertEquals(1, g.entityCount(), "A has no SUPPORTS incident edges");
            assertTrue(g.containsEntity("A"));
        }

        @Test
        @DisplayName("Allow SUPPORTS: seed C → C + E (C→E is SUPPORTS)")
        void supportsFilter_seedC() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedId("C")
                    .radius(1)
                    .allowedRelationType("SUPPORTS")
                    .build();
            SubgraphView view = materializer.materialize(source, spec);
            MutableReasoningGraph g = view.graph();

            assertEquals(2, g.entityCount(), "C and E");
            assertTrue(g.containsEntity("C"));
            assertTrue(g.containsEntity("E"));
            assertEquals(1, g.relationCount());
        }
    }

    // -----------------------------------------------------------------------
    // maxNodes cap
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("maxNodes cap")
    class MaxNodesCap {

        @Test
        @DisplayName("maxNodes=2: seed A, radius=2 → only 2 nodes (A + first neighbour)")
        void maxNodesCapsAt2() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedId("A")
                    .radius(2)
                    .maxNodes(2)
                    .build();
            SubgraphView view = materializer.materialize(source, spec);

            assertEquals(2, view.graph().entityCount(), "Hard cap at 2");
            assertTrue(view.provenance().cappedByMaxNodes(), "Should report capped");
        }

        @Test
        @DisplayName("maxNodes=1: only the seed itself")
        void maxNodesCapsAt1() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedId("A")
                    .radius(2)
                    .maxNodes(1)
                    .build();
            SubgraphView view = materializer.materialize(source, spec);

            assertEquals(1, view.graph().entityCount(), "Only seed A");
            assertTrue(view.graph().containsEntity("A"));
            assertTrue(view.provenance().cappedByMaxNodes());
        }

        @Test
        @DisplayName("maxNodes=0 (unlimited): all reachable nodes included")
        void maxNodesZeroUnlimited() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedId("A")
                    .radius(5)
                    .maxNodes(0)   // 0 = unlimited
                    .build();
            SubgraphView view = materializer.materialize(source, spec);

            // All connected nodes reachable from A (6 of 7; G is isolated)
            assertEquals(6, view.graph().entityCount());
            assertFalse(view.provenance().cappedByMaxNodes());
        }

        @Test
        @DisplayName("maxNodes larger than graph: not capped, all reachable nodes returned")
        void maxNodesLargerThanGraph() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedId("A")
                    .radius(5)
                    .maxNodes(1000)
                    .build();
            SubgraphView view = materializer.materialize(source, spec);

            assertEquals(6, view.graph().entityCount());
            assertFalse(view.provenance().cappedByMaxNodes());
        }
    }

    // -----------------------------------------------------------------------
    // minEdgeConfidence filter
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("minEdgeConfidence filter")
    class MinConfidenceFilter {

        @Test
        @DisplayName("minEdgeConfidence=0.5: WEAK edge (0.2) excluded; F not reachable from B")
        void weakEdgeExcluded() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedId("B")
                    .radius(1)
                    .minEdgeConfidence(0.5)
                    .build();
            SubgraphView view = materializer.materialize(source, spec);
            MutableReasoningGraph g = view.graph();

            // B→D (confidence 1.0) admitted; B→F (confidence 0.2) blocked
            assertTrue(g.containsEntity("D"), "D reachable via high-confidence CAUSES edge");
            assertFalse(g.containsEntity("F"), "F only reachable via WEAK (0.2) edge — blocked");
        }

        @Test
        @DisplayName("minEdgeConfidence=0.0 (default): WEAK edge included; F reachable from B")
        void noConfidenceFilter() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedId("B")
                    .radius(1)
                    .minEdgeConfidence(0.0)
                    .build();
            SubgraphView view = materializer.materialize(source, spec);

            assertTrue(view.graph().containsEntity("F"), "F reachable when confidence filter is off");
        }

        @Test
        @DisplayName("minEdgeConfidence=1.0: only confidence-1.0 edges traverse; WEAK edge dropped")
        void strictConfidenceFilter() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedId("A")
                    .radius(2)
                    .minEdgeConfidence(1.0)
                    .build();
            SubgraphView view = materializer.materialize(source, spec);

            // WEAK (B→F, confidence 0.2) is dropped; F not included
            assertFalse(view.graph().containsEntity("F"));
            // CAUSES and SUPPORTS are all confidence=1.0 so the rest of the chain is present
            assertTrue(view.graph().containsEntity("D"));
            assertTrue(view.graph().containsEntity("E"));
        }
    }

    // -----------------------------------------------------------------------
    // Entity-type seeds
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Seed by entity type")
    class SeedByEntityType {

        @Test
        @DisplayName("Seed type SOURCE (A,B,C): radius=0 → only A, B, C")
        void seedByTypeSources_radius0() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedEntityType("SOURCE")
                    .radius(0)
                    .build();
            SubgraphView view = materializer.materialize(source, spec);
            MutableReasoningGraph g = view.graph();

            assertEquals(3, g.entityCount(), "A, B, C are SOURCE");
            assertTrue(g.containsEntity("A"));
            assertTrue(g.containsEntity("B"));
            assertTrue(g.containsEntity("C"));
        }

        @Test
        @DisplayName("Seed type TARGET (D,E): radius=1 → D, E + their neighbours B, C")
        void seedByTypeTargets_radius1() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedEntityType("TARGET")
                    .radius(1)
                    .build();
            SubgraphView view = materializer.materialize(source, spec);
            MutableReasoningGraph g = view.graph();

            // D←B and E←C; also B has outgoing F
            assertTrue(g.containsEntity("D"), "seed D");
            assertTrue(g.containsEntity("E"), "seed E");
            assertTrue(g.containsEntity("B"), "B→D (incoming)");
            assertTrue(g.containsEntity("C"), "C→E (incoming)");
        }

        @Test
        @DisplayName("Seed type ISOLATED (G): radius=1 → only G (no edges)")
        void seedByTypeIsolated() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedEntityType("ISOLATED")
                    .radius(1)
                    .build();
            SubgraphView view = materializer.materialize(source, spec);

            assertEquals(1, view.graph().entityCount());
            assertTrue(view.graph().containsEntity("G"));
        }
    }

    // -----------------------------------------------------------------------
    // Provenance
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Provenance metadata")
    class Provenance {

        @Test
        @DisplayName("Provenance records resolved seeds, radius, and source size")
        void provenanceFields() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedId("A")
                    .seedId("B")
                    .radius(1)
                    .build();
            SubgraphView view = materializer.materialize(source, spec);
            SubgraphProvenance prov = view.provenance();

            assertEquals(2, prov.resolvedSeedIds().size());
            assertTrue(prov.resolvedSeedIds().contains("A"));
            assertTrue(prov.resolvedSeedIds().contains("B"));
            assertEquals(1, prov.radius());
            assertEquals(7, prov.sourceEntityCount(), "Source has 7 nodes");
            assertFalse(prov.cappedByMaxNodes());
        }

        @Test
        @DisplayName("Non-existent seed id is not included in resolved seeds")
        void missingSeeds_notInProvenance() {
            SubgraphSpec spec = SubgraphSpec.builder()
                    .seedId("A")
                    .seedId("DOES_NOT_EXIST")
                    .radius(0)
                    .build();
            SubgraphView view = materializer.materialize(source, spec);
            SubgraphProvenance prov = view.provenance();

            assertEquals(1, prov.resolvedSeedIds().size(), "Only A resolved");
            assertFalse(prov.resolvedSeedIds().contains("DOES_NOT_EXIST"));
        }
    }

    // -----------------------------------------------------------------------
    // radius=0 (seeds only)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("radius=0 returns only the seed node, no traversal")
    void radius0_seedOnly() {
        SubgraphSpec spec = SubgraphSpec.builder()
                .seedId("A")
                .radius(0)
                .build();
        SubgraphView view = materializer.materialize(source, spec);

        assertEquals(1, view.graph().entityCount());
        assertTrue(view.graph().containsEntity("A"));
        assertEquals(0, view.graph().relationCount(), "No edges when only seed is included");
    }

    // -----------------------------------------------------------------------
    // Multiple seeds
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Multiple seeds merged: A + G, radius=1 → A's neighbourhood + isolated G")
    void multipleSeeds() {
        SubgraphSpec spec = SubgraphSpec.builder()
                .seedId("A")
                .seedId("G")
                .radius(1)
                .build();
        SubgraphView view = materializer.materialize(source, spec);
        MutableReasoningGraph g = view.graph();

        // From A (radius 1): A, B, C
        // From G (radius 1): G (isolated)
        assertEquals(4, g.entityCount());
        assertTrue(g.containsEntity("A"));
        assertTrue(g.containsEntity("B"));
        assertTrue(g.containsEntity("C"));
        assertTrue(g.containsEntity("G"));
    }

    @Test
    @DisplayName("Unified subgraph preserves applicable analysis assets")
    void materializeUnifiedPreservesScopedAssets() {
        UnifiedGraph unified = UnifiedGraph.of(source)
                .graphId("full-graph")
                .factSheetId(42L)
                .meta("owner", "analysis-team")
                .putEntityVector("kge", "A", new double[] {1.0, 0.0})
                .putEntityVector("kge", "B", new double[] {0.9, 0.1})
                .putEntityVector("kge", "D", new double[] {0.0, 1.0})
                .putRelationVector("rel-kge", "r-AB", new double[] {1.0})
                .putRelationVector("rel-kge", "r-BD", new double[] {0.2})
                .putGlobalVector("relation-types", "CAUSES", new double[] {0.7})
                .putEntityOpinion("A", Opinion.fromBetaEvidence(8, 2))
                .putEntityOpinion("D", Opinion.fromBetaEvidence(2, 8))
                .putRelationOpinion("r-AB", Opinion.fromSoftTruth(0.9))
                .putRelationOpinion("r-BD", Opinion.fromSoftTruth(0.4))
                .putWeightMap("pslWeights", Map.of("CAUSES", 1.5))
                .putArtifactText("notes", "model artifact");

        UnifiedGraph subgraph = materializer.materializeUnified(
                unified, SubgraphSpec.builder().seedId("A").radius(1).build());

        assertTrue(subgraph.containsEntity("A"));
        assertTrue(subgraph.containsEntity("B"));
        assertTrue(subgraph.containsEntity("C"));
        assertFalse(subgraph.containsEntity("D"));
        assertNotNull(subgraph.vectorLayer("kge").get("A"));
        assertNotNull(subgraph.vectorLayer("kge").get("B"));
        assertNull(subgraph.vectorLayer("kge").get("D"));
        assertNotNull(subgraph.vectorLayer("rel-kge").get("r-AB"));
        assertNull(subgraph.vectorLayer("rel-kge").get("r-BD"));
        assertNotNull(subgraph.vectorLayer("relation-types").get("CAUSES"));
        assertNotNull(subgraph.entityOpinion("A"));
        assertNull(subgraph.entityOpinion("D"));
        assertNotNull(subgraph.relationOpinion("r-AB"));
        assertNull(subgraph.relationOpinion("r-BD"));
        assertEquals(1.5, subgraph.weightMap("pslWeights").get("CAUSES"));
        assertEquals("model artifact", subgraph.artifactText("notes"));
        assertEquals("analysis-team", subgraph.meta().get("owner"));
        assertEquals(42L, subgraph.factSheetId());
        assertEquals(1, subgraph.meta().get("subgraph.radius"));
    }

    // -----------------------------------------------------------------------
    // Spec validation guards
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("SubgraphSpec validation")
    class SpecValidation {

        @Test
        @DisplayName("Builder requires at least one seed")
        void noSeedsThrows() {
            assertThrows(IllegalStateException.class, () -> SubgraphSpec.builder().build());
        }

        @Test
        @DisplayName("Negative radius throws")
        void negativeRadiusThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> SubgraphSpec.builder().seedId("A").radius(-1).build());
        }

        @Test
        @DisplayName("minEdgeConfidence out of range throws")
        void confidenceOutOfRangeThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> SubgraphSpec.builder().seedId("A").minEdgeConfidence(1.5).build());
            assertThrows(IllegalArgumentException.class,
                    () -> SubgraphSpec.builder().seedId("A").minEdgeConfidence(-0.1).build());
        }

        @Test
        @DisplayName("Null materializer arguments throw")
        void nullArgsThrow() {
            SubgraphSpec spec = SubgraphSpec.ofSeedId("A");
            assertThrows(NullPointerException.class, () -> materializer.materialize(null, spec));
            assertThrows(NullPointerException.class, () -> materializer.materialize(source, null));
        }
    }
}
