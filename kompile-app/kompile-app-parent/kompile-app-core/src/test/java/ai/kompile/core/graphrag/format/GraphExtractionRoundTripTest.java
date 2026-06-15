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
package ai.kompile.core.graphrag.format;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionMetadata;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the graph-extraction data model, including round-trip conversion
 * between {@link Graph} and {@link ExtractionResult} via {@link GraphExtractionValidator},
 * and verification that graph hierarchy fields are preserved end-to-end.
 */
class GraphExtractionRoundTripTest {

    // =========================================================================
    // ExtractionMetadata factory methods
    // =========================================================================

    @Test
    void testExtractionMetadataForChunkInGraph() {
        ExtractionMetadata meta = ExtractionMetadata.forChunkInGraph(
                "chunk-1", "doc-1", "gpt-4", "graph-abc", "parent-graph-xyz");

        assertEquals("chunk-1", meta.sourceChunkId());
        assertEquals("doc-1", meta.sourceDocumentId());
        assertEquals("gpt-4", meta.extractionModel());
        assertNotNull(meta.extractionTimestamp());
        assertEquals("graph-abc", meta.graphId());
        assertEquals("parent-graph-xyz", meta.parentGraphId());
    }

    @Test
    void testExtractionMetadataForChunkWithoutGraph() {
        ExtractionMetadata meta = ExtractionMetadata.forChunk("chunk-2", "doc-2", "claude-3");

        assertEquals("chunk-2", meta.sourceChunkId());
        assertEquals("doc-2", meta.sourceDocumentId());
        assertEquals("claude-3", meta.extractionModel());
        assertNotNull(meta.extractionTimestamp());
        assertNull(meta.graphId(), "graphId should be null for forChunk factory");
        assertNull(meta.parentGraphId(), "parentGraphId should be null for forChunk factory");
    }

    @Test
    void testExtractionMetadataForChunkInGraphWithNullHierarchy() {
        ExtractionMetadata meta =
                ExtractionMetadata.forChunkInGraph("c", "d", "model", null, null);

        assertNull(meta.graphId());
        assertNull(meta.parentGraphId());
    }

    // =========================================================================
    // Graph -> fromGraph (Graph-to-ExtractionResult conversion)
    // =========================================================================

    @Test
    void testGraphToExtractionResultPreservesHierarchy() {
        Graph graph = buildGraph("graph-42", "parent-graph-7", true);

        ExtractionResult result = GraphExtractionValidator.fromGraph(graph, "test-model");

        assertNotNull(result.metadata());
        assertEquals("graph-42", result.metadata().graphId());
        assertEquals("parent-graph-7", result.metadata().parentGraphId());
    }

