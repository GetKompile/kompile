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
package ai.kompile.core.graphrag;

import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExtractorUtils} — the shared utility used by all
 * {@link DocumentGraphExtractor} implementations.
 */
class ExtractorUtilsTest {

    // ── str() ───────────────────────────────────────────────────────

    @Nested
    class StrMethod {
        @Test
        void returnsNullForNull() {
            assertNull(ExtractorUtils.str(null));
        }

        @Test
        void returnsNullForEmptyString() {
            assertNull(ExtractorUtils.str(""));
        }

        @Test
        void returnsNullForBlankString() {
            assertNull(ExtractorUtils.str("   "));
        }

        @Test
        void trimsWhitespace() {
            assertEquals("hello", ExtractorUtils.str("  hello  "));
        }

        @Test
        void convertsNonStringObjects() {
            assertEquals("42", ExtractorUtils.str(42));
            assertEquals("true", ExtractorUtils.str(true));
        }
    }

    // ── entityId() ──────────────────────────────────────────────────

    @Nested
    class EntityId {
        @Test
        void deterministicForSameInput() {
            String id1 = ExtractorUtils.entityId("person:alice");
            String id2 = ExtractorUtils.entityId("person:alice");
            assertEquals(id1, id2);
        }

        @Test
        void differsByInput() {
            String id1 = ExtractorUtils.entityId("person:alice");
            String id2 = ExtractorUtils.entityId("person:bob");
            assertNotEquals(id1, id2);
        }

        @Test
        void returnsValidUuid() {
            String id = ExtractorUtils.entityId("test");
            assertDoesNotThrow(() -> UUID.fromString(id));
        }
    }

    // ── splitAuthors() ──────────────────────────────────────────────

    @Nested
    class SplitAuthors {
        @Test
        void singleAuthor() {
            assertEquals(List.of("Alice Smith"), ExtractorUtils.splitAuthors("Alice Smith"));
        }

        @Test
        void commaSeparated() {
            assertEquals(List.of("Alice", "Bob"), ExtractorUtils.splitAuthors("Alice, Bob"));
        }

        @Test
        void semicolonSeparated() {
            assertEquals(List.of("Alice", "Bob"), ExtractorUtils.splitAuthors("Alice; Bob"));
        }

        @Test
        void andSeparated() {
            assertEquals(List.of("Alice", "Bob"), ExtractorUtils.splitAuthors("Alice and Bob"));
        }

        @Test
        void ampersandSeparated() {
            assertEquals(List.of("Alice", "Bob"), ExtractorUtils.splitAuthors("Alice & Bob"));
        }

        @Test
        void mixedDelimiters() {
            List<String> result = ExtractorUtils.splitAuthors("Alice, Bob and Charlie; Dan");
            assertEquals(4, result.size());
            assertTrue(result.contains("Alice"));
            assertTrue(result.contains("Bob"));
            assertTrue(result.contains("Charlie"));
            assertTrue(result.contains("Dan"));
        }

        @Test
        void trimsWhitespace() {
            List<String> result = ExtractorUtils.splitAuthors("  Alice  ,  Bob  ");
            assertEquals(2, result.size());
            assertEquals("Alice", result.get(0));
            assertEquals("Bob", result.get(1));
        }

        @Test
        void skipsEmptySegments() {
            List<String> result = ExtractorUtils.splitAuthors(",Alice,,Bob,");
            assertEquals(2, result.size());
        }
    }

    // ── splitKeywords() ─────────────────────────────────────────────

    @Nested
    class SplitKeywords {
        @Test
        void commaSeparated() {
            assertEquals(List.of("java", "python"), ExtractorUtils.splitKeywords("java, python"));
        }

        @Test
        void semicolonSeparated() {
            assertEquals(List.of("java", "python"), ExtractorUtils.splitKeywords("java; python"));
        }

        @Test
        void pipeSeparated() {
            assertEquals(List.of("java", "python"), ExtractorUtils.splitKeywords("java|python"));
        }

        @Test
        void filtersSingleCharEntries() {
            List<String> result = ExtractorUtils.splitKeywords("a, java, b, python");
            assertEquals(2, result.size());
            assertTrue(result.contains("java"));
            assertTrue(result.contains("python"));
        }

        @Test
        void trimsEntries() {
            assertEquals(List.of("machine learning"),
                    ExtractorUtils.splitKeywords("  machine learning  "));
        }
    }

    // ── addEntity() ─────────────────────────────────────────────────

    @Nested
    class AddEntity {
        @Test
        void addsNewEntity() {
            Map<String, ExtractedEntity> index = new LinkedHashMap<>();
            ExtractedEntity entity = entity("e1", "Alice", "PERSON", 0.9);
            ExtractorUtils.addEntity(index, entity);

            assertEquals(1, index.size());
            assertEquals("Alice", index.get("e1").name());
        }

