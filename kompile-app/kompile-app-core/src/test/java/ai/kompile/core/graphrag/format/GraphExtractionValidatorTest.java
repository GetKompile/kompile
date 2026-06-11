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
import ai.kompile.core.graphrag.format.GraphExtractionValidator.ValidationResult;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GraphExtractionValidator} covering JSON serialization,
 * validation edge cases, and conversion corner cases not covered by
 * the round-trip test.
 */
class GraphExtractionValidatorTest {

    // ── JSON serialization (toJson / fromJson) ──────────────────────

    @Nested
    class JsonSerialization {
        @Test
        void roundTripJsonPreservesAllFields() throws JsonProcessingException {
            ExtractedEntity entity = new ExtractedEntity(
                    "e1", "Alice", "PERSON", List.of("Ali"), "A person", 0.95,
                    Map.of("role", "engineer"));
            ExtractedRelation relation = new ExtractedRelation(
                    "e1", "e2", "KNOWS", "Alice knows Bob", 0.8,
                    Map.of("since", "2020"));
            ExtractedEntity entity2 = new ExtractedEntity(
                    "e2", "Bob", "PERSON", List.of(), "Another person", 0.9, Map.of());
            ExtractionResult original = ExtractionResult.of(
                    List.of(entity, entity2), List.of(relation),
                    ExtractionMetadata.forChunk("chunk-1", "doc-1", "test-model"));

            String json = GraphExtractionValidator.toJson(original);
            ExtractionResult restored = GraphExtractionValidator.fromJson(json);

            assertEquals(original.schema(), restored.schema());
            assertEquals(original.entities().size(), restored.entities().size());
            assertEquals(original.relations().size(), restored.relations().size());

            ExtractedEntity restoredAlice = restored.entities().stream()
                    .filter(e -> "e1".equals(e.id())).findFirst().orElseThrow();
            assertEquals("Alice", restoredAlice.name());
            assertEquals("PERSON", restoredAlice.type());
            assertEquals(List.of("Ali"), restoredAlice.aliases());
            assertEquals(0.95, restoredAlice.confidence());
            assertEquals("engineer", restoredAlice.properties().get("role"));

            ExtractedRelation restoredRel = restored.relations().get(0);
            assertEquals("e1", restoredRel.source());
            assertEquals("e2", restoredRel.target());
            assertEquals("KNOWS", restoredRel.type());
            assertEquals(0.8, restoredRel.confidence());
            assertEquals("2020", restoredRel.properties().get("since"));
        }

        @Test
        void toJsonProducesValidJsonWithSchemaField() throws JsonProcessingException {
            ExtractionResult result = ExtractionResult.of(List.of(), List.of(), null);
            String json = GraphExtractionValidator.toJson(result);

            assertTrue(json.contains("\"$schema\""));
            assertTrue(json.contains(GraphExtractionSchema.SCHEMA_VERSION));
        }

        @Test
        void fromJsonIgnoresUnknownFields() throws JsonProcessingException {
            String json = """
                    {
                      "$schema": "kompile-graph-extraction/v1",
                      "entities": [],
                      "relations": [],
                      "unknownField": "should be ignored"
                    }
                    """;
            ExtractionResult result = GraphExtractionValidator.fromJson(json);
            assertNotNull(result);
            assertTrue(result.entities().isEmpty());
        }

        @Test
        void jsonRoundTripPreservesMetadata() throws JsonProcessingException {
            ExtractionResult original = ExtractionResult.of(
                    List.of(), List.of(),
                    ExtractionMetadata.forChunkInGraph(
                            "chunk-42", "doc-7", "gpt-4", "graph-abc", "parent-xyz"));

            String json = GraphExtractionValidator.toJson(original);
            ExtractionResult restored = GraphExtractionValidator.fromJson(json);

            assertNotNull(restored.metadata());
            assertEquals("chunk-42", restored.metadata().sourceChunkId());
            assertEquals("doc-7", restored.metadata().sourceDocumentId());
            assertEquals("gpt-4", restored.metadata().extractionModel());
            assertEquals("graph-abc", restored.metadata().graphId());
            assertEquals("parent-xyz", restored.metadata().parentGraphId());
        }

        @Test
        void jsonRoundTripNullMetadata() throws JsonProcessingException {
            ExtractionResult original = ExtractionResult.of(List.of(), List.of(), null);
            String json = GraphExtractionValidator.toJson(original);
            ExtractionResult restored = GraphExtractionValidator.fromJson(json);
            assertNull(restored.metadata());
        }
    }

