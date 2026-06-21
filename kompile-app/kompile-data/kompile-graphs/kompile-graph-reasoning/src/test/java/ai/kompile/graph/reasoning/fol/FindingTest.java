/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol;

import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Finding, FindingStore, Fact, FactStore, and EntailmentEngine.
 */
class FindingTest {

    // ─── Finding tests ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Finding")
    class FindingTests {

        @Test
        @DisplayName("Finding.hard() creates hard finding with correct fields")
        void hardFinding() {
            Finding f = Finding.hard("isActive", List.of("alice"), 1, "test-source");
            assertEquals("isActive", f.rvName());
            assertEquals(List.of("alice"), f.entityArgs());
            assertEquals(1, f.stateIndex());
            assertNull(f.likelihood());
            assertEquals("test-source", f.sourceId());
            assertNotNull(f.timestamp());
            assertNull(f.inferenceRunId());
            assertTrue(f.isHard());
            assertFalse(f.isSoft());
        }

        @Test
        @DisplayName("Finding.soft() creates soft finding with correct fields")
        void softFinding() {
            double[] lh = {0.3, 0.7};
            Finding f = Finding.soft("isRisky", List.of("bob"), lh, "channel-1");
            assertEquals("isRisky", f.rvName());
            assertEquals(List.of("bob"), f.entityArgs());
            assertEquals(-1, f.stateIndex());
            assertNotNull(f.likelihood());
            assertEquals(2, f.likelihood().length);
            assertEquals(0.3, f.likelihood()[0], 1e-9);
            assertEquals(0.7, f.likelihood()[1], 1e-9);
            assertFalse(f.isHard());
            assertTrue(f.isSoft());
        }

        @Test
        @DisplayName("Finding.groundedKey() returns correct format")
        void groundedKey() {
            Finding f1 = Finding.hard("isActive", List.of("alice"), 1, "src");
            assertEquals("isActive(alice)", f1.groundedKey());

            Finding f2 = Finding.hard("knows", List.of("alice", "bob"), 1, "src");
            assertEquals("knows(alice,bob)", f2.groundedKey());

            Finding f3 = Finding.hard("global", List.of(), 1, "src");
            assertEquals("global()", f3.groundedKey());
        }

        @Test
        @DisplayName("Finding.hard() rejects negative stateIndex")
        void hardRejectsNegativeIndex() {
            assertThrows(IllegalArgumentException.class,
                    () -> Finding.hard("rv", List.of("x"), -1, "src"));
        }

        @Test
        @DisplayName("Finding.soft() rejects likelihood with less than 2 entries")
        void softRejectsTooShortLikelihood() {
            assertThrows(IllegalArgumentException.class,
                    () -> Finding.soft("rv", List.of("x"), new double[]{0.5}, "src"));
        }

        @Test
        @DisplayName("Finding.soft() rejects null likelihood")
        void softRejectsNullLikelihood() {
            assertThrows(NullPointerException.class,
                    () -> Finding.soft("rv", List.of("x"), null, "src"));
        }
    }

    // ─── FindingStore tests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("FindingStore")
    class FindingStoreTests {

        FindingStore store;

        @BeforeEach
        void setup() {
            store = new FindingStore();
        }

        @Test
        @DisplayName("assertFinding and findingFor work correctly")
        void assertAndFind() {
            Finding f = Finding.hard("isActive", List.of("alice"), 1, "src");
            store.assertFinding(f);
            assertEquals(1, store.size());
            Optional<Finding> found = store.findingFor("isActive(alice)");
            assertTrue(found.isPresent());
            assertEquals(f, found.get());
        }

        @Test
        @DisplayName("assertFinding replaces existing finding (revision semantics)")
        void assertReplacesExisting() {
            Finding f1 = Finding.hard("isActive", List.of("alice"), 1, "src");
            Finding f2 = Finding.hard("isActive", List.of("alice"), 0, "src2");
            store.assertFinding(f1);
            store.assertFinding(f2);
            assertEquals(1, store.size());
            Optional<Finding> found = store.findingFor("isActive(alice)");
            assertTrue(found.isPresent());
            assertEquals(0, found.get().stateIndex());
        }

        @Test
        @DisplayName("retract by rvName and entityArgs removes finding")
        void retractByArgs() {
            Finding f = Finding.hard("isActive", List.of("alice"), 1, "src");
            store.assertFinding(f);
            Optional<Finding> removed = store.retract("isActive", List.of("alice"));
            assertTrue(removed.isPresent());
            assertEquals(f, removed.get());
            assertTrue(store.isEmpty());
        }

        @Test
        @DisplayName("retract by groundedKey removes finding")
        void retractByKey() {
            Finding f = Finding.hard("isActive", List.of("bob"), 1, "src");
            store.assertFinding(f);
            Optional<Finding> removed = store.retract("isActive(bob)");
            assertTrue(removed.isPresent());
            assertTrue(store.isEmpty());
        }