        @Test
        void mergesPropertiesOnDuplicate() {
            Map<String, ExtractedEntity> index = new LinkedHashMap<>();
            ExtractedEntity first = new ExtractedEntity("e1", "Alice", "PERSON",
                    List.of("A. Smith"), "desc", 0.8, Map.of("role", "engineer"));
            ExtractedEntity second = new ExtractedEntity("e1", "Alice B", "PERSON",
                    List.of("Alice Smith"), "a more complete description", 0.7,
                    Map.of("dept", "engineering"));

            ExtractorUtils.addEntity(index, first);
            ExtractorUtils.addEntity(index, second);

            assertEquals(1, index.size());
            ExtractedEntity merged = index.get("e1");
            assertEquals("engineer", merged.properties().get("role"));
            assertEquals("engineering", merged.properties().get("dept"));
            assertEquals(List.of("A. Smith", "Alice Smith", "Alice B"), merged.aliases());
            assertEquals("a more complete description", merged.description());
        }

        @Test
        void retainsExplicitCanonicalAliasAcrossObservations() {
            Map<String, ExtractedEntity> index = new LinkedHashMap<>();
            ExtractorUtils.addEntity(index, new ExtractedEntity(
                    "e1", "Alice", "PERSON", List.of("Alice"), null, 0.8, Map.of()));
            ExtractorUtils.addEntity(index, new ExtractedEntity(
                    "e1", "Alice Smith", "PERSON", List.of("Alice Smith"), null, 0.9, Map.of()));

            assertEquals(List.of("Alice", "Alice Smith"), index.get("e1").aliases());
        }

        @Test
        void takesMaxConfidence() {
            Map<String, ExtractedEntity> index = new LinkedHashMap<>();
            ExtractedEntity first = entity("e1", "Alice", "PERSON", 0.7);
            ExtractedEntity second = entity("e1", "Alice", "PERSON", 0.95);

            ExtractorUtils.addEntity(index, first);
            ExtractorUtils.addEntity(index, second);

            assertEquals(0.95, index.get("e1").confidence());
        }

        @Test
        void keepsFirstEntityNameOnMerge() {
            Map<String, ExtractedEntity> index = new LinkedHashMap<>();
            ExtractedEntity first = entity("e1", "Original Name", "PERSON", 0.8);
            ExtractedEntity second = entity("e1", "Updated Name", "PERSON", 0.9);

            ExtractorUtils.addEntity(index, first);
            ExtractorUtils.addEntity(index, second);

            assertEquals("Original Name", index.get("e1").name());
            assertEquals(List.of("Updated Name"), index.get("e1").aliases(),
                    "alternate names must remain searchable after deterministic batch merging");
        }

        @Test
        void correctedEntityReplacesIdentityWithoutDroppingPriorEvidence() {
            ExtractedEntity retained = new ExtractedEntity(
                    "e1", "Draft Name", "DRAFT", List.of("Old Alias"),
                    "old but much longer incorrect description", 0.6, Map.of("first", "one"));
            ExtractedEntity corrected = new ExtractedEntity(
                    "e1", "Canonical Name", "PERSON", List.of("New Alias"),
                    "corrected description", 0.95, Map.of("second", "two"));

            ExtractedEntity merged = ExtractorUtils.replaceEntity(retained, corrected);

            assertEquals("Canonical Name", merged.name());
            assertEquals("PERSON", merged.type());
            assertEquals(List.of("Old Alias", "New Alias", "Draft Name"), merged.aliases());
            assertEquals("corrected description", merged.description());
            assertEquals("one", merged.properties().get("first"));
            assertEquals("two", merged.properties().get("second"));
            assertEquals(0.95, merged.confidence());
        }

        @Test
        void handlesNullConfidenceDefaultsToCalibratedFallback() {
            // ExtractedEntity compact constructor defaults null confidence to DEFAULT_ENTITY_CONFIDENCE (0.7),
            // not 1.0, so absent LLM confidence stays soft in PSL.
            Map<String, ExtractedEntity> index = new LinkedHashMap<>();
            ExtractedEntity first = new ExtractedEntity("e1", "A", "T", null, null, null, null);
            ExtractedEntity second = new ExtractedEntity("e1", "B", "T", null, null, 0.5, null);

            ExtractorUtils.addEntity(index, first);
            ExtractorUtils.addEntity(index, second);

            // First entity's null confidence became DEFAULT_ENTITY_CONFIDENCE (0.7),
            // max(0.7, 0.5) = 0.7
            assertEquals(GraphExtractionSchema.DEFAULT_ENTITY_CONFIDENCE, index.get("e1").confidence());
        }
    }

    // ── addRelation() ────────────────────────────────────────────────

