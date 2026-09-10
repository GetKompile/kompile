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

import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy.FailureMode;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionMetadata;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionValidator.ValidationResult;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.PropertyType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
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
        void incrementalDeltaMayReferenceEntitiesAlreadyInGraph() {
            ExtractedRelation r = new ExtractedRelation(
                    "existing-person", "existing-step", "APPROVED_BY",
                    "The source records this approval", 0.9, Map.of());
            ExtractionResult delta = ExtractionResult.of(List.of(), List.of(r), null);

            ValidationResult vr = GraphExtractionValidator.validate(
                    delta,
                    GraphExtractionValidationPolicy.defaults(),
                    null,
                    Map.of("existing-person", "PERSON", "existing-step", "CLOSE_STEP"));

            assertTrue(vr.valid(), () -> "Existing endpoints should close the delta: " + vr.errors());
        }

        @Test
        void knownEndpointTypesParticipateInRelationSchemaValidation() {
            GraphExtractionValidationPolicy policy = GraphExtractionValidationPolicy.builder()
                    .relationPatterns(List.of("(PERSON)-[:APPROVED_BY]->(CLOSE_STEP)"))
                    .build();
            ExtractionResult delta = ExtractionResult.of(
                    List.of(),
                    List.of(new ExtractedRelation(
                            "existing-person", "existing-step", "APPROVED_BY",
                            "The source records this approval", 0.9, Map.of())),
                    null);

            assertTrue(GraphExtractionValidator.validate(
                    delta, policy, null,
                    Map.of("existing-person", "PERSON", "existing-step", "CLOSE_STEP")).valid());

            ValidationResult wrongTypes = GraphExtractionValidator.validate(
                    delta, policy, null,
                    Map.of("existing-person", "PERSON", "existing-step", "SPREADSHEET"));
            assertFalse(wrongTypes.valid());
            assertTrue(wrongTypes.errors().stream()
                    .anyMatch(error -> error.contains("[RELATION_SCHEMA_PATTERN]")));
        }

        @Test
        void existingIdMayBeEnrichedButDuplicatesInsideDeltaRemainInvalid() {
            ExtractedEntity enrichment = entity("existing", "Existing entity", "PERSON");
            assertTrue(GraphExtractionValidator.validate(
                    ExtractionResult.of(List.of(enrichment), List.of(), null),
                    GraphExtractionValidationPolicy.defaults(),
                    null,
                    Map.of("existing", "PERSON")).valid());

            ValidationResult duplicate = GraphExtractionValidator.validate(
                    ExtractionResult.of(List.of(enrichment, enrichment), List.of(), null),
                    GraphExtractionValidationPolicy.defaults(),
                    null,
                    Map.of("existing", "PERSON"));
            assertFalse(duplicate.valid());
            assertTrue(duplicate.errors().stream()
                    .anyMatch(error -> error.contains("Duplicate entity id")));
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
        void aliasesStoredInEntityFieldAndMetadata() {
            ExtractedEntity e = new ExtractedEntity(
                    "e1", "Corp", "ORGANIZATION", List.of("Acme", "ACME Inc"),
                    "A corporation", 0.9, Map.of("industry", "tech"));
            ExtractionResult result = ExtractionResult.of(List.of(e), List.of(), null);

            Graph graph = GraphExtractionValidator.toGraph(result);

            Entity entity = graph.getEntities().get(0);
            assertEquals(List.of("Acme", "ACME Inc"), entity.getAliases());
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
                    Map.of("since", "2024", "context", "work"), "2026-04-30T09:00:00Z");
            ExtractionResult result = ExtractionResult.of(List.of(e1, e2), List.of(r), null);

            Graph graph = GraphExtractionValidator.toGraph(result);

            Relationship rel = graph.getRelationships().get(0);
            assertEquals("2024", rel.getMetadata().get("since"));
            assertEquals("work", rel.getMetadata().get("context"));
            assertEquals("2026-04-30T09:00:00Z", rel.getOccurredAt());
            assertEquals("2026-04-30T09:00:00Z", rel.getMetadata().get("occurredAt"));
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

        @Test
        void knownSourcePassageIsAttachedToEveryExtractedFact() {
            ExtractedEntity e1 = entity("e1", "M. Chen", "PERSON");
            ExtractedEntity e2 = entity("e2", "VP Planning", "ROLE");
            ExtractedRelation relation = new ExtractedRelation(
                    "e1", "e2", "HAS_ROLE", null, 0.8, Map.of());
            ExtractionResult result = ExtractionResult.of(
                    List.of(e1, e2), List.of(relation),
                    ExtractionMetadata.forChunk("chunk-1", "doc-1", "model"));

            Graph graph = GraphExtractionValidator.toGraph(result, "M. Chen is VP, Planning.");

            for (Entity entity : graph.getEntities()) {
                assertEquals("chunk-1", entity.getMetadata().get("sourceChunkId"));
                assertEquals("doc-1", entity.getMetadata().get("sourceDocumentId"));
                assertEquals("M. Chen is VP, Planning.", entity.getMetadata().get("evidenceQuote"));
                assertInstanceOf(List.class, entity.getMetadata().get("supportingEvidence"));
            }
            Relationship extracted = graph.getRelationships().get(0);
            assertEquals("chunk-1", extracted.getMetadata().get("sourceChunkId"));
            assertEquals("M. Chen is VP, Planning.", extracted.getMetadata().get("evidenceQuote"));
            assertInstanceOf(List.class, extracted.getMetadata().get("supportingEvidence"));
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

        @Test
        void fieldAliasesAndStructuredProvenanceSurviveGraphExtractionRoundTrip() {
            Entity entity = new Entity();
            entity.setId("person-mei");
            entity.setTitle("Mei Chen");
            entity.setType("PERSON");
            entity.setAliases(new ArrayList<>(List.of("M. Chen", "mei@example.com")));
            entity.setMetadata(new LinkedHashMap<>(Map.of(
                    "sourceChunkId", "chunk-1",
                    "supportingEvidence", List.of(Map.of(
                            "sourceChunkId", "chunk-1", "evidenceQuote", "Mei approved C-03")))));

            Relationship relation = new Relationship();
            relation.setSource("person-mei");
            relation.setTarget("control-c03");
            relation.setType("APPROVED_BY");
            relation.setOccurredAt("2026-06-03T09:30:00Z");
            relation.setMetadata(new LinkedHashMap<>(Map.of(
                    "sourceChunkIds", List.of("chunk-1", "chunk-2"),
                    "supportingEvidence", List.of(
                            Map.of("sourceChunkId", "chunk-1", "evidenceQuote", "Mei approved C-03"),
                            Map.of("sourceChunkId", "chunk-2", "evidenceQuote", "Approval: Mei")),
                    "supportingCount", 2)));

            Graph graph = new Graph();
            graph.setEntities(new ArrayList<>(List.of(entity)));
            graph.setRelationships(new ArrayList<>(List.of(relation)));

            Graph roundTrip = GraphExtractionValidator.toGraph(
                    GraphExtractionValidator.fromGraph(graph, "model"));

            assertEquals(entity.getAliases(), roundTrip.getEntities().get(0).getAliases());
            assertInstanceOf(List.class,
                    roundTrip.getEntities().get(0).getMetadata().get("supportingEvidence"));
            assertEquals(List.of("chunk-1", "chunk-2"),
                    roundTrip.getRelationships().get(0).getMetadata().get("sourceChunkIds"));
            assertInstanceOf(List.class,
                    roundTrip.getRelationships().get(0).getMetadata().get("supportingEvidence"));
            assertEquals(2, roundTrip.getRelationships().get(0).getMetadata().get("supportingCount"));
            assertEquals("2026-06-03T09:30:00Z",
                    roundTrip.getRelationships().get(0).getOccurredAt());
        }
    }

    // ── Non-degenerate confidence defaults ──────────────────────────

    @Test
    void absentEntityConfidenceIsNotHardCertain() {
        // Absent LLM confidence must NOT produce 1.0 (hard-certain). It must produce
        // DEFAULT_ENTITY_CONFIDENCE so PSL treats it as a soft atom (< 0.99 threshold).
        ExtractedEntity e = new ExtractedEntity("e1", "Name", "TYPE", null, "desc", null, null);
        double confidence = e.confidence();
        assertTrue(confidence < 1.0, "Absent entity confidence must be < 1.0, was: " + confidence);
        assertEquals(GraphExtractionSchema.DEFAULT_ENTITY_CONFIDENCE, confidence, 1e-9);
    }

    @Test
    void absentRelationConfidenceIsNotHardCertain() {
        // Absent LLM confidence must NOT produce 1.0 (hard-certain). It must produce
        // DEFAULT_RELATION_CONFIDENCE so PSL treats it as a soft atom (< 0.99 threshold).
        ExtractedRelation r = new ExtractedRelation("e1", "e2", "REL", "desc", null, null);
        double confidence = r.confidence();
        assertTrue(confidence < 1.0, "Absent relation confidence must be < 1.0, was: " + confidence);
        assertEquals(GraphExtractionSchema.DEFAULT_RELATION_CONFIDENCE, confidence, 1e-9);
    }

    @Test
    void llmSuppliedEntityConfidenceIsPreserved() {
        // A real LLM-supplied value must never be clobbered by the fallback.
        ExtractedEntity e = new ExtractedEntity("e1", "Name", "TYPE", null, "desc", 0.85, null);
        assertEquals(0.85, e.confidence(), 1e-9);
    }

    @Test
    void llmSuppliedRelationConfidenceIsPreserved() {
        ExtractedRelation r = new ExtractedRelation("e1", "e2", "REL", "desc", 0.63, null);
        assertEquals(0.63, r.confidence(), 1e-9);
    }

    @Nested
    class SemanticPolicyValidation {

        @Test
        void defaultsEnforceSchemaButDoNotRejectOptionalDescriptions() {
            List<String> defaults = GraphExtractionValidationPolicy.defaultValidatorIds();
            assertTrue(defaults.contains(GraphExtractionValidationPolicy.ENTITY_TYPE_SCHEMA));
            assertTrue(defaults.contains(GraphExtractionValidationPolicy.RELATION_TYPE_SCHEMA));
            assertTrue(defaults.contains(GraphExtractionValidationPolicy.PROPERTY_SCHEMA));
            assertFalse(defaults.contains(GraphExtractionValidationPolicy.REQUIRED_DESCRIPTIONS));

            ExtractedEntity withoutDescription = new ExtractedEntity(
                    "e1", "M. Chen", "PERSON", List.of(), null, 0.9, Map.of());
            assertTrue(GraphExtractionValidator.validate(
                    ExtractionResult.of(List.of(withoutDescription), List.of(), null),
                    GraphExtractionValidationPolicy.defaults(), null).valid());
        }

        @Test
        void standardizedSchemaRejectsUnknownEntityAndRelationTypes() {
            GraphSchema schema = new GraphSchema(
                    List.of(
                            new NodeType("PERSON", "A person", null),
                            new NodeType("ROLE", "A role", null)),
                    List.of(new RelationshipType(
                            "HAS_ROLE", "Person has role", null, List.of("serves_as"))),
                    List.of("(PERSON)-[:HAS_ROLE]->(ROLE)"));
            ExtractionResult result = ExtractionResult.of(
                    List.of(
                            entity("person", "M. Chen", "PERSON"),
                            entity("role", "VP, Planning", "JOB_TITLE")),
                    List.of(new ExtractedRelation(
                            "person", "role", "OCCUPIES", "Role fact", 0.9, Map.of())),
                    null);

            ValidationResult validation = GraphExtractionValidator.validate(
                    result, GraphExtractionValidationPolicy.defaults(), schema);

            assertFalse(validation.valid());
            assertTrue(validation.errors().stream()
                    .anyMatch(error -> error.contains("[ENTITY_TYPE_SCHEMA]")
                            && error.contains("JOB_TITLE")));
            assertTrue(validation.errors().stream()
                    .anyMatch(error -> error.contains("[RELATION_TYPE_SCHEMA]")
                            && error.contains("OCCUPIES")));
        }

        @Test
        void relationAliasesAreHintsButPersistedRelationsMustUseCanonicalType() {
            GraphSchema schema = new GraphSchema(
                    List.of(
                            new NodeType("PERSON", "A person", null),
                            new NodeType("ROLE", "A role", null)),
                    List.of(new RelationshipType(
                            "HAS_ROLE", "Person has role", null, List.of("serves_as"))),
                    List.of("(PERSON)-[:HAS_ROLE]->(ROLE)"));
            ExtractionResult result = ExtractionResult.of(
                    List.of(
                            entity("person", "M. Chen", "PERSON"),
                            entity("role", "VP, Planning", "ROLE")),
                    List.of(new ExtractedRelation(
                            "person", "role", "SERVES_AS", "Role fact", 0.9, Map.of())),
                    null);

            ValidationResult validation = GraphExtractionValidator.validate(
                    result, GraphExtractionValidationPolicy.defaults(), schema);

            assertFalse(validation.valid());
            assertTrue(validation.errors().stream().anyMatch(error ->
                    error.contains("[RELATION_TYPE_SCHEMA]")
                            && error.contains("use canonical type HAS_ROLE")));
        }

        @Test
        void standardizedSchemaValidatesEntityAndRelationProperties() {
            GraphSchema schema = new GraphSchema(
                    List.of(
                            new NodeType("PERSON", "A person", List.of(
                                    new PropertyType("email", "String"),
                                    new PropertyType("level", "Integer"))),
                            new NodeType("ROLE", "A role", List.of())),
                    List.of(new RelationshipType(
                            "HAS_ROLE", "Person has role",
                            List.of(new PropertyType("primary", "Boolean")))),
                    List.of("(PERSON)-[:HAS_ROLE]->(ROLE)"));
            ExtractedEntity person = new ExtractedEntity(
                    "person", "M. Chen", "PERSON", List.of(), "A person", 0.9,
                    Map.of("email", "m.chen@example.com", "level", "senior", "nickname", "M"));
            ExtractedRelation relation = new ExtractedRelation(
                    "person", "role", "HAS_ROLE", "Role fact", 0.9,
                    Map.of("primary", "sometimes"));

            ValidationResult validation = GraphExtractionValidator.validate(
                    ExtractionResult.of(List.of(person, entity("role", "VP, Planning", "ROLE")),
                            List.of(relation), null),
                    GraphExtractionValidationPolicy.defaults(), schema);

            assertFalse(validation.valid());
            assertTrue(validation.errors().stream().anyMatch(error ->
                    error.contains("[PROPERTY_SCHEMA]") && error.contains("nickname")));
            assertTrue(validation.errors().stream().anyMatch(error ->
                    error.contains("property 'level' must be Integer")));
            assertTrue(validation.errors().stream().anyMatch(error ->
                    error.contains("property 'primary' must be Boolean")));
        }

        @Test
        void emptySchemaVocabularyLeavesDiscoveryOpenAndWarnModeDoesNotDropFacts() {
            GraphSchema openSchema = new GraphSchema(List.of(), List.of(), List.of());
            ExtractionResult result = ExtractionResult.of(
                    List.of(entity("e1", "Emerging concept", "NEW_CONCEPT")), List.of(), null);
            assertTrue(GraphExtractionValidator.validate(
                    result, GraphExtractionValidationPolicy.defaults(), openSchema).valid());

            GraphExtractionValidationPolicy warn = GraphExtractionValidationPolicy.builder()
                    .failureMode(FailureMode.WARN)
                    .build();
            GraphSchema closedSchema = new GraphSchema(
                    List.of(new NodeType("PERSON", "A person", null)), List.of(), List.of());
            ValidationResult warned = GraphExtractionValidator.validate(result, warn, closedSchema);
            assertTrue(warned.valid());
            assertTrue(warned.errors().isEmpty());
            assertTrue(warned.warnings().stream()
                    .anyMatch(error -> error.contains("[ENTITY_TYPE_SCHEMA]")));
        }

        @Test
        void retryModeRejectsSameEntityNameWithConflictingTypes() {
            ExtractionResult result = ExtractionResult.of(
                    List.of(
                            entity("e1", "North Region", "REGION"),
                            entity("e2", " north region ", "BUSINESS_UNIT")),
                    List.of(),
                    null);

            ValidationResult validation = GraphExtractionValidator.validate(
                    result, GraphExtractionValidationPolicy.defaults(), null);

            assertFalse(validation.valid());
            assertTrue(validation.errors().stream()
                    .anyMatch(error -> error.contains("[ENTITY_NAME_TYPE_CONSISTENCY]")));
        }

        @Test
        void warnModeAcceptsSemanticViolationsAndReturnsWarnings() {
            GraphExtractionValidationPolicy policy = GraphExtractionValidationPolicy.builder()
                    .failureMode(FailureMode.WARN)
                    .build();
            ExtractionResult result = ExtractionResult.of(
                    List.of(
                            entity("e1", "Forecast", "REGIONAL_FORECAST"),
                            entity("e2", "Forecast", "SPREADSHEET")),
                    List.of(),
                    null);

            ValidationResult validation = GraphExtractionValidator.validate(result, policy, null);

            assertTrue(validation.valid());
            assertTrue(validation.errors().isEmpty());
            assertTrue(validation.warnings().stream()
                    .anyMatch(warning -> warning.contains("[ENTITY_NAME_TYPE_CONSISTENCY]")));
        }

        @Test
        void disabledModeSkipsSemanticRulesButNeverStructuralRules() {
            ExtractedEntity lowerCaseType = new ExtractedEntity(
                    "e1", "Forecast", "regional_forecast", List.of(), null, 0.9, Map.of());
            ExtractionResult semanticOnly = ExtractionResult.of(
                    List.of(lowerCaseType), List.of(), null);

            assertTrue(GraphExtractionValidator.validate(
                    semanticOnly, GraphExtractionValidationPolicy.disabled(), null).valid());

            ExtractedRelation unknownEndpoint = new ExtractedRelation(
                    "missing", "e1", "REL", "bad endpoint", 0.8, Map.of());
            ExtractionResult structurallyInvalid = ExtractionResult.of(
                    List.of(lowerCaseType), List.of(unknownEndpoint), null);
            assertFalse(GraphExtractionValidator.validate(
                    structurallyInvalid, GraphExtractionValidationPolicy.disabled(), null).valid());
        }

        @Test
        void validatesRelationEndpointTypesAgainstConfiguredSignatures() {
            GraphExtractionValidationPolicy policy = GraphExtractionValidationPolicy.builder()
                    .relationPatterns(List.of("(PERSON)-[:APPROVED_BY]->(CLOSE_STEP)"))
                    .build();
            ExtractedRelation approval = new ExtractedRelation(
                    "person", "target", "APPROVED_BY", "Approval evidence", 0.95, Map.of());

            ExtractionResult wrongTarget = ExtractionResult.of(
                    List.of(
                            entity("person", "Finance Lead", "PERSON"),
                            entity("target", "North Forecast", "REGIONAL_FORECAST")),
                    List.of(approval),
                    null);
            ValidationResult rejected = GraphExtractionValidator.validate(wrongTarget, policy, null);
            assertFalse(rejected.valid());
            assertTrue(rejected.errors().stream()
                    .anyMatch(error -> error.contains("[RELATION_SCHEMA_PATTERN]")));

            ExtractionResult rightTarget = ExtractionResult.of(
                    List.of(
                            entity("person", "Finance Lead", "PERSON"),
                            entity("target", "Review close checklist", "CLOSE_STEP")),
                    List.of(approval),
                    null);
            assertTrue(GraphExtractionValidator.validate(rightTarget, policy, null).valid());
        }

        @Test
        void relationEndpointSignaturesAcceptDeclaredEntitySubtypes() {
            GraphExtractionValidationPolicy policy = GraphExtractionValidationPolicy.builder()
                    .relationPatterns(List.of("(PERSON)-[:APPROVED_BY]->(DOCUMENT)"))
                    .build();
            GraphSchema schema = new GraphSchema(
                    List.of(
                            new NodeType("PERSON", "A person", null),
                            new NodeType("EMPLOYEE", "An employee", null, "PERSON"),
                            new NodeType("DOCUMENT", "A document", null),
                            new NodeType("CONTRACT", "A contract", null, "DOCUMENT")),
                    List.of(new RelationshipType(
                            "APPROVED_BY", "Approval", null, List.of(), "PARTICIPATION")),
                    List.of("(PERSON)-[:APPROVED_BY]->(DOCUMENT)"));
            ExtractionResult subtypeEndpoints = ExtractionResult.of(
                    List.of(
                            entity("employee", "Finance Lead", "EMPLOYEE"),
                            entity("contract", "Supply Agreement", "CONTRACT")),
                    List.of(new ExtractedRelation(
                            "employee", "contract", "APPROVED_BY",
                            "The employee approved the contract", 0.95, Map.of())),
                    null);

            assertTrue(GraphExtractionValidator.validate(
                    subtypeEndpoints, policy, schema).valid());
        }

        @Test
        void rejectsSelfLoopsAndInvalidOrMissingRequiredTimestamps() {
            GraphExtractionValidationPolicy policy = GraphExtractionValidationPolicy.builder()
                    .requiredOccurredAtRelationTypes(List.of("SUBMITTED_BY"))
                    .build();
            List<ExtractedEntity> entities = List.of(
                    entity("forecast", "North Forecast", "REGIONAL_FORECAST"),
                    entity("person", "Finance Lead", "PERSON"));

            ExtractedRelation selfLoop = new ExtractedRelation(
                    "person", "person", "APPROVED_BY", "Self approval", 0.8, Map.of(), null);
            ValidationResult selfLoopResult = GraphExtractionValidator.validate(
                    ExtractionResult.of(entities, List.of(selfLoop), null), policy, null);
            assertFalse(selfLoopResult.valid());
            assertTrue(selfLoopResult.errors().stream()
                    .anyMatch(error -> error.contains("[RELATION_SELF_LOOP]")));

            ExtractedRelation missingTimestamp = new ExtractedRelation(
                    "forecast", "person", "SUBMITTED_BY", "Submission", 0.9, Map.of(), null);
            ValidationResult missingResult = GraphExtractionValidator.validate(
                    ExtractionResult.of(entities, List.of(missingTimestamp), null), policy, null);
            assertTrue(missingResult.errors().stream()
                    .anyMatch(error -> error.contains("[REQUIRED_RELATION_OCCURRED_AT]")));

            ExtractedRelation malformedTimestamp = new ExtractedRelation(
                    "forecast", "person", "SUBMITTED_BY", "Submission", 0.9, Map.of(), "last Tuesday");
            ValidationResult malformedResult = GraphExtractionValidator.validate(
                    ExtractionResult.of(entities, List.of(malformedTimestamp), null), policy, null);
            assertTrue(malformedResult.errors().stream()
                    .anyMatch(error -> error.contains("[OCCURRED_AT_FORMAT]")));

            ExtractedRelation validTimestamp = new ExtractedRelation(
                    "forecast", "person", "SUBMITTED_BY", "Submission", 0.9, Map.of(), "2026-07-19T09:15:00Z");
            assertTrue(GraphExtractionValidator.validate(
                    ExtractionResult.of(entities, List.of(validTimestamp), null), policy, null).valid());

            ExtractedRelation sourceOnlyGaveYear = new ExtractedRelation(
                    "forecast", "person", "SUBMITTED_BY", "Submission", 0.9, Map.of(), "2019");
            ExtractedRelation sourceOnlyGaveMonth = new ExtractedRelation(
                    "forecast", "person", "SUBMITTED_BY", "Submission", 0.9, Map.of(), "2019-05");
            assertTrue(GraphExtractionValidator.validate(
                    ExtractionResult.of(entities,
                            List.of(sourceOnlyGaveYear, sourceOnlyGaveMonth), null),
                    policy, null).valid(),
                    "reduced source precision must be retained rather than inventing January 1");

            ExtractedRelation impossibleMonth = new ExtractedRelation(
                    "forecast", "person", "SUBMITTED_BY", "Submission", 0.9, Map.of(), "2019-13");
            assertFalse(GraphExtractionValidator.validate(
                    ExtractionResult.of(entities, List.of(impossibleMonth), null),
                    policy, null).valid());

            assertTrue(GraphExtractionValidator.isValidOccurredAt("2019"));
            assertTrue(GraphExtractionValidator.isValidOccurredAt("2019-05"));
            assertTrue(GraphExtractionValidator.isValidOccurredAt("2026-07-19T09:15:00Z"));
            assertFalse(GraphExtractionValidator.isValidOccurredAt("now"));
            assertFalse(GraphExtractionValidator.isValidOccurredAt("last Tuesday"));
        }

        @Test
        void unknownValidatorIdsFailClosedInDefaultRetryMode() {
            GraphExtractionValidationPolicy policy = GraphExtractionValidationPolicy.builder()
                    .enabledValidators(List.of("misspelled-validator"))
                    .build();

            ValidationResult validation = GraphExtractionValidator.validate(
                    ExtractionResult.of(List.of(entity("e1", "Forecast", "FORECAST")), List.of(), null),
                    policy,
                    null);

            assertFalse(validation.valid());
            assertTrue(validation.errors().stream()
                    .anyMatch(error -> error.contains("[VALIDATION_CONFIG]")));
        }

        @Test
        void promptRulesAreGeneratedFromTheSamePolicyWithoutExampleFacts() {
            GraphExtractionValidationPolicy policy = GraphExtractionValidationPolicy.builder()
                    .relationPatterns(List.of("(PERSON)-[:APPROVED_BY]->(CLOSE_STEP)"))
                    .requiredOccurredAtRelationTypes(List.of("APPROVED_BY"))
                    .build();
            GraphSchema schema = new GraphSchema(null, null, policy.getRelationPatterns());

            String prompt = GraphExtractionValidator.getExtractionPromptInstructions(policy, schema);

            assertTrue(prompt.contains("(PERSON)-[:APPROVED_BY]->(CLOSE_STEP)"));
            assertTrue(prompt.contains("occurredAt is REQUIRED"));
            assertTrue(prompt.contains("same normalized entity name"));
            assertFalse(prompt.contains("Acme Corp"));
            assertFalse(prompt.contains("John"));
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

    @Test
    void extractionPromptRequiresConfidenceExplicitly() {
        // Both single-chunk and multi-chunk prompts must explicitly require confidence so the
        // calibrated fallback is rarely hit in practice.
        String single = GraphExtractionValidator.getExtractionPromptInstructions();
        assertTrue(single.contains("confidence") && single.contains("MUST"),
                "Single-chunk prompt must explicitly require confidence");

        String multi = GraphExtractionValidator.getMultiChunkExtractionPromptInstructions();
        assertTrue(multi.contains("confidence") && multi.contains("MUST"),
                "Multi-chunk prompt must explicitly require confidence");
    }

    @Test
    void extractionPromptsDoNotContainSemanticExampleFacts() {
        for (String instructions : List.of(
                GraphExtractionValidator.getExtractionPromptInstructions(),
                GraphExtractionValidator.getMultiChunkExtractionPromptInstructions())) {
            assertTrue(instructions.contains("{\"entities\":[],\"relations\":[]}"));
            assertTrue(instructions.contains("never invent facts"));
            assertFalse(instructions.contains("Acme Corp"));
            assertFalse(instructions.contains("CEO employment relationship"));
            assertFalse(instructions.contains("\"founded\": \"2010\""));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private ExtractedEntity entity(String id, String name, String type) {
        return new ExtractedEntity(id, name, type, List.of(), "desc", 0.9, Map.of());
    }
}
