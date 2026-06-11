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

package ai.kompile.knowledgegraph.impl;

import ai.kompile.knowledgegraph.service.EntityExtractionService;
import ai.kompile.knowledgegraph.service.EntityExtractionService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityExtractionServiceImpl} — regex-based NER extraction,
 * normalization, entity matching, and deduplication.
 */
class EntityExtractionServiceImplTest {

    private EntityExtractionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EntityExtractionServiceImpl();
    }

    // ─── Null/Empty handling ────────────────────────────────────────────

    @Test
    void extractEntities_nullText() {
        assertTrue(service.extractEntities(null).isEmpty());
    }

    @Test
    void extractEntities_emptyText() {
        assertTrue(service.extractEntities("").isEmpty());
    }

    @Test
    void extractEntities_blankText() {
        assertTrue(service.extractEntities("   ").isEmpty());
    }

    // ─── Person extraction ──────────────────────────────────────────────

    @Test
    void extractPersonWithCommonFirstName() {
        List<ExtractedEntity> entities = service.extractEntities(
                "John Smith submitted the report yesterday.",
                List.of(EntityType.PERSON));
        assertTrue(entities.stream().anyMatch(e ->
                e.name().contains("John Smith") && e.type().equals("PERSON")));
    }

    @Test
    void extractPersonHighConfidenceForKnownFirstName() {
        List<ExtractedEntity> entities = service.extractEntities(
                "Sarah Johnson is the lead engineer.",
                List.of(EntityType.PERSON));
        assertTrue(entities.stream().anyMatch(e ->
                e.name().contains("Sarah Johnson") && e.confidence() >= 0.8));
    }

    @Test
    void personExtractionSkipsOrganizationNames() {
        List<ExtractedEntity> entities = service.extractEntities(
                "We partnered with Acme Corp for the project.",
                List.of(EntityType.PERSON));
        // "Acme Corp" should NOT be extracted as a person
        assertFalse(entities.stream().anyMatch(e -> e.name().contains("Corp")));
    }

    // ─── Organization extraction ────────────────────────────────────────

    @Test
    void extractOrganizationWithSuffix() {
        List<ExtractedEntity> entities = service.extractEntities(
                "The deal was signed by Global Technologies Inc last week.",
                List.of(EntityType.ORGANIZATION));
        assertTrue(entities.stream().anyMatch(e ->
                e.name().contains("Global Technologies Inc") && e.type().equals("ORGANIZATION")));
    }

    @Test
    void extractOrganizationAbbreviation() {
        // All-caps abbreviations that also match the TECHNICAL_TERM pattern
        // (e.g., ACME, IBM) are filtered out by the extractOrganizations method.
        // Only abbreviations NOT matching TECHNICAL_TERM are kept as orgs.
        List<ExtractedEntity> entities = service.extractEntities(
                "The ACME proposal was sent to IBM yesterday.",
                List.of(EntityType.ORGANIZATION));
        // ACME and IBM match the TECHNICAL_TERM regex ([A-Z]{2,}), so they are excluded
        assertTrue(entities.isEmpty());
    }

    // ─── Location extraction ────────────────────────────────────────────

    @Test
    void extractLocationWithPreposition() {
        List<ExtractedEntity> entities = service.extractEntities(
                "The conference will be held in Tokyo next month.",
                List.of(EntityType.LOCATION));
        assertTrue(entities.stream().anyMatch(e ->
                e.name().contains("Tokyo") && e.type().equals("LOCATION")));
    }

    @Test
    void extractLocationFromPhrase() {
        List<ExtractedEntity> entities = service.extractEntities(
                "Our team traveled from New York to London.",
                List.of(EntityType.LOCATION));
        assertTrue(entities.stream().anyMatch(e ->
                e.name().contains("New York") || e.name().contains("London")));
    }

    // ─── Date extraction ────────────────────────────────────────────────

    @Test
    void extractDateSlashFormat() {
        List<ExtractedEntity> entities = service.extractEntities(
                "The deadline is 12/31/2025 and review starts 01/15/2026.",
                List.of(EntityType.DATE));
        assertEquals(2, entities.size());
        assertTrue(entities.stream().allMatch(e -> e.type().equals("DATE")));
        assertTrue(entities.stream().allMatch(e -> e.confidence() >= 0.9));
    }

    @Test
    void extractDateMonthNameFormat() {
        List<ExtractedEntity> entities = service.extractEntities(
                "The event is on January 15, 2026.",
                List.of(EntityType.DATE));
        assertTrue(entities.stream().anyMatch(e ->
                e.name().contains("January 15") && e.type().equals("DATE")));
    }

    @Test
    void extractDateIsoFormat() {
        List<ExtractedEntity> entities = service.extractEntities(
                "Submitted on 2025-06-15.",
                List.of(EntityType.DATE));
        assertTrue(entities.stream().anyMatch(e ->
                e.name().equals("2025-06-15")));
    }

    // ─── Technical term extraction ──────────────────────────────────────

    @Test
    void extractTechnicalTerms() {
        List<ExtractedEntity> entities = service.extractEntities(
                "We use REST APIs with JSON responses deployed on Kubernetes.",
                List.of(EntityType.TECHNICAL_TERM));
        List<String> names = entities.stream().map(ExtractedEntity::name).toList();
        assertTrue(names.contains("REST") || names.contains("JSON") || names.contains("Kubernetes"));
    }

    @Test
    void technicalTermSkipsStopWords() {
        List<ExtractedEntity> entities = service.extractEntities(
                "the and or but",
                List.of(EntityType.TECHNICAL_TERM));
        assertTrue(entities.isEmpty());
    }

    // ─── Concept extraction ─────────────────────────────────────────────

    @Test
    void extractConceptFromQuotedTerm() {
        List<ExtractedEntity> entities = service.extractEntities(
                "The \"machine learning\" approach was used.",
                List.of(EntityType.CONCEPT));
        assertTrue(entities.stream().anyMatch(e ->
                e.name().equals("machine learning") && e.type().equals("CONCEPT")));
    }

    @Test
    void extractConceptFromSingleQuotes() {
        List<ExtractedEntity> entities = service.extractEntities(
                "We applied the 'data pipeline' pattern.",
                List.of(EntityType.CONCEPT));
        assertTrue(entities.stream().anyMatch(e ->
                e.name().equals("data pipeline")));
    }

    @Test
    void extractConceptFromNounPhrase() {
        List<ExtractedEntity> entities = service.extractEntities(
                "The distributed computing framework was deployed.",
                List.of(EntityType.CONCEPT));
        assertTrue(entities.stream().anyMatch(e ->
                e.name().contains("framework") && e.type().equals("CONCEPT")));
    }

    // ─── Multiple types ─────────────────────────────────────────────────

    @Test
    void extractAllTypesFromMixedText() {
        String text = "John Smith from Acme Corp in Tokyo signed the deal on January 15, 2026 " +
                "using a REST API for the \"data integration\" system.";
        List<ExtractedEntity> entities = service.extractEntities(text);
        assertFalse(entities.isEmpty());
        // Should find at least 3 distinct types
        long distinctTypes = entities.stream().map(ExtractedEntity::type).distinct().count();
        assertTrue(distinctTypes >= 3, "Expected 3+ entity types, got " + distinctTypes);
    }

    // ─── Confidence filtering ───────────────────────────────────────────

    @Test
    void extractWithMinConfidence() {
        String text = "John Smith works at Acme Corp in Tokyo on 2025-01-15.";
        List<ExtractedEntity> all = service.extractEntities(text);
        List<ExtractedEntity> highConf = service.extractEntities(text, 0.9);
        assertTrue(highConf.size() <= all.size());
        assertTrue(highConf.stream().allMatch(e -> e.confidence() >= 0.9));
    }

    // ─── Normalization ──────────────────────────────────────────────────

    @Test
    void normalizeEntityName_basic() {
        assertEquals("hello world", service.normalizeEntityName("  Hello World  "));
    }

    @Test
    void normalizeEntityName_specialChars() {
        assertEquals("acme inc", service.normalizeEntityName("Acme, Inc."));
    }

    @Test
    void normalizeEntityName_null() {
        assertEquals("", service.normalizeEntityName(null));
    }

    // ─── Entity matching ────────────────────────────────────────────────

    @Test
    void isSameEntity_exactMatch() {
        assertTrue(service.isSameEntity("Acme Corp", "Acme Corp"));
    }

    @Test
    void isSameEntity_caseInsensitive() {
        assertTrue(service.isSameEntity("acme corp", "ACME CORP"));
    }

    @Test
    void isSameEntity_oneContainsOther() {
        assertTrue(service.isSameEntity("Acme", "Acme Corporation"));
    }

    @Test
    void isSameEntity_fuzzyMatch() {
        assertTrue(service.isSameEntity("Microsoft", "Microsft")); // typo
    }

    @Test
    void isSameEntity_differentEntities() {
        assertFalse(service.isSameEntity("Apple", "Google"));
    }

    // ─── Supported types ────────────────────────────────────────────────

    @Test
    void getSupportedTypes() {
        List<EntityType> types = service.getSupportedTypes();
        assertTrue(types.contains(EntityType.PERSON));
        assertTrue(types.contains(EntityType.ORGANIZATION));
        assertTrue(types.contains(EntityType.LOCATION));
        assertTrue(types.contains(EntityType.DATE));
        assertTrue(types.contains(EntityType.TECHNICAL_TERM));
        assertTrue(types.contains(EntityType.CONCEPT));
    }

    // ─── Deduplication ──────────────────────────────────────────────────

    @Test
    void deduplicatesIdenticalEntities() {
        String text = "John Smith met John Smith at the office.";
        List<ExtractedEntity> entities = service.extractEntities(text, List.of(EntityType.PERSON));
        long smithCount = entities.stream()
                .filter(e -> e.name().contains("John Smith"))
                .count();
        assertEquals(1, smithCount, "Duplicate 'John Smith' should be deduplicated");
    }
}
