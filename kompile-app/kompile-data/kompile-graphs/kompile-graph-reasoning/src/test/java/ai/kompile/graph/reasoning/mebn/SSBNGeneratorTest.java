/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.mebn;

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.bayesian.BayesianNode;
import ai.kompile.graph.reasoning.mebn.logic.KnowledgeBase;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the SSBN-construction improvements:
 * <ol>
 *   <li><b>Finding-node / evidence termination</b> (Mahoney &amp; Laskey UAI 1998;
 *       Laskey 2008 Def. 4): a grounded node with an observed value is added as a
 *       deterministic leaf whose parents are NOT instantiated.</li>
 *   <li><b>Bayes-Ball ancestral-set pruning</b> (Santos &amp; Carvalho 2016;
 *       Shachter 1998): only ancestors of (query ∪ evidence) nodes are retained in
 *       the SSBN produced by {@link SSBNGenerator#generateForQuery}.</li>
 * </ol>
 *
 * <p>All tests use hand-built, fully propositional MFrags (no entity arguments)
 * so that the grounded variable names equal the raw RV names.  A no-op stub
 * {@link KnowledgeBase} is used throughout because none of these MFrags have
 * context constraints.</p>
 */
class SSBNGeneratorTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Shared: no-op KnowledgeBase (no context constraints used in these tests)
    // ─────────────────────────────────────────────────────────────────────────

    /** Minimal KB stub: all predicates return false/empty. */
    private static final KnowledgeBase EMPTY_KB = new KnowledgeBase() {
        @Override public boolean entityExists(String id)                               { return false; }
        @Override public boolean edgeExists(String s, String t)                        { return false; }
        @Override public boolean edgeExistsOfType(String s, String t, String type)    { return false; }
        @Override public Optional<String> getEntityType(String id)                     { return Optional.empty(); }
        @Override public Optional<String> getMetadata(String id, String key)           { return Optional.empty(); }
        @Override public Optional<Double> getEdgeWeight(String s, String t)            { return Optional.empty(); }
        @Override public Set<String> getEntitiesOfType(String type)                   { return Set.of(); }
        @Override public Set<String> getConnectedEntities(String id)                  { return Set.of(); }
        @Override public boolean shareProperty(String id1, String id2, String key)    { return false; }
    };

    // ═════════════════════════════════════════════════════════════════════════
    // FINDING-NODE / EVIDENCE TERMINATION
    // (Mahoney & Laskey UAI 1998; Laskey 2008 Def. 4)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Finding-node / evidence termination")
    class FindingNodeTests {

        /**
         * Simple 3-node chain  A → B → C,  B observed as TRUE.
         *
         * <p>Expected: B is a root in the SSBN (its parent A was NOT instantiated as a
         * parent of B); C is present with parent B (propagation downward from B continues).</p>
         *
         * <p>Specifically tests assertion (a) from the task: a finding node is a leaf with
         * no instantiated parents.</p>
         */
        @Test
        @DisplayName("(a) Finding node is a root — its parents are not wired")
        void findingNodeHasNoParents() {
            // Build propositional chain A → B → C
            MTheory theory = buildChainTheory("A", "B", "C");

            // Observe B = TRUE
            SSBNGenerator gen = new SSBNGenerator(theory, EMPTY_KB)
                    .withFindings(Map.of("B", "TRUE"));

            BayesianNetwork ssbn = gen.generate();

            // All three nodes must be present
            assertNotNull(ssbn.getNode("A"), "A should be in the SSBN");
            assertNotNull(ssbn.getNode("B"), "B should be in the SSBN (finding node)");
            assertNotNull(ssbn.getNode("C"), "C should be in the SSBN");

            // B is a finding: it should have NO parents (upward expansion terminated)
            BayesianNode nodeB = ssbn.getNode("B");
            assertTrue(nodeB.isRoot(),
                    "Finding node B must be a root (no parents instantiated)");
            assertEquals(0, nodeB.getParents().size(),
                    "B's parent list must be empty — A was not wired as parent");

            // C is downstream of B and should have B as its (only) parent
            BayesianNode nodeC = ssbn.getNode("C");
            assertFalse(nodeC.isRoot(), "C should have parent B");
            assertEquals(1, nodeC.getParents().size());
            assertEquals("B", nodeC.getParents().get(0).getVariableName(),
                    "C's parent must be B");
        }

        /**
         * A finding node's CPT must be a point-mass at the observed state.
         *
         * <p>For a binary variable observed as TRUE, P(TRUE) must equal 1.0 and
         * P(FALSE) must equal 0.0.</p>
         */
        @Test
        @DisplayName("Finding node CPT is a point mass at the observed state")
        void findingNodeCptIsPointMass() {
            MTheory theory = buildChainTheory("A", "B", "C");

            SSBNGenerator gen = new SSBNGenerator(theory, EMPTY_KB)
                    .withFindings(Map.of("B", "TRUE"));
            BayesianNetwork ssbn = gen.generate();

            BayesianNode nodeB = ssbn.getNode("B");
            assertNotNull(nodeB.getCpt(), "Finding node B must have a CPT set");

            // The CPT is a single-variable factor: [P(FALSE), P(TRUE)]
            double[] cptValues = nodeB.getCpt().getValues();
            assertEquals(2, cptValues.length, "Binary CPT should have 2 entries");
            assertEquals(0.0, cptValues[0], 1e-9, "P(FALSE) for observed-TRUE finding must be 0");
            assertEquals(1.0, cptValues[1], 1e-9, "P(TRUE) for observed-TRUE finding must be 1");
        }

        /**
         * A finding node observed as FALSE also gets the correct point-mass CPT.
         */
        @Test
        @DisplayName("Finding node observed as FALSE has P(FALSE)=1, P(TRUE)=0")
        void findingNodeObservedFalse() {
            MTheory theory = buildChainTheory("A", "B", "C");

            SSBNGenerator gen = new SSBNGenerator(theory, EMPTY_KB)
                    .withFindings(Map.of("B", "FALSE"));
            BayesianNetwork ssbn = gen.generate();

            BayesianNode nodeB = ssbn.getNode("B");
            double[] cptValues = nodeB.getCpt().getValues();
            assertEquals(2, cptValues.length);
            assertEquals(1.0, cptValues[0], 1e-9, "P(FALSE) for observed-FALSE finding must be 1");
            assertEquals(0.0, cptValues[1], 1e-9, "P(TRUE) for observed-FALSE finding must be 0");
        }

        /**
         * When no findings are registered the generator behaves exactly as before
         * (backward-compat guard).
         */
        @Test
        @DisplayName("No findings registered — SSBNGenerator behaves as before")
        void noFindingsPreservesOriginalBehavior() {
            MTheory theory = buildChainTheory("A", "B", "C");

            SSBNGenerator gen = new SSBNGenerator(theory, EMPTY_KB); // no withFindings()
            BayesianNetwork ssbn = gen.generate();

            // All three nodes present with standard parent structure
            BayesianNode nodeB = ssbn.getNode("B");
            assertNotNull(nodeB);
            assertFalse(nodeB.isRoot(), "Without findings, B should have parent A");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BAYES-BALL ANCESTRAL-SET PRUNING  (Santos & Carvalho 2016; Shachter 1998)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Bayes-Ball ancestral-set pruning in generateForQuery")
    class BayesBallPruningTests {

        /**
         * (b) A node irrelevant to the query given evidence is excluded.
         *
         * <p>Setup: two propositional root nodes A and D share the same home MFrag
         * (MFrag_AD).  B's home MFrag (MFrag_B) has A as an input parent.  D has no
         * connection to B.</p>
         *
         * <p>MFrag-level BFS from "B" reaches MFrag_AD (because A is an input of B),
         * so MFrag_AD's residents A and D are both instantiated in the raw SSBN.
         * After Bayes-Ball pruning, D is removed (not an ancestor of B).</p>
         */
        @Test
        @DisplayName("(b) Irrelevant sibling node in the same MFrag is pruned from generateForQuery")
        void irrelevantNodePrunedFromQuerySsbn() {
            // MFrag_AD: two propositional residents A and D (both roots, in same MFrag)
            MFrag fragAD = new MFrag("MFrag_AD");
            RandomVariable rvA = RandomVariable.propositional("A", RandomVariable.NodeRole.RESIDENT);
            RandomVariable rvD = RandomVariable.propositional("D", RandomVariable.NodeRole.RESIDENT);
            fragAD.addResidentNode(rvA);
            fragAD.addResidentNode(rvD);

            // MFrag_B: resident B, parent A (input)
            MFrag fragB = new MFrag("MFrag_B");
            RandomVariable rvBRes = RandomVariable.propositional("B", RandomVariable.NodeRole.RESIDENT);
            RandomVariable rvAIn  = RandomVariable.propositional("A", RandomVariable.NodeRole.INPUT);
            fragB.addResidentNode(rvBRes);
            fragB.addInputNode(rvAIn);
            fragB.addParentEdge("A", "B", 0.8);

            MTheory theory = new MTheory("pruneSiblingTest");
            theory.addMFrag(fragAD);
            theory.addMFrag(fragB);

            SSBNGenerator gen = new SSBNGenerator(theory, EMPTY_KB);

            // Full generate() includes all three nodes (A, D, B)
            BayesianNetwork full = gen.generate();
            assertEquals(3, full.size(), "Full SSBN should have A, B, D");
            assertNotNull(full.getNode("D"), "D present before pruning");

            // generateForQuery("B") should prune D (no path from D to B)
            BayesianNetwork focused = gen.generateForQuery("B");
            assertEquals(2, focused.size(), "Focused SSBN should have only A and B");
            assertNotNull(focused.getNode("A"), "A must be retained (ancestor of B)");
            assertNotNull(focused.getNode("B"), "B must be retained (query node)");
            assertNull(focused.getNode("D"), "D must be pruned (not an ancestor of B)");
        }

        /**
         * (c) Network size is bounded for a simple chain: all nodes in the chain
         * are retained (they are all ancestors of the query), none are added spuriously.
         */
        @Test
        @DisplayName("(c) Chain A→B→C: generateForQuery keeps exactly the chain, nothing extra")
        void chainQueryNetworkIsBounded() {
            // Chain A → B → C in three separate MFrags
            MTheory theory = buildChainTheory("A", "B", "C");
            SSBNGenerator gen = new SSBNGenerator(theory, EMPTY_KB);

            BayesianNetwork ssbn = gen.generateForQuery("C");

            // Exactly 3 nodes: A (ancestor of C), B (ancestor of C), C (query)
            assertEquals(3, ssbn.size(), "Chain query should have exactly 3 nodes");
            assertNotNull(ssbn.getNode("A"));
            assertNotNull(ssbn.getNode("B"));
            assertNotNull(ssbn.getNode("C"));
        }

        /**
         * Combining findings with pruning: in chain A→B→C with B as evidence,
         * generating for query C should retain only B and C — A is pruned because
         * B (a finding node) has no parents in the SSBN, so A is not an ancestor of
         * B or C within the grounded network.
         */
        @Test
        @DisplayName("Finding evidence + pruning: A→B→C with B found → only {B,C} in focused SSBN")
        void findingCombinedWithBayesBallPruning() {
            MTheory theory = buildChainTheory("A", "B", "C");

            SSBNGenerator gen = new SSBNGenerator(theory, EMPTY_KB)
                    .withFindings(Map.of("B", "TRUE")); // B is observed

            BayesianNetwork focused = gen.generateForQuery("C");

            // B is a finding (root, deterministic), C depends on B.
            // A is not an ancestor of B (finding terminates expansion) or C in the network.
            assertEquals(2, focused.size(),
                    "With B as finding, only B and C are needed for the query on C");
            assertNotNull(focused.getNode("B"), "B (finding/evidence) must be retained");
            assertNotNull(focused.getNode("C"), "C (query) must be retained");
            assertNull(focused.getNode("A"),
                    "A must be pruned: B's parent expansion was terminated, so A is not an ancestor of B or C");

            // B is still a root (finding — no parents)
            assertTrue(focused.getNode("B").isRoot(),
                    "B must remain a root (finding node) in the pruned network");
        }

        /**
         * Standalone BayesBallRelevanceFilter unit test.
         *
         * <p>Directly verifies the ancestral-relevant helper on a hand-built network
         * without going through SSBNGenerator.</p>
         */
        @Test
        @DisplayName("BayesBallRelevanceFilter.ancestralRelevant returns correct ancestor set")
        void bayesBallFilterDirectTest() {
            // Build a small network manually: A → B → C, D (disconnected root)
            BayesianNetwork net = new BayesianNetwork();
            net.addNode(new BayesianNode("A", "A", "node A"));
            net.addNode(new BayesianNode("B", "B", "node B"));
            net.addNode(new BayesianNode("C", "C", "node C"));
            net.addNode(new BayesianNode("D", "D", "node D"));
            net.addEdge("A", "B");
            net.addEdge("B", "C");

            // Query = {C}, evidence = {}
            Set<String> relevant = BayesBallRelevanceFilter.ancestralRelevant(
                    net, Set.of("C"), Set.of());

            assertTrue(relevant.contains("A"), "A is an ancestor of C — must be relevant");
            assertTrue(relevant.contains("B"), "B is an ancestor of C — must be relevant");
            assertTrue(relevant.contains("C"), "C is the query node — must be relevant");
            assertFalse(relevant.contains("D"), "D has no path to C — must not be relevant");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPER: build a propositional chain MTheory  X → Y → Z
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Build a propositional 3-node chain theory: nodeA → nodeB → nodeC.
     *
     * <ul>
     *   <li>MFrag_A: sole resident {@code nodeA} (root)</li>
     *   <li>MFrag_B: resident {@code nodeB}, input {@code nodeA}, edge nodeA→nodeB (strength 0.8)</li>
     *   <li>MFrag_C: resident {@code nodeC}, input {@code nodeB}, edge nodeB→nodeC (strength 0.8)</li>
     * </ul>
     */
    private static MTheory buildChainTheory(String nodeA, String nodeB, String nodeC) {
        // MFrag_A: root
        MFrag fragA = new MFrag("MFrag_" + nodeA);
        fragA.addResidentNode(RandomVariable.propositional(nodeA, RandomVariable.NodeRole.RESIDENT));

        // MFrag_B: nodeB with parent nodeA
        MFrag fragB = new MFrag("MFrag_" + nodeB);
        fragB.addResidentNode(RandomVariable.propositional(nodeB, RandomVariable.NodeRole.RESIDENT));
        fragB.addInputNode(RandomVariable.propositional(nodeA, RandomVariable.NodeRole.INPUT));
        fragB.addParentEdge(nodeA, nodeB, 0.8);

        // MFrag_C: nodeC with parent nodeB
        MFrag fragC = new MFrag("MFrag_" + nodeC);
        fragC.addResidentNode(RandomVariable.propositional(nodeC, RandomVariable.NodeRole.RESIDENT));
        fragC.addInputNode(RandomVariable.propositional(nodeB, RandomVariable.NodeRole.INPUT));
        fragC.addParentEdge(nodeB, nodeC, 0.8);

        MTheory theory = new MTheory("chain_" + nodeA + "_" + nodeB + "_" + nodeC);
        theory.addMFrag(fragA);
        theory.addMFrag(fragB);
        theory.addMFrag(fragC);
        return theory;
    }
}
