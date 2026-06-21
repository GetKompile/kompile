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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link InferredFact}, {@link InferredFactStore}, and
 * {@link InMemoryInferredFactStore} (GAP 6).
 */
class InferredFactTest {

    // ─── InferredFact record ──────────────────────────────────────────────────────

    @Nested
    class InferredFactRecord {

        @Test
        void basicConstruction() {
            Instant now = Instant.now();
            InferredFact fact = new InferredFact(
                    "State(alice)", 0.8, 0.75,
                    List.of("Prior(alice)"), List.of("propagation rule"),
                    "run-001", 1L, now);
            assertEquals("State(alice)", fact.atomKey());
            assertEquals(0.8, fact.value(), 1e-9);
            assertEquals(0.75, fact.confidence(), 1e-9);
            assertEquals(1, fact.supportingFactKeys().size());
            assertEquals(1, fact.supportingRuleIds().size());
            assertEquals("run-001", fact.runId());
            assertEquals(1L, fact.version());
            assertEquals(now, fact.inferredAt());
        }

        @Test
        void valueOutOfRangeThrows() {
            Instant now = Instant.now();
            assertThrows(IllegalArgumentException.class,
                    () -> new InferredFact("k", 1.5, 0.5, List.of(), List.of(), "r", 1L, now),
                    "value > 1 should throw");
            assertThrows(IllegalArgumentException.class,
                    () -> new InferredFact("k", -0.1, 0.5, List.of(), List.of(), "r", 1L, now),
                    "value < 0 should throw");
        }

        @Test
        void confidenceOutOfRangeThrows() {
            Instant now = Instant.now();
            assertThrows(IllegalArgumentException.class,
                    () -> new InferredFact("k", 0.5, 1.1, List.of(), List.of(), "r", 1L, now));
        }

        @Test
        void nullRunIdThrows() {
            Instant now = Instant.now();
            assertThrows(NullPointerException.class,
                    () -> new InferredFact("k", 0.5, 0.5, List.of(), List.of(), null, 1L, now));
        }

        @Test
        void factoryOfMethod() {
            InferredFact fact = InferredFact.of("State(bob)", 0.9,
                    List.of("State(alice)"), List.of("prop rule"), "run-2", 3L);
            assertEquals("State(bob)", fact.atomKey());
            assertEquals(0.9, fact.value(), 1e-9);
            assertEquals(3L, fact.version());
            assertEquals("run-2", fact.runId());
        }

        @Test
        void nullSupportingListsDefaultToEmpty() {
            Instant now = Instant.now();
            InferredFact fact = new InferredFact("k", 0.5, 0.5, null, null, "r", 1L, now);
            assertNotNull(fact.supportingFactKeys());
            assertNotNull(fact.supportingRuleIds());
            assertTrue(fact.supportingFactKeys().isEmpty());
            assertTrue(fact.supportingRuleIds().isEmpty());
        }

        @Test
        void fromEntailmentRecord() {
            EntailmentRecord rec = new EntailmentRecord(
                    "State(alice)", 0.82,
                    List.of("Prior(alice)", "Link(seed, alice)"),
                    List.of("propagation rule"),
                    Instant.now(), "run-X");
            InferredFact fact = InferredFact.fromEntailment(rec, 7L);
            assertEquals("State(alice)", fact.atomKey());
            assertEquals(0.82, fact.value(), 1e-9);
            assertEquals(7L, fact.version());
            assertEquals("run-X", fact.runId());
            assertEquals(2, fact.supportingFactKeys().size());
        }
    }

    // ─── JSON round-trip ─────────────────────────────────────────────────────────

    @Nested
    class JsonRoundTrip {

        private InferredFact sampleFact() {
            return InferredFact.of("State(alice)", 0.82,
                    List.of("Prior(alice)", "Link(seed, alice)"),
                    List.of("propagation rule"),
                    "run-001", 1L);
        }

        @Test
        void toJsonProducesNonEmptyString() {
            InferredFact fact = sampleFact();
            String json = fact.toJson();
            assertNotNull(json);
            assertFalse(json.isBlank());
            assertTrue(json.startsWith("{"));
            assertTrue(json.endsWith("}"));
        }