    @Test
    void testGraphToExtractionResultPreservesEntities() {
        Graph graph = buildGraph("g1", null, true);

        ExtractionResult result = GraphExtractionValidator.fromGraph(graph, "test-model");

        assertNotNull(result.entities());
        assertEquals(2, result.entities().size());

        ExtractedEntity alice = result.entities().stream()
                .filter(e -> "e1".equals(e.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("Alice", alice.name());
        assertEquals("PERSON", alice.type());
        assertEquals("A person named Alice", alice.description());
    }

    @Test
    void testGraphToExtractionResultPreservesRelationships() {
        Graph graph = buildGraph("g1", null, true);

        ExtractionResult result = GraphExtractionValidator.fromGraph(graph, "test-model");

        assertNotNull(result.relations());
        assertEquals(1, result.relations().size());
        ExtractedRelation rel = result.relations().get(0);
        assertEquals("e1", rel.source());
        assertEquals("e2", rel.target());
        assertEquals("KNOWS", rel.type());
    }

    @Test
    void testGraphToExtractionResultSchemaVersionSet() {
        Graph graph = buildGraph("g1", null, false);

        ExtractionResult result = GraphExtractionValidator.fromGraph(graph, "model");

        assertEquals(GraphExtractionSchema.SCHEMA_VERSION, result.schema());
    }

    @Test
    void testGraphToExtractionResultNullGraphIdInMetadata() {
        Graph graph = buildGraph(null, null, false);

        ExtractionResult result = GraphExtractionValidator.fromGraph(graph, "model");

        assertNotNull(result.metadata());
        assertNull(result.metadata().graphId());
        assertNull(result.metadata().parentGraphId());
    }

    // =========================================================================
    // ExtractionResult -> toGraph (ExtractionResult-to-Graph conversion)
    // =========================================================================

    @Test
    void testExtractionResultToGraphPreservesHierarchy() {
        ExtractionMetadata meta =
                ExtractionMetadata.forChunkInGraph("c", "d", "m", "gid-100", "parent-gid-50");
        ExtractionResult result = ExtractionResult.of(
                List.of(buildExtractedEntity("e1", "Alice", "PERSON")),
                List.of(),
                meta);

        Graph graph = GraphExtractionValidator.toGraph(result);

        assertEquals("gid-100", graph.getId());
        assertEquals("parent-gid-50", graph.getParentGraphId());
    }

    @Test
    void testExtractionResultToGraphNullMetadataLeavesHierarchyNull() {
        ExtractionResult result = new ExtractionResult(
                GraphExtractionSchema.SCHEMA_VERSION,
                List.of(buildExtractedEntity("e1", "Alice", "PERSON")),
                List.of(),
                null);

        Graph graph = GraphExtractionValidator.toGraph(result);

        assertNull(graph.getId());
        assertNull(graph.getParentGraphId());
    }

    @Test
    void testExtractionResultToGraphPreservesEntities() {
        ExtractionMetadata meta = ExtractionMetadata.forChunk("c", "d", "m");
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        buildExtractedEntity("e1", "Alice", "PERSON"),
                        buildExtractedEntity("e2", "Acme", "ORGANIZATION")
                ),
                List.of(),
                meta);

        Graph graph = GraphExtractionValidator.toGraph(result);

        assertNotNull(graph.getEntities());
        assertEquals(2, graph.getEntities().size());
        Entity alice = graph.getEntities().stream()
                .filter(e -> "e1".equals(e.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("Alice", alice.getTitle());
        assertEquals("PERSON", alice.getType());
    }

    @Test
    void testExtractionResultToGraphPreservesRelationships() {
        List<ExtractedEntity> entities = List.of(
                buildExtractedEntity("e1", "Alice", "PERSON"),
                buildExtractedEntity("e2", "Acme", "ORGANIZATION")
        );
        List<ExtractedRelation> relations = List.of(
                new ExtractedRelation("e1", "e2", "WORKS_AT", "Alice works at Acme", 0.95, Map.of())
        );
        ExtractionMetadata meta = ExtractionMetadata.forChunk("c", "d", "m");
        ExtractionResult result = ExtractionResult.of(entities, relations, meta);

        Graph graph = GraphExtractionValidator.toGraph(result);

        assertNotNull(graph.getRelationships());
        assertEquals(1, graph.getRelationships().size());
        Relationship rel = graph.getRelationships().get(0);
        assertEquals("e1", rel.getSource());
        assertEquals("e2", rel.getTarget());
        assertEquals("WORKS_AT", rel.getType());
        assertEquals(0.95, rel.getConfidence());
    }

    // =========================================================================
    // Full round-trip: Graph -> fromGraph -> toGraph
    // =========================================================================

    @Test
    void testFullRoundTrip() {
        Graph original = buildGraph("graph-round-trip", "parent-graph", true);

        ExtractionResult intermediate = GraphExtractionValidator.fromGraph(original, "model");
        Graph restored = GraphExtractionValidator.toGraph(intermediate);

        // Hierarchy identity preserved
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getParentGraphId(), restored.getParentGraphId());

        // Entity count preserved
        assertNotNull(restored.getEntities());
        assertEquals(original.getEntities().size(), restored.getEntities().size());

        // Relationship count preserved
        assertNotNull(restored.getRelationships());
        assertEquals(original.getRelationships().size(), restored.getRelationships().size());
    }

    @Test
    void testFullRoundTripEntityFieldsPreserved() {
        Graph original = buildGraph("g-fields", null, true);

        ExtractionResult intermediate = GraphExtractionValidator.fromGraph(original, "model");
        Graph restored = GraphExtractionValidator.toGraph(intermediate);

        Entity originalAlice = original.getEntities().stream()
                .filter(e -> "e1".equals(e.getId()))
                .findFirst()
                .orElseThrow();
        Entity restoredAlice = restored.getEntities().stream()
                .filter(e -> "e1".equals(e.getId()))
                .findFirst()
                .orElseThrow();

        assertEquals(originalAlice.getTitle(), restoredAlice.getTitle());
        assertEquals(originalAlice.getType(), restoredAlice.getType());
        assertEquals(originalAlice.getDescription(), restoredAlice.getDescription());
        assertEquals(originalAlice.getConfidence(), restoredAlice.getConfidence());
    }

    @Test
    void testFullRoundTripWithNullHierarchy() {
        Graph original = buildGraph(null, null, true);

        ExtractionResult intermediate = GraphExtractionValidator.fromGraph(original, "model");
        Graph restored = GraphExtractionValidator.toGraph(intermediate);

        assertNull(restored.getId());
        assertNull(restored.getParentGraphId());
    }

    @Test
    void testFullRoundTripRelationshipFieldsPreserved() {
        Graph original = buildGraph("g-rel-fields", null, true);

        ExtractionResult intermediate = GraphExtractionValidator.fromGraph(original, "model");
        Graph restored = GraphExtractionValidator.toGraph(intermediate);

        Relationship originalRel = original.getRelationships().get(0);
        Relationship restoredRel = restored.getRelationships().get(0);

        assertEquals(originalRel.getSource(), restoredRel.getSource());
        assertEquals(originalRel.getTarget(), restoredRel.getTarget());
        assertEquals(originalRel.getType(), restoredRel.getType());
        assertEquals(originalRel.getDescription(), restoredRel.getDescription());
    }

    // =========================================================================
    // Graph model defaults and builder
    // =========================================================================

    @Test
    void testGraphBuilderDefaults() {
        Graph graph = Graph.builder().build();

        assertNotNull(graph.getChildGraphIds(), "childGraphIds should not be null by default");
        assertTrue(graph.getChildGraphIds().isEmpty(), "childGraphIds should be empty by default");
        assertNotNull(graph.getMetadata(), "metadata should not be null by default");
        assertTrue(graph.getMetadata().isEmpty(), "metadata should be empty by default");
    }

    @Test
    void testGraphWithAllHierarchyFields() {
        Graph graph = Graph.builder()
                .id("g-full")
                .name("Full Graph")
                .description("A complete graph with all hierarchy fields")
                .parentGraphId("parent-g")
                .childGraphIds(new ArrayList<>(List.of("child-1", "child-2")))
                .factSheetId(99L)
                .metadata(new HashMap<>(Map.of("region", "EMEA")))
                .build();

        assertEquals("g-full", graph.getId());
        assertEquals("Full Graph", graph.getName());
        assertEquals("A complete graph with all hierarchy fields", graph.getDescription());
        assertEquals("parent-g", graph.getParentGraphId());
        assertEquals(List.of("child-1", "child-2"), graph.getChildGraphIds());
        assertEquals(99L, graph.getFactSheetId());
        assertEquals("EMEA", graph.getMetadata().get("region"));
    }

    @Test
    void testGraphNoArgsConstructorYieldsNullHierarchyFields() {
        Graph graph = new Graph();

        assertNull(graph.getId());
        assertNull(graph.getParentGraphId());
        assertNull(graph.getFactSheetId());
        assertNull(graph.getName());
        assertNull(graph.getDescription());
    }

    // =========================================================================
    // Validation
    // =========================================================================

    @Test
    void testValidatePassesForValidExtractionResult() {
        List<ExtractedEntity> entities = List.of(
                buildExtractedEntity("e1", "Alice", "PERSON"),
                buildExtractedEntity("e2", "Acme", "ORGANIZATION")
        );
        List<ExtractedRelation> relations = List.of(
                new ExtractedRelation("e1", "e2", "WORKS_AT", "desc", 0.9, Map.of())
        );
        ExtractionResult result = ExtractionResult.of(entities, relations,
                ExtractionMetadata.forChunk("c", "d", "m"));

        GraphExtractionValidator.ValidationResult vr = GraphExtractionValidator.validate(result);

        assertTrue(vr.valid());
        assertTrue(vr.errors().isEmpty());
    }

    @Test
    void testValidateFailsForNullResult() {
        GraphExtractionValidator.ValidationResult vr = GraphExtractionValidator.validate(null);

        assertFalse(vr.valid());
        assertFalse(vr.errors().isEmpty());
    }

    @Test
    void testValidateFailsForEntityMissingId() {
        ExtractedEntity badEntity = new ExtractedEntity(
                null, "No ID Entity", "CONCEPT", List.of(), "desc", 0.8, Map.of());
        ExtractionResult result = ExtractionResult.of(
                List.of(badEntity), List.of(),
                ExtractionMetadata.forChunk("c", "d", "m"));

        GraphExtractionValidator.ValidationResult vr = GraphExtractionValidator.validate(result);

        assertFalse(vr.valid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("null or blank id")));
    }

