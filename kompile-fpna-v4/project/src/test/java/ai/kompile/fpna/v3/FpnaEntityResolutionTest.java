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
package ai.kompile.fpna.v3;

import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import ai.kompile.knowledgegraph.resolution.EntityMergeResult;
import ai.kompile.knowledgegraph.resolution.EntityResolutionService;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests entity resolution (cross-document deduplication) using FP&A domain data.
 * Validates that entities mentioned across AMER, EMEA, and APAC documents are
 * correctly merged, that corporate suffix stripping works, and that the
 * Levenshtein similarity threshold properly controls merge behavior.
 */
class FpnaEntityResolutionTest {

    private EntityResolutionService resolutionService;

    @BeforeEach
    void setUp() {
        resolutionService = new EntityResolutionService();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CROSS-DOCUMENT ENTITY RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Resolve: Mei Chen mentioned in AMER, EMEA, and process docs merges to one entity")
    void meiChenMergesAcrossDocuments() {
        // Mei Chen appears in 3 different extraction results (one per document)
        ExtractionResult amerResult = ExtractionResult.of(
                List.of(entity("amer-p1", "Mei Chen", "PERSON", "Recipient of AMER forecast")),
                List.of(), null);
        ExtractionResult emeaResult = ExtractionResult.of(
                List.of(entity("emea-p1", "Mei Chen", "PERSON", "Recipient of EMEA forecast")),
                List.of(), null);
        ExtractionResult processResult = ExtractionResult.of(
                List.of(entity("proc-p1", "Mei Chen", "PERSON", "Process approver")),
                List.of(), null);

        ExtractionResult resolved = resolutionService.resolve(
                List.of(amerResult, emeaResult, processResult));

        long meiCount = resolved.entities().stream()
                .filter(e -> e.name().equals("Mei Chen"))
                .count();

        assertEquals(1, meiCount, "Mei Chen should merge to exactly 1 entity across 3 documents");
    }

    @Test
    @DisplayName("Resolve: different persons with different names stay separate")
    void differentPersonsStaySeparate() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        entity("p1", "Sarah Chen", "PERSON", "AMER Sales Ops"),
                        entity("p2", "Mei Chen", "PERSON", "VP FP&A"),
                        entity("p3", "François Vasseur", "PERSON", "EMEA Sales Director"),
                        entity("p4", "J. Park", "PERSON", "Approver")
                ),
                List.of(), null);

        ExtractionResult resolved = resolutionService.resolveSingle(result);

        // Sarah Chen and Mei Chen share "Chen" but are different people
        // The normalized names "sarah chen" and "mei chen" have low similarity
        assertTrue(resolved.entities().size() >= 3,
                "At least 3 distinct persons should remain after resolution");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CORPORATE SUFFIX NORMALIZATION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Normalize: strip Inc, Corp, LLC, Ltd suffixes")
    void corporateSuffixStripping() {
        assertEquals("northstar goods", EntityResolutionService.normalize("Northstar Goods Inc"));
        assertEquals("northstar goods", EntityResolutionService.normalize("Northstar Goods Inc."));
        assertEquals("northstar goods", EntityResolutionService.normalize("Northstar Goods Corp"));
        assertEquals("northstar goods", EntityResolutionService.normalize("Northstar Goods LLC"));
        assertEquals("northstar goods", EntityResolutionService.normalize("Northstar Goods Ltd"));
        assertEquals("northstar goods", EntityResolutionService.normalize("Northstar Goods Ltd."));
        assertEquals("northstar goods", EntityResolutionService.normalize("Northstar Goods Company"));
    }

    @Test
    @DisplayName("Resolve: 'Northstar Goods Inc' and 'Northstar Goods' merge")
    void organizationWithAndWithoutSuffixMerge() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        entity("org1", "Northstar Goods Inc", "ORGANIZATION", "Parent company"),
                        entity("org2", "Northstar Goods", "ORGANIZATION", "Same company, no suffix")
                ),
                List.of(), null);

        ExtractionResult resolved = resolutionService.resolveSingle(result);

        long northstarCount = resolved.entities().stream()
                .filter(e -> EntityResolutionService.normalize(e.name()).equals("northstar goods"))
                .count();

        assertEquals(1, northstarCount,
                "'Northstar Goods Inc' and 'Northstar Goods' should merge to 1 entity");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ALIAS-BASED RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Resolve: entity with aliases merges with matching name")
    void aliasMatchingMerges() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("e1", "Direct-to-Consumer", "CHANNEL_TAXONOMY",
                                List.of("DTC", "ecom", "D2C"), "Primary DTC channel", 0.95, Map.of()),
                        new ExtractedEntity("e2", "DTC", "CHANNEL_TAXONOMY",
                                List.of(), "DTC channel reference", 0.8, Map.of())
                ),
                List.of(), null);

        ExtractionResult resolved = resolutionService.resolveSingle(result);

        long channelCount = resolved.entities().stream()
                .filter(e -> e.type().equals("CHANNEL_TAXONOMY"))
                .count();

        assertEquals(1, channelCount,
                "'Direct-to-Consumer' with alias 'DTC' should merge with standalone 'DTC'");

        // Verify the merged entity retains the alias
        ExtractedEntity mergedChannel = resolved.entities().stream()
                .filter(e -> e.type().equals("CHANNEL_TAXONOMY"))
                .findFirst().orElseThrow();
        assertTrue(mergedChannel.aliases().contains("DTC") || mergedChannel.name().contains("DTC")
                        || mergedChannel.name().contains("Direct-to-Consumer"),
                "Merged entity should retain DTC reference");
    }

    @Test
    @DisplayName("Resolve: EMEA channel mapping — Marketplace → Amazon → DTC Marketplace")
    void emeaChannelMappingResolution() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("ch1", "Amazon", "CHANNEL_TAXONOMY",
                                List.of("Marketplace"), "Amazon marketplace channel", 0.9, Map.of()),
                        new ExtractedEntity("ch2", "Marketplace", "CHANNEL_TAXONOMY",
                                List.of(), "EMEA marketplace term", 0.8, Map.of()),
                        new ExtractedEntity("ch3", "Wholesale", "CHANNEL_TAXONOMY",
                                List.of("Retail", "Distributor"), "Wholesale umbrella channel", 0.9, Map.of())
                ),
                List.of(), null);

        ExtractionResult resolved = resolutionService.resolveSingle(result);

        // Amazon with alias "Marketplace" should merge with standalone "Marketplace"
        long channelCount = resolved.entities().stream()
                .filter(e -> e.type().equals("CHANNEL_TAXONOMY"))
                .count();

        assertEquals(2, channelCount,
                "Amazon/Marketplace should merge to 1, Wholesale stays separate = 2 total");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TYPE-GATED RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Different types never merge even if names match")
    void differentTypesNeverMerge() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        entity("e1", "Target", "ORGANIZATION", "Target retailer"),
                        entity("e2", "Target", "CONCEPT", "Target metric/goal")
                ),
                List.of(), null);

        ExtractionResult resolved = resolutionService.resolveSingle(result);

        assertEquals(2, resolved.entities().size(),
                "'Target' as ORGANIZATION and 'Target' as CONCEPT should not merge");
    }

    @Test
    @DisplayName("PERSON and APPROVAL_ROLE with same name stay separate")
    void personAndRoleStaySeparate() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        entity("e1", "Mei Chen", "PERSON", "Individual named Mei Chen"),
                        entity("e2", "Mei Chen", "APPROVAL_ROLE", "VP FP&A approval role")
                ),
                List.of(), null);

        ExtractionResult resolved = resolutionService.resolveSingle(result);

        assertEquals(2, resolved.entities().size(),
                "PERSON and APPROVAL_ROLE with same name should not merge");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RELATION REMAPPING AFTER MERGE
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Relations remap to canonical entity IDs after merge")
    void relationsRemapAfterMerge() {
        ExtractionResult amerResult = ExtractionResult.of(
                List.of(
                        entity("amer-mei", "Mei Chen", "PERSON", "AMER recipient"),
                        entity("amer-email", "AMER forecast email", "EMAIL_MESSAGE", "AMER email")
                ),
                List.of(new ExtractedRelation("amer-email", "amer-mei", "SENT_TO",
                        "AMER email sent to Mei", 0.95, Map.of())),
                null);

        ExtractionResult emeaResult = ExtractionResult.of(
                List.of(
                        entity("emea-mei", "Mei Chen", "PERSON", "EMEA recipient"),
                        entity("emea-email", "EMEA forecast email", "EMAIL_MESSAGE", "EMEA email")
                ),
                List.of(new ExtractedRelation("emea-email", "emea-mei", "SENT_TO",
                        "EMEA email sent to Mei", 0.95, Map.of())),
                null);

        ExtractionResult resolved = resolutionService.resolve(List.of(amerResult, emeaResult));

        // Mei Chen should merge — both relations should point to the same canonical ID
        assertEquals(1, resolved.entities().stream()
                        .filter(e -> e.name().equals("Mei Chen")).count(),
                "Mei Chen should merge to 1 entity");

        // Both SENT_TO relations should now reference the same Mei Chen ID
        Set<String> sentToTargets = resolved.relations().stream()
                .filter(r -> r.type().equals("SENT_TO"))
                .map(ExtractedRelation::target)
                .collect(Collectors.toSet());

        assertEquals(1, sentToTargets.size(),
                "Both SENT_TO relations should point to the same canonical Mei Chen ID");
    }

    @Test
    @DisplayName("Duplicate relations after remapping are deduplicated")
    void duplicateRelationsDeduplicatedAfterRemap() {
        ExtractionResult chunk1 = ExtractionResult.of(
                List.of(
                        entity("c1-sarah", "Sarah Chen", "PERSON", "AMER manager"),
                        entity("c1-northstar", "Northstar Goods Inc", "ORGANIZATION", "Company")
                ),
                List.of(new ExtractedRelation("c1-sarah", "c1-northstar", "WORKS_AT",
                        "Sarah works at Northstar", 0.9, Map.of())),
                null);

        ExtractionResult chunk2 = ExtractionResult.of(
                List.of(
                        entity("c2-sarah", "Sarah Chen", "PERSON", "Sales Ops Senior Manager"),
                        entity("c2-northstar", "Northstar Goods", "ORGANIZATION", "Same company no suffix")
                ),
                List.of(new ExtractedRelation("c2-sarah", "c2-northstar", "WORKS_AT",
                        "Sarah works at Northstar", 0.9, Map.of())),
                null);

        ExtractionResult resolved = resolutionService.resolve(List.of(chunk1, chunk2));

        long worksAtCount = resolved.relations().stream()
                .filter(r -> r.type().equals("WORKS_AT"))
                .count();

        assertEquals(1, worksAtCount,
                "Duplicate WORKS_AT relation should be deduplicated after entity merge");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LEVENSHTEIN SIMILARITY
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Levenshtein similarity: identical strings = 1.0")
    void levenshteinIdentical() {
        assertEquals(1.0, EntityResolutionService.levenshteinSimilarity("test", "test"));
    }

    @Test
    @DisplayName("Levenshtein similarity: empty string = 0.0")
    void levenshteinEmpty() {
        assertEquals(0.0, EntityResolutionService.levenshteinSimilarity("test", ""));
        assertEquals(0.0, EntityResolutionService.levenshteinSimilarity("", "test"));
    }

    @Test
    @DisplayName("Levenshtein similarity: similar names above threshold")
    void levenshteinSimilarNames() {
        // "northstar goods" vs "northstar good" — 1 char difference in 15 chars = 0.93 similarity
        double sim = EntityResolutionService.levenshteinSimilarity("northstar goods", "northstar good");
        assertTrue(sim >= 0.85, "Single-char typo should be above 0.85 threshold, got " + sim);
    }

    @Test
    @DisplayName("Levenshtein similarity: dissimilar names below threshold")
    void levenshteinDissimilarNames() {
        double sim = EntityResolutionService.levenshteinSimilarity("sarah chen", "mei chen");
        assertTrue(sim < 0.85,
                "'sarah chen' and 'mei chen' should be below 0.85 threshold, got " + sim);
    }

    @Test
    @DisplayName("Custom threshold: stricter threshold prevents fuzzy merges")
    void customSimilarityThreshold() {
        resolutionService.setSimilarityThreshold(0.95);

        ExtractionResult result = ExtractionResult.of(
                List.of(
                        entity("e1", "Northstar Goods", "ORGANIZATION", "Company"),
                        entity("e2", "Northstar Good", "ORGANIZATION", "Typo")
                ),
                List.of(), null);

        ExtractionResult resolved = resolutionService.resolveSingle(result);

        // At 0.95 threshold, a 1-char difference in a 14-char name (0.93 similarity)
        // should NOT merge
        assertEquals(2, resolved.entities().size(),
                "With strict 0.95 threshold, minor typo should not merge");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONFIDENCE PROPAGATION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Merged entity keeps highest confidence")
    void mergedEntityKeepsHighestConfidence() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("e1", "Mei Chen", "PERSON", List.of(),
                                "First mention", 0.8, Map.of()),
                        new ExtractedEntity("e2", "Mei Chen", "PERSON", List.of(),
                                "Second mention with more context", 0.95, Map.of())
                ),
                List.of(), null);

        ExtractionResult resolved = resolutionService.resolveSingle(result);

        ExtractedEntity mei = resolved.entities().stream()
                .filter(e -> e.name().equals("Mei Chen"))
                .findFirst().orElseThrow();

        assertEquals(0.95, mei.confidence(),
                "Merged entity should retain the highest confidence (0.95)");
    }

    @Test
    @DisplayName("Merged entity keeps longer description")
    void mergedEntityKeepsLongerDescription() {
        ExtractionResult result = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("e1", "Mei Chen", "PERSON", List.of(),
                                "VP FP&A", 0.9, Map.of()),
                        new ExtractedEntity("e2", "Mei Chen", "PERSON", List.of(),
                                "VP FP&A at Northstar Goods, responsible for monthly close cycle approval",
                                0.85, Map.of())
                ),
                List.of(), null);

        ExtractionResult resolved = resolutionService.resolveSingle(result);

        ExtractedEntity mei = resolved.entities().stream()
                .filter(e -> e.name().equals("Mei Chen"))
                .findFirst().orElseThrow();

        assertTrue(mei.description().contains("monthly close"),
                "Merged entity should keep the longer, more detailed description");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FP&A BUSINESS SCENARIO: FULL CROSS-DOCUMENT MERGE
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Full FP&A scenario: 3 regional emails + process doc → unified graph")
    void fullFpnaCrossDocumentResolution() {
        // Simulate entities extracted from AMER email
        ExtractionResult amerResult = ExtractionResult.of(
                List.of(
                        entity("a1", "Sarah Chen", "PERSON", "AMER Sales Ops Senior Manager"),
                        entity("a2", "Mei Chen", "PERSON", "Forecast recipient"),
                        entity("a3", "J. Park", "PERSON", "Forecast recipient"),
                        entity("a4", "Northstar Goods Inc", "ORGANIZATION", "Parent company"),
                        entity("a5", "Americas Regional Forecast Q3 2026", "REGIONAL_FORECAST", "AMER regional forecast"),
                        entity("a6", "AMER forecast Q3 submission from Sarah Chen", "EMAIL_MESSAGE", "AMER submission email"),
                        entity("a7", "AMER_Forecast_Q3_v3_FINAL_v2.xlsx", "SPREADSHEET", "AMER forecast file"),
                        entity("a8", "HYD-110", "SKU_MASTER", "Sleep serum new launch")
                ),
                List.of(
                        relation("a6", "a1", "SENT_BY", "Email from Sarah", 0.95),
                        relation("a6", "a2", "SENT_TO", "Email to Mei", 0.95),
                        relation("a6", "a3", "SENT_TO", "Email to J. Park", 0.9),
                        relation("a6", "a7", "HAS_ATTACHMENT", "Forecast attached", 0.95),
                        relation("a1", "a5", "SUBMITTED_BY", "Sarah submitted AMER forecast", 0.9),
                        relation("a5", "a8", "CONTAINS", "Forecast includes HYD-110", 0.85)
                ),
                null);

        // Simulate entities extracted from EMEA email
        ExtractionResult emeaResult = ExtractionResult.of(
                List.of(
                        entity("e1", "François Vasseur", "PERSON", "EMEA Sales Director"),
                        entity("e2", "Mei Chen", "PERSON", "Consolidation recipient"),
                        entity("e3", "Kira O'Donnell", "PERSON", "UK Retail manager"),
                        entity("e4", "Northstar Goods", "ORGANIZATION", "Company reference"),
                        entity("e5", "Europe Middle East Africa Regional Forecast Q3 2026", "REGIONAL_FORECAST", "EMEA regional forecast"),
                        entity("e6", "EMEA forecast Q3 submission from François Vasseur", "EMAIL_MESSAGE", "EMEA submission email"),
                        entity("e7", "EMEA forecast Jun-Aug 2026.xlsx", "SPREADSHEET", "EMEA forecast file")
                ),
                List.of(
                        relation("e6", "e1", "SENT_BY", "Email from Vasseur", 0.95),
                        relation("e6", "e2", "SENT_TO", "Email to Mei", 0.95),
                        relation("e6", "e7", "HAS_ATTACHMENT", "Forecast attached", 0.95),
                        relation("e1", "e5", "SUBMITTED_BY", "Vasseur submitted EMEA forecast", 0.9)
                ),
                null);

        ExtractionResult resolved = resolutionService.resolve(List.of(amerResult, emeaResult));

        // ── Entity count assertions ──
        // Mei Chen: 2 mentions → 1
        assertEquals(1, countByName(resolved, "Mei Chen"),
                "Mei Chen should merge to 1 entity");

        // Northstar Goods Inc + Northstar Goods → 1 (suffix stripped)
        long northstarCount = resolved.entities().stream()
                .filter(e -> e.type().equals("ORGANIZATION") &&
                        EntityResolutionService.normalize(e.name()).equals("northstar goods"))
                .count();
        assertEquals(1, northstarCount,
                "'Northstar Goods Inc' and 'Northstar Goods' should merge to 1");

        // Sarah Chen, François Vasseur, J. Park, Kira O'Donnell should all be separate
        assertEquals(1, countByName(resolved, "Sarah Chen"), "Sarah Chen should appear once");
        assertEquals(1, countByName(resolved, "François Vasseur"), "Vasseur should appear once");

        // Both forecasts stay separate (different regions)
        long forecastCount = resolved.entities().stream()
                .filter(e -> e.type().equals("REGIONAL_FORECAST"))
                .count();
        assertEquals(2, forecastCount, "AMER and EMEA forecasts should remain separate");

        // Both emails stay separate
        long emailCount = resolved.entities().stream()
                .filter(e -> e.type().equals("EMAIL_MESSAGE"))
                .count();
        assertEquals(2, emailCount, "AMER and EMEA emails should remain separate");

        // ── Relation assertions ──
        // Both SENT_TO relations targeting Mei should now point to the same ID
        Set<String> sentToTargets = resolved.relations().stream()
                .filter(r -> r.type().equals("SENT_TO"))
                .map(ExtractedRelation::target)
                .collect(Collectors.toSet());

        // At least the two Mei Chen SENT_TO should share the same target
        // (J. Park SENT_TO is a different target)
        assertTrue(sentToTargets.size() <= 2,
                "SENT_TO targets should converge after Mei Chen merge");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Empty input returns empty result")
    void emptyInputReturnsEmptyResult() {
        ExtractionResult resolved = resolutionService.resolve(List.of());
        assertTrue(resolved.entities().isEmpty());
        assertTrue(resolved.relations().isEmpty());
    }

    @Test
    @DisplayName("Null input returns empty result")
    void nullInputReturnsEmptyResult() {
        ExtractionResult resolved = resolutionService.resolve(null);
        assertTrue(resolved.entities().isEmpty());
        assertTrue(resolved.relations().isEmpty());
    }

    @Test
    @DisplayName("Single result with no duplicates passes through unchanged")
    void singleResultPassesThrough() {
        ExtractionResult input = ExtractionResult.of(
                List.of(entity("e1", "Test Entity", "PERSON", "Test")),
                List.of(), null);

        ExtractionResult resolved = resolutionService.resolveSingle(input);
        assertEquals(1, resolved.entities().size());
        assertEquals("Test Entity", resolved.entities().get(0).name());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private static ExtractedEntity entity(String id, String name, String type, String description) {
        return new ExtractedEntity(id, name, type, List.of(), description, 0.9, Map.of());
    }

    private static ExtractedRelation relation(String source, String target, String type,
                                               String description, double confidence) {
        return new ExtractedRelation(source, target, type, description, confidence, Map.of());
    }

    private long countByName(ExtractionResult result, String name) {
        return result.entities().stream()
                .filter(e -> e.name().equals(name))
                .count();
    }
}
