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

package ai.kompile.core.graphrag.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Graph RAG model classes (Entity, Relationship, Graph).
 */
@DisplayName("Graph Model Tests")
public class GraphModelTest {

    // ========================================
    // Entity Tests
    // ========================================

    @Nested
    @DisplayName("Entity Tests")
    class EntityTests {

        @Test
        @DisplayName("Entity should store all properties")
        void entity_shouldStoreAllProperties() {
            Entity entity = new Entity();
            entity.setId("e1");
            entity.setTitle("TechCorp");
            entity.setType("ORGANIZATION");
            entity.setDescription("A technology company");
            entity.setConfidence(0.95);

            List<String> textUnits = Arrays.asList("chunk1", "chunk2");
            entity.setTextUnits(textUnits);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("industry", "Technology");
            metadata.put("size", 1000);
            entity.setMetadata(metadata);

            assertEquals("e1", entity.getId());
            assertEquals("TechCorp", entity.getTitle());
            assertEquals("ORGANIZATION", entity.getType());
            assertEquals("A technology company", entity.getDescription());
            assertEquals(0.95, entity.getConfidence());
            assertEquals(textUnits, entity.getTextUnits());
            assertEquals(metadata, entity.getMetadata());
        }

        @Test
        @DisplayName("Entity should allow null values")
        void entity_shouldAllowNullValues() {
            Entity entity = new Entity();
            entity.setId(null);
            entity.setTitle(null);
            entity.setType(null);
            entity.setDescription(null);
            entity.setConfidence(null);
            entity.setTextUnits(null);
            entity.setMetadata(null);

            assertNull(entity.getId());
            assertNull(entity.getTitle());
            assertNull(entity.getType());
            assertNull(entity.getDescription());
            assertNull(entity.getConfidence());
            assertNull(entity.getTextUnits());
            assertNull(entity.getMetadata());
        }

        @Test
        @DisplayName("Entity confidence should be between 0 and 1")
        void entity_confidenceShouldBeBoundaryValid() {
            Entity entity = new Entity();

            entity.setConfidence(0.0);
            assertEquals(0.0, entity.getConfidence());

            entity.setConfidence(1.0);
            assertEquals(1.0, entity.getConfidence());

            entity.setConfidence(0.5);
            assertEquals(0.5, entity.getConfidence());
        }

        @Test
        @DisplayName("Entity types should be stored correctly")
        void entity_typesShouldBeStoredCorrectly() {
            String[] types = {"PERSON", "ORGANIZATION", "LOCATION", "CONCEPT", "EVENT", "CUSTOM_TYPE"};

            for (String type : types) {
                Entity entity = new Entity();
                entity.setType(type);
                assertEquals(type, entity.getType());
            }
        }

        @Test
        @DisplayName("Entities with same values should be equal")
        void entities_withSameValuesShouldBeEqual() {
            Entity entity1 = new Entity();
            entity1.setId("e1");
            entity1.setTitle("Test");
            entity1.setType("PERSON");

            Entity entity2 = new Entity();
            entity2.setId("e1");
            entity2.setTitle("Test");
            entity2.setType("PERSON");

            assertEquals(entity1, entity2);
            assertEquals(entity1.hashCode(), entity2.hashCode());
        }
    }

    // ========================================
    // Relationship Tests
    // ========================================

    @Nested
    @DisplayName("Relationship Tests")
    class RelationshipTests {

        @Test
        @DisplayName("Relationship should store all properties")
        void relationship_shouldStoreAllProperties() {
            Relationship rel = new Relationship();
            rel.setSource("e1");
            rel.setTarget("e2");
            rel.setType("WORKS_AT");
            rel.setDescription("Person works at organization");
            rel.setWeight(0.9);
            rel.setConfidence(0.85);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("since", "2020");
            rel.setMetadata(metadata);

            assertEquals("e1", rel.getSource());
            assertEquals("e2", rel.getTarget());
            assertEquals("WORKS_AT", rel.getType());
            assertEquals("Person works at organization", rel.getDescription());
            assertEquals(0.9, rel.getWeight());
            assertEquals(0.85, rel.getConfidence());
            assertEquals(metadata, rel.getMetadata());
        }