    @Test
    void testValidateFailsForRelationReferencingUnknownEntity() {
        List<ExtractedEntity> entities = List.of(buildExtractedEntity("e1", "Alice", "PERSON"));
        List<ExtractedRelation> relations = List.of(
                new ExtractedRelation("e1", "e-unknown", "KNOWS", "desc", 0.9, Map.of())
        );
        ExtractionResult result = ExtractionResult.of(entities, relations,
                ExtractionMetadata.forChunk("c", "d", "m"));

        GraphExtractionValidator.ValidationResult vr = GraphExtractionValidator.validate(result);

        assertFalse(vr.valid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("unknown")));
    }

    @Test
    void testValidateFailsForEntityConfidenceOutOfRange() {
        ExtractedEntity bad = new ExtractedEntity(
                "e-bad", "Bad Confidence", "CONCEPT", List.of(), "desc", 1.5, Map.of());
        ExtractionResult result = ExtractionResult.of(
                List.of(bad), List.of(),
                ExtractionMetadata.forChunk("c", "d", "m"));

        GraphExtractionValidator.ValidationResult vr = GraphExtractionValidator.validate(result);

        assertFalse(vr.valid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("confidence out of range")));
    }

    // =========================================================================
    // Metadata propagation edge cases
    // =========================================================================

    @Test
    void testAliasesPreservedThroughFromGraph() {
        Entity entity = new Entity();
        entity.setId("e-alias");
        entity.setTitle("Corp");
        entity.setType("ORGANIZATION");
        entity.setDescription("A corporation");
        entity.setConfidence(0.9);
        entity.setMetadata(new HashMap<>(Map.of(
                "aliases", List.of("Corporation", "The Corp")
        )));

        Graph graph = new Graph();
        graph.setId("g-alias");
        graph.setEntities(List.of(entity));
        graph.setRelationships(List.of());

        ExtractionResult result = GraphExtractionValidator.fromGraph(graph, "model");

        ExtractedEntity extracted = result.entities().get(0);
        assertNotNull(extracted.aliases());
        assertTrue(extracted.aliases().contains("Corporation"));
        assertTrue(extracted.aliases().contains("The Corp"));
    }

    @Test
    void testPropertiesPreservedThroughFromGraph() {
        Entity entity = new Entity();
        entity.setId("e-props");
        entity.setTitle("Widget");
        entity.setType("PRODUCT");
        entity.setDescription("A widget product");
        entity.setConfidence(0.75);
        entity.setMetadata(new HashMap<>(Map.of(
                "sku", "W-12345",
                "category", "hardware"
        )));

        Graph graph = new Graph();
        graph.setId("g-props");
        graph.setEntities(List.of(entity));
        graph.setRelationships(List.of());

        ExtractionResult result = GraphExtractionValidator.fromGraph(graph, "model");

        ExtractedEntity extracted = result.entities().get(0);
        assertNotNull(extracted.properties());
        assertEquals("W-12345", extracted.properties().get("sku"));
        assertEquals("hardware", extracted.properties().get("category"));
    }

    // =========================================================================
    // ExtractionResult defaults
    // =========================================================================

    @Test
    void testExtractionResultSchemaDefaultsToVersion() {
        ExtractionResult result = new ExtractionResult(null, null, null, null);

        assertEquals(GraphExtractionSchema.SCHEMA_VERSION, result.schema());
        assertNotNull(result.entities());
        assertTrue(result.entities().isEmpty());
        assertNotNull(result.relations());
        assertTrue(result.relations().isEmpty());
    }

    @Test
    void testExtractedEntityDefaultsConfidenceToOne() {
        ExtractedEntity entity = new ExtractedEntity(
                "e1", "name", "TYPE", null, "desc", null, null);

        assertEquals(1.0, entity.confidence());
        assertNotNull(entity.aliases());
        assertNotNull(entity.properties());
    }

    @Test
    void testExtractedRelationDefaultsConfidenceToOne() {
        ExtractedRelation rel = new ExtractedRelation(
                "e1", "e2", "REL", "desc", null, null);

        assertEquals(1.0, rel.confidence());
        assertNotNull(rel.properties());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Build a Graph with two entities (Alice, Bob), one relationship, and optional hierarchy fields.
     *
     * @param graphId       the graph's own ID (may be null)
     * @param parentGraphId the parent graph ID (may be null)
     * @param addRelation   whether to add a relationship between the entities
     */
    private Graph buildGraph(String graphId, String parentGraphId, boolean addRelation) {
        Entity alice = new Entity();
        alice.setId("e1");
        alice.setTitle("Alice");
        alice.setType("PERSON");
        alice.setDescription("A person named Alice");
        alice.setConfidence(0.95);
        alice.setMetadata(new HashMap<>());

        Entity bob = new Entity();
        bob.setId("e2");
        bob.setTitle("Bob");
        bob.setType("PERSON");
        bob.setDescription("A person named Bob");
        bob.setConfidence(0.88);
        bob.setMetadata(new HashMap<>());

        Graph graph = new Graph();
        graph.setId(graphId);
        graph.setParentGraphId(parentGraphId);
        graph.setEntities(new ArrayList<>(List.of(alice, bob)));

        if (addRelation) {
            Relationship rel = new Relationship();
            rel.setSource("e1");
            rel.setTarget("e2");
            rel.setType("KNOWS");
            rel.setDescription("Alice knows Bob");
            rel.setConfidence(0.9);
            rel.setWeight(0.9);
            rel.setMetadata(new HashMap<>());
            graph.setRelationships(new ArrayList<>(List.of(rel)));
        } else {
            graph.setRelationships(new ArrayList<>());
        }

        return graph;
    }

    private ExtractedEntity buildExtractedEntity(String id, String name, String type) {
        return new ExtractedEntity(id, name, type, List.of(),
                "Description of " + name, 0.9, Map.of());
    }
}
