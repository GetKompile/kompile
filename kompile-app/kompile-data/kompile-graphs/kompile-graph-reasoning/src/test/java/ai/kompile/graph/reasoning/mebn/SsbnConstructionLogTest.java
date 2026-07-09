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
import ai.kompile.graph.reasoning.fol.ReasoningGraphKnowledgeBase;
import ai.kompile.graph.reasoning.mebn.logic.Constraints;
import ai.kompile.graph.reasoning.mebn.logic.KnowledgeBase;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SSBN construction log: {@link SsbnConstructionResult}, {@link SsbnConstructionLog},
 * {@link GroundedNodeLog}, {@link PrunedNodeLog}, and {@link ConstraintOutcome}.
 *
 * <h3>Scenarios</h3>
 * <ol>
 *   <li>One-constraint MFrag with two groundings: one passes (→ CONTEXTUAL), one fails (→ DEFAULT).
 *       The log must capture the constraint display string and pass/fail for each.</li>
 *   <li>Bayes-Ball pruning: {@code generateForQueryWithLog} prunes irrelevant nodes and records
 *       them in {@link SsbnConstructionLog#prunedNodes()}.</li>
 *   <li>Finding node: a finding grounding receives {@link SSBNGenerator.DistributionMode#FINDING}
 *       in the log.</li>
 * </ol>
 */
class SsbnConstructionLogTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Shared no-op KB stub (no edges, overridden per test that needs edges)
    // ─────────────────────────────────────────────────────────────────────────

    private static final KnowledgeBase EMPTY_KB = new KnowledgeBase() {
        @Override public boolean entityExists(String id)                              { return false; }
        @Override public boolean edgeExists(String s, String t)                       { return false; }
        @Override public boolean edgeExistsOfType(String s, String t, String type)   { return false; }
        @Override public Optional<String> getEntityType(String id)                    { return Optional.empty(); }
        @Override public Optional<String> getMetadata(String id, String key)          { return Optional.empty(); }
        @Override public Optional<Double> getEdgeWeight(String s, String t)           { return Optional.empty(); }
        @Override public Set<String> getEntitiesOfType(String type)                  { return Set.of(); }
        @Override public Set<String> getConnectedEntities(String id)                 { return Set.of(); }
        @Override public boolean shareProperty(String id1, String id2, String key)   { return false; }
    };

    // ═════════════════════════════════════════════════════════════════════════
    // CONTEXT-CONSTRAINT OUTCOMES: CONTEXTUAL vs DEFAULT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Build a graph with two entities: alice (has an ACTIVATES edge → bob) and dave (no edges).
     * MFrag context: {@code exists(Y, Person, edgeOfType(X, Y, ACTIVATES))}.
     *
     * Expected per node:
     * - isActive(alice) → constraint PASSES → CONTEXTUAL
     * - isActive(dave)  → constraint FAILS  → DEFAULT  (only if a defaultDistribution is set)
     */
    @Nested
    @DisplayName("Context-constraint outcomes logged per grounding")
    class ContextConstraintLog {

        private SSBNGenerator generator;
        private EntityType personType;

        @BeforeEach
        void setUp() {
            MutableReasoningGraph graph = new MutableReasoningGraph();
            graph.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").build());
            graph.addEntity(GraphEntity.builder("bob").type("Person").label("Bob").build());
            graph.addEntity(GraphEntity.builder("dave").type("Person").label("Dave").build());
            graph.addRelation("r1", "alice", "bob", "ACTIVATES", 0.9);

            KnowledgeBase kb = new ReasoningGraphKnowledgeBase(graph);

            personType = new EntityType("Person", "All persons");
            personType.addEntity("alice");
            personType.addEntity("bob");
            personType.addEntity("dave");

            // Use the named-arg-var factory so the grounding map stores the entity under key "X",
            // matching the variable name used in the context constraint edgeOfType("X","Y","ACTIVATES").
            // Without this, the default arg-var name would be "Person_0" and bindings.get("X")
            // would return null, causing the constraint to always fail.
            RandomVariable rv = RandomVariable.unary("isActive", "X", personType,
                    RandomVariable.NodeRole.RESIDENT);

            MFrag mfrag = new MFrag("isActiveMFrag");
            mfrag.addResidentNode(rv);
            // Context: alice satisfies this; dave does NOT
            mfrag.addContextConstraint(
                    Constraints.exists("Y", "Person",
                            Constraints.edgeOfType("X", "Y", "ACTIVATES")));
            // Must set a default distribution or context-failed groundings are skipped
            mfrag.setDefaultDistribution((name, strengths) -> new double[]{0.70, 0.30});

            MTheory theory = new MTheory("contextLogTest");
            theory.addEntityType(personType);
            theory.addMFrag(mfrag);

            generator = new SSBNGenerator(theory, kb);
        }

        @Test
        @DisplayName("alice (context passes) → CONTEXTUAL mode in log")
        void aliceIsContextual() {
            SsbnConstructionResult result = generator.generateWithLog();
            SsbnConstructionLog log = result.log();

            GroundedNodeLog aliceLog = log.findNode("isActive(alice)");
            assertNotNull(aliceLog, "isActive(alice) should appear in the construction log");

            assertEquals(SSBNGenerator.DistributionMode.CONTEXTUAL, aliceLog.distributionMode(),
                    "alice's grounding passes the context → CONTEXTUAL");
            assertEquals("isActiveMFrag", aliceLog.mfragName());
        }

        @Test
        @DisplayName("dave (context fails) → DEFAULT mode in log")
        void daveIsDefault() {
            SsbnConstructionResult result = generator.generateWithLog();
            SsbnConstructionLog log = result.log();

            GroundedNodeLog daveLog = log.findNode("isActive(dave)");
            assertNotNull(daveLog, "isActive(dave) should appear in the construction log (default distribution set)");

            assertEquals(SSBNGenerator.DistributionMode.DEFAULT, daveLog.distributionMode(),
                    "dave's grounding fails the context → DEFAULT");
        }

        @Test
        @DisplayName("Context-constraint outcome is recorded with display string and pass/fail")
        void constraintOutcomeRecorded() {
            SsbnConstructionResult result = generator.generateWithLog();
            SsbnConstructionLog log = result.log();

            // alice: constraint passed
            GroundedNodeLog aliceLog = log.findNode("isActive(alice)");
            assertNotNull(aliceLog, "isActive(alice) should be logged");
            List<ConstraintOutcome> aliceOutcomes = aliceLog.contextResults();
            assertEquals(1, aliceOutcomes.size(), "MFrag has exactly one context constraint");
            assertTrue(aliceOutcomes.get(0).passed(), "alice's constraint should have passed");
            assertFalse(aliceOutcomes.get(0).constraintDisplay().isBlank(),
                    "constraint display string must not be blank");

            // dave: constraint failed
            GroundedNodeLog daveLog = log.findNode("isActive(dave)");
            assertNotNull(daveLog, "isActive(dave) should be logged");
            List<ConstraintOutcome> daveOutcomes = daveLog.contextResults();
            assertEquals(1, daveOutcomes.size(), "MFrag has exactly one context constraint");
            assertFalse(daveOutcomes.get(0).passed(), "dave's constraint should have failed");
        }

        @Test
        @DisplayName("OV substitution is recorded for each grounding")
        void ovSubstitutionRecorded() {
            SsbnConstructionResult result = generator.generateWithLog();
            SsbnConstructionLog log = result.log();

            // All logged nodes must have a non-null OV substitution
            for (GroundedNodeLog g : log.groundedNodes()) {
                assertNotNull(g.ovSubstitution(),
                        "OV substitution must not be null for node " + g.nodeKey());
            }
        }

        @Test
        @DisplayName("MFrag name is recorded for every grounded node")
        void mfragNameRecorded() {
            SsbnConstructionResult result = generator.generateWithLog();
            SsbnConstructionLog log = result.log();

            for (GroundedNodeLog g : log.groundedNodes()) {
                assertEquals("isActiveMFrag", g.mfragName(),
                        "All nodes should reference isActiveMFrag, got: " + g.mfragName());
            }
        }

        @Test
        @DisplayName("No-constraint MFrag: empty contextResults list")
        void noConstraintMeansEmptyOutcomes() {
            // Build a fresh MFrag with NO context constraints
            EntityType type = new EntityType("Widget", "widgets");
            type.addEntity("w1");
            RandomVariable rv = RandomVariable.unary("isOn", type, RandomVariable.NodeRole.RESIDENT);
            MFrag mfrag = new MFrag("unconstrained");
            mfrag.addResidentNode(rv);

            MTheory theory = new MTheory("unconstrainedTheory");
            theory.addEntityType(type);
            theory.addMFrag(mfrag);

            SSBNGenerator gen = new SSBNGenerator(theory, EMPTY_KB);
            SsbnConstructionResult result = gen.generateWithLog();

            for (GroundedNodeLog g : result.log().groundedNodes()) {
                assertTrue(g.contextResults().isEmpty(),
                        "Unconstrained MFrag should produce empty contextResults for " + g.nodeKey());
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BAYES-BALL PRUNING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Build a 3-node chain A → B → C using propositional (no-arg) MFrags,
     * then generateForQueryWithLog("B").
     *
     * Node C is barren (not an ancestor of B or any evidence variable) and should be pruned.
     * Node A is an ancestor of B and should be kept.
     * The pruned-nodes log must contain exactly "C" with reason "barren".
     *
     * Note: the 3 MFrags are built using the SSBNGeneratorTest helper pattern.
     */
    @Nested
    @DisplayName("Bayes-Ball pruning recorded in prunedNodes")
    class BayesBallPruning {

        /**
         * Build a propositional chain MTheory: A → B → C.
         * All are propositional (no entity arguments).
         */
        private MTheory buildChainTheory() {
            // A (root)
            MFrag fragA = new MFrag("FragA");
            fragA.addResidentNode(RandomVariable.propositional("A", RandomVariable.NodeRole.RESIDENT));

            // B depends on A
            MFrag fragB = new MFrag("FragB");
            RandomVariable bRv = RandomVariable.propositional("B", RandomVariable.NodeRole.RESIDENT);
            RandomVariable aInput = RandomVariable.propositional("A", RandomVariable.NodeRole.INPUT);
            fragB.addResidentNode(bRv);
            fragB.addInputNode(aInput);
            fragB.addParentEdge("A", "B", 0.8);

            // C depends on B
            MFrag fragC = new MFrag("FragC");
            RandomVariable cRv = RandomVariable.propositional("C", RandomVariable.NodeRole.RESIDENT);
            RandomVariable bInput = RandomVariable.propositional("B", RandomVariable.NodeRole.INPUT);
            fragC.addResidentNode(cRv);
            fragC.addInputNode(bInput);
            fragC.addParentEdge("B", "C", 0.8);

            MTheory theory = new MTheory("ChainABC");
            theory.addMFrag(fragA);
            theory.addMFrag(fragB);
            theory.addMFrag(fragC);
            return theory;
        }

        @Test
        @DisplayName("generateForQueryWithLog prunes C when querying B; A is kept")
        void cIsPrunedWhenQueryingB() {
            MTheory theory = buildChainTheory();
            SSBNGenerator gen = new SSBNGenerator(theory, EMPTY_KB);
            SsbnConstructionResult result = gen.generateForQueryWithLog("B");

            SsbnConstructionLog log = result.log();
            BayesianNetwork network = result.network();

            // C must NOT be in the pruned network (it's barren for query B)
            assertNull(network.getNode("C"),
                    "Pruned network must not contain barren node C");

            // A must be in the pruned network (it's an ancestor of B)
            assertNotNull(network.getNode("A"),
                    "Pruned network must contain A (ancestor of B)");

            // B itself must be in the network
            assertNotNull(network.getNode("B"),
                    "Pruned network must contain query node B");

            // Construction log must have at least one pruned node
            assertFalse(log.prunedNodes().isEmpty(),
                    "prunedNodes must be non-empty when C was removed");

            // C should be in prunedNodes with reason "barren"
            boolean cRecorded = log.prunedNodes().stream()
                    .anyMatch(p -> p.nodeKey().equals("C") && "barren".equals(p.reason()));
            assertTrue(cRecorded,
                    "prunedNodes should contain C with reason 'barren', got: " + log.prunedNodes());
        }

        @Test
        @DisplayName("generate (full) does not record pruned nodes")
        void fullGenerateHasNoPrunedNodes() {
            MTheory theory = buildChainTheory();
            SSBNGenerator gen = new SSBNGenerator(theory, EMPTY_KB);
            SsbnConstructionResult result = gen.generateWithLog();

            assertTrue(result.log().prunedNodes().isEmpty(),
                    "Full generate() performs no pruning; prunedNodes must be empty");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FINDING NODE
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Finding nodes appear in log with FINDING mode")
    class FindingNodeLog {

        @Test
        @DisplayName("Finding node receives FINDING distribution mode in log")
        void findingNodeHasFindingMode() {
            // Simple propositional: one node X, observed as TRUE
            MFrag frag = new MFrag("SingleFrag");
            frag.addResidentNode(RandomVariable.propositional("X", RandomVariable.NodeRole.RESIDENT));

            MTheory theory = new MTheory("FindingTheory");
            theory.addMFrag(frag);

            SSBNGenerator gen = new SSBNGenerator(theory, EMPTY_KB)
                    .withFindings(Map.of("X", "TRUE"));

            SsbnConstructionResult result = gen.generateWithLog();
            SsbnConstructionLog log = result.log();

            GroundedNodeLog xLog = log.findNode("X");
            assertNotNull(xLog, "Finding node X must appear in the log");
            assertEquals(SSBNGenerator.DistributionMode.FINDING, xLog.distributionMode(),
                    "Observed node must have FINDING mode in log");
        }
    }
}