    // ── Validation edge cases ───────────────────────────────────────

    @Nested
    class Validation {
        @Test
        void validResultWithNoEntitiesOrRelations() {
            ExtractionResult result = ExtractionResult.of(List.of(), List.of(), null);
            ValidationResult vr = GraphExtractionValidator.validate(result);
            assertTrue(vr.valid());
            assertTrue(vr.errors().isEmpty());
        }

        @Test
        void duplicateEntityIds() {
            ExtractedEntity e1 = entity("dup-id", "First", "TYPE_A");
            ExtractedEntity e2 = entity("dup-id", "Second", "TYPE_B");
            ExtractionResult result = ExtractionResult.of(List.of(e1, e2), List.of(), null);

            ValidationResult vr = GraphExtractionValidator.validate(result);

            assertFalse(vr.valid());
            assertTrue(vr.errors().stream().anyMatch(e -> e.contains("Duplicate entity id")));
        }

        @Test
        void entityWithBlankName() {
            ExtractedEntity e = new ExtractedEntity("e1", "  ", "TYPE", null, "desc", 0.5, null);
            ExtractionResult result = ExtractionResult.of(List.of(e), List.of(), null);

            ValidationResult vr = GraphExtractionValidator.validate(result);

            assertFalse(vr.valid());
            assertTrue(vr.errors().stream().anyMatch(err -> err.contains("blank name")));
        }

        @Test
        void entityWithBlankType() {
            ExtractedEntity e = new ExtractedEntity("e1", "Name", "", null, "desc", 0.5, null);
            ExtractionResult result = ExtractionResult.of(List.of(e), List.of(), null);

            ValidationResult vr = GraphExtractionValidator.validate(result);

            assertFalse(vr.valid());
            assertTrue(vr.errors().stream().anyMatch(err -> err.contains("blank type")));
        }

        @Test
        void entityWithBlankId() {
            ExtractedEntity e = new ExtractedEntity("", "Name", "TYPE", null, "desc", 0.5, null);
            ExtractionResult result = ExtractionResult.of(List.of(e), List.of(), null);

            ValidationResult vr = GraphExtractionValidator.validate(result);

            assertFalse(vr.valid());
            assertTrue(vr.errors().stream().anyMatch(err -> err.contains("null or blank id")));
        }

        @Test
        void entityWithNegativeConfidence() {
            ExtractedEntity e = new ExtractedEntity("e1", "Name", "TYPE", null, "desc", -0.1, null);
            ExtractionResult result = ExtractionResult.of(List.of(e), List.of(), null);

            ValidationResult vr = GraphExtractionValidator.validate(result);

            assertFalse(vr.valid());
            assertTrue(vr.errors().stream().anyMatch(err -> err.contains("confidence out of range")));
        }

        @Test
        void relationWithBlankSource() {
            ExtractedEntity e = entity("e1", "A", "T");
            ExtractedRelation r = new ExtractedRelation("", "e1", "REL", "desc", 0.5, null);
            ExtractionResult result = ExtractionResult.of(List.of(e), List.of(r), null);

            ValidationResult vr = GraphExtractionValidator.validate(result);

            assertFalse(vr.valid());
            assertTrue(vr.errors().stream().anyMatch(err -> err.contains("null or blank source")));
        }

        @Test
        void relationWithBlankTarget() {
            ExtractedEntity e = entity("e1", "A", "T");
            ExtractedRelation r = new ExtractedRelation("e1", null, "REL", "desc", 0.5, null);
            ExtractionResult result = ExtractionResult.of(List.of(e), List.of(r), null);

            ValidationResult vr = GraphExtractionValidator.validate(result);

            assertFalse(vr.valid());
            assertTrue(vr.errors().stream().anyMatch(err -> err.contains("null or blank target")));
        }

        @Test
        void relationWithBlankType() {
            ExtractedEntity e1 = entity("e1", "A", "T");
            ExtractedEntity e2 = entity("e2", "B", "T");
            ExtractedRelation r = new ExtractedRelation("e1", "e2", "  ", "desc", 0.5, null);
            ExtractionResult result = ExtractionResult.of(List.of(e1, e2), List.of(r), null);

            ValidationResult vr = GraphExtractionValidator.validate(result);

            assertFalse(vr.valid());
            assertTrue(vr.errors().stream().anyMatch(err -> err.contains("null or blank type")));
        }