        @Test
        void fromJsonRoundTrip() {
            InferredFact original = sampleFact();
            String json = original.toJson();
            InferredFact restored = InferredFact.fromJson(json);
            assertEquals(original.atomKey(), restored.atomKey());
            assertEquals(original.value(), restored.value(), 1e-9);
            assertEquals(original.confidence(), restored.confidence(), 1e-9);
            assertEquals(original.runId(), restored.runId());
            assertEquals(original.version(), restored.version());
            assertEquals(original.supportingFactKeys(), restored.supportingFactKeys());
            assertEquals(original.supportingRuleIds(), restored.supportingRuleIds());
            assertEquals(original.inferredAt().toEpochMilli(), restored.inferredAt().toEpochMilli());
        }

        @Test
        void fromJsonWithEmptyLists() {
            InferredFact original = InferredFact.of("X(a)", 0.5, List.of(), List.of(), "r", 2L);
            String json = original.toJson();
            InferredFact restored = InferredFact.fromJson(json);
            assertTrue(restored.supportingFactKeys().isEmpty());
            assertTrue(restored.supportingRuleIds().isEmpty());
        }

        @Test
        void fromJsonNullOrBlankThrows() {
            assertThrows(IllegalArgumentException.class, () -> InferredFact.fromJson(null));
            assertThrows(IllegalArgumentException.class, () -> InferredFact.fromJson("  "));
        }

        @Test
        void fromJsonMalformedThrows() {
            assertThrows(IllegalArgumentException.class, () -> InferredFact.fromJson("not json"));
        }

        @Test
        void toJsonContainsAtomKey() {
            InferredFact fact = sampleFact();
            String json = fact.toJson();
            assertTrue(json.contains("atomKey"), "JSON should contain 'atomKey' field");
            assertTrue(json.contains("State(alice)"), "JSON should contain the atom key value");
        }

        @Test
        void specialCharsInAtomKeyRoundTrip() {
            InferredFact original = InferredFact.of("Knows(alice, \"bob\")", 0.7,
                    List.of(), List.of(), "r", 1L);
            String json = original.toJson();
            InferredFact restored = InferredFact.fromJson(json);
            assertEquals(original.atomKey(), restored.atomKey());
        }
    }

    // ─── InMemoryInferredFactStore ────────────────────────────────────────────────

    @Nested
    class InMemoryStoreTests {

        @Test
        void storeAndRetrieveLatest() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            InferredFact f = InferredFact.of("State(alice)", 0.8, List.of(), List.of(), "r1", 1L);
            store.store(f);
            Optional<InferredFact> latest = store.latest("State(alice)");
            assertTrue(latest.isPresent());
            assertEquals(0.8, latest.get().value(), 1e-9);
        }