        @Test
        @DisplayName("Relationship should allow null values")
        void relationship_shouldAllowNullValues() {
            Relationship rel = new Relationship();
            rel.setSource(null);
            rel.setTarget(null);
            rel.setType(null);
            rel.setDescription(null);
            rel.setWeight(null);
            rel.setConfidence(null);
            rel.setMetadata(null);

            assertNull(rel.getSource());
            assertNull(rel.getTarget());
            assertNull(rel.getType());
            assertNull(rel.getDescription());
            assertNull(rel.getWeight());
            assertNull(rel.getConfidence());
            assertNull(rel.getMetadata());
        }

        @Test
        @DisplayName("Relationship types should be stored correctly")
        void relationship_typesShouldBeStoredCorrectly() {
            String[] types = {
                    "WORKS_AT", "LOCATED_IN", "RELATED_TO", "LEADS",
                    "CONTRIBUTES_TO", "OWNS", "MANAGES", "CUSTOM_RELATION"
            };

            for (String type : types) {
                Relationship rel = new Relationship();
                rel.setType(type);
                assertEquals(type, rel.getType());
            }
        }

        @Test
        @DisplayName("Relationship weight should be between 0 and 1")
        void relationship_weightShouldBeBoundaryValid() {
            Relationship rel = new Relationship();

            rel.setWeight(0.0);
            assertEquals(0.0, rel.getWeight());

            rel.setWeight(1.0);
            assertEquals(1.0, rel.getWeight());

            rel.setWeight(0.5);
            assertEquals(0.5, rel.getWeight());
        }

        @Test
        @DisplayName("Relationships with same values should be equal")
        void relationships_withSameValuesShouldBeEqual() {
            Relationship rel1 = new Relationship();
            rel1.setSource("e1");
            rel1.setTarget("e2");
            rel1.setType("WORKS_AT");

            Relationship rel2 = new Relationship();
            rel2.setSource("e1");
            rel2.setTarget("e2");
            rel2.setType("WORKS_AT");

            assertEquals(rel1, rel2);
            assertEquals(rel1.hashCode(), rel2.hashCode());
        }
    }

    // ========================================
    // Graph Tests
    // ========================================

    @Nested
    @DisplayName("Graph Tests")
    class GraphTests {

        @Test
        @DisplayName("Graph should store entities and relationships")
        void graph_shouldStoreEntitiesAndRelationships() {
            Graph graph = new Graph();

            List<Entity> entities = createTestEntities();
            List<Relationship> relationships = createTestRelationships();

            graph.setEntities(entities);
            graph.setRelationships(relationships);
            graph.setCommunities(new ArrayList<>());

            assertEquals(entities, graph.getEntities());
            assertEquals(relationships, graph.getRelationships());
            assertNotNull(graph.getCommunities());
        }

        @Test
        @DisplayName("Graph should allow empty lists")
        void graph_shouldAllowEmptyLists() {
            Graph graph = new Graph();
            graph.setEntities(new ArrayList<>());
            graph.setRelationships(new ArrayList<>());
            graph.setCommunities(new ArrayList<>());

            assertTrue(graph.getEntities().isEmpty());
            assertTrue(graph.getRelationships().isEmpty());
            assertTrue(graph.getCommunities().isEmpty());
        }

        @Test
        @DisplayName("Graph should allow null lists")
        void graph_shouldAllowNullLists() {
            Graph graph = new Graph();
            graph.setEntities(null);
            graph.setRelationships(null);
            graph.setCommunities(null);

            assertNull(graph.getEntities());
            assertNull(graph.getRelationships());
            assertNull(graph.getCommunities());
        }