        @Test
        void relationWithUnknownSource() {
            ExtractedEntity e = entity("e1", "A", "T");
            ExtractedRelation r = new ExtractedRelation("missing", "e1", "REL", "desc", 0.5, null);
            ExtractionResult result = ExtractionResult.of(List.of(e), List.of(r), null);

            ValidationResult vr = GraphExtractionValidator.validate(result);

            assertFalse(vr.valid());
            assertTrue(vr.errors().stream().anyMatch(err -> err.contains("unknown source")));
        }

        @Test
        void relationConfidenceOutOfRange() {
            ExtractedEntity e1 = entity("e1", "A", "T");
            ExtractedEntity e2 = entity("e2", "B", "T");
            ExtractedRelation r = new ExtractedRelation("e1", "e2", "REL", "desc", 2.0, null);
            ExtractionResult result = ExtractionResult.of(List.of(e1, e2), List.of(r), null);

            ValidationResult vr = GraphExtractionValidator.validate(result);

            assertFalse(vr.valid());
            assertTrue(vr.errors().stream().anyMatch(err -> err.contains("confidence out of range")));
        }

        @Test
        void multipleErrorsAccumulated() {
            // blank id + blank name + relation referencing unknown
            ExtractedEntity bad = new ExtractedEntity(null, null, "TYPE", null, "desc", 0.5, null);
            ExtractedRelation badRel = new ExtractedRelation("x", "y", null, null, -1.0, null);
            ExtractionResult result = ExtractionResult.of(List.of(bad), List.of(badRel), null);

            ValidationResult vr = GraphExtractionValidator.validate(result);

            assertFalse(vr.valid());
            // Should have errors for: null id, null name, null rel type, unknown source, unknown target, confidence out of range
            assertTrue(vr.errors().size() >= 4, "Expected multiple accumulated errors, got: " + vr.errors());
        }

        @Test
        void validationResultOkFactory() {
            ValidationResult ok = ValidationResult.ok();
            assertTrue(ok.valid());
            assertTrue(ok.errors().isEmpty());
        }

        @Test
        void validationResultFailFactory() {
            ValidationResult fail = ValidationResult.fail(List.of("err1", "err2"));
            assertFalse(fail.valid());
            assertEquals(2, fail.errors().size());
        }
    }

    // ── toGraph edge cases ──────────────────────────────────────────

    @Nested
    class ToGraphConversion {
        @Test
        void aliasesStoredInEntityMetadata() {
            ExtractedEntity e = new ExtractedEntity(
                    "e1", "Corp", "ORGANIZATION", List.of("Acme", "ACME Inc"),
                    "A corporation", 0.9, Map.of("industry", "tech"));
            ExtractionResult result = ExtractionResult.of(List.of(e), List.of(), null);

            Graph graph = GraphExtractionValidator.toGraph(result);

            Entity entity = graph.getEntities().get(0);
            assertNotNull(entity.getMetadata().get("aliases"));
            @SuppressWarnings("unchecked")
            List<String> aliases = (List<String>) entity.getMetadata().get("aliases");
            assertTrue(aliases.contains("Acme"));
            assertTrue(aliases.contains("ACME Inc"));
            assertEquals("tech", entity.getMetadata().get("industry"));
        }

        @Test
        void emptyAliasesNotStoredInMetadata() {
            ExtractedEntity e = new ExtractedEntity(
                    "e1", "Corp", "ORGANIZATION", List.of(),
                    "desc", 0.9, Map.of());
            ExtractionResult result = ExtractionResult.of(List.of(e), List.of(), null);

            Graph graph = GraphExtractionValidator.toGraph(result);

            Entity entity = graph.getEntities().get(0);
            assertFalse(entity.getMetadata().containsKey("aliases"));
        }

        @Test
        void relationPropertiesPreservedInMetadata() {
            ExtractedEntity e1 = entity("e1", "A", "T");
            ExtractedEntity e2 = entity("e2", "B", "T");
            ExtractedRelation r = new ExtractedRelation(
                    "e1", "e2", "REL", "desc", 0.8,
                    Map.of("since", "2024", "context", "work"));
            ExtractionResult result = ExtractionResult.of(List.of(e1, e2), List.of(r), null);

            Graph graph = GraphExtractionValidator.toGraph(result);

            Relationship rel = graph.getRelationships().get(0);
            assertEquals("2024", rel.getMetadata().get("since"));
            assertEquals("work", rel.getMetadata().get("context"));
        }

