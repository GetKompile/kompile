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

package ai.kompile.knowledgegraph.resolution;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityResolutionService} — cross-chunk entity deduplication,
 * fuzzy name matching, alias resolution, and relation remapping.
 */
class EntityResolutionServiceTest {

    private EntityResolutionService service;

    @BeforeEach
    void setUp() {
        service = new EntityResolutionService();
    }

    private ExtractedEntity entity(String id, String name, String type) {
        return new ExtractedEntity(id, name, type, List.of(), null, 1.0, Map.of());
    }

    private ExtractedEntity entityWithAliases(String id, String name, String type, List<String> aliases) {
        return new ExtractedEntity(id, name, type, aliases, null, 1.0, Map.of());
    }

    private ExtractedEntity entityWithConfidence(String id, String name, String type, double confidence) {
        return new ExtractedEntity(id, name, type, List.of(), null, confidence, Map.of());
    }

    private ExtractedRelation relation(String source, String target, String type) {
        return new ExtractedRelation(source, target, type, null, 1.0, Map.of());
    }

    // ─── Null/Empty handling ────────────────────────────────────────────

    @Test
    void resolve_nullInput() {
        ExtractionResult result = service.resolve(null);
        assertTrue(result.entities().isEmpty());
        assertTrue(result.relations().isEmpty());
    }

    @Test
    void resolve_emptyList() {
        ExtractionResult result = service.resolve(List.of());
        assertTrue(result.entities().isEmpty());
    }

    // ─── Single result passthrough ──────────────────────────────────────

    @Test
    void resolveSingle_noMergeNeeded() {
        ExtractionResult input = ExtractionResult.of(
                List.of(entity("e1", "Apple", "ORG"), entity("e2", "Google", "ORG")),
                List.of(relation("e1", "e2", "COMPETES_WITH")),
                null);

        ExtractionResult resolved = service.resolveSingle(input);
        assertEquals(2, resolved.entities().size());
        assertEquals(1, resolved.relations().size());
    }

    // ─── Exact name deduplication ───────────────────────────────────────

    @Test
    void mergeEntities_exactDuplicate() {
        List<ExtractedEntity> entities = List.of(
                entity("e1", "Apple Inc", "ORG"),
                entity("e2", "Apple Inc", "ORG"));

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(1, result.mergedEntities().size());
        assertEquals("e1", result.idMapping().get("e1"));
        assertEquals("e1", result.idMapping().get("e2"));
    }

    // ─── Suffix stripping ───────────────────────────────────────────────

    @Test
    void normalize_stripsCorporateSuffix() {
        assertEquals("apple", EntityResolutionService.normalize("Apple Inc."));
        assertEquals("google", EntityResolutionService.normalize("Google Corp"));
        assertEquals("amazon", EntityResolutionService.normalize("Amazon LLC"));
    }

    @Test
    void normalize_handlesNull() {
        assertEquals("", EntityResolutionService.normalize(null));
    }

    @Test
    void normalize_collapsesWhitespace() {
        assertEquals("new york", EntityResolutionService.normalize("  New   York  "));
    }

    // ─── Same-type constraint ───────────────────────────────────────────

    @Test
    void mergeEntities_doesNotMergeDifferentTypes() {
        List<ExtractedEntity> entities = List.of(
                entity("e1", "Apple", "ORG"),
                entity("e2", "Apple", "CONCEPT"));

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(2, result.mergedEntities().size());
    }

    // ─── Fuzzy matching ─────────────────────────────────────────────────

    @Test
    void mergeEntities_fuzzyNameMatch() {
        List<ExtractedEntity> entities = List.of(
                entity("e1", "Microsoft Corporation", "ORG"),
                entity("e2", "Microsoft Corp", "ORG"));

        EntityMergeResult result = service.mergeEntities(entities);
        // Both normalize to "microsoft" after suffix stripping
        assertEquals(1, result.mergedEntities().size());
    }

    @Test
    void levenshteinSimilarity_identicalStrings() {
        assertEquals(1.0, EntityResolutionService.levenshteinSimilarity("hello", "hello"));
    }

    @Test
    void levenshteinSimilarity_emptyString() {
        assertEquals(0.0, EntityResolutionService.levenshteinSimilarity("hello", ""));
    }

    @Test
    void levenshteinSimilarity_similarStrings() {
        double sim = EntityResolutionService.levenshteinSimilarity("microsoft", "microsft");
        assertTrue(sim > 0.85, "Expected high similarity, got " + sim);
    }