        @Test
        @DisplayName("Graph should handle large number of entities")
        void graph_shouldHandleLargeNumberOfEntities() {
            Graph graph = new Graph();
            List<Entity> entities = new ArrayList<>();

            for (int i = 0; i < 10000; i++) {
                Entity entity = new Entity();
                entity.setId("e" + i);
                entity.setTitle("Entity " + i);
                entities.add(entity);
            }

            graph.setEntities(entities);

            assertEquals(10000, graph.getEntities().size());
        }

        @Test
        @DisplayName("Graph should handle circular relationships")
        void graph_shouldHandleCircularRelationships() {
            Graph graph = new Graph();

            List<Entity> entities = new ArrayList<>();
            Entity e1 = new Entity();
            e1.setId("e1");
            e1.setTitle("Entity 1");
            entities.add(e1);

            Entity e2 = new Entity();
            e2.setId("e2");
            e2.setTitle("Entity 2");
            entities.add(e2);

            graph.setEntities(entities);

            // Create circular relationships
            List<Relationship> relationships = new ArrayList<>();
            Relationship r1 = new Relationship();
            r1.setSource("e1");
            r1.setTarget("e2");
            r1.setType("RELATES_TO");
            relationships.add(r1);

            Relationship r2 = new Relationship();
            r2.setSource("e2");
            r2.setTarget("e1");
            r2.setType("RELATES_TO");
            relationships.add(r2);

            graph.setRelationships(relationships);

            assertEquals(2, graph.getRelationships().size());
        }

        @Test
        @DisplayName("Graph should handle self-referential relationships")
        void graph_shouldHandleSelfReferentialRelationships() {
            Graph graph = new Graph();

            List<Entity> entities = new ArrayList<>();
            Entity e1 = new Entity();
            e1.setId("e1");
            e1.setTitle("Entity 1");
            entities.add(e1);

            graph.setEntities(entities);

            // Self-referential relationship
            List<Relationship> relationships = new ArrayList<>();
            Relationship r1 = new Relationship();
            r1.setSource("e1");
            r1.setTarget("e1");
            r1.setType("SELF_REFERENCE");
            relationships.add(r1);

            graph.setRelationships(relationships);

            assertEquals(1, graph.getRelationships().size());
            assertEquals("e1", graph.getRelationships().get(0).getSource());
            assertEquals("e1", graph.getRelationships().get(0).getTarget());
        }

        @Test
        @DisplayName("Graphs with same content should be equal")
        void graphs_withSameContentShouldBeEqual() {
            Graph graph1 = new Graph();
            graph1.setEntities(createTestEntities());
            graph1.setRelationships(createTestRelationships());
            graph1.setCommunities(new ArrayList<>());

            Graph graph2 = new Graph();
            graph2.setEntities(createTestEntities());
            graph2.setRelationships(createTestRelationships());
            graph2.setCommunities(new ArrayList<>());

            assertEquals(graph1, graph2);
            assertEquals(graph1.hashCode(), graph2.hashCode());
        }
    }

    // ========================================
    // Helper Methods
    // ========================================

    private List<Entity> createTestEntities() {
        List<Entity> entities = new ArrayList<>();

        Entity e1 = new Entity();
        e1.setId("e1");
        e1.setTitle("Alice");
        e1.setType("PERSON");
        entities.add(e1);

        Entity e2 = new Entity();
        e2.setId("e2");
        e2.setTitle("TechCorp");
        e2.setType("ORGANIZATION");
        entities.add(e2);

        return entities;
    }

    private List<Relationship> createTestRelationships() {
        List<Relationship> relationships = new ArrayList<>();

        Relationship r1 = new Relationship();
        r1.setSource("e1");
        r1.setTarget("e2");
        r1.setType("WORKS_AT");
        relationships.add(r1);

        return relationships;
    }
}