    @Nested
    class AddRelation {
        @Test
        void mergesDuplicateRelationEvidenceInsteadOfKeepingFirstOnly() {
            Map<String, ExtractedRelation> index = new LinkedHashMap<>();
            ExtractedRelation first = new ExtractedRelation(
                    "e1", "e2", "WORKS_AT", "works there", 0.6,
                    Map.of("sourceChunk", "one"), "2025-01-01T00:00:00Z");
            ExtractedRelation second = new ExtractedRelation(
                    "e1", "e2", "WORKS_AT", "works at the engineering division", 0.9,
                    Map.of("reviewed", "true"), null);

            ExtractorUtils.addRelation(index, first);
            ExtractorUtils.addRelation(index, second);

            assertEquals(1, index.size());
            ExtractedRelation merged = index.values().iterator().next();
            assertEquals("one", merged.properties().get("sourceChunk"));
            assertEquals("true", merged.properties().get("reviewed"));
            assertEquals("works at the engineering division", merged.description());
            assertEquals("2025-01-01T00:00:00Z", merged.occurredAt());
            assertEquals(0.9, merged.confidence());
        }

        @Test
        void correctedRelationRetainsPropertiesAndUsesCorrectedOptionalFields() {
            ExtractedRelation retained = new ExtractedRelation(
                    "e1", "e2", "WORKS_AT", "draft", 0.6,
                    Map.of("first", "one"), "2025-01-01T00:00:00Z");
            ExtractedRelation corrected = new ExtractedRelation(
                    "e1", "e2", "WORKS_AT", "corrected", 0.95,
                    Map.of("second", "two"), "2025-02-01T00:00:00Z");

            ExtractedRelation merged = ExtractorUtils.replaceRelation(retained, corrected);

            assertEquals("corrected", merged.description());
            assertEquals("2025-02-01T00:00:00Z", merged.occurredAt());
            assertEquals("one", merged.properties().get("first"));
            assertEquals("two", merged.properties().get("second"));
            assertEquals(0.95, merged.confidence());
        }
    }

    // ── extractAuthors() ────────────────────────────────────────────

    @Nested
    class ExtractAuthors {
        @Test
        void createsPersonEntityAndRelation() {
            Map<String, ExtractedEntity> entities = new LinkedHashMap<>();
            List<ExtractedRelation> relations = new ArrayList<>();

            ExtractorUtils.extractAuthors("Alice Smith", "doc-1", "My Doc", entities, relations);

            assertEquals(1, entities.size());
            ExtractedEntity person = entities.values().iterator().next();
            assertEquals("Alice Smith", person.name());
            assertEquals("PERSON", person.type());

            assertEquals(1, relations.size());
            assertEquals("AUTHORED_BY", relations.get(0).type());
            assertEquals("doc-1", relations.get(0).source());
        }

        @Test
        void splitsMultipleAuthors() {
            Map<String, ExtractedEntity> entities = new LinkedHashMap<>();
            List<ExtractedRelation> relations = new ArrayList<>();

            ExtractorUtils.extractAuthors("Alice, Bob and Charlie", "doc-1", "Doc", entities, relations);

            assertEquals(3, entities.size());
            assertEquals(3, relations.size());
        }

        @Test
        void deduplicatesSameAuthorByLowercase() {
            Map<String, ExtractedEntity> entities = new LinkedHashMap<>();
            List<ExtractedRelation> relations = new ArrayList<>();

            ExtractorUtils.extractAuthors("Alice", "doc-1", "Doc 1", entities, relations);
            ExtractorUtils.extractAuthors("alice", "doc-2", "Doc 2", entities, relations);

            assertEquals(1, entities.size(), "Same author (case-insensitive) should deduplicate");
            assertEquals(2, relations.size(), "Each doc should have its own AUTHORED_BY relation");
        }
    }

    // ── extractProducer() ───────────────────────────────────────────

    @Nested
    class ExtractProducer {
        @Test
        void createsOrganizationEntityAndRelation() {
            Map<String, ExtractedEntity> entities = new LinkedHashMap<>();
            List<ExtractedRelation> relations = new ArrayList<>();

            ExtractorUtils.extractProducer("Microsoft Word", "doc-1", "My Doc", entities, relations);

            assertEquals(1, entities.size());
            ExtractedEntity org = entities.values().iterator().next();
            assertEquals("Microsoft Word", org.name());
            assertEquals("ORGANIZATION", org.type());

            assertEquals(1, relations.size());
            assertEquals("PRODUCED_BY", relations.get(0).type());
        }
    }

    // ── extractTopics() ─────────────────────────────────────────────

    @Nested
    class ExtractTopics {
        @Test
        void createsTopicEntitiesAndRelations() {
            Map<String, ExtractedEntity> entities = new LinkedHashMap<>();
            List<ExtractedRelation> relations = new ArrayList<>();

            ExtractorUtils.extractTopics("java, machine learning, AI", "doc-1", "Doc", entities, relations);

            assertEquals(3, entities.size());
            assertEquals(3, relations.size());
            assertTrue(relations.stream().allMatch(r -> "HAS_TOPIC".equals(r.type())));
        }

