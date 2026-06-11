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
package ai.kompile.core.graphrag.model;

import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionMetadata;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Graph} hierarchy fields and their round-trip through
 * {@link GraphExtractionValidator#fromGraph} / {@link GraphExtractionValidator#toGraph},
 * as well as {@link ExtractionMetadata} factory methods.
 */
class GraphModelTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH BUILDER / DEFAULT FIELDS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGraphBuilderDefaults() {
        Graph graph = Graph.builder()
            .id("g1")
            .name("Test Graph")
            .build();

        // @Builder.Default lists should be empty, not null
        assertNotNull(graph.getChildGraphIds());
        assertTrue(graph.getChildGraphIds().isEmpty());

        assertNotNull(graph.getMetadata());
        assertTrue(graph.getMetadata().isEmpty());

        assertNull(graph.getParentGraphId());
        assertNull(graph.getFactSheetId());
        assertNull(graph.getEntities());
        assertNull(graph.getRelationships());
    }

    @Test
    void testGraphWithHierarchy() {
        Graph graph = Graph.builder()
            .id("child-graph")
            .name("Child Graph")
            .description("A nested sub-graph")
            .parentGraphId("root-graph")
            .childGraphIds(List.of("grandchild-1", "grandchild-2"))
            .build();

        assertEquals("child-graph", graph.getId());
        assertEquals("Child Graph", graph.getName());
        assertEquals("A nested sub-graph", graph.getDescription());
        assertEquals("root-graph", graph.getParentGraphId());
        assertEquals(2, graph.getChildGraphIds().size());
        assertTrue(graph.getChildGraphIds().contains("grandchild-1"));
        assertTrue(graph.getChildGraphIds().contains("grandchild-2"));
    }

    @Test
    void testGraphWithFactSheetId() {
        Graph graph = Graph.builder()
            .id("scoped-graph")
            .name("Scoped Graph")
            .factSheetId(99L)
            .build();

        assertEquals(99L, graph.getFactSheetId());
        assertEquals("scoped-graph", graph.getId());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ROUND-TRIP: fromGraph → toGraph
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGraphRoundTripThroughValidator() {
        // Build a graph with entities, relationships, and hierarchy fields
        Entity entity = new Entity();
        entity.setId("e1");
        entity.setTitle("Acme Corp");
        entity.setType("ORGANIZATION");
        entity.setDescription("A tech company");
        entity.setConfidence(0.9);

        Relationship rel = new Relationship();
        rel.setSource("e1");
        rel.setTarget("e1");
        rel.setType("SELF_REF");
        rel.setDescription("self reference");
        rel.setConfidence(0.5);

        Graph original = Graph.builder()
            .id("my-graph-id")
            .name("Round Trip Graph")
            .description("Tests hierarchy preservation")
            .parentGraphId("parent-graph-id")
            .entities(List.of(entity))
            .relationships(List.of(rel))
            .build();

        // Convert Graph → ExtractionResult → Graph
        ExtractionResult result = GraphExtractionValidator.fromGraph(original, "test-model");
        Graph reconstructed = GraphExtractionValidator.toGraph(result);

        // Graph identity fields must survive the round-trip
        assertEquals("my-graph-id", reconstructed.getId());
        assertEquals("parent-graph-id", reconstructed.getParentGraphId());

        // Entities should be preserved
        assertNotNull(reconstructed.getEntities());
        assertEquals(1, reconstructed.getEntities().size());
        assertEquals("e1", reconstructed.getEntities().get(0).getId());
        assertEquals("Acme Corp", reconstructed.getEntities().get(0).getTitle());
        assertEquals("ORGANIZATION", reconstructed.getEntities().get(0).getType());
        assertEquals(0.9, reconstructed.getEntities().get(0).getConfidence());

        // Relationships should be preserved
        assertNotNull(reconstructed.getRelationships());
        assertEquals(1, reconstructed.getRelationships().size());
        assertEquals("e1", reconstructed.getRelationships().get(0).getSource());
        assertEquals("SELF_REF", reconstructed.getRelationships().get(0).getType());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXTRACTION METADATA — forChunkInGraph
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testExtractionMetadataForChunkInGraph() {
        ExtractionMetadata meta = ExtractionMetadata.forChunkInGraph(
            "chunk-1", "doc-1", "gpt-4", "graph-abc", "parent-xyz");

        assertEquals("chunk-1", meta.sourceChunkId());
        assertEquals("doc-1", meta.sourceDocumentId());
        assertEquals("gpt-4", meta.extractionModel());
        assertEquals("graph-abc", meta.graphId());
        assertEquals("parent-xyz", meta.parentGraphId());
        assertNotNull(meta.extractionTimestamp());
    }

    @Test
    void testExtractionMetadataForChunkWithoutGraph() {
        ExtractionMetadata meta = ExtractionMetadata.forChunk("chunk-2", "doc-2", "claude-3");

        assertEquals("chunk-2", meta.sourceChunkId());
        assertEquals("doc-2", meta.sourceDocumentId());
        assertEquals("claude-3", meta.extractionModel());
        assertNull(meta.graphId());
        assertNull(meta.parentGraphId());
        assertNotNull(meta.extractionTimestamp());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXTRACTION METADATA — toGraph propagation
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testToGraphPropagatesGraphIdAndParentGraphId() {
        ExtractedEntity entity = new ExtractedEntity(
            "e1", "Alice", "PERSON", List.of(), "A person", 0.8, Map.of());
        ExtractedRelation relation = new ExtractedRelation(
            "e1", "e1", "KNOWS", "self", 0.5, Map.of());

        ExtractionMetadata meta = ExtractionMetadata.forChunkInGraph(
            null, null, "model-x", "g-999", "g-parent");

        ExtractionResult result = ExtractionResult.of(List.of(entity), List.of(relation), meta);
        Graph graph = GraphExtractionValidator.toGraph(result);

        assertEquals("g-999", graph.getId());
        assertEquals("g-parent", graph.getParentGraphId());
    }

    @Test
    void testToGraphWithNullMetadata() {
        ExtractedEntity entity = new ExtractedEntity(
            "e1", "Bob", "PERSON", List.of(), "Another person", 1.0, Map.of());

        ExtractionResult result = ExtractionResult.of(List.of(entity), List.of(), null);
        Graph graph = GraphExtractionValidator.toGraph(result);

        // With null metadata, graph identity fields should remain null
        assertNull(graph.getId());
        assertNull(graph.getParentGraphId());
        assertNotNull(graph.getEntities());
        assertEquals(1, graph.getEntities().size());
    }
}
