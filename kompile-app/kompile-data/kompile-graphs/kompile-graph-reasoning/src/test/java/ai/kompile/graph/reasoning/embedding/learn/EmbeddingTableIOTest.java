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
package ai.kompile.graph.reasoning.embedding.learn;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for {@link EmbeddingTableIO}.
 *
 * <h3>What is tested</h3>
 * <ol>
 *   <li>Build an {@link EmbeddingTable} → {@link EmbeddingTableIO#toJson} →
 *       {@link EmbeddingTableIO#fromJson} → assert entity vectors and id mapping identical.</li>
 *   <li>Handles special characters in entity ids (JSON escaping).</li>
 *   <li>Single-entity degenerate case.</li>
 *   <li>{@code asMap()} on the restored table contains all ids with correct vectors.</li>
 * </ol>
 */
class EmbeddingTableIOTest {

    private static final double EPSILON = 1e-15;

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — full round-trip: vectors and mapping identical
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_vectorsAndMappingIdentical() {
        List<String> ids = List.of("Alice", "Bob", "Carol", "CompanyX");
        int dim = 6;
        EmbeddingTable original = new EmbeddingTable(ids, dim, 42L);

        // Serialize
        String json = EmbeddingTableIO.toJson(original);
        assertNotNull(json, "toJson must not return null");
        assertFalse(json.isEmpty(), "toJson must not return an empty string");

        // Deserialize
        EmbeddingTable restored = EmbeddingTableIO.fromJson(json);
        assertNotNull(restored, "fromJson must not return null");

        // dim must match
        assertEquals(dim, restored.dim(), "dim must match after round-trip");

        // size must match
        assertEquals(original.size(), restored.size(), "size must match after round-trip");

        // Per-entity vector equality (exact — same double values via %.17g formatting)
        for (String id : ids) {
            double[] origVec = original.vector(id);
            double[] restVec = restored.vector(id);
            assertNotNull(origVec, "original.vector(" + id + ") must not be null");
            assertNotNull(restVec, "restored.vector(" + id + ") must not be null after round-trip");
            assertEquals(origVec.length, restVec.length,
                    "vector length must match for entity '" + id + "'");
            for (int d = 0; d < dim; d++) {
                assertEquals(origVec[d], restVec[d], EPSILON,
                        "vector[" + d + "] must be identical for entity '" + id
                        + "': original=" + origVec[d] + " restored=" + restVec[d]);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — asMap() round-trip
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_asMap_containsAllEntities() {
        List<String> ids = List.of("X", "Y", "Z");
        int dim = 4;
        EmbeddingTable original = new EmbeddingTable(ids, dim, 7L);

        EmbeddingTable restored = EmbeddingTableIO.fromJson(EmbeddingTableIO.toJson(original));

        Map<String, double[]> origMap = original.asMap();
        Map<String, double[]> restMap = restored.asMap();

        assertEquals(origMap.size(), restMap.size(), "asMap() must have same number of entries");
        for (String id : ids) {
            assertTrue(restMap.containsKey(id), "restored map must contain id '" + id + "'");
            double[] o = origMap.get(id);
            double[] r = restMap.get(id);
            for (int d = 0; d < dim; d++) {
                assertEquals(o[d], r[d], EPSILON,
                        "asMap vector[" + d + "] must match for '" + id + "'");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — single entity degenerate case
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_singleEntity() {
        List<String> ids = List.of("Solo");
        int dim = 3;
        EmbeddingTable original = new EmbeddingTable(ids, dim, 1L);

        EmbeddingTable restored = EmbeddingTableIO.fromJson(EmbeddingTableIO.toJson(original));

        assertEquals(1, restored.size());
        assertEquals(dim, restored.dim());
        double[] o = original.vector("Solo");
        double[] r = restored.vector("Solo");
        assertNotNull(r, "restored.vector('Solo') must not be null");
        assertArrayEquals(o, r, EPSILON, "Single-entity vector must survive round-trip");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 — special characters in entity ids (JSON escaping)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_specialCharactersInIds() {
        // ids that require JSON escaping: backslash and double-quote
        List<String> ids = List.of("Entity/A", "Entity \"B\"", "C\\D");
        int dim = 2;
        EmbeddingTable original = new EmbeddingTable(ids, dim, 99L);

        String json = EmbeddingTableIO.toJson(original);
        EmbeddingTable restored = EmbeddingTableIO.fromJson(json);

        for (String id : ids) {
            double[] r = restored.vector(id);
            assertNotNull(r, "restored must contain entity '" + id + "' after JSON escaping round-trip");
            double[] o = original.vector(id);
            assertArrayEquals(o, r, EPSILON,
                    "Vector for '" + id + "' must survive escaping round-trip");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5 — JSON structure sanity check
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void json_containsExpectedFields() {
        List<String> ids = List.of("A", "B");
        EmbeddingTable table = new EmbeddingTable(ids, 3, 0L);
        String json = EmbeddingTableIO.toJson(table);

        assertTrue(json.contains("\"dim\""),       "JSON must contain 'dim' field");
        assertTrue(json.contains("\"entityIds\""), "JSON must contain 'entityIds' field");
        assertTrue(json.contains("\"vectors\""),   "JSON must contain 'vectors' field");
        assertTrue(json.contains("\"A\""),         "JSON must contain entity id 'A'");
        assertTrue(json.contains("\"B\""),         "JSON must contain entity id 'B'");
    }
}
