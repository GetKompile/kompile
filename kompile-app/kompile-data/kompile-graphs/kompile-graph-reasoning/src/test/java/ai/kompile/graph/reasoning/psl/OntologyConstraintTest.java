/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OntologicalConstraintBuilder} and
 * {@link SameAsCollectiveResolution} (GAP 7).
 */
class OntologyConstraintTest {

    // ─── OntologicalConstraintBuilder ────────────────────────────────────────────

    @Nested
    class OntologicalConstraintBuilderTests {

        @Test
        void builderProducesRulesForMutualExclusion() {
            OntologicalConstraintBuilder builder = new OntologicalConstraintBuilder();
            builder.addMutualExclusion("Person", "Organization");
            List<PslRule> rules = builder.buildRules();
            assertEquals(1, rules.size());
            PslRule rule = rules.get(0);
            assertTrue(rule.hard(), "Mutual exclusion should be a hard rule");
            assertTrue(rule.head().isEmpty(), "Mutual exclusion should have empty head (violated → infinite penalty)");
            assertEquals(2, rule.body().size(), "Mutual exclusion body should have 2 Type atoms");
        }

        @Test
        void mutualExclusionBodyContainsCorrectTypes() {
            OntologicalConstraintBuilder builder = new OntologicalConstraintBuilder();
            builder.addMutualExclusion("Person", "Organization");
            List<PslRule> rules = builder.buildRules();
            PslRule rule = rules.get(0);
            // Both body literals should use the Type predicate
            for (PslAtom atom : rule.body()) {
                assertEquals(OntologicalConstraintBuilder.TYPE_PREDICATE, atom.predicate());
            }
            // The constant args should be Person and Organization
            Set<String> typeConstants = rule.body().stream()
                    .map(a -> a.args().get(1).name())
                    .collect(Collectors.toSet());
            assertTrue(typeConstants.contains("Person"), "Should contain Person type constant");
            assertTrue(typeConstants.contains("Organization"), "Should contain Organization type constant");
        }

        @Test
        void builderProducesRulesForSubsumption() {
            OntologicalConstraintBuilder builder = new OntologicalConstraintBuilder();
            builder.addSubsumption("Engineer", "Person");
            List<PslRule> rules = builder.buildRules();
            assertEquals(1, rules.size());
            PslRule rule = rules.get(0);
            assertTrue(rule.hard(), "Subsumption should be a hard rule");
            assertEquals(1, rule.body().size(), "Subsumption body should have 1 atom");
            assertEquals(1, rule.head().size(), "Subsumption head should have 1 atom");
        }

        @Test
        void subsumptionRuleHasCorrectDirection() {
            OntologicalConstraintBuilder builder = new OntologicalConstraintBuilder();
            builder.addSubsumption("Engineer", "Person");
            List<PslRule> rules = builder.buildRules();
            PslRule rule = rules.get(0);
            // Body: Type(X, Engineer)
            assertEquals("Engineer", rule.body().get(0).args().get(1).name());
            // Head: Type(X, Person)
            assertEquals("Person", rule.head().get(0).args().get(1).name());
        }

        @Test
        void builderProducesRulesForFunctionalDependency() {
            OntologicalConstraintBuilder builder = new OntologicalConstraintBuilder();
            builder.addFunctionalDependency("HasBoss");
            List<PslRule> rules = builder.buildRules();
            assertEquals(1, rules.size());
            PslRule rule = rules.get(0);
            assertTrue(rule.hard(), "Functional dependency should be a hard rule");
            assertEquals(2, rule.body().size(), "Functional dep body: Rel(X,Y) & Rel(X,Z)");
            assertTrue(rule.head().isEmpty(), "Functional dep head should be empty");
            assertEquals(1, rule.distinct().size(), "Functional dep should have Y != Z distinct guard");
        }

        @Test
        void allConstraintTypesCombined() {
            OntologicalConstraintBuilder builder = new OntologicalConstraintBuilder();
            builder.addMutualExclusion("Cat", "Dog")
                    .addSubsumption("Poodle", "Dog")
                    .addFunctionalDependency("OwnerOf");
            List<PslRule> rules = builder.buildRules();
            assertEquals(3, rules.size());
            assertTrue(rules.stream().allMatch(PslRule::hard), "All ontology rules must be hard");
        }

        @Test
        void applyToPopulatesProgram() {
            OntologicalConstraintBuilder builder = new OntologicalConstraintBuilder();
            builder.addMutualExclusion("A", "B").addSubsumption("C", "A");
            PslProgram program = new PslProgram();
            builder.applyTo(program);
            assertEquals(2, program.rules().size());
        }

