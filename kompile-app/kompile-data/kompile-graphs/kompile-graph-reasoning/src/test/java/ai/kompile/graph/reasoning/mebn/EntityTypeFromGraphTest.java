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
package ai.kompile.graph.reasoning.mebn;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link EntityType#fromGraph(ReasoningGraph, String)} and
 * {@link EntityType#fromGraph(ReasoningGraph, String, String)} factory methods.
 *
 * <p>These factories replace the inline new + loop pattern that was duplicated across
 * {@code MebnInferenceService.buildSimpleTheory}, {@code buildCausalTheory}, and
 * {@code buildPropagationTheory}.</p>
 */
class EntityTypeFromGraphTest {

    /**
     * Graph with three Person entities and one Organization entity.
     *
     * <pre>
     *   alice   — type: Person
     *   bob     — type: Person
     *   carol   — type: person   (lower-case — tests case-insensitive matching)
     *   acme    — type: Organization
     * </pre>
     */
    private ReasoningGraph graph;

    @BeforeEach
    void buildGraph() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").build());
        g.addEntity(GraphEntity.builder("bob").type("Person").label("Bob").build());
        g.addEntity(GraphEntity.builder("carol").type("person").label("Carol").build()); // lower-case
        g.addEntity(GraphEntity.builder("acme").type("Organization").label("Acme Corp").build());
        graph = g;
    }

    @Test
    @DisplayName("fromGraph(graph, typeName) registers exactly the entities of the requested type")
    void fromGraph_registersExactMatchingEntities() {
        EntityType personType = EntityType.fromGraph(graph, "Person");

        Set<String> ids = personType.getEntityIds();
        assertEquals(3, ids.size(),
                "Should register alice, bob, and carol (case-insensitive match); got: " + ids);
        assertTrue(ids.contains("alice"), "alice must be registered");
        assertTrue(ids.contains("bob"),   "bob must be registered");
        assertTrue(ids.contains("carol"), "carol must be registered (lower-case type match)");
    }

    @Test
    @DisplayName("fromGraph does NOT register entities of other types")
    void fromGraph_excludesOtherTypes() {
        EntityType personType = EntityType.fromGraph(graph, "Person");

        assertFalse(personType.getEntityIds().contains("acme"),
                "acme (Organization) must not be registered under Person");
    }

    @Test
    @DisplayName("fromGraph matching is case-insensitive")
    void fromGraph_caseInsensitiveMatch() {
        EntityType upper = EntityType.fromGraph(graph, "PERSON");
        EntityType lower = EntityType.fromGraph(graph, "person");

        assertEquals(upper.getEntityIds(), lower.getEntityIds(),
                "PERSON and person should resolve to the same entity set");
        assertEquals(3, upper.getEntityIds().size());
    }

    @Test
    @DisplayName("fromGraph with an explicit description stores the description")
    void fromGraph_withDescription_storesDescription() {
        EntityType personType = EntityType.fromGraph(graph, "Person", "All human actors");

        assertEquals("All human actors", personType.getDescription());
        assertEquals("Person", personType.getTypeName());
    }

    @Test
    @DisplayName("fromGraph for a type with no matching entities returns an empty EntityType")
    void fromGraph_noMatchingEntities_returnsEmpty() {
        EntityType unknownType = EntityType.fromGraph(graph, "Vehicle");

        assertTrue(unknownType.getEntityIds().isEmpty(),
                "No entities should be registered for an absent type");
        assertEquals("Vehicle", unknownType.getTypeName());
    }

    @Test
    @DisplayName("fromGraph one-arg overload derives description from type name")
    void fromGraph_oneArg_defaultDescription() {
        EntityType orgType = EntityType.fromGraph(graph, "Organization");

        assertEquals("Organization entities", orgType.getDescription(),
                "one-arg overload should derive description as '<typeName> entities'");
        assertEquals(Set.of("acme"), orgType.getEntityIds());
    }
}
