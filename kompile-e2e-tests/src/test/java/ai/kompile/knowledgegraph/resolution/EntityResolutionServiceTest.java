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

import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EntityResolutionServiceTest {

    private EntityResolutionService service;

    @BeforeEach
    void setUp() {
        service = new EntityResolutionService();
    }

    // ==================== Normalization ====================

    @Test
    void testNormalizeStripsSuffixes() {
        assertEquals("acme", EntityResolutionService.normalize("Acme Corp"));
        assertEquals("acme", EntityResolutionService.normalize("Acme Inc."));
        assertEquals("acme", EntityResolutionService.normalize("Acme Corporation"));
        assertEquals("acme", EntityResolutionService.normalize("ACME Ltd"));
        assertEquals("acme", EntityResolutionService.normalize("Acme LLC"));
    }

    @Test
    void testNormalizeTrimAndLowercase() {
        assertEquals("john doe", EntityResolutionService.normalize("  John  Doe  "));
        assertEquals("test", EntityResolutionService.normalize("TEST"));
    }

    @Test
    void testNormalizeNull() {
        assertEquals("", EntityResolutionService.normalize(null));
    }

    // ==================== Levenshtein Similarity ====================

    @Test
    void testLevenshteinIdentical() {
        assertEquals(1.0, EntityResolutionService.levenshteinSimilarity("hello", "hello"));
    }

    @Test
    void testLevenshteinSimilar() {
        double similarity = EntityResolutionService.levenshteinSimilarity("microsoft", "microsft");
        assertTrue(similarity > 0.85, "Expected > 0.85, got " + similarity);
    }

    @Test
    void testLevenshteinDifferent() {
        double similarity = EntityResolutionService.levenshteinSimilarity("apple", "orange");
        assertTrue(similarity < 0.5, "Expected < 0.5, got " + similarity);
    }

    @Test
    void testLevenshteinEmpty() {
        assertEquals(0.0, EntityResolutionService.levenshteinSimilarity("", "hello"));
        assertEquals(0.0, EntityResolutionService.levenshteinSimilarity("hello", ""));
    }

    // ==================== Entity Merge ====================

    @Test
    void testMergeExactNameMatch() {
        List<ExtractedEntity> entities = List.of(
                new ExtractedEntity("e1", "Acme Corp", "ORGANIZATION", List.of(), "A tech company", 0.9, Map.of()),
                new ExtractedEntity("e2", "Acme Corp", "ORGANIZATION", List.of(), "A software company", 0.95, Map.of())
        );

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(1, result.mergedEntities().size());
        assertEquals("e1", result.idMapping().get("e1"));
        assertEquals("e1", result.idMapping().get("e2"));
        // Should keep the longer description
        assertEquals("A software company", result.mergedEntities().get(0).description());
        // Should keep the higher confidence
        assertEquals(0.95, result.mergedEntities().get(0).confidence());
    }

    @Test
    void testMergeWithSuffixStripping() {
        List<ExtractedEntity> entities = List.of(
                new ExtractedEntity("e1", "Acme Corp", "ORGANIZATION", List.of(), "Entity 1", 0.9, Map.of()),
                new ExtractedEntity("e2", "Acme Inc.", "ORGANIZATION", List.of(), "Entity 2", 0.8, Map.of())
        );

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(1, result.mergedEntities().size());
    }

    @Test
    void testMergeByAliasMatch() {
        List<ExtractedEntity> entities = List.of(
                new ExtractedEntity("e1", "Acme Corporation", "ORGANIZATION",
                        List.of("Acme", "ACME"), "Full name", 0.9, Map.of()),
                new ExtractedEntity("e2", "Acme", "ORGANIZATION",
                        List.of(), "Short name", 0.8, Map.of())
        );

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(1, result.mergedEntities().size());
    }

    @Test
    void testMergeBySimilarity() {
        List<ExtractedEntity> entities = List.of(
                new ExtractedEntity("e1", "Microsoft Corporation", "ORGANIZATION", List.of(), "Tech company", 0.9, Map.of()),
                new ExtractedEntity("e2", "Microsft Corporation", "ORGANIZATION", List.of(), "Typo version", 0.8, Map.of())
        );

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(1, result.mergedEntities().size());
    }

    @Test
    void testNoMergeDifferentTypes() {
        List<ExtractedEntity> entities = List.of(
                new ExtractedEntity("e1", "Apple", "ORGANIZATION", List.of(), "Tech company", 0.9, Map.of()),
                new ExtractedEntity("e2", "Apple", "PRODUCT", List.of(), "A fruit or product", 0.8, Map.of())
        );

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(2, result.mergedEntities().size());
    }

    @Test
    void testNoMergeDissimilarNames() {
        List<ExtractedEntity> entities = List.of(
                new ExtractedEntity("e1", "Acme Corp", "ORGANIZATION", List.of(), "Company A", 0.9, Map.of()),
                new ExtractedEntity("e2", "Global Industries", "ORGANIZATION", List.of(), "Company B", 0.8, Map.of())
        );

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(2, result.mergedEntities().size());
    }

    @Test
    void testMergeAccumulatesAliases() {
        List<ExtractedEntity> entities = List.of(
                new ExtractedEntity("e1", "Acme Corp", "ORGANIZATION", List.of("ACME"), "Company", 0.9, Map.of()),
                new ExtractedEntity("e2", "Acme Inc", "ORGANIZATION", List.of("Acme International"), "Company", 0.8, Map.of())
        );

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(1, result.mergedEntities().size());

        List<String> aliases = result.mergedEntities().get(0).aliases();
        assertTrue(aliases.size() >= 2, "Expected at least 2 aliases, got " + aliases.size());
    }

    @Test
    void testMergeProperties() {
        List<ExtractedEntity> entities = List.of(
                new ExtractedEntity("e1", "Acme", "ORGANIZATION", List.of(), "Company", 0.9,
                        Map.of("industry", "tech", "founded", "2010")),
                new ExtractedEntity("e2", "Acme", "ORGANIZATION", List.of(), "Company", 0.8,
                        Map.of("location", "NYC", "industry", "software"))
        );

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(1, result.mergedEntities().size());

        Map<String, String> props = result.mergedEntities().get(0).properties();
        assertEquals("tech", props.get("industry")); // First one wins
        assertEquals("2010", props.get("founded"));
        assertEquals("NYC", props.get("location"));
    }

    // ==================== Relation Remapping ====================

    @Test
    void testRelationRemapping() {
        Map<String, String> idMapping = Map.of("e1", "e1", "e2", "e1", "e3", "e3");
        List<ExtractedRelation> relations = List.of(
                new ExtractedRelation("e1", "e3", "WORKS_AT", "Works at", 0.9, Map.of()),
                new ExtractedRelation("e2", "e3", "WORKS_AT", "Also works at", 0.8, Map.of())
        );

        List<ExtractedRelation> remapped = service.remapRelations(relations, idMapping);
        // e1->e3 and e2->e3 both become e1->e3, so deduplication should keep only one
        assertEquals(1, remapped.size());
        assertEquals("e1", remapped.get(0).source());
        assertEquals("e3", remapped.get(0).target());
    }

    // ==================== Full Resolution ====================

    @Test
    void testResolveMultipleChunks() {
        ExtractionResult chunk1 = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("c1_e1", "Acme Corp", "ORGANIZATION", List.of("Acme"), "Company", 0.9, Map.of()),
                        new ExtractedEntity("c1_e2", "John Doe", "PERSON", List.of(), "CEO", 0.95, Map.of())
                ),
                List.of(
                        new ExtractedRelation("c1_e1", "c1_e2", "EMPLOYS", "CEO of Acme", 0.85, Map.of())
                ),
                null
        );

        ExtractionResult chunk2 = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("c2_e1", "ACME Corporation", "ORGANIZATION", List.of(), "Tech firm", 0.85, Map.of()),
                        new ExtractedEntity("c2_e2", "Jane Smith", "PERSON", List.of(), "CTO", 0.9, Map.of())
                ),
                List.of(
                        new ExtractedRelation("c2_e1", "c2_e2", "EMPLOYS", "CTO of ACME", 0.8, Map.of())
                ),
                null
        );

        ExtractionResult resolved = service.resolve(List.of(chunk1, chunk2));

        // Acme Corp and ACME Corporation should merge
        long orgCount = resolved.entities().stream()
                .filter(e -> "ORGANIZATION".equals(e.type()))
                .count();
        assertEquals(1, orgCount, "Should have merged the two ORGANIZATION entities");

        // John Doe and Jane Smith should remain separate
        long personCount = resolved.entities().stream()
                .filter(e -> "PERSON".equals(e.type()))
                .count();
        assertEquals(2, personCount, "Should keep both PERSON entities");
    }

    @Test
    void testResolveEmptyInput() {
        ExtractionResult result = service.resolve(List.of());
        assertNotNull(result);
        assertTrue(result.entities().isEmpty());
        assertTrue(result.relations().isEmpty());
    }

    @Test
    void testResolveNullInput() {
        ExtractionResult result = service.resolve(null);
        assertNotNull(result);
        assertTrue(result.entities().isEmpty());
    }

    @Test
    void testCustomSimilarityThreshold() {
        service.setSimilarityThreshold(0.99); // Very strict

        List<ExtractedEntity> entities = List.of(
                new ExtractedEntity("e1", "Microsoft Corp", "ORGANIZATION", List.of(), "Company", 0.9, Map.of()),
                new ExtractedEntity("e2", "Microsft Corp", "ORGANIZATION", List.of(), "Typo", 0.8, Map.of())
        );

        EntityMergeResult result = service.mergeEntities(entities);
        assertEquals(2, result.mergedEntities().size(), "Should NOT merge with strict threshold");
    }
}