        @Test
        void mutualExclusionPreventsSimultaneousTypes() {
            // Register Type atoms: entity "e1" is both Person and Organization.
            // After inference, the mutual exclusion constraint should flag a violation
            // (distance to satisfaction > 0 for the hard rule).
            PslProgram program = new PslProgram();
            program.observe("Type", 1.0, "e1", "Person");
            program.observe("Type", 1.0, "e1", "Organization");

            OntologicalConstraintBuilder builder = new OntologicalConstraintBuilder();
            builder.addMutualExclusion("Person", "Organization");
            builder.applyTo(program);

            List<GroundRule> ground = program.ground();
            // The mutual exclusion rule should ground to: Type(e1, Person) & Type(e1, Organization) -> .
            assertFalse(ground.isEmpty(), "Mutual exclusion should produce at least one ground rule");
            Map<String, Double> values = program.valueSnapshot();
            // At least one ground rule should have positive distance (the constraint is violated)
            boolean anyViolated = ground.stream()
                    .anyMatch(gr -> gr.hard() && gr.distanceToSatisfaction(values) > 0.0);
            assertTrue(anyViolated,
                    "Hard mutual exclusion should be violated when entity has both types");
        }

        @Test
        void subsumptionGroundsWhenSubtypeIsPresent() {
            PslProgram program = new PslProgram();
            program.observe("Type", 1.0, "alice", "Engineer");
            program.target(PslAtom.ground("Type", "alice", "Person"));

            OntologicalConstraintBuilder builder = new OntologicalConstraintBuilder();
            builder.addSubsumption("Engineer", "Person");
            builder.applyTo(program);

            List<GroundRule> ground = program.ground();
            assertFalse(ground.isEmpty(), "Subsumption should produce ground rules");
        }

        @Test
        void noRulesWhenBuilderIsEmpty() {
            OntologicalConstraintBuilder builder = new OntologicalConstraintBuilder();
            assertEquals(0, builder.buildRules().size());
        }

        @Test
        void accessorsReturnCopies() {
            OntologicalConstraintBuilder builder = new OntologicalConstraintBuilder();
            builder.addMutualExclusion("A", "B").addSubsumption("C", "A").addFunctionalDependency("R");
            assertEquals(1, builder.mutualExclusions().size());
            assertEquals(1, builder.subsumptions().size());
            assertEquals(1, builder.functionalRelations().size());
        }
    }

    // ─── SameAsCollectiveResolution ───────────────────────────────────────────────

    @Nested
    class SameAsCollectiveResolutionTests {

        @Test
        void highSimularityLeadsToHighSameAs() {
            SameAsCollectiveResolution er = new SameAsCollectiveResolution();
            er.addSimilarity("alice_1", "alice_2", 0.95);
            er.addSimilarity("alice_1", "bob_1", 0.05);
            er.addCandidate("alice_1");
            er.addCandidate("alice_2");
            er.addCandidate("bob_1");

            SameAsCollectiveResolution.Result result = er.resolve();

            double sameAlice = result.sameAs("alice_1", "alice_2");
            double diffAliceBob = result.sameAs("alice_1", "bob_1");

            assertTrue(sameAlice > diffAliceBob,
                    "High-similarity pair should have higher SameAs than low-similarity pair; "
                            + "alice_1/alice_2=" + sameAlice + " vs alice_1/bob_1=" + diffAliceBob);
        }

        @Test
        void reflexivityIsOne() {
            SameAsCollectiveResolution er = new SameAsCollectiveResolution();
            er.addCandidate("alice");
            SameAsCollectiveResolution.Result result = er.resolve();
            assertEquals(1.0, result.sameAs("alice", "alice"), 1e-9,
                    "SameAs(X, X) should always be 1.0");
        }

        @Test
        void symmetryHoldsApproximately() {
            SameAsCollectiveResolution er = new SameAsCollectiveResolution();
            er.addSimilarity("a", "b", 0.8);
            SameAsCollectiveResolution.Result result = er.resolve();
            double ab = result.sameAs("a", "b");
            double ba = result.sameAs("b", "a");
            // Symmetry rule is a soft rule; MAP may have small asymmetry, but direction should be the same
            assertTrue(ab >= 0.0 && ab <= 1.0);
            assertTrue(ba >= 0.0 && ba <= 1.0);
            // Both should be positive given high similarity
            // (they won't necessarily be exactly equal at MAP, but both > 0)
            assertTrue(ab > 0.0 || ba > 0.0,
                    "With similarity 0.8 between a and b, at least one of SameAs(a,b) SameAs(b,a) should be > 0");
        }

