/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.service;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DispatchResult} — factory methods, null safety,
 * hasDiscoveredNodeIds(), and output preservation.
 */
class DispatchResultTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Constructor: single-arg (outputs only)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class SingleArgConstructor {

        @Test
        void outputsPreserved() {
            Map<String, Object> outputs = Map.of("key", "value");
            DispatchResult result = new DispatchResult(outputs);
            assertEquals("value", result.getOutputs().get("key"));
        }

        @Test
        void nullOutputsBecomesEmptyMap() {
            DispatchResult result = new DispatchResult(null);
            assertNotNull(result.getOutputs());
            assertTrue(result.getOutputs().isEmpty());
        }

        @Test
        void discoveredNodeIdsEmptyByDefault() {
            DispatchResult result = new DispatchResult(Map.of("k", "v"));
            assertNotNull(result.getDiscoveredGraphNodeIds());
            assertTrue(result.getDiscoveredGraphNodeIds().isEmpty());
            assertFalse(result.hasDiscoveredNodeIds());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Constructor: two-arg (outputs + graph node IDs)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class TwoArgConstructor {

        @Test
        void bothFieldsPreserved() {
            Map<String, Object> outputs = Map.of("status", "ok");
            List<String> nodeIds = List.of("node-1", "node-2");
            DispatchResult result = new DispatchResult(outputs, nodeIds);

            assertEquals("ok", result.getOutputs().get("status"));
            assertEquals(2, result.getDiscoveredGraphNodeIds().size());
            assertTrue(result.getDiscoveredGraphNodeIds().contains("node-1"));
            assertTrue(result.getDiscoveredGraphNodeIds().contains("node-2"));
        }

        @Test
        void nullOutputsBecomesEmptyMap() {
            DispatchResult result = new DispatchResult(null, List.of("n1"));
            assertNotNull(result.getOutputs());
            assertTrue(result.getOutputs().isEmpty());
        }

        @Test
        void nullNodeIdsBecomesEmptyList() {
            DispatchResult result = new DispatchResult(Map.of("k", "v"), null);
            assertNotNull(result.getDiscoveredGraphNodeIds());
            assertTrue(result.getDiscoveredGraphNodeIds().isEmpty());
        }

        @Test
        void bothNullProducesEmptyCollections() {
            DispatchResult result = new DispatchResult(null, null);
            assertNotNull(result.getOutputs());
            assertNotNull(result.getDiscoveredGraphNodeIds());
            assertTrue(result.getOutputs().isEmpty());
            assertTrue(result.getDiscoveredGraphNodeIds().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Factory methods
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class FactoryMethods {

        @Test
        void ofOutputsOnly() {
            DispatchResult result = DispatchResult.of(Map.of("result", 42));
            assertEquals(42, result.getOutputs().get("result"));
            assertFalse(result.hasDiscoveredNodeIds());
        }

        @Test
        void ofOutputsOnlyNullSafe() {
            DispatchResult result = DispatchResult.of(null);
            assertNotNull(result.getOutputs());
            assertTrue(result.getOutputs().isEmpty());
        }

        @Test
        void ofWithNodeIds() {
            DispatchResult result = DispatchResult.of(
                    Map.of("v", 1),
                    List.of("graph-node-abc")
            );
            assertEquals(1, result.getOutputs().get("v"));
            assertEquals(1, result.getDiscoveredGraphNodeIds().size());
            assertEquals("graph-node-abc", result.getDiscoveredGraphNodeIds().get(0));
            assertTrue(result.hasDiscoveredNodeIds());
        }

        @Test
        void ofWithNodeIdsNullSafe() {
            DispatchResult result = DispatchResult.of(null, null);
            assertNotNull(result.getOutputs());
            assertNotNull(result.getDiscoveredGraphNodeIds());
        }

        @Test
        void ofWithEmptyNodeIds() {
            DispatchResult result = DispatchResult.of(Map.of(), List.of());
            assertFalse(result.hasDiscoveredNodeIds());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // hasDiscoveredNodeIds
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class HasDiscoveredNodeIds {

        @Test
        void trueWhenNonEmpty() {
            DispatchResult result = DispatchResult.of(Map.of(), List.of("id-1"));
            assertTrue(result.hasDiscoveredNodeIds());
        }

        @Test
        void falseWhenEmpty() {
            DispatchResult result = DispatchResult.of(Map.of(), List.of());
            assertFalse(result.hasDiscoveredNodeIds());
        }

        @Test
        void falseWhenConstructedWithNullIds() {
            DispatchResult result = new DispatchResult(Map.of(), null);
            assertFalse(result.hasDiscoveredNodeIds());
        }

        @Test
        void trueWithMultipleIds() {
            DispatchResult result = DispatchResult.of(Map.of(),
                    List.of("n1", "n2", "n3"));
            assertTrue(result.hasDiscoveredNodeIds());
            assertEquals(3, result.getDiscoveredGraphNodeIds().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Output mutability
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class OutputMutability {

        @Test
        void outputsAreMutableWhenConstructedWithNull() {
            DispatchResult result = new DispatchResult(null);
            result.getOutputs().put("added", "after");
            assertEquals("after", result.getOutputs().get("added"));
        }

        @Test
        void discoveredNodeIdsAreMutableWhenConstructedWithNull() {
            DispatchResult result = new DispatchResult(null, null);
            result.getDiscoveredGraphNodeIds().add("new-id");
            assertTrue(result.hasDiscoveredNodeIds());
            assertEquals("new-id", result.getDiscoveredGraphNodeIds().get(0));
        }
    }
}