        @Test
        @DisplayName("retract returns empty if not present")
        void retractMissing() {
            Optional<Finding> removed = store.retract("nonexistent(x)");
            assertFalse(removed.isPresent());
        }

        @Test
        @DisplayName("findingsFor filters by rvName")
        void findingsFor() {
            store.assertFinding(Finding.hard("isActive", List.of("alice"), 1, "src"));
            store.assertFinding(Finding.hard("isActive", List.of("bob"), 1, "src"));
            store.assertFinding(Finding.hard("isRisky", List.of("carol"), 1, "src"));

            List<Finding> active = store.findingsFor("isActive");
            assertEquals(2, active.size());

            List<Finding> risky = store.findingsFor("isRisky");
            assertEquals(1, risky.size());

            List<Finding> none = store.findingsFor("nonexistent");
            assertTrue(none.isEmpty());
        }

        @Test
        @DisplayName("allFindings returns unmodifiable view")
        void allFindings() {
            store.assertFinding(Finding.hard("rv", List.of("x"), 1, "src"));
            var all = store.allFindings();
            assertEquals(1, all.size());
            assertThrows(UnsupportedOperationException.class, () -> all.clear());
        }

        @Test
        @DisplayName("clear removes all findings")
        void clear() {
            store.assertFinding(Finding.hard("rv", List.of("x"), 1, "src"));
            store.assertFinding(Finding.hard("rv", List.of("y"), 1, "src"));
            store.clear();
            assertTrue(store.isEmpty());
            assertEquals(0, store.size());
        }
    }

    // ─── Fact tests ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Fact")
    class FactTests {

        @Test
        @DisplayName("Fact.observed() creates hard fact with value 1.0")
        void observed() {
            Fact f = Fact.observed("State(alice)", "crawl-1");
            assertEquals("State(alice)", f.atomKey());
            assertEquals(1.0, f.value(), 1e-9);
            assertEquals("crawl-1", f.sourceId());
            assertTrue(f.hard());
        }

        @Test
        @DisplayName("Fact.soft() creates soft fact with given value")
        void soft() {
            Fact f = Fact.soft("Risky(bob)", 0.6, "user");
            assertEquals("Risky(bob)", f.atomKey());
            assertEquals(0.6, f.value(), 1e-9);
            assertFalse(f.hard());
        }

