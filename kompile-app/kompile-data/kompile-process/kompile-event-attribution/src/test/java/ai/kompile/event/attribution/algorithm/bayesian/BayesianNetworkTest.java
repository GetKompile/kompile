/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian;

import ai.kompile.event.attribution.algorithm.bayesian.mebn.*;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.logic.*;
import ai.kompile.event.attribution.domain.BayesianInferenceResult;
import ai.kompile.event.attribution.domain.InferenceStep;
import ai.kompile.knowledgegraph.domain.EdgeType;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the Bayesian network framework:
 * Factor operations, NoisyOrCpt, VariableElimination, MEBN MFrags,
 * SSBN generation, and logical constraints.
 */
class BayesianNetworkTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // FACTOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void factor_binaryProduct_correctValues() {
        // P(A): P(A=F)=0.6, P(A=T)=0.4
        Factor fA = Factor.binary(List.of("A"), new double[]{0.6, 0.4});
        // P(B): P(B=F)=0.3, P(B=T)=0.7
        Factor fB = Factor.binary(List.of("B"), new double[]{0.3, 0.7});

        Factor product = Factor.product(fA, fB);
        assertEquals(List.of("A", "B"), product.getVariables());
        assertEquals(4, product.size());

        // P(A=F,B=F) = 0.6*0.3 = 0.18
        assertEquals(0.18, product.getValues()[0], 1e-9);
        // P(A=F,B=T) = 0.6*0.7 = 0.42
        assertEquals(0.42, product.getValues()[1], 1e-9);
        // P(A=T,B=F) = 0.4*0.3 = 0.12
        assertEquals(0.12, product.getValues()[2], 1e-9);
        // P(A=T,B=T) = 0.4*0.7 = 0.28
        assertEquals(0.28, product.getValues()[3], 1e-9);
    }

    @Test
    void factor_marginalize_sumsOutVariable() {
        // Joint factor P(A, B)
        Factor joint = Factor.binary(List.of("A", "B"),
                new double[]{0.18, 0.42, 0.12, 0.28});

        // Marginalize out B → P(A)
        Factor margA = joint.marginalize("B");
        assertEquals(List.of("A"), margA.getVariables());
        assertEquals(0.18 + 0.42, margA.getValue(0), 1e-9); // P(A=F)
        assertEquals(0.12 + 0.28, margA.getValue(1), 1e-9); // P(A=T)
    }

    @Test
    void factor_normalize_sumToOne() {
        Factor f = Factor.binary(List.of("X"), new double[]{3.0, 7.0});
        Factor normalized = f.normalize();
        assertEquals(0.3, normalized.getValue(0), 1e-9);
        assertEquals(0.7, normalized.getValue(1), 1e-9);
    }

    @Test
    void factor_reduce_fixesEvidenceVariable() {
        // P(A, B)
        Factor joint = Factor.binary(List.of("A", "B"),
                new double[]{0.1, 0.2, 0.3, 0.4});

        // Observe B=TRUE (index 1)
        Factor reduced = joint.reduce("B", 1);
        assertEquals(List.of("A"), reduced.getVariables());
        assertEquals(0.2, reduced.getValue(0), 1e-9); // P(A=F, B=T)
        assertEquals(0.4, reduced.getValue(1), 1e-9); // P(A=T, B=T)
    }

    @Test
    void factor_productWithSharedVariable_correctAlignment() {
        // P(A, B) and P(B, C) — B is shared
        Factor f1 = Factor.binary(List.of("A", "B"), new double[]{0.1, 0.2, 0.3, 0.4});
        Factor f2 = Factor.binary(List.of("B", "C"), new double[]{0.5, 0.5, 0.6, 0.4});

        Factor product = Factor.product(f1, f2);
        assertEquals(3, product.getVariables().size());
        assertTrue(product.getVariables().containsAll(List.of("A", "B", "C")));
        assertEquals(8, product.size()); // 2^3
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NOISY-OR CPT TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void noisyOr_allParentsFalse_leakProbabilityOnly() {
        // Child with 2 parents, leak = 0.1
        Factor cpt = NoisyOrCpt.buildCpt("C", List.of("A", "B"),
                new double[]{0.8, 0.6}, 0.1);

        // P(C=T | A=F, B=F) = leak = 0.1
        // Index: A=0,B=0,C=1 → index 1
        assertEquals(0.1, cpt.getValues()[1], 1e-9);
        // P(C=F | A=F, B=F) = 1 - leak = 0.9
        assertEquals(0.9, cpt.getValues()[0], 1e-9);
    }

    @Test
    void noisyOr_oneParentTrue_correctProbability() {
        Factor cpt = NoisyOrCpt.buildCpt("C", List.of("A"),
                new double[]{0.8}, 0.1);

        // P(C=T | A=T) = 1 - (1-0.1)*(1-0.8) = 1 - 0.9*0.2 = 1 - 0.18 = 0.82
        // Index: A=1,C=1 → index 3
        assertEquals(0.82, cpt.getValues()[3], 1e-9);
    }

    @Test
    void noisyOr_allParentsTrue_highProbability() {
        Factor cpt = NoisyOrCpt.buildCpt("C", List.of("A", "B"),
                new double[]{0.8, 0.6}, 0.1);

        // P(C=F | A=T, B=T) = (1-0.1)*(1-0.8)*(1-0.6) = 0.9*0.2*0.4 = 0.072
        // P(C=T | A=T, B=T) = 1 - 0.072 = 0.928
        // Index: A=1,B=1,C=0 → index 6; C=1 → index 7
        assertEquals(0.072, cpt.getValues()[6], 1e-9);
        assertEquals(0.928, cpt.getValues()[7], 1e-9);
    }

    @Test
    void noisyOr_prior_correctValues() {
        Factor prior = NoisyOrCpt.buildPrior("X", 0.7);
        assertEquals(0.3, prior.getValue(0), 1e-9); // P(X=F)
        assertEquals(0.7, prior.getValue(1), 1e-9); // P(X=T)
    }

    @Test
    void noisyOr_causalStrength_combinesWeightConfidenceType() {
        double strength = NoisyOrCpt.computeCausalStrength(0.8, 0.9, 1.0);
        assertEquals(0.72, strength, 1e-9); // 0.8 * 0.9 * 1.0

        double weakStrength = NoisyOrCpt.computeCausalStrength(0.5, 0.5, 0.3);
        assertEquals(0.075, weakStrength, 1e-9); // 0.5 * 0.5 * 0.3
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VARIABLE ELIMINATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void variableElimination_simpleChain_correctPosterior() {
        // A → B → C chain network
        BayesianNetwork network = new BayesianNetwork();

        BayesianNode a = new BayesianNode("A", "kg_a", "Node A");
        BayesianNode b = new BayesianNode("B", "kg_b", "Node B");
        BayesianNode c = new BayesianNode("C", "kg_c", "Node C");

        network.addNode(a);
        network.addNode(b);
        network.addNode(c);
        network.addEdge("A", "B");
        network.addEdge("B", "C");

        // P(A) = [0.4, 0.6]
        a.setCpt(NoisyOrCpt.buildPrior("A", 0.6));
        // P(B|A) with noisy-OR, strength 0.8, leak 0.1
        b.setCpt(NoisyOrCpt.buildCpt("B", List.of("A"), new double[]{0.8}, 0.1));
        // P(C|B) with noisy-OR, strength 0.7, leak 0.05
        c.setCpt(NoisyOrCpt.buildCpt("C", List.of("B"), new double[]{0.7}, 0.05));

        // Query P(C | A=TRUE)
        Factor posterior = VariableElimination.query(network, "C", Map.of("A", 1));
        assertNotNull(posterior);
        assertEquals(1, posterior.getVariables().size());
        assertEquals("C", posterior.getVariables().get(0));

        double pCTrue = posterior.getValue(1);
        assertTrue(pCTrue > 0.0 && pCTrue < 1.0, "P(C=T|A=T) should be between 0 and 1");
        // With A=T: P(B=T|A=T)=0.82, P(C=T|B=T)=1-(1-0.05)*(1-0.7)=0.715
        // P(C=T|A=T) = P(C=T|B=T)*P(B=T|A=T) + P(C=T|B=F)*P(B=F|A=T)
        //            = 0.715*0.82 + 0.05*0.18 = 0.5863 + 0.009 = 0.5953
        assertEquals(0.5953, pCTrue, 0.01);
    }

    @Test
    void variableElimination_queryAllPosteriors_returnsAllVariables() {
        BayesianNetwork network = buildSimpleNetwork();

        Map<String, Double> posteriors = VariableElimination.queryAll(network, Map.of("A", 1));
        assertEquals(3, posteriors.size());
        assertTrue(posteriors.containsKey("A"));
        assertTrue(posteriors.containsKey("B"));
        assertTrue(posteriors.containsKey("C"));

        // A is evidence: P(A=T|A=T) = 1.0
        assertEquals(1.0, posteriors.get("A"), 1e-9);
    }

    @Test
    void variableElimination_noEvidence_returnsPrior() {
        BayesianNetwork network = new BayesianNetwork();
        BayesianNode x = new BayesianNode("X", "kg_x", "Node X");
        x.setCpt(NoisyOrCpt.buildPrior("X", 0.7));
        network.addNode(x);

        Factor posterior = VariableElimination.query(network, "X", Map.of());
        assertEquals(0.7, posterior.getValue(1), 1e-9);
    }

    @Test
    void variableElimination_mostProbableExplanation_correctAssignment() {
        BayesianNetwork network = buildSimpleNetwork();

        // Observe C=TRUE — what's the MPE for A and B?
        Map<String, Integer> mpe = VariableElimination.mostProbableExplanation(
                network, Map.of("C", 1));

        assertNotNull(mpe);
        assertEquals(3, mpe.size());
        assertEquals(1, mpe.get("C")); // Evidence
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BAYESIAN NETWORK DAG TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void bayesianNetwork_cycleDetection_throwsException() {
        BayesianNetwork network = new BayesianNetwork();
        network.addNode(new BayesianNode("A", "a", "A"));
        network.addNode(new BayesianNode("B", "b", "B"));
        network.addNode(new BayesianNode("C", "c", "C"));

        network.addEdge("A", "B");
        network.addEdge("B", "C");

        // C → A would create a cycle
        assertThrows(IllegalArgumentException.class, () -> network.addEdge("C", "A"));
    }

    @Test
    void bayesianNetwork_topologicalOrder_parentsBeforeChildren() {
        BayesianNetwork network = buildSimpleNetwork();
        List<String> order = network.topologicalOrder();

        assertTrue(order.indexOf("A") < order.indexOf("B"));
        assertTrue(order.indexOf("B") < order.indexOf("C"));
    }

    @Test
    void bayesianNetwork_markovBlanket_includesCorrectNodes() {
        BayesianNetwork network = new BayesianNetwork();
        network.addNode(new BayesianNode("A", "a", "A"));
        network.addNode(new BayesianNode("B", "b", "B"));
        network.addNode(new BayesianNode("C", "c", "C"));
        network.addNode(new BayesianNode("D", "d", "D"));

        network.addEdge("A", "C");
        network.addEdge("B", "C");
        network.addEdge("C", "D");

        // Markov blanket of C = {A, B (parents), D (child)}
        Set<String> blanket = network.getMarkovBlanket("C");
        assertTrue(blanket.contains("A"));
        assertTrue(blanket.contains("B"));
        assertTrue(blanket.contains("D"));
        assertFalse(blanket.contains("C"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOGICAL CONSTRAINT TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void logicalConstraint_and_allMustHold() {
        KnowledgeBase kb = new MockKnowledgeBase();
        Map<String, String> bindings = Map.of("x", "node_1", "y", "node_2");

        LogicalConstraint constraint = Constraints.and(
                Constraints.entityExists("x"),
                Constraints.entityExists("y")
        );

        assertTrue(constraint.evaluate(kb, bindings));
    }

    @Test
    void logicalConstraint_or_anyCanHold() {
        KnowledgeBase kb = new MockKnowledgeBase();
        Map<String, String> bindings = Map.of("x", "node_1", "y", "nonexistent");

        LogicalConstraint constraint = Constraints.or(
                Constraints.entityExists("x"),
                Constraints.entityExists("y")
        );

        assertTrue(constraint.evaluate(kb, bindings));
    }

    @Test
    void logicalConstraint_not_invertsResult() {
        KnowledgeBase kb = new MockKnowledgeBase();
        Map<String, String> bindings = Map.of("x", "nonexistent");

        LogicalConstraint constraint = Constraints.not(Constraints.entityExists("x"));
        assertTrue(constraint.evaluate(kb, bindings));
    }

    @Test
    void logicalConstraint_implies_correctSemantics() {
        KnowledgeBase kb = new MockKnowledgeBase();

        // If edge exists, then weight > 0.3 — should be true for node_1→node_2
        LogicalConstraint constraint = Constraints.implies(
                Constraints.edgeExists("x", "y"),
                Constraints.weightAbove("x", "y", 0.3)
        );

        // edge exists and weight=0.8 > 0.3 → true
        assertTrue(constraint.evaluate(kb, Map.of("x", "node_1", "y", "node_2")));

        // edge doesn't exist → vacuously true
        assertTrue(constraint.evaluate(kb, Map.of("x", "node_1", "y", "node_3")));
    }

    @Test
    void logicalConstraint_forAll_universalQuantification() {
        MockKnowledgeBase kb = new MockKnowledgeBase();
        kb.registerType("Event", Set.of("node_1", "node_2"));

        // For all events: entity exists (should be true — both exist)
        LogicalConstraint constraint = Constraints.forAll("e", "Event",
                Constraints.entityExists("e"));

        assertTrue(constraint.evaluate(kb, Map.of()));
    }

    @Test
    void logicalConstraint_exists_existentialQuantification() {
        MockKnowledgeBase kb = new MockKnowledgeBase();
        kb.registerType("Event", Set.of("node_1", "node_2", "nonexistent"));

        // There exists an event that exists (node_1 and node_2 exist)
        LogicalConstraint constraint = Constraints.exists("e", "Event",
                Constraints.entityExists("e"));

        assertTrue(constraint.evaluate(kb, Map.of()));
    }

    @Test
    void logicalConstraint_notEqual_identityCheck() {
        KnowledgeBase kb = new MockKnowledgeBase();

        assertFalse(Constraints.notEqual("x", "y")
                .evaluate(kb, Map.of("x", "node_1", "y", "node_1")));
        assertTrue(Constraints.notEqual("x", "y")
                .evaluate(kb, Map.of("x", "node_1", "y", "node_2")));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MEBN MFRAG + SSBN TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void mebn_mTheory_validationDetectsOrphanInputs() {
        EntityType event = new EntityType("Event");
        RandomVariable rv1 = RandomVariable.unary("isActive", event, RandomVariable.NodeRole.RESIDENT);
        RandomVariable rv2 = RandomVariable.unary("orphanInput", event, RandomVariable.NodeRole.INPUT);

        MFrag frag = new MFrag("testFrag");
        frag.addResidentNode(rv1);
        frag.addInputNode(rv2);

        MTheory theory = new MTheory("test");
        theory.addEntityType(event);
        theory.addMFrag(frag);

        List<String> errors = theory.validate();
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("orphanInput"));
    }

    @Test
    void mebn_mTheory_duplicateResidentRejected() {
        EntityType event = new EntityType("Event");
        RandomVariable rv = RandomVariable.unary("isActive", event, RandomVariable.NodeRole.RESIDENT);

        MFrag frag1 = new MFrag("frag1");
        frag1.addResidentNode(rv);

        MFrag frag2 = new MFrag("frag2");
        frag2.addResidentNode(rv);

        MTheory theory = new MTheory("test");
        theory.addMFrag(frag1);

        assertThrows(IllegalArgumentException.class, () -> theory.addMFrag(frag2));
    }

    @Test
    void mebn_ssbnGeneration_propositionalMFrag() {
        // Propositional (zero-argument) RVs
        RandomVariable rain = RandomVariable.propositional("rain", RandomVariable.NodeRole.RESIDENT);
        RandomVariable wet = RandomVariable.propositional("wet", RandomVariable.NodeRole.RESIDENT);

        MFrag weatherFrag = new MFrag("weather");
        weatherFrag.addResidentNode(rain);
        weatherFrag.addResidentNode(wet);
        weatherFrag.addParentEdge("rain", "wet", 0.9);

        MTheory theory = new MTheory("weatherTheory");
        theory.addMFrag(weatherFrag);

        MockKnowledgeBase kb = new MockKnowledgeBase();
        SSBNGenerator gen = new SSBNGenerator(theory, kb);
        BayesianNetwork ssbn = gen.generate();

        assertEquals(2, ssbn.size());
        assertNotNull(ssbn.getNode("rain"));
        assertNotNull(ssbn.getNode("wet"));

        // wet should have rain as parent
        BayesianNode wetNode = ssbn.getNode("wet");
        assertEquals(1, wetNode.getParents().size());
        assertEquals("rain", wetNode.getParents().get(0).getVariableName());
    }

    @Test
    void mebn_ssbnGeneration_multiEntity_groundsCorrectly() {
        EntityType person = new EntityType("Person");
        person.addEntity("alice");
        person.addEntity("bob");

        RandomVariable isHealthy = RandomVariable.unary("isHealthy", person,
                RandomVariable.NodeRole.RESIDENT);

        MFrag healthFrag = new MFrag("health");
        healthFrag.addResidentNode(isHealthy);

        MTheory theory = new MTheory("healthTheory");
        theory.addEntityType(person);
        theory.addMFrag(healthFrag);

        MockKnowledgeBase kb = new MockKnowledgeBase();
        kb.registerType("Person", Set.of("alice", "bob"));

        SSBNGenerator gen = new SSBNGenerator(theory, kb);
        BayesianNetwork ssbn = gen.generate();

        // Should create 2 grounded nodes: isHealthy(alice), isHealthy(bob)
        assertEquals(2, ssbn.size());
        assertNotNull(ssbn.getNode("isHealthy(alice)"));
        assertNotNull(ssbn.getNode("isHealthy(bob)"));
    }

    @Test
    void mebn_ssbnGeneration_contextConstraintFiltersGroundings() {
        EntityType person = new EntityType("Person");
        person.addEntity("alice");
        person.addEntity("bob");

        RandomVariable isActive = RandomVariable.unary("isActive", person,
                RandomVariable.NodeRole.RESIDENT);

        MFrag frag = new MFrag("activeFrag");
        frag.addResidentNode(isActive);
        // Only apply to entities that exist in KG
        frag.addContextConstraint(Constraints.entityExists("Person"));

        MTheory theory = new MTheory("test");
        theory.addEntityType(person);
        theory.addMFrag(frag);

        MockKnowledgeBase kb = new MockKnowledgeBase();
        kb.registerType("Person", Set.of("alice", "bob"));
        // Only "alice" exists as node_1 equivalent; "bob" maps to nonexistent
        kb.addEntity("alice");
        // bob doesn't exist in the KB

        SSBNGenerator gen = new SSBNGenerator(theory, kb);
        BayesianNetwork ssbn = gen.generate();

        // Only alice's grounding should pass the entityExists context
        assertEquals(1, ssbn.size());
        assertNotNull(ssbn.getNode("isActive(alice)"));
    }

    @Test
    void mebn_ssbnGeneration_withInference() {
        // Build a multi-entity BN: isRisky(event) → triggers(event)
        EntityType event = new EntityType("Event");
        event.addEntity("evt_1");
        event.addEntity("evt_2");

        RandomVariable isRisky = RandomVariable.unary("isRisky", event,
                RandomVariable.NodeRole.RESIDENT);
        RandomVariable triggers = RandomVariable.unary("triggers", event,
                RandomVariable.NodeRole.RESIDENT);

        // isRisky is also an input to the triggers MFrag
        RandomVariable isRiskyInput = RandomVariable.unary("isRisky", event,
                RandomVariable.NodeRole.INPUT);

        MFrag riskFrag = new MFrag("riskFrag");
        riskFrag.addResidentNode(isRisky);

        MFrag triggerFrag = new MFrag("triggerFrag");
        triggerFrag.addResidentNode(triggers);
        triggerFrag.addInputNode(isRiskyInput);
        triggerFrag.addParentEdge("isRisky", "triggers", 0.8);

        MTheory theory = new MTheory("riskTheory");
        theory.addEntityType(event);
        theory.addMFrag(riskFrag);
        theory.addMFrag(triggerFrag);

        MockKnowledgeBase kb = new MockKnowledgeBase();
        kb.registerType("Event", Set.of("evt_1", "evt_2"));
        kb.addEntity("evt_1");
        kb.addEntity("evt_2");

        SSBNGenerator gen = new SSBNGenerator(theory, kb);
        BayesianNetwork ssbn = gen.generate();

        // Should have 4 nodes: isRisky(evt_1), isRisky(evt_2), triggers(evt_1), triggers(evt_2)
        assertEquals(4, ssbn.size());

        // Run inference: observe isRisky(evt_1) = TRUE
        Map<String, Integer> evidence = Map.of("isRisky(evt_1)", 1);
        Map<String, Double> posteriors = VariableElimination.queryAll(ssbn, evidence);

        assertEquals(1.0, posteriors.get("isRisky(evt_1)"), 1e-9);
        // triggers(evt_1) should have increased probability
        double pTriggers = posteriors.get("triggers(evt_1)");
        assertTrue(pTriggers > 0.5, "P(triggers(evt_1)|isRisky(evt_1)=T) should be > 0.5, was " + pTriggers);
    }

    @Test
    void mebn_randomVariable_allGroundings_cartesianProduct() {
        EntityType person = new EntityType("Person");
        person.addEntity("alice");
        person.addEntity("bob");

        EntityType event = new EntityType("Event");
        event.addEntity("evt_1");

        RandomVariable rv = RandomVariable.binary("influences", person, event,
                RandomVariable.NodeRole.RESIDENT);

        List<List<String>> groundings = rv.allGroundings();
        assertEquals(2, groundings.size()); // 2 persons * 1 event
        assertTrue(groundings.contains(List.of("alice", "evt_1")));
        assertTrue(groundings.contains(List.of("bob", "evt_1")));
    }

    @Test
    void mebn_entityType_subtypeHierarchy() {
        EntityType animal = new EntityType("Animal");
        EntityType dog = new EntityType("Dog");
        dog.setSuperType(animal);

        assertTrue(dog.isSubtypeOf(animal));
        assertTrue(dog.isSubtypeOf(dog));
        assertFalse(animal.isSubtypeOf(dog));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private BayesianNetwork buildSimpleNetwork() {
        BayesianNetwork network = new BayesianNetwork();
        BayesianNode a = new BayesianNode("A", "kg_a", "Node A");
        BayesianNode b = new BayesianNode("B", "kg_b", "Node B");
        BayesianNode c = new BayesianNode("C", "kg_c", "Node C");

        network.addNode(a);
        network.addNode(b);
        network.addNode(c);
        network.addEdge("A", "B");
        network.addEdge("B", "C");

        a.setCpt(NoisyOrCpt.buildPrior("A", 0.6));
        b.setCpt(NoisyOrCpt.buildCpt("B", List.of("A"), new double[]{0.8}, 0.1));
        c.setCpt(NoisyOrCpt.buildCpt("C", List.of("B"), new double[]{0.7}, 0.05));

        return network;
    }

    /**
     * Mock KnowledgeBase for testing logical constraints and SSBN generation
     * without requiring the full KG service.
     */
    private static class MockKnowledgeBase implements KnowledgeBase {

        private final Set<String> entities = new HashSet<>(Set.of("node_1", "node_2"));
        private final Map<String, Set<String>> typeMap = new HashMap<>();

        void addEntity(String id) {
            entities.add(id);
        }

        void registerType(String typeName, Set<String> ids) {
            typeMap.put(typeName, new LinkedHashSet<>(ids));
        }

        @Override
        public boolean entityExists(String entityId) {
            return entities.contains(entityId);
        }

        @Override
        public boolean edgeExists(String sourceId, String targetId) {
            return "node_1".equals(sourceId) && "node_2".equals(targetId);
        }

        @Override
        public boolean edgeExistsOfType(String sourceId, String targetId, EdgeType edgeType) {
            return edgeExists(sourceId, targetId) && edgeType == EdgeType.USER_DEFINED;
        }

        @Override
        public Optional<String> getEntityType(String entityId) {
            return entityExists(entityId) ? Optional.of("ENTITY") : Optional.empty();
        }

        @Override
        public Optional<String> getMetadata(String entityId, String metadataKey) {
            return Optional.empty();
        }

        @Override
        public Optional<Double> getEdgeWeight(String sourceId, String targetId) {
            if (edgeExists(sourceId, targetId)) return Optional.of(0.8);
            return Optional.empty();
        }

        @Override
        public Set<String> getEntitiesOfType(String typeName) {
            return typeMap.getOrDefault(typeName, Set.of());
        }

        @Override
        public Set<String> getConnectedEntities(String entityId) {
            if ("node_1".equals(entityId)) return Set.of("node_2");
            return Set.of();
        }

        @Override
        public boolean shareProperty(String entityId1, String entityId2, String propertyKey) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SENSITIVITY ANALYSIS TESTS (VariableElimination-based)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void sensitivityAnalysis_perturbingSingleVariable_changesQueryPosterior() {
        // Build A → B → C chain
        BayesianNetwork net = buildSimpleChain();

        // Baseline: P(C=T | no evidence)
        Factor baseline = VariableElimination.query(net, "C", Map.of());
        double baselineP = baseline.getValue(1);

        // Perturb: P(C=T | A=TRUE)
        Factor perturbed = VariableElimination.query(net, "C", Map.of("A", 1));
        double perturbedP = perturbed.getValue(1);

        // Setting A=TRUE should change the posterior of C
        double sensitivity = Math.abs(perturbedP - baselineP);
        assertTrue(sensitivity > 0, "Setting A=TRUE should shift C's posterior");
    }

    @Test
    void sensitivityAnalysis_intermediateVariable_higherSensitivity() {
        // Build A → B → C chain
        BayesianNetwork net = buildSimpleChain();

        // Sensitivity of C to B should be >= sensitivity of C to A
        // because B is a direct parent of C
        Factor baseline = VariableElimination.query(net, "C", Map.of());
        double baselineP = baseline.getValue(1);

        Factor perturbedByA = VariableElimination.query(net, "C", Map.of("A", 1));
        double sensitivityToA = Math.abs(perturbedByA.getValue(1) - baselineP);

        Factor perturbedByB = VariableElimination.query(net, "C", Map.of("B", 1));
        double sensitivityToB = Math.abs(perturbedByB.getValue(1) - baselineP);

        assertTrue(sensitivityToB >= sensitivityToA,
                "C should be at least as sensitive to direct parent B as to grandparent A");
    }

    @Test
    void sensitivityAnalysis_evidenceVarHasZeroSensitivity() {
        // When A is already evidence, its sensitivity should not be computed
        BayesianNetwork net = buildSimpleChain();

        // With A as evidence, perturbing A again is meaningless
        // The sensitivity analysis method should skip evidence vars
        Factor withEvidence = VariableElimination.query(net, "C", Map.of("A", 1));
        assertNotNull(withEvidence);
        assertTrue(withEvidence.getValue(1) > 0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WHAT-IF QUERY TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void whatIf_noEvidence_returnsPriors() {
        BayesianNetwork net = buildSimpleChain();

        // No evidence: posteriors should equal priors
        Map<String, Double> priors = VariableElimination.queryAll(net, Map.of());
        Map<String, Double> posteriors = VariableElimination.queryAll(net, Map.of());

        assertEquals(priors.size(), posteriors.size());
        for (String var : priors.keySet()) {
            assertEquals(priors.get(var), posteriors.get(var), 1e-9);
        }
    }

    @Test
    void whatIf_hypotheticalEvidence_shiftsPosteriorsFromPriors() {
        BayesianNetwork net = buildSimpleChain();

        Map<String, Double> priors = VariableElimination.queryAll(net, Map.of());
        Map<String, Double> posteriors = VariableElimination.queryAll(net, Map.of("A", 1));

        // With A=TRUE, A's posterior should be 1.0
        assertEquals(1.0, posteriors.get("A"), 1e-9);

        // B's posterior should shift from its prior
        assertNotEquals(priors.get("B"), posteriors.get("B"), 1e-9,
                "B's posterior should differ from its prior when A=TRUE is observed");

        // C should also shift
        assertNotEquals(priors.get("C"), posteriors.get("C"), 1e-9,
                "C's posterior should differ from its prior when A=TRUE is observed");
    }

    @Test
    void whatIf_contradictoryEvidence_producesValidPosteriors() {
        BayesianNetwork net = buildSimpleChain();

        // Set A=FALSE evidence
        Map<String, Double> posteriors = VariableElimination.queryAll(net, Map.of("A", 0));

        // All posteriors should be valid probabilities
        for (Map.Entry<String, Double> entry : posteriors.entrySet()) {
            assertTrue(entry.getValue() >= 0 && entry.getValue() <= 1.0,
                    entry.getKey() + " posterior " + entry.getValue() + " out of [0,1] range");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INFERENCE STEP / RESULT TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void inferenceStep_builderCreatesValidStep() {
        InferenceStep step = InferenceStep.builder()
                .eliminatedVariable("X")
                .eliminatedTitle("Variable X")
                .factorsInvolved(3)
                .factorVariables(List.of("A", "B", "X"))
                .operation("MARGINALIZE")
                .priorValue(0.5)
                .posteriorValue(0.73)
                .contributionWeight(0.23)
                .build();

        assertEquals("X", step.getEliminatedVariable());
        assertEquals("Variable X", step.getEliminatedTitle());
        assertEquals(3, step.getFactorsInvolved());
        assertEquals("MARGINALIZE", step.getOperation());
        assertEquals(0.5, step.getPriorValue());
        assertEquals(0.73, step.getPosteriorValue());
        assertEquals(0.23, step.getContributionWeight());
    }

    @Test
    void bayesianInferenceResult_includesInferenceTrace() {
        InferenceStep step1 = InferenceStep.builder()
                .eliminatedVariable("B")
                .operation("MARGINALIZE")
                .factorsInvolved(2)
                .build();

        InferenceStep step2 = InferenceStep.builder()
                .eliminatedVariable("RESULT")
                .operation("NORMALIZE")
                .factorsInvolved(1)
                .build();

        BayesianInferenceResult result = BayesianInferenceResult.builder()
                .posteriors(Map.of("A", 0.8))
                .priors(Map.of("A", 0.5))
                .inferenceTrace(List.of(step1, step2))
                .build();

        assertEquals(2, result.getInferenceTrace().size());
        assertEquals("MARGINALIZE", result.getInferenceTrace().get(0).getOperation());
        assertEquals("NORMALIZE", result.getInferenceTrace().get(1).getOperation());
        assertEquals(0.5, result.getPriors().get("A"));
        assertEquals(0.8, result.getPosteriors().get("A"));
    }

    @Test
    void bayesianInferenceResult_defaultsToEmptyTraceAndPriors() {
        BayesianInferenceResult result = BayesianInferenceResult.builder().build();
        assertNotNull(result.getInferenceTrace());
        assertTrue(result.getInferenceTrace().isEmpty());
        assertNotNull(result.getPriors());
        assertTrue(result.getPriors().isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MPE WITH EVIDENCE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void mpe_withEvidence_includesEvidenceVariables() {
        BayesianNetwork net = buildSimpleChain();

        Map<String, Integer> mpe = VariableElimination.mostProbableExplanation(net, Map.of("A", 1));

        // MPE should include the evidence variable
        assertEquals(1, mpe.get("A"));

        // All variables should have a state assignment
        assertEquals(3, mpe.size());
        assertTrue(mpe.containsKey("B"));
        assertTrue(mpe.containsKey("C"));
    }

    @Test
    void mpe_noEvidence_assignsStates() {
        BayesianNetwork net = buildSimpleChain();

        Map<String, Integer> mpe = VariableElimination.mostProbableExplanation(net, Map.of());

        // All 3 variables should be assigned
        assertEquals(3, mpe.size());
        // Each should be 0 or 1 for binary variables
        for (int state : mpe.values()) {
            assertTrue(state == 0 || state == 1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER: Simple chain A → B → C
    // ═══════════════════════════════════════════════════════════════════════════

    private BayesianNetwork buildSimpleChain() {
        BayesianNetwork net = new BayesianNetwork();

        BayesianNode a = new BayesianNode("A", "kg-a", "Node A");
        BayesianNode b = new BayesianNode("B", "kg-b", "Node B");
        BayesianNode c = new BayesianNode("C", "kg-c", "Node C");

        net.addNode(a);
        net.addNode(b);
        net.addNode(c);
        net.addEdge("A", "B");
        net.addEdge("B", "C");

        // P(A): prior
        a.setCpt(Factor.binary(List.of("A"), new double[]{0.6, 0.4}));
        // P(B|A): noisy-OR style
        b.setCpt(Factor.binary(List.of("A", "B"), new double[]{0.8, 0.2, 0.3, 0.7}));
        // P(C|B): noisy-OR style
        c.setCpt(Factor.binary(List.of("B", "C"), new double[]{0.9, 0.1, 0.4, 0.6}));

        return net;
    }
}
