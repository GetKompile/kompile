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
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.mebn.type.TypeRegistry;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates that EVERY aspect of a {@link UnifiedGraph} is enumerable: entities, relations,
 * opinions, vector layers, weight maps, bundled models/artifacts, meta, the instantiated ontology
 * (types), and the graph projected as a flat fact base.
 */
class UnifiedGraphEnumerationTest {

    /** One graph carrying every aspect. */
    private UnifiedGraph everything() {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity(new SimpleGraphEntity("alice", "Person", "Alice", 1.0, 1.0, Set.of(), null, null,
                Map.of("additionalTypes", List.of("Employee", "Manager"))));
        g.addEntity("acme", "Company", "Acme");
        g.addRelation("r1", "alice", "acme", "worksFor", 1.0);
        g.putEntityOpinion("alice", Opinion.fromBetaEvidence(8, 2));
        g.putRelationOpinion("r1", Opinion.fromSoftTruth(0.9));
        g.putEntityVector("kge", "alice", new double[] {1.0, 0.0});
        g.putWeightMap("pslWeights", Map.of("Precedes", 1.5, "Occurs", 0.8));
        g.putModel("typeRegistry", new TypeRegistry().declare("Person").declare("Company"));
        g.putArtifactText("notes", "hello");
        g.graphId("g1").factSheetId(42L).meta("owner", "alice-team");
        return g;
    }

    private Set<String> ids(List<GraphEntity> entities) {
        return entities.stream().map(GraphEntity::id).collect(Collectors.toSet());
    }

    @Test
    void everyAspectIsEnumerable() {
        UnifiedGraph g = everything();

        // Entities
        assertEquals(Set.of("alice", "acme"), g.entities().stream().map(GraphEntity::id).collect(Collectors.toSet()));

        // Relations
        assertEquals(1, g.relations().size());
        assertEquals("worksFor", g.relations().iterator().next().type());

        // Opinions (per entity, per relation, and as a merged OpinionStore)
        assertEquals(1, g.entityOpinions().size());
        assertEquals(1, g.relationOpinions().size());
        assertEquals(2, g.opinionStore().size());
        assertTrue(g.opinionStore().has("alice"));
        assertTrue(g.opinionStore().has("r1"));
        assertEquals(2, g.opinionStore().entries().size());

        // Vector layers (+ their rows)
        assertEquals(Set.of("kge"), g.vectorLayers().keySet());
        assertTrue(g.vectorLayer("kge").ids().contains("alice"));
        assertEquals(1, g.vectorLayer("kge").rows().size());

        // Weight maps
        assertEquals(Set.of("pslWeights"), g.weightMaps().keySet());
        assertEquals(Set.of("Precedes", "Occurs"), g.weightMap("pslWeights").keySet());

        // Models / artifacts
        assertTrue(g.artifacts().keySet().containsAll(Set.of("typeRegistry", "notes")));
        assertNotNull((TypeRegistry) g.model("typeRegistry"));
        assertEquals("hello", g.artifactText("notes"));

        // Meta
        assertTrue(g.meta().keySet().containsAll(Set.of("graphId", "factSheetId", "owner")));
        assertEquals("g1", g.graphId());
        assertEquals(42L, g.factSheetId());

        // Ontology / types (instantiated classes, incl. additionalTypes)
        assertEquals(Set.of("Person", "Employee", "Manager", "Company"), g.types());
        assertEquals(Set.of("alice"), ids(g.entitiesOfType("Employee")));
        assertEquals(Set.of("acme"), ids(g.entitiesOfType("Company")));

        // Facts (the graph as a flat fact base: relations + type memberships)
        Set<String> factKeys = g.facts().stream().map(Fact::atomKey).collect(Collectors.toSet());
        assertTrue(factKeys.contains("worksFor(alice, acme)"), "binary relation fact");
        assertTrue(factKeys.contains("Person(alice)"), "unary type fact");
        assertTrue(factKeys.contains("Employee(alice)"));
        assertTrue(factKeys.contains("Company(acme)"));
        assertEquals(1 + 4, g.facts().size(), "1 relation + (3 alice types + 1 acme type)");
    }

    @Test
    void enumerationIsIdenticalAfterRoundTrip() throws IOException {
        UnifiedGraph g = everything();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        g.save(bos);
        UnifiedGraph back = UnifiedGraph.load(new ByteArrayInputStream(bos.toByteArray()));

        assertEquals(g.types(), back.types());
        assertEquals(g.weightMaps().keySet(), back.weightMaps().keySet());
        assertEquals(g.artifacts().keySet(), back.artifacts().keySet());
        assertEquals(g.opinionStore().size(), back.opinionStore().size());
        assertEquals(
                g.facts().stream().map(Fact::atomKey).collect(Collectors.toSet()),
                back.facts().stream().map(Fact::atomKey).collect(Collectors.toSet()));
    }
}