        @Test
        void filtersSingleCharKeywords() {
            Map<String, ExtractedEntity> entities = new LinkedHashMap<>();
            List<ExtractedRelation> relations = new ArrayList<>();

            ExtractorUtils.extractTopics("a, java, b", "doc-1", "Doc", entities, relations);

            assertEquals(1, entities.size());
            assertEquals("java", entities.values().iterator().next().name());
        }
    }

    // ── putIfPresent() / putValueIfPresent() ────────────────────────

    @Nested
    class PutIfPresent {
        @Test
        void addsValueWhenPresent() {
            Map<String, String> props = new LinkedHashMap<>();
            Map<String, Object> meta = Map.of("key", "value");
            ExtractorUtils.putIfPresent(props, "target", meta, "key");
            assertEquals("value", props.get("target"));
        }

        @Test
        void skipsWhenAbsent() {
            Map<String, String> props = new LinkedHashMap<>();
            Map<String, Object> meta = Map.of();
            ExtractorUtils.putIfPresent(props, "target", meta, "missing");
            assertFalse(props.containsKey("target"));
        }

        @Test
        void skipsBlankValues() {
            Map<String, String> props = new LinkedHashMap<>();
            Map<String, Object> meta = Map.of("key", "   ");
            ExtractorUtils.putIfPresent(props, "target", meta, "key");
            assertFalse(props.containsKey("target"));
        }

        @Test
        void putValueIfPresentAddsNumericValue() {
            Map<String, String> props = new LinkedHashMap<>();
            Map<String, Object> meta = Map.of("count", 42);
            ExtractorUtils.putValueIfPresent(props, "count", meta, "count");
            assertEquals("42", props.get("count"));
        }

        @Test
        void putValueIfPresentSkipsNull() {
            Map<String, String> props = new LinkedHashMap<>();
            Map<String, Object> meta = new HashMap<>();
            meta.put("key", null);
            ExtractorUtils.putValueIfPresent(props, "target", meta, "key");
            assertFalse(props.containsKey("target"));
        }
    }

    // ── extractBatch() ──────────────────────────────────────────────

    @Nested
    class ExtractBatch {
        @Test
        void mergesEntitiesAcrossDocuments() {
            DocumentGraphExtractor extractor = new StubExtractor();
            Document doc1 = docWithMeta("id", "1");
            Document doc2 = docWithMeta("id", "2");

            ExtractionResult result = ExtractorUtils.extractBatch(
                    extractor, List.of(doc1, doc2), "test-extractor");

            // StubExtractor always creates the same entity and relation atom,
            // so both documents merge without losing the richer evidence.
            assertEquals(1, result.entities().size());
            assertEquals(1, result.relations().size());
            assertEquals("self from doc 1", result.relations().get(0).description());
            assertEquals("test-extractor", result.metadata().extractionModel());
        }

        @Test
        void emptyDocsListProducesEmptyResult() {
            DocumentGraphExtractor extractor = new StubExtractor();
            ExtractionResult result = ExtractorUtils.extractBatch(
                    extractor, List.of(), "test-extractor");
            assertTrue(result.entities().isEmpty());
            assertTrue(result.relations().isEmpty());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private ExtractedEntity entity(String id, String name, String type, double confidence) {
        return new ExtractedEntity(id, name, type, null, "desc", confidence, Map.of());
    }

    private Document docWithMeta(String key, String value) {
        Document doc = new Document("content");
        doc.getMetadata().put(key, value);
        return doc;
    }

    /**
     * Minimal DocumentGraphExtractor for testing extractBatch.
     * Creates one fixed entity and one relation per document.
     */
    private static class StubExtractor implements DocumentGraphExtractor {
        @Override
        public List<String> supportedDocumentTypes() {
            return List.of("stub");
        }

        @Override
        public boolean canExtract(Document doc) {
            return true;
        }

        @Override
        public ExtractionResult extract(Document doc) {
            String docId = doc.getMetadata().getOrDefault("id", "unknown").toString();
            ExtractedEntity entity = new ExtractedEntity(
                    "stub-entity", "Stub", "STUB_TYPE", null, "stub", 0.9, Map.of());
            ExtractedRelation relation = new ExtractedRelation(
                    "stub-entity", "stub-entity", "SELF_REF",
                    "self from doc " + docId, 0.8, null);
            return ExtractionResult.of(List.of(entity), List.of(relation), null);
        }

        @Override
        public ExtractionResult extractBatch(List<Document> docs) {
            return ExtractorUtils.extractBatch(this, docs, "stub-extractor");
        }
    }
}
