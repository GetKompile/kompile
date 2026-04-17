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

package ai.kompile.core.graphrag.format;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import ai.kompile.core.graphrag.format.GraphExtractionValidator.ValidationResult;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphExtractionSchemaTest {

    @Test
    void testExtractionResultDefaults() {
        ExtractionResult result = new ExtractionResult(null, null, null, null);
        assertEquals(GraphExtractionSchema.SCHEMA_VERSION, result.schema());
        assertNotNull(result.entities());
        assertTrue(result.entities().isEmpty());
        assertNotNull(result.relations());
        assertTrue(result.relations().isEmpty());
    }

    @Test
    void testExtractedEntityDefaults() {
        ExtractedEntity entity = new ExtractedEntity("e1", "Acme", "ORGANIZATION", null, "A company", null, null);
        assertNotNull(entity.aliases());
        assertTrue(entity.aliases().isEmpty());
        assertEquals(1.0, entity.confidence());
        assertNotNull(entity.properties());
        assertTrue(entity.properties().isEmpty());
    }

    @Test
    void testExtractedRelationDefaults() {
        ExtractedRelation rel = new ExtractedRelation("e1", "e2", "EMPLOYS", "Employment", null, null);
        assertEquals(1.0, rel.confidence());
        assertNotNull(rel.properties());
        assertTrue(rel.properties().isEmpty());
    }

    @Test
    void testValidationValid() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("e1", "Acme", "ORGANIZATION", List.of("ACME"), "A company", 0.95, Map.of()),
                        new ExtractedEntity("e2", "John", "PERSON", List.of(), "A person", 0.9, Map.of())
                ),
                List.of(
                        new ExtractedRelation("e1", "e2", "EMPLOYS", "Employment", 0.85, Map.of())
                ),
                ExtractionMetadata.forChunk("c1", "d1", "gpt-4o")
        );

        ValidationResult validation = GraphExtractionValidator.validate(result);
        assertTrue(validation.valid());
        assertTrue(validation.errors().isEmpty());
    }

    @Test
    void testValidationDuplicateIds() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("e1", "Acme", "ORGANIZATION", List.of(), "A company", 0.95, Map.of()),
                        new ExtractedEntity("e1", "Other", "PERSON", List.of(), "Another entity", 0.9, Map.of())
                ),
                List.of(),
                null
        );

        ValidationResult validation = GraphExtractionValidator.validate(result);
        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(e -> e.contains("Duplicate")));
    }

    @Test
    void testValidationMissingFields() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("", "Acme", "ORGANIZATION", List.of(), "A company", 0.95, Map.of()),
                        new ExtractedEntity("e2", "", null, List.of(), null, 0.9, Map.of())
                ),
                List.of(),
                null
        );

        ValidationResult validation = GraphExtractionValidator.validate(result);
        assertFalse(validation.valid());
        assertTrue(validation.errors().size() >= 2);
    }

    @Test
    void testValidationInvalidRelationReferences() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("e1", "Acme", "ORGANIZATION", List.of(), "A company", 0.95, Map.of())
                ),
                List.of(
                        new ExtractedRelation("e1", "e99", "EMPLOYS", "Bad ref", 0.85, Map.of())
                ),
                null
        );

        ValidationResult validation = GraphExtractionValidator.validate(result);
        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(e -> e.contains("unknown target")));
    }

    @Test
    void testValidationConfidenceOutOfRange() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("e1", "Acme", "ORGANIZATION", List.of(), "A company", 1.5, Map.of())
                ),
                List.of(),
                null
        );

        ValidationResult validation = GraphExtractionValidator.validate(result);
        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(e -> e.contains("confidence out of range")));
    }

    @Test
    void testJsonRoundTrip() throws JsonProcessingException {
        ExtractionResult original = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("e1", "Acme Corp", "ORGANIZATION",
                                List.of("Acme", "ACME Corporation"), "A tech company", 0.95,
                                Map.of("founded", "2010", "industry", "software"))
                ),
                List.of(
                        new ExtractedRelation("e1", "e1", "SELF", "Self-reference", 0.5, Map.of("note", "test"))
                ),
                ExtractionMetadata.forChunk("chunk_1", "doc_1", "gpt-4o")
        );

        String json = GraphExtractionValidator.toJson(original);
        assertNotNull(json);
        assertTrue(json.contains("Acme Corp"));
        assertTrue(json.contains("kompile-graph-extraction/v1"));

        ExtractionResult deserialized = GraphExtractionValidator.fromJson(json);
        assertEquals(1, deserialized.entities().size());
        assertEquals("Acme Corp", deserialized.entities().get(0).name());
        assertEquals(2, deserialized.entities().get(0).aliases().size());
        assertEquals(0.95, deserialized.entities().get(0).confidence());
    }

    @Test
    void testToGraph() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("e1", "Acme", "ORGANIZATION", List.of("ACME Inc"), "A company", 0.95, Map.of("industry", "tech")),
                        new ExtractedEntity("e2", "John", "PERSON", List.of(), "CEO", 0.9, Map.of())
                ),
                List.of(
                        new ExtractedRelation("e1", "e2", "EMPLOYS", "Employment", 0.85, Map.of("role", "CEO"))
                ),
                null
        );

        Graph graph = GraphExtractionValidator.toGraph(result);
        assertNotNull(graph);
        assertEquals(2, graph.getEntities().size());
        assertEquals(1, graph.getRelationships().size());

        Entity acme = graph.getEntities().get(0);
        assertEquals("Acme", acme.getTitle());
        assertEquals("ORGANIZATION", acme.getType());
        assertEquals(0.95, acme.getConfidence());
        assertTrue(acme.getMetadata().containsKey("aliases"));

        Relationship rel = graph.getRelationships().get(0);
        assertEquals("e1", rel.getSource());
        assertEquals("e2", rel.getTarget());
        assertEquals("EMPLOYS", rel.getType());
        assertEquals(0.85, rel.getConfidence());
    }

    @Test
    void testFromGraph() {
        Graph graph = new Graph();
        Entity entity = new Entity();
        entity.setId("e1");
        entity.setTitle("Acme");
        entity.setType("ORGANIZATION");
        entity.setDescription("A company");
        entity.setConfidence(0.9);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("aliases", List.of("ACME Inc"));
        metadata.put("industry", "tech");
        entity.setMetadata(metadata);
        graph.setEntities(List.of(entity));

        Relationship rel = new Relationship();
        rel.setSource("e1");
        rel.setTarget("e1");
        rel.setType("SELF");
        rel.setDescription("Self");
        rel.setConfidence(0.5);
        rel.setMetadata(Map.of());
        graph.setRelationships(List.of(rel));

        ExtractionResult result = GraphExtractionValidator.fromGraph(graph, "test-model");
        assertEquals(1, result.entities().size());
        assertEquals("Acme", result.entities().get(0).name());
        assertEquals(1, result.entities().get(0).aliases().size());
        assertEquals(1, result.relations().size());
    }

    @Test
    void testValidationNull() {
        ValidationResult validation = GraphExtractionValidator.validate(null);
        assertFalse(validation.valid());
    }

    @Test
    void testExtractionPromptInstructions() {
        String instructions = GraphExtractionValidator.getExtractionPromptInstructions();
        assertNotNull(instructions);
        assertTrue(instructions.contains("entities"));
        assertTrue(instructions.contains("relations"));
        assertTrue(instructions.contains("confidence"));
        assertTrue(instructions.contains("aliases"));
    }
}
