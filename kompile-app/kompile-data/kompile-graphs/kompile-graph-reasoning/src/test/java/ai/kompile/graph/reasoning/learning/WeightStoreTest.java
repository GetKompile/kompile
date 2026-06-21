/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.learning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WeightStore}, covering both {@link InMemoryWeightStore} and
 * {@link FileWeightStore}.
 */
class WeightStoreTest {

    static final Map<String, Double> WEIGHTS_V1 = Map.of("rule-A", 1.5, "rule-B", 0.3);
    static final Map<String, Double> WEIGHTS_V2 = Map.of("rule-A", 2.0, "rule-B", 0.7);

    // ─── Shared contract tests ────────────────────────────────────────────────────

    /** Run all store-contract assertions on any {@link WeightStore} implementation. */
    static void assertStoreContract(WeightStore store) {

        // 1. Monotonic versioning per program
        int v1 = store.save("prog-1", WEIGHTS_V1);
        assertEquals(1, v1, "First save should return version 1");

        int v2 = store.save("prog-1", WEIGHTS_V2);
        assertEquals(2, v2, "Second save should return version 2");

        // 2. latest() reflects the most recent version
        Optional<Map<String, Double>> latest = store.latest("prog-1");
        assertTrue(latest.isPresent(), "latest() must be present after saves");
        assertEquals(2.0, latest.get().get("rule-A"), 1e-9, "latest() must return v2 weights");

        // 3. latestVersion()
        assertEquals(2, store.latestVersion("prog-1"));
        assertEquals(0, store.latestVersion("nonexistent"), "Unknown programId must return 0");

        // 4. get(version) retrieval
        Optional<Map<String, Double>> gotV1 = store.get("prog-1", 1);
        assertTrue(gotV1.isPresent(), "get(1) must be present");
        assertEquals(1.5, gotV1.get().get("rule-A"), 1e-9, "get(1) must return v1 weights");

        Optional<Map<String, Double>> gotV2 = store.get("prog-1", 2);
        assertTrue(gotV2.isPresent(), "get(2) must be present");
        assertEquals(2.0, gotV2.get().get("rule-A"), 1e-9, "get(2) must return v2 weights");

        assertTrue(store.get("prog-1", 99).isEmpty(), "get(99) must be empty");

        // 5. versions() returns ascending list
        List<Integer> vers = store.versions("prog-1");
        assertEquals(List.of(1, 2), vers, "versions() must be [1, 2]");
        assertTrue(store.versions("nonexistent").isEmpty());

        // 6. Isolation across programIds
        store.save("prog-2", Map.of("rule-X", 0.5));
        assertEquals(1, store.latestVersion("prog-2"), "prog-2 starts at version 1");
        assertEquals(2, store.latestVersion("prog-1"), "prog-1 must still be at version 2");
        assertTrue(store.programIds().contains("prog-1"));
        assertTrue(store.programIds().contains("prog-2"));

        // 7. Empty cases before any data
        WeightStore empty = new InMemoryWeightStore();
        assertTrue(empty.latest("x").isEmpty());
        assertEquals(0, empty.latestVersion("x"));
        assertTrue(empty.versions("x").isEmpty());
        assertTrue(empty.programIds().isEmpty());
    }

    // ─── InMemoryWeightStore ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("InMemoryWeightStore")
    class InMemoryTests {

        @Test
        @DisplayName("Full store contract: monotonic versioning, latest, get, versions, isolation")
        void storeContract() {
            assertStoreContract(new InMemoryWeightStore());
        }

        @Test
        @DisplayName("Defensive copies: mutating the returned map does not affect stored data")
        void defensiveCopy() {
            InMemoryWeightStore store = new InMemoryWeightStore();
            store.save("prog", Map.of("w", 1.0));
            Map<String, Double> got = store.latest("prog").orElseThrow();
            // Mutate the returned map — the store must remain unchanged.
            try {
                got.put("injected", 999.0);
            } catch (UnsupportedOperationException ignored) {
                // Some Map.of() wrappers are unmodifiable — that's fine too.
                return;
            }
            // If the put succeeded, the store's copy must be unaffected.
            Map<String, Double> again = store.latest("prog").orElseThrow();
            assertFalse(again.containsKey("injected"), "Stored data must not be mutated via returned map");
        }
    }

    // ─── FileWeightStore ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FileWeightStore")
    class FileTests {

        @Test
        @DisplayName("Full store contract: monotonic versioning, latest, get, versions, isolation")
        void storeContract(@TempDir Path tmp) {
            assertStoreContract(new FileWeightStore(tmp));
        }

        @Test
        @DisplayName("Round-trip: data written by one instance is readable by a fresh instance over the same dir")
        void roundTrip(@TempDir Path tmp) {
            // Write with first instance
            FileWeightStore storeA = new FileWeightStore(tmp);
            storeA.save("round-trip", WEIGHTS_V1);
            storeA.save("round-trip", WEIGHTS_V2);

            // Read with a fresh instance (simulates restart)
            FileWeightStore storeB = new FileWeightStore(tmp);
            assertEquals(2, storeB.latestVersion("round-trip"),
                    "Fresh instance must see both versions from disk");

            Optional<Map<String, Double>> latest = storeB.latest("round-trip");
            assertTrue(latest.isPresent(), "latest() must be present after restart");
            assertEquals(2.0, latest.get().get("rule-A"), 1e-6,
                    "latest() must return v2 rule-A weight after restart");

            Optional<Map<String, Double>> v1 = storeB.get("round-trip", 1);
            assertTrue(v1.isPresent(), "get(1) must be present after restart");
            assertEquals(1.5, v1.get().get("rule-A"), 1e-6,
                    "get(1) rule-A weight must survive restart");
        }

        @Test
        @DisplayName("Continuing versioning after restart: new saves get version 3+")
        void continuesAfterRestart(@TempDir Path tmp) {
            FileWeightStore storeA = new FileWeightStore(tmp);
            storeA.save("prog", WEIGHTS_V1);
            storeA.save("prog", WEIGHTS_V2);

            FileWeightStore storeB = new FileWeightStore(tmp);
            int v3 = storeB.save("prog", Map.of("rule-A", 3.0));
            assertEquals(3, v3, "After restart, next version must be 3");
            assertEquals(3, storeB.latestVersion("prog"));
            assertEquals(List.of(1, 2, 3), storeB.versions("prog"));
        }
    }
}