        @Test
        void latestForUnknownKeyIsEmpty() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            assertFalse(store.latest("State(nobody)").isPresent());
        }

        @Test
        void versionIncreasesMonotonically() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            // Store two facts for the same key with version 0 (auto-assign)
            InferredFact f1 = InferredFact.of("State(alice)", 0.7, List.of(), List.of(), "r1", 0L);
            InferredFact f2 = InferredFact.of("State(alice)", 0.8, List.of(), List.of(), "r2", 0L);
            store.store(f1);
            store.store(f2);
            List<InferredFact> history = store.history("State(alice)");
            assertEquals(2, history.size());
            assertTrue(history.get(1).version() > history.get(0).version(),
                    "Later version must be strictly greater than earlier version");
        }

        @Test
        void historyOrderedByVersion() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            for (int i = 1; i <= 5; i++) {
                store.store(InferredFact.of("X(a)", 0.1 * i, List.of(), List.of(), "r", (long) i));
            }
            List<InferredFact> history = store.history("X(a)");
            assertEquals(5, history.size());
            for (int i = 1; i < history.size(); i++) {
                assertTrue(history.get(i).version() > history.get(i - 1).version());
            }
        }

        @Test
        void latestReturnsMostRecentVersion() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            store.store(InferredFact.of("X(a)", 0.3, List.of(), List.of(), "r", 0L));
            store.store(InferredFact.of("X(a)", 0.9, List.of(), List.of(), "r", 0L));
            Optional<InferredFact> latest = store.latest("X(a)");
            assertTrue(latest.isPresent());
            assertEquals(0.9, latest.get().value(), 1e-9);
        }

        @Test
        void byRunReturnsCorrectFacts() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            store.store(InferredFact.of("A(x)", 0.5, List.of(), List.of(), "run-1", 0L));
            store.store(InferredFact.of("B(y)", 0.6, List.of(), List.of(), "run-1", 0L));
            store.store(InferredFact.of("C(z)", 0.7, List.of(), List.of(), "run-2", 0L));
            var run1 = store.byRun("run-1");
            assertEquals(2, run1.size());
            var run2 = store.byRun("run-2");
            assertEquals(1, run2.size());
            assertEquals(0, store.byRun("run-nonexistent").size());
        }

        @Test
        void allLatestReturnsOnePerKey() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            store.store(InferredFact.of("A(x)", 0.3, List.of(), List.of(), "r", 0L));
            store.store(InferredFact.of("A(x)", 0.9, List.of(), List.of(), "r", 0L));
            store.store(InferredFact.of("B(y)", 0.5, List.of(), List.of(), "r", 0L));
            var all = store.allLatest();
            assertEquals(2, all.size());
        }

        @Test
        void purgeRemovesKey() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            store.store(InferredFact.of("X(a)", 0.5, List.of(), List.of(), "r", 0L));
            assertEquals(1, store.size());
            store.purge("X(a)");
            assertEquals(0, store.size());
            assertTrue(store.isEmpty());
            assertFalse(store.latest("X(a)").isPresent());
        }

        @Test
        void sizeAndIsEmpty() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            assertTrue(store.isEmpty());
            store.store(InferredFact.of("A(x)", 0.5, List.of(), List.of(), "r", 0L));
            assertEquals(1, store.size());
            assertFalse(store.isEmpty());
        }

        @Test
        void storeNullThrows() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            assertThrows(IllegalArgumentException.class, () -> store.store(null));
        }
    }

    // ─── JSONL round-trip ─────────────────────────────────────────────────────────

    @Nested
    class JsonlRoundTrip {

        @Test
        void toJsonlAndFromJsonl() {
            InMemoryInferredFactStore original = new InMemoryInferredFactStore();
            original.store(InferredFact.of("State(alice)", 0.8, List.of("Prior(alice)"), List.of("rule1"), "r1", 0L));
            original.store(InferredFact.of("State(bob)", 0.6, List.of("Link(alice,bob)"), List.of("rule2"), "r1", 0L));

            String jsonl = original.toJsonl();
            assertFalse(jsonl.isBlank());
            assertEquals(2, jsonl.trim().split("\n").length);

            InMemoryInferredFactStore restored = new InMemoryInferredFactStore();
            restored.fromJsonl(jsonl);
            assertEquals(2, restored.size());
            assertTrue(restored.latest("State(alice)").isPresent());
            assertEquals(0.8, restored.latest("State(alice)").get().value(), 1e-9);
            assertTrue(restored.latest("State(bob)").isPresent());
        }

        @Test
        void emptyStoreProducesEmptyJsonl() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            String jsonl = store.toJsonl();
            assertTrue(jsonl.isBlank());
        }

        @Test
        void fromJsonlNullOrBlankIsNoop() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            assertDoesNotThrow(() -> store.fromJsonl(null));
            assertDoesNotThrow(() -> store.fromJsonl("  "));
            assertTrue(store.isEmpty());
        }
    }

    // ─── Version stamping ─────────────────────────────────────────────────────────

    @Nested
    class VersionStamping {

        @Test
        void versionIsStampedEvenWhenCallerProvidesZero() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            store.store(InferredFact.of("X(a)", 0.5, List.of(), List.of(), "r", 0L));
            Optional<InferredFact> fact = store.latest("X(a)");
            assertTrue(fact.isPresent());
            assertTrue(fact.get().version() >= 1L, "Auto-assigned version should be >= 1");
        }

        @Test
        void versionNeverDecreases() {
            InMemoryInferredFactStore store = new InMemoryInferredFactStore();
            long lastVersion = 0L;
            for (int i = 0; i < 10; i++) {
                store.store(InferredFact.of("X(a)", 0.1 * i, List.of(), List.of(), "r", 0L));
                long v = store.latest("X(a)").map(InferredFact::version).orElse(-1L);
                assertTrue(v > lastVersion, "Each new version must be strictly greater than the last");
                lastVersion = v;
            }
        }
    }
}