        @Test
        void confidenceSetAsWeight() {
            ExtractedEntity e1 = entity("e1", "A", "T");
            ExtractedEntity e2 = entity("e2", "B", "T");
            ExtractedRelation r = new ExtractedRelation("e1", "e2", "REL", "desc", 0.75, null);
            ExtractionResult result = ExtractionResult.of(List.of(e1, e2), List.of(r), null);

            Graph graph = GraphExtractionValidator.toGraph(result);

            Relationship rel = graph.getRelationships().get(0);
            assertEquals(0.75, rel.getConfidence());
            assertEquals(0.75, rel.getWeight());
        }
    }

    // ── fromGraph edge cases ────────────────────────────────────────

    @Nested
    class FromGraphConversion {
        @Test
        void nullEntitiesListProducesEmptyResult() {
            Graph graph = new Graph();
            graph.setId("g1");
            graph.setEntities(null);
            graph.setRelationships(null);

            ExtractionResult result = GraphExtractionValidator.fromGraph(graph, "model");

            assertTrue(result.entities().isEmpty());
            assertTrue(result.relations().isEmpty());
        }

        @Test
        void entityWithNullMetadata() {
            Entity entity = new Entity();
            entity.setId("e1");
            entity.setTitle("Test");
            entity.setType("TYPE");
            entity.setDescription("desc");
            entity.setConfidence(0.5);
            entity.setMetadata(null);

            Graph graph = new Graph();
            graph.setId("g1");
            graph.setEntities(List.of(entity));
            graph.setRelationships(List.of());

            ExtractionResult result = GraphExtractionValidator.fromGraph(graph, "model");

            assertEquals(1, result.entities().size());
            ExtractedEntity ee = result.entities().get(0);
            assertEquals("Test", ee.name());
            assertTrue(ee.aliases().isEmpty());
            assertTrue(ee.properties().isEmpty());
        }

        @Test
        void relationWithNullMetadata() {
            Relationship rel = new Relationship();
            rel.setSource("e1");
            rel.setTarget("e2");
            rel.setType("REL");
            rel.setDescription("desc");
            rel.setConfidence(0.9);
            rel.setMetadata(null);

            Entity e1 = new Entity();
            e1.setId("e1");
            e1.setTitle("A");
            e1.setType("T");
            Entity e2 = new Entity();
            e2.setId("e2");
            e2.setTitle("B");
            e2.setType("T");

            Graph graph = new Graph();
            graph.setEntities(List.of(e1, e2));
            graph.setRelationships(List.of(rel));

            ExtractionResult result = GraphExtractionValidator.fromGraph(graph, "model");

            assertEquals(1, result.relations().size());
            assertTrue(result.relations().get(0).properties().isEmpty());
        }

        @Test
        void aliasesExtractedFromMetadataAndExcludedFromProperties() {
            Entity entity = new Entity();
            entity.setId("e1");
            entity.setTitle("Corp");
            entity.setType("ORG");
            entity.setMetadata(new HashMap<>(Map.of(
                    "aliases", List.of("Alias1", "Alias2"),
                    "industry", "tech")));

            Graph graph = new Graph();
            graph.setEntities(List.of(entity));
            graph.setRelationships(List.of());

            ExtractionResult result = GraphExtractionValidator.fromGraph(graph, "m");

            ExtractedEntity ee = result.entities().get(0);
            assertEquals(List.of("Alias1", "Alias2"), ee.aliases());
            assertEquals("tech", ee.properties().get("industry"));
            assertFalse(ee.properties().containsKey("aliases"));
        }

        @Test
        void metadataWithNullValueSkipped() {
            Entity entity = new Entity();
            entity.setId("e1");
            entity.setTitle("Test");
            entity.setType("T");
            Map<String, Object> meta = new HashMap<>();
            meta.put("key1", "value1");
            meta.put("key2", null);
            entity.setMetadata(meta);

            Graph graph = new Graph();
            graph.setEntities(List.of(entity));
            graph.setRelationships(List.of());

            ExtractionResult result = GraphExtractionValidator.fromGraph(graph, "m");

            ExtractedEntity ee = result.entities().get(0);
            assertEquals("value1", ee.properties().get("key1"));
            assertFalse(ee.properties().containsKey("key2"));
        }
    }

    // ── Prompt instructions ─────────────────────────────────────────

    @Test
    void extractionPromptInstructionsNotEmpty() {
        String instructions = GraphExtractionValidator.getExtractionPromptInstructions();
        assertNotNull(instructions);
        assertFalse(instructions.isBlank());
        assertTrue(instructions.contains("entities"));
        assertTrue(instructions.contains("relations"));
        assertTrue(instructions.contains("JSON"));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private ExtractedEntity entity(String id, String name, String type) {
        return new ExtractedEntity(id, name, type, List.of(), "desc", 0.9, Map.of());
    }
}