    @Test
    void levenshteinSimilarity_differentStrings() {
        double sim = EntityResolutionService.levenshteinSimilarity("apple", "google");
        assertTrue(sim < 0.5);
    }

    // ─── Alias matching ─────────────────────────────────────────────────

    @Test
    void mergeEntities_aliasMatch() {
        List<ExtractedEntity> entities = List.of(
                entityWithAliases("e1", "IBM", "ORG", List.of("International Business Machines")),
                entity("e2", "International Business Machines", "ORG"));

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(1, result.mergedEntities().size());
    }

    @Test
    void mergeEntities_reverseAliasMatch() {
        List<ExtractedEntity> entities = List.of(
                entity("e1", "International Business Machines", "ORG"),
                entityWithAliases("e2", "IBM", "ORG", List.of("International Business Machines")));

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(1, result.mergedEntities().size());
    }

    // ─── Confidence merging ─────────────────────────────────────────────

    @Test
    void mergeEntities_keepsHighestConfidence() {
        List<ExtractedEntity> entities = List.of(
                entityWithConfidence("e1", "Apple", "ORG", 0.7),
                entityWithConfidence("e2", "Apple", "ORG", 0.95));

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(1, result.mergedEntities().size());
        assertEquals(0.95, result.mergedEntities().get(0).confidence());
    }

    // ─── Relation remapping ─────────────────────────────────────────────

    @Test
    void remapRelations_updatesSourceAndTarget() {
        Map<String, String> idMapping = Map.of("e1", "e1", "e2", "e1", "e3", "e3");
        List<ExtractedRelation> relations = List.of(
                relation("e2", "e3", "WORKS_AT"));

        List<ExtractedRelation> remapped = service.remapRelations(relations, idMapping);
        assertEquals(1, remapped.size());
        assertEquals("e1", remapped.get(0).source()); // e2 remapped to e1
        assertEquals("e3", remapped.get(0).target());
    }

    @Test
    void remapRelations_deduplicatesAfterRemapping() {
        Map<String, String> idMapping = Map.of("e1", "canonical", "e2", "canonical", "e3", "e3");
        List<ExtractedRelation> relations = List.of(
                relation("e1", "e3", "WORKS_AT"),
                relation("e2", "e3", "WORKS_AT"));

        List<ExtractedRelation> remapped = service.remapRelations(relations, idMapping);
        // Both map to canonical->e3|WORKS_AT, should be deduplicated
        assertEquals(1, remapped.size());
    }

    // ─── Cross-chunk resolution ─────────────────────────────────────────

    @Test
    void resolve_mergesAcrossChunks() {
        ExtractionResult chunk1 = ExtractionResult.of(
                List.of(entity("c1-e1", "Apple Inc", "ORG")),
                List.of(),
                null);
        ExtractionResult chunk2 = ExtractionResult.of(
                List.of(entity("c2-e1", "Apple Corp", "ORG")),
                List.of(),
                null);

        ExtractionResult resolved = service.resolve(List.of(chunk1, chunk2));
        assertEquals(1, resolved.entities().size());
    }

    @Test
    void resolve_mergesRelationsAcrossChunks() {
        ExtractionResult chunk1 = ExtractionResult.of(
                List.of(entity("e1", "Apple", "ORG"), entity("e2", "Tim Cook", "PERSON")),
                List.of(relation("e2", "e1", "CEO_OF")),
                null);
        ExtractionResult chunk2 = ExtractionResult.of(
                List.of(entity("e3", "Apple", "ORG")),
                List.of(),
                null);

        ExtractionResult resolved = service.resolve(List.of(chunk1, chunk2));
        // e3 should merge with e1 — relations should point to canonical IDs
        assertEquals(2, resolved.entities().size()); // Apple + Tim Cook
        assertEquals(1, resolved.relations().size());
    }

    // ─── Similarity threshold ───────────────────────────────────────────

    @Test
    void customThreshold_tighterThresholdPreventsMatch() {
        service.setSimilarityThreshold(0.99);
        List<ExtractedEntity> entities = List.of(
                entity("e1", "Microsft", "ORG"),
                entity("e2", "Microsoft", "ORG"));

        EntityMergeResult result = service.mergeEntities(entities);
        // With 0.99 threshold, these won't match by Levenshtein
        // But they may still match via normalized name if suffix stripping makes them equal
        // "microsft" vs "microsoft" — similarity < 0.99
        assertEquals(2, result.mergedEntities().size());
    }
}