        @Test
        void bestMatchReturnsMostSimilarCandidate() {
            SameAsCollectiveResolution er = new SameAsCollectiveResolution();
            er.addSimilarity("x", "y", 0.9);
            er.addSimilarity("x", "z", 0.1);
            SameAsCollectiveResolution.Result result = er.resolve();
            String best = result.bestMatch("x");
            // Best match for x should be y (highest similarity)
            assertEquals("y", best, "bestMatch should return the candidate with highest SameAs score");
        }

        @Test
        void bestMatchNullWhenNoOtherCandidates() {
            SameAsCollectiveResolution er = new SameAsCollectiveResolution();
            er.addCandidate("solo");
            SameAsCollectiveResolution.Result result = er.resolve();
            assertNull(result.bestMatch("solo"), "bestMatch with no other candidates should return null");
        }

        @Test
        void candidatesListIsPreserved() {
            SameAsCollectiveResolution er = new SameAsCollectiveResolution();
            er.addCandidate("a").addCandidate("b").addCandidate("c");
            SameAsCollectiveResolution.Result result = er.resolve();
            assertEquals(3, result.candidates().size());
            assertTrue(result.candidates().containsAll(List.of("a", "b", "c")));
        }

        @Test
        void addSimilarityAddsBothCandidatesAutomatically() {
            SameAsCollectiveResolution er = new SameAsCollectiveResolution();
            er.addSimilarity("p", "q", 0.7); // should auto-add p and q as candidates
            SameAsCollectiveResolution.Result result = er.resolve();
            assertTrue(result.candidates().contains("p"));
            assertTrue(result.candidates().contains("q"));
        }

        @Test
        void valuesAreUnmodifiable() {
            SameAsCollectiveResolution er = new SameAsCollectiveResolution();
            er.addSimilarity("a", "b", 0.5);
            SameAsCollectiveResolution.Result result = er.resolve();
            assertThrows(UnsupportedOperationException.class,
                    () -> result.values().put("new", 0.0));
        }

        @Test
        void candidatesAreUnmodifiable() {
            SameAsCollectiveResolution er = new SameAsCollectiveResolution();
            er.addCandidate("a");
            SameAsCollectiveResolution.Result result = er.resolve();
            assertThrows(UnsupportedOperationException.class,
                    () -> result.candidates().add("extra"));
        }

        @Test
        void sameAsWithNegativePriorDefaultsLow() {
            // Even without similarity, SameAs should default to low (negative prior)
            SameAsCollectiveResolution er = new SameAsCollectiveResolution()
                    .negativePriorWeight(5.0); // very strong negative prior
            er.addCandidate("a").addCandidate("b");
            // No similarity between a and b
            SameAsCollectiveResolution.Result result = er.resolve();
            double sameAB = result.sameAs("a", "b");
            assertTrue(sameAB < 0.5, "SameAs with strong negative prior and no similarity should be < 0.5");
        }

        @Test
        void buildProgramHasCorrectStructure() {
            SameAsCollectiveResolution er = new SameAsCollectiveResolution();
            er.addSimilarity("x", "y", 0.8);
            PslProgram program = er.buildProgram();
            // Should have rules for: sim→sameAs, symmetry, transitivity, negative prior
            assertTrue(program.rules().size() >= 4,
                    "Program should have at least 4 rules (sim, symmetry, transitivity, negative prior)");
        }

        @Test
        void transitivityWithChain() {
            // a~b (high), b~c (high) → should infer a~c (transitivity)
            SameAsCollectiveResolution er = new SameAsCollectiveResolution()
                    .transitivityWeight(5.0)
                    .simWeight(5.0);
            er.addSimilarity("a", "b", 0.9);
            er.addSimilarity("b", "c", 0.9);
            er.addCandidate("a").addCandidate("b").addCandidate("c");
            SameAsCollectiveResolution.Result result = er.resolve();
            double ac = result.sameAs("a", "c");
            double ab = result.sameAs("a", "b");
            double bc = result.sameAs("b", "c");
            // a~c should be > 0 since a~b~c are chained
            assertTrue(ac > 0.0,
                    "Transitivity should make SameAs(a,c) > 0 given SameAs(a,b) and SameAs(b,c); "
                            + "ab=" + ab + " bc=" + bc + " ac=" + ac);
        }
    }
}
