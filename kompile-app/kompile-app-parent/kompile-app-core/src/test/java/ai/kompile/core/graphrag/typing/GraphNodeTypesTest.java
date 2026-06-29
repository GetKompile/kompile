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
package ai.kompile.core.graphrag.typing;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphNodeTypesTest {

    @Test
    void categoryTakesPrecedenceOverType() {
        assertEquals("Vendor", GraphNodeTypes.resolveEntityType(
                Map.of("entity_category", "Vendor", "entity_type", "Organization"), "X"));
    }

    @Test
    void fallsThroughToTypeThenSubtype() {
        assertEquals("Organization", GraphNodeTypes.resolveEntityType(Map.of("entity_type", "Organization"), "X"));
        assertEquals("Sub", GraphNodeTypes.resolveEntityType(Map.of("entity_subtype", "Sub"), "X"));
    }

    @Test
    void camelCaseKeysAreRecognized() {
        assertEquals("Vendor", GraphNodeTypes.resolveEntityType(Map.of("entityCategory", "Vendor"), null));
    }

    @Test
    void usesFallbackWhenNoTypeKeyPresent() {
        assertEquals("ENTITY", GraphNodeTypes.resolveEntityType(Map.of("foo", "bar"), "ENTITY"));
    }

    @Test
    void resolvesReasoningTypeMembershipsSpecificToBroad() {
        assertEquals(List.of("Cabernet", "RedWine", "Wine"),
                GraphNodeTypes.resolveTypeMemberships(Map.of(
                        "entity_subtype", "Cabernet",
                        "entity_type", "RedWine",
                        "entity_category", "Wine")));
    }

    @Test
    void resolvesHierarchySpecificToBroad() {
        List<GraphNodeTypes.TypeHierarchyEdge> hierarchy = GraphNodeTypes.resolveTypeHierarchy(Map.of(
                "entity_subtype", "Cabernet",
                "entity_type", "RedWine",
                "entity_category", "Wine"));

        assertEquals(2, hierarchy.size());
        assertEquals("Cabernet", hierarchy.get(0).type());
        assertEquals("RedWine", hierarchy.get(0).parentType());
        assertEquals("RedWine", hierarchy.get(1).type());
        assertEquals("Wine", hierarchy.get(1).parentType());
    }

    @Test
    void hierarchyFallsBackFromSubtypeToCategoryWhenTypeMissing() {
        List<GraphNodeTypes.TypeHierarchyEdge> hierarchy = GraphNodeTypes.resolveTypeHierarchy(Map.of(
                "entity_subtype", "RedWine",
                "entity_category", "Wine"));

        assertEquals(1, hierarchy.size());
        assertEquals("RedWine", hierarchy.get(0).type());
        assertEquals("Wine", hierarchy.get(0).parentType());
    }

    @Test
    void nullAndBlankAndNonStringAreSafe() {
        assertEquals("ENTITY", GraphNodeTypes.resolveEntityType(null, "ENTITY"));
        assertEquals("ENTITY", GraphNodeTypes.resolveEntityType(Map.of("entity_type", "   "), "ENTITY"));
        Map<String, Object> nonString = new HashMap<>();
        nonString.put("entity_type", 42);
        assertEquals("ENTITY", GraphNodeTypes.resolveEntityType(nonString, "ENTITY"));
        assertNull(GraphNodeTypes.resolveEntityType(Map.of(), null));
    }
}