        @Test
        @DisplayName("Fact constructor rejects value out of [0,1]")
        void rejectsOutOfRangeValue() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Fact("Key(x)", 1.5, "src", Instant.now(), true));
            assertThrows(IllegalArgumentException.class,
                    () -> new Fact("Key(x)", -0.1, "src", Instant.now(), true));
        }
    }

    // ─── FactStore tests ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FactStore")
    class FactStoreTests {

        FactStore store;

        @BeforeEach
        void setup() {
            store = new FactStore();
        }

        @Test
        @DisplayName("assertFact and factFor work correctly")
        void assertAndFind() {
            Fact f = Fact.observed("State(alice)", "src");
            store.assertFact(f);
            assertEquals(1, store.size());
            Optional<Fact> found = store.factFor("State(alice)");
            assertTrue(found.isPresent());
            assertEquals(f, found.get());
        }

        @Test
        @DisplayName("assertFact replaces existing fact")
        void assertReplaces() {
            store.assertFact(Fact.observed("State(alice)", "src1"));
            store.assertFact(Fact.soft("State(alice)", 0.3, "src2"));
            assertEquals(1, store.size());
            Optional<Fact> found = store.factFor("State(alice)");
            assertTrue(found.isPresent());
            assertEquals(0.3, found.get().value(), 1e-9);
        }

        @Test
        @DisplayName("retract removes fact")
        void retract() {
            Fact f = Fact.observed("State(bob)", "src");
            store.assertFact(f);
            Optional<Fact> removed = store.retract("State(bob)");
            assertTrue(removed.isPresent());
            assertTrue(store.isEmpty());
        }

        @Test
        @DisplayName("factsFor filters by predicate name")
        void factsFor() {
            store.assertFact(Fact.observed("State(alice)", "src"));
            store.assertFact(Fact.observed("State(bob)", "src"));
            store.assertFact(Fact.observed("Knows(alice, bob)", "src"));

            List<Fact> states = store.factsFor("State");
            assertEquals(2, states.size());

            List<Fact> knows = store.factsFor("Knows");
            assertEquals(1, knows.size());

            List<Fact> none = store.factsFor("Nonexistent");
            assertTrue(none.isEmpty());
        }

        @Test
        @DisplayName("FactStore.applyToProgram observes atoms in a PslProgram")
        void applyToProgram() {
            // Build a small PSL program
            PslProgram program = new PslProgram();
            program.addRule("1.0: State(X) & Link(X, Y) -> State(Y) ^2");
            program.target("State", "alice");
            program.target("State", "bob");
            program.target("Link", "alice", "bob");

            // Add facts
            store.assertFact(Fact.observed("State(alice)", "src"));
            store.assertFact(Fact.observed("Link(alice, bob)", "src"));

            // Apply facts to program
            store.applyToProgram(program);

            // Verify atoms are observed
            assertTrue(program.isObserved("State(alice)"));
            assertTrue(program.isObserved("Link(alice, bob)"));
            assertEquals(1.0, program.value("State(alice)"), 1e-9);
            assertEquals(1.0, program.value("Link(alice, bob)"), 1e-9);
        }
    }

    // ─── EntailmentEngine tests ──────────────────────────────────────────────────

    @Nested
    @DisplayName("EntailmentEngine")
    class EntailmentEngineTests {

        MutableReasoningGraph graph;

        @BeforeEach
        void buildGraph() {
            graph = new MutableReasoningGraph();
            graph.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").weight(0.9).build());
            graph.addEntity(GraphEntity.builder("bob").type("Person").label("Bob").weight(0.7).build());
            graph.addEntity(GraphEntity.builder("carol").type("Person").label("Carol").weight(0.5).build());
            graph.addRelation("r1", "alice", "bob", "ACTIVATES", 0.9);
            graph.addRelation("r2", "bob", "carol", "ACTIVATES", 0.8);
        }

        @Test
        @DisplayName("EntailmentEngine.entailFromPsl() produces EntailmentRecords")
        void entailFromPsl() {
            PslProgram program = new PslProgram();
            program.addRule("2.0: State(X) & Link(X, Y) -> State(Y) ^2");
            program.target("State", "alice");
            program.target("State", "bob");
            program.target("Link", "alice", "bob");

            FactStore factStore = new FactStore();
            factStore.assertFact(Fact.observed("State(alice)", "user-input"));

            List<EntailmentRecord> records = EntailmentEngine.entailFromPsl(program, factStore);
            assertNotNull(records);
            // Should have records for target atoms
            // Note: target atoms are those not marked as observed
            assertFalse(records.isEmpty(), "Should produce at least one entailment record");

            for (EntailmentRecord r : records) {
                assertNotNull(r.groundedRvOrAtomKey());
                assertTrue(r.posterior() >= 0.0 && r.posterior() <= 1.0,
                        "Posterior out of [0,1] for " + r.groundedRvOrAtomKey() + ": " + r.posterior());
                assertNotNull(r.computedAt());
                assertNotNull(r.inferenceRunId());
            }
        }

        @Test
        @DisplayName("EntailmentEngine.entailFromMebn() produces EntailmentRecords with supporting findings")
        void entailFromMebn() {
            MTheory theory = MebnInferenceService.buildSimpleTheory(graph, "Person", "isActive");
            FindingStore findingStore = new FindingStore();
            findingStore.assertFinding(Finding.hard("isActive", List.of("alice"), 1, "user-input"));

            List<EntailmentRecord> records = EntailmentEngine.entailFromMebn(graph, findingStore, theory);
            assertNotNull(records);
            assertFalse(records.isEmpty(), "Should produce at least one entailment record from MEBN");

            for (EntailmentRecord r : records) {
                assertNotNull(r.groundedRvOrAtomKey());
                assertTrue(r.posterior() >= 0.0 && r.posterior() <= 1.0,
                        "Posterior out of [0,1] for " + r.groundedRvOrAtomKey() + ": " + r.posterior());
                assertNotNull(r.computedAt());
                assertNotNull(r.inferenceRunId());
            }

            // The finding for alice should appear as a supporting finding key for alice's record
            boolean aliceRecordFound = records.stream()
                    .anyMatch(r -> r.groundedRvOrAtomKey().contains("alice")
                            && !r.supportingFindingKeys().isEmpty());
            assertTrue(aliceRecordFound, "Alice record should have a supporting finding");
        }

        @Test
        @DisplayName("EntailmentEngine.entailFromMebn() with soft finding")
        void entailFromMebnSoftFinding() {
            MTheory theory = MebnInferenceService.buildSimpleTheory(graph, "Person", "isActive");
            FindingStore findingStore = new FindingStore();
            // Soft finding: alice is more likely active (likelihood ratio 3:1)
            findingStore.assertFinding(Finding.soft("isActive", List.of("alice"),
                    new double[]{0.25, 0.75}, "sensor-1"));

            List<EntailmentRecord> records = EntailmentEngine.entailFromMebn(graph, findingStore, theory);
            assertNotNull(records);
            assertFalse(records.isEmpty());
        }
    }
}
