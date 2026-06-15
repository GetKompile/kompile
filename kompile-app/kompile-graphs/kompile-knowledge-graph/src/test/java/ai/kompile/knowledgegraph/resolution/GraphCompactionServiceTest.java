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
package ai.kompile.knowledgegraph.resolution;

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.resolution.GraphCompactionService.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GraphCompactionService} — graph-level entity resolution
 * with connected-component merging and Senzing-style explainability.
 */
class GraphCompactionServiceTest {

    private KnowledgeGraphService knowledgeGraphService;
    private GraphCompactionService compactionService;

    @BeforeEach
    void setUp() {
        knowledgeGraphService = mock(KnowledgeGraphService.class);
        compactionService = new GraphCompactionService(knowledgeGraphService);
    }

    private void setNodeRepository(GraphNodeRepository nodeRepository) throws Exception {
        java.lang.reflect.Field field = GraphCompactionService.class.getDeclaredField("nodeRepository");
        field.setAccessible(true);
        field.set(compactionService, nodeRepository);
    }

    private GraphNode entityNode(String nodeId, String title, String entityType) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-" + nodeId)
                .title(title)
                .metadataJson("{\"entity_type\":\"" + entityType + "\"}")
                .confidence(0.9)
                .edgeCount(2)
                .childCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private GraphNode entityNodeWithAliases(String nodeId, String title, String entityType,
                                             List<String> aliases) {
        String aliasJson = aliases.stream()
                .map(a -> "\"" + a + "\"")
                .reduce((a, b) -> a + "," + b)
                .map(s -> "[" + s + "]")
                .orElse("[]");
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-" + nodeId)
                .title(title)
                .metadataJson("{\"entity_type\":\"" + entityType + "\",\"aliases\":" + aliasJson + "}")
                .confidence(0.9)
                .edgeCount(2)
                .childCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private GraphNode entityNodeWithMetadata(String nodeId, String title, String metadataJson) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-" + nodeId)
                .title(title)
                .metadataJson(metadataJson)
                .confidence(0.9)
                .edgeCount(2)
                .childCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ─── Normalization ─────────────────────────────────────────────────

    @Test
    void normalize_stripsSuffixes() {
        assertEquals("apple", GraphCompactionService.normalize("Apple Inc."));
        assertEquals("google", GraphCompactionService.normalize("Google Corporation"));
        assertEquals("meta", GraphCompactionService.normalize("Meta LLC"));
    }

    @Test
    void normalize_collapsesWhitespace() {
        assertEquals("new york city", GraphCompactionService.normalize("  New   York   City  "));
    }

    @Test
    void normalize_handlesNull() {
        assertEquals("", GraphCompactionService.normalize(null));
    }

    // ─── Levenshtein ───────────────────────────────────────────────────

    @Test
    void levenshtein_identical() {
        assertEquals(1.0, GraphCompactionService.levenshteinSimilarity("hello", "hello"));
    }

    @Test
    void levenshtein_empty() {
        assertEquals(0.0, GraphCompactionService.levenshteinSimilarity("hello", ""));
    }

    @Test
    void levenshtein_similar() {
        double sim = GraphCompactionService.levenshteinSimilarity("microsoft", "microsft");
        assertTrue(sim > 0.85, "Expected > 0.85, got " + sim);
    }

    @Test
    void levenshtein_different() {
        double sim = GraphCompactionService.levenshteinSimilarity("apple", "google");
        assertTrue(sim < 0.5);
    }

    // ─── Compact — empty/small graphs ──────────────────────────────────

    @Test
    void compact_emptyGraph() {
        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of());
        CompactionResult result = compactionService.compact(new CompactionConfig());
        assertEquals(0, result.entitiesMerged());
        assertEquals(0, result.componentsFound());
    }

    @Test
    void compact_singleEntity() {
        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(entityNode("n1", "Apple", "ORGANIZATION")));
        CompactionResult result = compactionService.compact(new CompactionConfig());
        assertEquals(0, result.entitiesMerged());
    }

    // ─── Compact — merges similar entities ─────────────────────────────

    @Test
    void compact_mergesExactTitleMatch() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of());
        when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

        CompactionResult result = compactionService.compact(new CompactionConfig());
        assertEquals(1, result.entitiesMerged());
        assertEquals(1, result.componentsFound());
        assertEquals(1, result.decisions().size());
    }

    @Test
    void compact_doesNotMergeDifferentTypes() {
        GraphNode n1 = entityNode("n1", "Apple", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple", "PRODUCT");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        CompactionResult result = compactionService.compact(new CompactionConfig());
        assertEquals(0, result.entitiesMerged());
    }

    @Test
    void compact_mergesTransitiveChain() {
        // A matches B, B matches C — all three should merge
        GraphNode n1 = entityNode("n1", "Microsoft Corporation", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Microsoft Corp", "ORGANIZATION");
        GraphNode n3 = entityNode("n3", "Microsoft Co", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2, n3));
        when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

        CompactionResult result = compactionService.compact(new CompactionConfig());
        // All three normalize to "microsoft" after suffix stripping
        assertEquals(2, result.entitiesMerged());
        assertEquals(1, result.componentsFound());
    }

    // ─── Preview — candidates without execution ────────────────────────

    @Test
    void preview_returnsMatchCandidates() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).score() >= 0.85);
        assertEquals("ORGANIZATION", candidates.get(0).entityType());
    }

    @Test
    void preview_noMatchBelowThreshold() {
        GraphNode n1 = entityNode("n1", "Apple", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Google", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertTrue(candidates.isEmpty());
    }

    @Test
    void preview_factSheetScopedUsesRepositoryInsteadOfGlobalGraphSearch() throws Exception {
        GraphNode scopedA = entityNode("scoped-a", "Hydrate Daily Set", "SKU_MASTER");
        GraphNode scopedB = entityNode("scoped-b", "Hydrate Daily Set", "SKU_MASTER");
        GraphNode globalA = entityNode("global-a", "Channel", "APPROVAL_ROLE");
        GraphNode globalB = entityNode("global-b", "Channel", "APPROVAL_ROLE");

        GraphNodeRepository nodeRepository = mock(GraphNodeRepository.class);
        setNodeRepository(nodeRepository);
        when(nodeRepository.findByFactSheetIdAndNodeType(1L, NodeLevel.ENTITY))
                .thenReturn(List.of(scopedA, scopedB));
        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(globalA, globalB));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                1L, CompactionConfig.previewOnly(0.85));

        assertEquals(1, candidates.size());
        assertEquals(Set.of("scoped-a", "scoped-b"),
                Set.of(candidates.get(0).nodeIdA(), candidates.get(0).nodeIdB()));
        verify(nodeRepository).findByFactSheetIdAndNodeType(1L, NodeLevel.ENTITY);
        verify(knowledgeGraphService, never()).searchNodes("", NodeLevel.ENTITY, 100_000);
    }

    // ─── Explain — why / why not ───────────────────────────────────────

    @Test
    void explain_wouldMerge() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");

        when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

        MatchExplanation explanation = compactionService.explain("n1", "n2");
        assertTrue(explanation.wouldMerge());
        assertTrue(explanation.score() >= 0.85);
        assertFalse(explanation.matchReasons().isEmpty());
        assertTrue(explanation.blockers().isEmpty());
    }

    @Test
    void explain_wouldNotMerge_differentType() {
        GraphNode n1 = entityNode("n1", "Apple", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple", "PRODUCT");

        when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

        MatchExplanation explanation = compactionService.explain("n1", "n2");
        assertFalse(explanation.wouldMerge());
        assertFalse(explanation.blockers().isEmpty());
        assertTrue(explanation.blockers().get(0).contains("Different entity types"));
    }

    @Test
    void explain_wouldNotMerge_lowSimilarity() {
        GraphNode n1 = entityNode("n1", "Apple", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Google", "ORGANIZATION");

        when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

        MatchExplanation explanation = compactionService.explain("n1", "n2");
        assertFalse(explanation.wouldMerge());
        assertTrue(explanation.score() < 0.85);
    }

    @Test
    void explain_nodeNotFound() {
        when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.empty());
        when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.empty());

        MatchExplanation explanation = compactionService.explain("n1", "n2");
        assertFalse(explanation.wouldMerge());
        assertTrue(explanation.blockers().get(0).contains("not found"));
    }

    // ─── Alias matching ────────────────────────────────────────────────

    @Test
    void preview_matchesViaAlias() {
        GraphNode n1 = entityNodeWithAliases("n1", "IBM", "ORGANIZATION",
                List.of("International Business Machines"));
        GraphNode n2 = entityNode("n2", "International Business Machines", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).reasons().stream()
                .anyMatch(r -> r.contains("ALIAS")));
    }

    @Test
    void preview_deduplicatesAliasAndTitleBlocks() {
        GraphNode n1 = entityNodeWithAliases("n1", "IBM", "ORGANIZATION",
                List.of("International Business Machines"));
        GraphNode n2 = entityNodeWithAliases("n2", "International Business Machines", "ORGANIZATION",
                List.of("IBM"));

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertEquals(1, candidates.size());
    }

    @Test
    void preview_skipsGenericSpreadsheetValueArtifacts() {
        GraphNode c1 = entityNodeWithMetadata("c1", "123",
                "{\"entity_type\":\"CELL\",\"cell_reference\":\"Sheet1!A1\"}");
        GraphNode c2 = entityNodeWithMetadata("c2", "123",
                "{\"entity_type\":\"CELL\",\"cell_reference\":\"Sheet2!A1\"}");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(c1, c2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertTrue(candidates.isEmpty());
    }

    @Test
    void preview_skipsFpnaStructuralArtifacts() {
        GraphNode h1 = entityNodeWithMetadata("h1", "Channel",
                "{\"entity_type\":\"HEADER_CELL\",\"cell_reference\":\"Assumptions!B2\"}");
        GraphNode h2 = entityNodeWithMetadata("h2", "Channel",
                "{\"entity_type\":\"HEADER_CELL\",\"cell_reference\":\"Forecast!B2\"}");
        GraphNode f1 = entityNodeWithMetadata("f1", "Revenue",
                "{\"entity_type\":\"FORMULA_CELL\",\"cell_reference\":\"Forecast!G12\"}");
        GraphNode f2 = entityNodeWithMetadata("f2", "Revenue",
                "{\"entity_type\":\"FORMULA_CELL\",\"cell_reference\":\"Forecast!G18\"}");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(h1, h2, f1, f2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertTrue(candidates.isEmpty());
    }

    @Test
    void preview_skipsStructuralArtifactsWhenOnlyEntitySubtypePresent() {
        GraphNode c1 = entityNodeWithMetadata("c1", "Summary!B5",
                "{\"entity_subtype\":\"cell\",\"cell_reference\":\"Summary!B5\"}");
        GraphNode c2 = entityNodeWithMetadata("c2", "Summary!B5",
                "{\"entity_subtype\":\"cell\",\"cell_reference\":\"Summary!B5\"}");
        GraphNode f1 = entityNodeWithMetadata("f1", "Group P&L!C16",
                "{\"entity_subtype\":\"formula_cell\",\"cell_reference\":\"Group P&L!C16\"}");
        GraphNode f2 = entityNodeWithMetadata("f2", "Group P&L!C16",
                "{\"entity_subtype\":\"formula_cell\",\"cell_reference\":\"Group P&L!C16\"}");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(c1, c2, f1, f2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertTrue(candidates.isEmpty());
    }

    @Test
    void preview_skipsRemainingCrawlArtifactTypes() {
        GraphNode q1 = entityNode("q1", "Data Quality (1 flags)", "DATA_QUALITY_REPORT");
        GraphNode q2 = entityNode("q2", "Data Quality (1 flags)", "DATA_QUALITY_REPORT");
        GraphNode d1 = entityNode("d1", "Thu May 07 10:13:39 JST 2026", "DATE");
        GraphNode d2 = entityNode("d2", "Thu May 07 10:13:39 JST 2026", "DATE");
        GraphNode l1 = entityNode("l1", "EMEA forecast Jun-Aug 2026.xlsx", "EXTERNAL_LINK");
        GraphNode l2 = entityNode("l2", "EMEA forecast Jun-Aug 2026.xlsx", "EXTERNAL_LINK");
        GraphNode a1 = entityNode("a1", "05b_EMEA forecast Jun-Aug 2026.xlsx", "ATTACHMENT");
        GraphNode a2 = entityNode("a2", "05b_EMEA forecast Jun-Aug 2026.xlsx", "ATTACHMENT");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(q1, q2, d1, d2, l1, l2, a1, a2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertTrue(candidates.isEmpty());
    }

    @Test
    void preview_usesCustomCategoryInsteadOfApprovalRoleFallback() {
        GraphNode n1 = entityNodeWithMetadata("n1", "Channel",
                "{\"entity_type\":\"APPROVAL_ROLE\",\"entity_category\":\"CHANNEL_TAXONOMY\"}");
        GraphNode n2 = entityNodeWithMetadata("n2", "Channel",
                "{\"entity_type\":\"APPROVAL_ROLE\",\"entity_category\":\"CHANNEL_TAXONOMY\"}");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertEquals(1, candidates.size());
        assertEquals("CHANNEL_TAXONOMY", candidates.get(0).entityType());
    }

    @Test
    void preview_usesNestedCustomCategoryForResolutionType() {
        GraphNode n1 = entityNodeWithMetadata("n1", "Gross Margin",
                "{\"entity_type\":\"APPROVAL_ROLE\",\"properties\":{\"custom_category\":\"FREE_CASH_FLOW_MARGIN\"}}");
        GraphNode n2 = entityNodeWithMetadata("n2", "Gross Margin",
                "{\"entity_type\":\"APPROVAL_ROLE\",\"properties\":{\"custom_category\":\"FREE_CASH_FLOW_MARGIN\"}}");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertEquals(1, candidates.size());
        assertEquals("FREE_CASH_FLOW_MARGIN", candidates.get(0).entityType());
    }

    @Test
    void preview_keepsApprovalRoleWhenMetadataHasRoleSignal() {
        GraphNode n1 = entityNodeWithMetadata("n1", "Channel",
                "{\"entity_type\":\"APPROVAL_ROLE\",\"properties\":{\"role\":\"Channel approval owner\"}}");
        GraphNode n2 = entityNodeWithMetadata("n2", "Channel",
                "{\"entity_type\":\"APPROVAL_ROLE\",\"properties\":{\"role\":\"Channel approval owner\"}}");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertEquals(1, candidates.size());
    }

    @Test
    void preview_explicitSkipFlagOverridesStableIdentityAttributes() {
        GraphNode n1 = entityNodeWithMetadata("n1", "Hydrate Daily Set",
                "{\"entity_type\":\"SKU_MASTER\",\"sku_id\":\"HYD-101\",\"skip_entity_resolution\":\"true\"}");
        GraphNode n2 = entityNodeWithMetadata("n2", "Hydrate Daily Set",
                "{\"entity_type\":\"SKU_MASTER\",\"sku_id\":\"HYD-101\",\"skip_entity_resolution\":true}");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertTrue(candidates.isEmpty());
    }

    @Test
    void preview_keepsMisclassifiedStructuralEntityWhenStableBusinessKeyExists() {
        GraphNode n1 = entityNodeWithMetadata("n1", "USD",
                "{\"entity_type\":\"CELL\",\"cell_reference\":\"FX!B4\",\"currency_code\":\"USD\"}");
        GraphNode n2 = entityNodeWithMetadata("n2", "US Dollar",
                "{\"entity_type\":\"CELL\",\"cell_reference\":\"Rates!C7\",\"currency_code\":\"USD\"}");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).reasons().stream()
                .anyMatch(reason -> reason.contains("ATTR_MATCH:currency_code")));
    }

    // ─── Threshold configuration ───────────────────────────────────────

    @Test
    void compact_respectsCustomThreshold() {
        GraphNode n1 = entityNode("n1", "Microsft", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Microsoft", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        // With very high threshold, these should NOT merge
        CompactionResult strict = compactionService.compact(CompactionConfig.withThreshold(0.99));
        assertEquals(0, strict.entitiesMerged());

        // With default threshold, they should merge
        when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());
        CompactionResult lenient = compactionService.compact(CompactionConfig.withThreshold(0.80));
        assertEquals(1, lenient.entitiesMerged());
    }

    // ─── Attribute-behavior scoring ───────────────────────────────────

    @Test
    void extractProperties_parsesFromMetadata() {
        GraphNode node = GraphNode.builder()
                .nodeId("n1")
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-n1")
                .title("Acme Corp")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\",\"properties\":{\"email\":\"info@acme.com\",\"url\":\"https://acme.com\"}}")
                .confidence(0.9)
                .edgeCount(2)
                .childCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Map<String, String> props = compactionService.extractProperties(node);
        assertEquals("info@acme.com", props.get("email"));
        assertEquals("https://acme.com", props.get("url"));
    }

    @Test
    void extractProperties_emptyWhenNoProperties() {
        GraphNode node = entityNode("n1", "Apple", "ORGANIZATION");
        Map<String, String> props = compactionService.extractProperties(node);
        assertTrue(props.isEmpty());
    }

    @Test
    void computeAttributeScore_exclusiveMatchHighScore() {
        Map<String, String> propsA = Map.of("email", "info@apple.com", "country", "USA");
        Map<String, String> propsB = Map.of("email", "info@apple.com", "country", "USA");

        double score = compactionService.computeAttributeScore(propsA, propsB);
        assertEquals(0.95, score, 0.01); // email is EXCLUSIVE (0.95)
    }

    @Test
    void computeAttributeScore_frequentMatchLowScore() {
        Map<String, String> propsA = Map.of("country", "USA");
        Map<String, String> propsB = Map.of("country", "USA");

        double score = compactionService.computeAttributeScore(propsA, propsB);
        assertEquals(0.20, score, 0.01); // country is VERY_FREQUENT (0.20)
    }

    @Test
    void computeAttributeScore_noMatchReturnsZero() {
        Map<String, String> propsA = Map.of("email", "a@example.com");
        Map<String, String> propsB = Map.of("email", "b@example.com");

        double score = compactionService.computeAttributeScore(propsA, propsB);
        assertEquals(0.0, score);
    }

    @Test
    void scoreAttributes_producesReasons() {
        Map<String, String> propsA = Map.of("email", "ceo@acme.com", "ticker", "ACME");
        Map<String, String> propsB = Map.of("email", "ceo@acme.com", "ticker", "ACME");

        List<String> reasons = compactionService.scoreAttributes(propsA, propsB);
        assertEquals(2, reasons.size());
        assertTrue(reasons.stream().anyMatch(r -> r.contains("ATTR_MATCH:email(EXCLUSIVE)")));
        assertTrue(reasons.stream().anyMatch(r -> r.contains("ATTR_MATCH:ticker(EXCLUSIVE)")));
    }

    @Test
    void compact_mergesViaAttributeMatch() {
        // Different titles but same exclusive attribute (email)
        GraphNode n1 = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Acme Industries")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\",\"properties\":{\"email\":\"info@acme.com\"}}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        GraphNode n2 = GraphNode.builder()
                .nodeId("n2").nodeType(NodeLevel.ENTITY).externalId("ext-n2")
                .title("Acme Holdings")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\",\"properties\":{\"email\":\"info@acme.com\"}}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

        // Use threshold low enough that attr matching can trigger
        CompactionResult result = compactionService.compact(CompactionConfig.withThreshold(0.85));
        assertEquals(1, result.entitiesMerged());
        assertTrue(result.decisions().get(0).matchReasons().stream()
                .anyMatch(r -> r.contains("ATTR_MATCH")));
    }

    // ─── Assembly steps (Senzing "How") ───────────────────────────────

    @Test
    void compact_includesAssemblySteps() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of());
        when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

        CompactionResult result = compactionService.compact(new CompactionConfig());
        MergeDecision decision = result.decisions().get(0);

        assertNotNull(decision.assemblySteps());
        assertFalse(decision.assemblySteps().isEmpty());
        // Should have: elected canonical + merge step + final entity
        assertTrue(decision.assemblySteps().size() >= 3);
        assertTrue(decision.assemblySteps().get(0).contains("STEP 1"));
        assertTrue(decision.assemblySteps().get(0).contains("Elected canonical"));
    }

    // ─── CompactionConfig factory methods ──────────────────────────────

    @Test
    void config_withEmbeddings() {
        CompactionConfig config = CompactionConfig.withEmbeddings(0.80, 0.92);
        assertEquals(0.80, config.similarityThreshold());
        assertTrue(config.useEmbeddings());
        assertEquals(0.92, config.embeddingThreshold());
        assertTrue(config.deleteAfterMerge());
    }

    @Test
    void config_withoutEmbeddings() {
        CompactionConfig config = CompactionConfig.withoutEmbeddings(0.85);
        assertEquals(0.85, config.similarityThreshold());
        assertFalse(config.useEmbeddings());
        assertTrue(config.deleteAfterMerge());
    }

    @Test
    void config_defaultHasEmbeddingsEnabled() {
        CompactionConfig config = new CompactionConfig();
        assertTrue(config.useEmbeddings());
        assertEquals(0.88, config.embeddingThreshold(), 0.01);
    }

    // ─── AttributeBehavior map ─────────────────────────────────────────

    @Test
    void attributeBehaviors_containsExpectedKeys() {
        assertTrue(GraphCompactionService.ATTRIBUTE_BEHAVIORS.containsKey("email"));
        assertTrue(GraphCompactionService.ATTRIBUTE_BEHAVIORS.containsKey("url"));
        assertTrue(GraphCompactionService.ATTRIBUTE_BEHAVIORS.containsKey("ticker"));
        assertTrue(GraphCompactionService.ATTRIBUTE_BEHAVIORS.containsKey("country"));

        assertEquals("EXCLUSIVE", GraphCompactionService.ATTRIBUTE_BEHAVIORS.get("email").exclusivity());
        assertEquals("VERY_FREQUENT", GraphCompactionService.ATTRIBUTE_BEHAVIORS.get("country").exclusivity());
        assertTrue(GraphCompactionService.ATTRIBUTE_BEHAVIORS.get("email").weight() >
                GraphCompactionService.ATTRIBUTE_BEHAVIORS.get("country").weight());
    }

    // ─── MergeDecision backwards compatibility ─────────────────────────

    @Test
    void mergeDecision_backwardsCompatibleConstructor() {
        MergeDecision d = new MergeDecision("n1", "Apple", List.of("n2"), 3,
                List.of("EXACT_TITLE_MATCH"), 1.0);
        assertEquals(List.of(), d.assemblySteps());
        assertEquals("n1", d.canonicalNodeId());
    }

    // ═══════════════════════════════════════════════════════════════════
    // ADDITIONAL COVERAGE
    // ═══════════════════════════════════════════════════════════════════

    // ─── Normalization edge cases ─────────────────────────────────────

    @Test
    void normalize_stripsAllSuffixVariants() {
        assertEquals("acme", GraphCompactionService.normalize("Acme Ltd."));
        assertEquals("acme", GraphCompactionService.normalize("Acme Limited"));
        assertEquals("acme", GraphCompactionService.normalize("Acme Co."));
        assertEquals("acme", GraphCompactionService.normalize("Acme Company"));
        assertEquals("acme", GraphCompactionService.normalize("Acme Group"));
        assertEquals("acme", GraphCompactionService.normalize("Acme Plc"));
    }

    @Test
    void normalize_emptyString() {
        assertEquals("", GraphCompactionService.normalize(""));
    }

    @Test
    void normalize_preservesNonSuffixWords() {
        assertEquals("incorporation media", GraphCompactionService.normalize("Incorporation Media"));
    }

    // ─── Levenshtein distance (direct) ────────────────────────────────

    @Test
    void levenshteinDistance_identical() {
        assertEquals(0, GraphCompactionService.levenshteinDistance("abc", "abc"));
    }

    @Test
    void levenshteinDistance_singleEdit() {
        assertEquals(1, GraphCompactionService.levenshteinDistance("cat", "bat"));
        assertEquals(1, GraphCompactionService.levenshteinDistance("cat", "ca"));
        assertEquals(1, GraphCompactionService.levenshteinDistance("cat", "cats"));
    }

    @Test
    void levenshteinDistance_completelyDifferent() {
        assertEquals(3, GraphCompactionService.levenshteinDistance("abc", "xyz"));
    }

    @Test
    void levenshtein_bothEmpty() {
        assertEquals(1.0, GraphCompactionService.levenshteinSimilarity("", ""));
    }

    @Test
    void levenshtein_symmetricResult() {
        double ab = GraphCompactionService.levenshteinSimilarity("microsoft", "microsft");
        double ba = GraphCompactionService.levenshteinSimilarity("microsft", "microsoft");
        assertEquals(ab, ba, 0.0001);
    }

    // ─── Canonical election ───────────────────────────────────────────

    @Test
    void compact_electsHighestConfidenceAsCanonical() {
        GraphNode lowConf = GraphNode.builder()
                .nodeId("low").nodeType(NodeLevel.ENTITY).externalId("ext-low")
                .title("Apple Inc").metadataJson("{\"entity_type\":\"ORGANIZATION\"}")
                .confidence(0.5).edgeCount(10).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        GraphNode highConf = GraphNode.builder()
                .nodeId("high").nodeType(NodeLevel.ENTITY).externalId("ext-high")
                .title("Apple Corp").metadataJson("{\"entity_type\":\"ORGANIZATION\"}")
                .confidence(0.99).edgeCount(1).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(lowConf, highConf));
        when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

        CompactionResult result = compactionService.compact(new CompactionConfig());
        assertEquals(1, result.decisions().size());
        assertEquals("high", result.decisions().get(0).canonicalNodeId());
    }

    @Test
    void compact_electsLongerDescriptionOnTiedConfidence() {
        GraphNode shortDesc = GraphNode.builder()
                .nodeId("short").nodeType(NodeLevel.ENTITY).externalId("ext-short")
                .title("Apple Inc").description("A company")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\"}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        GraphNode longDesc = GraphNode.builder()
                .nodeId("long").nodeType(NodeLevel.ENTITY).externalId("ext-long")
                .title("Apple Corp").description("A multinational technology company based in Cupertino")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\"}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(shortDesc, longDesc));
        when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

        CompactionResult result = compactionService.compact(new CompactionConfig());
        assertEquals("long", result.decisions().get(0).canonicalNodeId());
    }

    // ─── Edge redirection ─────────────────────────────────────────────

    @Test
    void compact_redirectsEdges() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");
        GraphNode other = entityNode("other", "Samsung", "ORGANIZATION");

        // n2 has an edge to "other"
        GraphEdge edge = GraphEdge.builder()
                .edgeId("e1")
                .sourceNode(n2)
                .targetNode(other)
                .edgeType(EdgeType.USER_DEFINED)
                .label("COMPETES_WITH")
                .description("Competes with")
                .metadataJson("{\"source\":\"test\"}")
                .provenance(EdgeProvenance.EXTRACTED.name())
                .factSheetId(42L)
                .weight(1.0)
                .build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of(edge));
        when(knowledgeGraphService.edgeExists("n1", "other",
                EdgeType.USER_DEFINED, "COMPETES_WITH", 42L)).thenReturn(false);

        CompactionResult result = compactionService.compact(new CompactionConfig());
        assertEquals(1, result.edgesRedirected());

        // Verify redirect: create new edge from canonical to other, delete old
        verify(knowledgeGraphService).createEdgeWithMetadata("n1", "other",
                EdgeType.USER_DEFINED, 1.0, "COMPETES_WITH", "Competes with",
                "{\"source\":\"test\"}", EdgeProvenance.EXTRACTED, 42L);
        verify(knowledgeGraphService).deleteEdgesBulk(List.of("e1"));
    }

    @Test
    void compact_skipsSelfLoopOnRedirect() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");

        // n2 has an edge to n1 — redirecting n2→n1 becomes n1→n1 (self-loop)
        GraphEdge selfEdge = GraphEdge.builder()
                .edgeId("e-self")
                .sourceNode(n2)
                .targetNode(n1)
                .edgeType(EdgeType.SHARED_ENTITY)
                .weight(0.5)
                .build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of(selfEdge));

        CompactionResult result = compactionService.compact(new CompactionConfig());
        // Self-loop is deleted, not redirected
        assertEquals(0, result.edgesRedirected());
        verify(knowledgeGraphService).deleteEdgesBulk(List.of("e-self"));
        verify(knowledgeGraphService, never()).createEdge(anyString(), anyString(), any(), anyDouble(), anyString());
        verify(knowledgeGraphService, never()).createEdgeWithMetadata(anyString(), anyString(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void compact_skipsDuplicateEdgeOnRedirect() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");
        GraphNode other = entityNode("other", "Samsung", "ORGANIZATION");

        GraphEdge edge = GraphEdge.builder()
                .edgeId("e1")
                .sourceNode(n2)
                .targetNode(other)
                .edgeType(EdgeType.USER_DEFINED)
                .weight(1.0)
                .build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of(edge));
        // Edge already exists between canonical and target
        when(knowledgeGraphService.edgeExists("n1", "other",
                EdgeType.USER_DEFINED, null, null)).thenReturn(true);

        CompactionResult result = compactionService.compact(new CompactionConfig());
        assertEquals(0, result.edgesRedirected());
        verify(knowledgeGraphService).deleteEdgesBulk(List.of("e1"));
        verify(knowledgeGraphService, never()).createEdge(anyString(), anyString(), any(), anyDouble(), anyString());
        verify(knowledgeGraphService, never()).createEdgeWithMetadata(anyString(), anyString(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void compact_skipsDuplicateEdgeUsingDescriptionWhenLabelMissing() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");
        GraphNode other = entityNode("other", "Tim Cook", "PERSON");

        GraphEdge edge = GraphEdge.builder()
                .edgeId("e-described")
                .sourceNode(n2)
                .targetNode(other)
                .edgeType(EdgeType.USER_DEFINED)
                .description("EMPLOYS")
                .factSheetId(42L)
                .weight(0.75)
                .build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of(edge));
        when(knowledgeGraphService.edgeExists("n1", "other",
                EdgeType.USER_DEFINED, "EMPLOYS", 42L)).thenReturn(true);

        CompactionResult result = compactionService.compact(new CompactionConfig());

        assertEquals(0, result.edgesRedirected());
        verify(knowledgeGraphService).deleteEdgesBulk(List.of("e-described"));
        verify(knowledgeGraphService, never()).createEdgeWithMetadata(anyString(), anyString(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void compact_redirectsEdgeWhenSemanticDuplicateAbsent() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");
        GraphNode other = entityNode("other", "Tim Cook", "PERSON");

        GraphEdge edge = GraphEdge.builder()
                .edgeId("e-distinct")
                .sourceNode(n2)
                .targetNode(other)
                .edgeType(EdgeType.USER_DEFINED)
                .label("FOUNDED_BY")
                .description("Founded by")
                .metadataJson("{\"sourceDocumentId\":\"doc-1\"}")
                .provenance(EdgeProvenance.EXTRACTED.name())
                .factSheetId(42L)
                .weight(0.8)
                .build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of(edge));
        when(knowledgeGraphService.edgeExists("n1", "other",
                EdgeType.USER_DEFINED, "FOUNDED_BY", 42L)).thenReturn(false);

        CompactionResult result = compactionService.compact(new CompactionConfig());

        assertEquals(1, result.edgesRedirected());
        verify(knowledgeGraphService).createEdgeWithMetadata("n1", "other",
                EdgeType.USER_DEFINED, 0.8, "FOUNDED_BY", "Founded by",
                "{\"sourceDocumentId\":\"doc-1\"}", EdgeProvenance.EXTRACTED, 42L);
        verify(knowledgeGraphService).deleteEdgesBulk(List.of("e-distinct"));
    }

    @Test
    void compact_redirectsIncomingEdges() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");
        GraphNode other = entityNode("other", "Tim Cook", "PERSON");

        // other → n2 (incoming to the merged node)
        GraphEdge incomingEdge = GraphEdge.builder()
                .edgeId("e-in")
                .sourceNode(other)
                .targetNode(n2)
                .edgeType(EdgeType.USER_DEFINED)
                .weight(0.8)
                .description("CEO of")
                .build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of(incomingEdge));
        when(knowledgeGraphService.edgeExists("other", "n1",
                EdgeType.USER_DEFINED, "CEO of", null)).thenReturn(false);

        CompactionResult result = compactionService.compact(new CompactionConfig());
        assertEquals(1, result.edgesRedirected());
        // When provenance is null on the source edge, production substitutes EdgeProvenance.EXTRACTED
        verify(knowledgeGraphService).createEdgeWithMetadata("other", "n1",
                EdgeType.USER_DEFINED, 0.8, null, "CEO of", null, EdgeProvenance.EXTRACTED, null);
    }

    @Test
    void compact_collapsesDuplicateEdgesAfterMerge() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        n1.setConfidence(0.95);
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");
        GraphNode other = entityNode("other", "Tim Cook", "PERSON");

        GraphEdge edgeFromMerged = GraphEdge.builder()
                .edgeId("e-from-merged")
                .sourceNode(n2)
                .targetNode(other)
                .edgeType(EdgeType.USER_DEFINED)
                .label("EMPLOYS")
                .description("employs")
                .factSheetId(42L)
                .weight(0.6)
                .build();
        GraphEdge weakerDuplicate = GraphEdge.builder()
                .edgeId("e-weaker")
                .sourceNode(n1)
                .targetNode(other)
                .edgeType(EdgeType.USER_DEFINED)
                .label("EMPLOYS")
                .description("employs")
                .factSheetId(42L)
                .weight(0.4)
                .build();
        GraphEdge strongerDuplicate = GraphEdge.builder()
                .edgeId("e-stronger")
                .sourceNode(n1)
                .targetNode(other)
                .edgeType(EdgeType.USER_DEFINED)
                .label("EMPLOYS")
                .description("employs strongly")
                .factSheetId(42L)
                .weight(0.9)
                .build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of(edgeFromMerged));
        when(knowledgeGraphService.getEdgesForNode("n1")).thenReturn(List.of(weakerDuplicate, strongerDuplicate));
        when(knowledgeGraphService.edgeExists("n1", "other",
                EdgeType.USER_DEFINED, "EMPLOYS", 42L)).thenReturn(false);

        CompactionResult result = compactionService.compact(new CompactionConfig());

        assertEquals(2, result.edgesRedirected());
        // When provenance is null on the source edge, production substitutes EdgeProvenance.EXTRACTED
        verify(knowledgeGraphService).createEdgeWithMetadata("n1", "other",
                EdgeType.USER_DEFINED, 0.6, "EMPLOYS", "employs",
                null, EdgeProvenance.EXTRACTED, 42L);
        verify(knowledgeGraphService).updateEdge("e-stronger", 0.9, "employs strongly");
        verify(knowledgeGraphService).deleteEdgesBulk(List.of("e-from-merged"));
        verify(knowledgeGraphService).deleteEdgesBulk(List.of("e-weaker"));
    }

    @Test
    void compact_deduplicatesCanonicalContainsEdgesAcrossEdgeTypes() {
        GraphNode n1 = entityNode("n1", "Forecast Workbook", "ORGANIZATION");
        n1.setConfidence(0.95);
        GraphNode n2 = entityNode("n2", "Forecast Workbook", "ORGANIZATION");
        n2.setConfidence(0.5);
        GraphNode table = entityNode("table", "Forecast Table", "TABLE");

        GraphEdge canonicalContains = GraphEdge.builder()
                .edgeId("e-contains")
                .sourceNode(n1)
                .targetNode(table)
                .edgeType(EdgeType.CONTAINS)
                .label("CONTAINS")
                .description("CONTAINS")
                .factSheetId(42L)
                .weight(1.0)
                .build();
        GraphEdge userDefinedContains = GraphEdge.builder()
                .edgeId("e-user")
                .sourceNode(n1)
                .targetNode(table)
                .edgeType(EdgeType.USER_DEFINED)
                .label("CONTAINS")
                .description("CONTAINS")
                .factSheetId(42L)
                .weight(0.2)
                .build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of());
        when(knowledgeGraphService.getEdgesForNode("n1"))
                .thenReturn(List.of(canonicalContains, userDefinedContains));

        CompactionResult result = compactionService.compact(new CompactionConfig());

        assertEquals(1, result.edgesRedirected());
        verify(knowledgeGraphService).deleteEdgesBulk(List.of("e-user"));
    }

    @Test
    void compact_deduplicatesContainsEdgesWhenUserDefinedLabelIsOnlyInDescription() {
        GraphNode n1 = entityNode("n1", "Forecast Workbook", "ORGANIZATION");
        n1.setConfidence(0.95);
        GraphNode n2 = entityNode("n2", "Forecast Workbook", "ORGANIZATION");
        n2.setConfidence(0.5);
        GraphNode table = entityNode("table", "Forecast Table", "TABLE");

        GraphEdge canonicalContains = GraphEdge.builder()
                .edgeId("e-contains")
                .sourceNode(n1)
                .targetNode(table)
                .edgeType(EdgeType.CONTAINS)
                .factSheetId(42L)
                .weight(1.0)
                .build();
        GraphEdge userDefinedContains = GraphEdge.builder()
                .edgeId("e-user")
                .sourceNode(n1)
                .targetNode(table)
                .edgeType(EdgeType.USER_DEFINED)
                .description("CONTAINS")
                .factSheetId(42L)
                .weight(0.2)
                .build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of());
        when(knowledgeGraphService.getEdgesForNode("n1"))
                .thenReturn(List.of(canonicalContains, userDefinedContains));

        CompactionResult result = compactionService.compact(new CompactionConfig());

        assertEquals(1, result.edgesRedirected());
        verify(knowledgeGraphService).deleteEdgesBulk(List.of("e-user"));
    }

    @Test
    void compact_doesNotCollapseDistinctUserDefinedRelationWithContainsEdge() {
        GraphNode n1 = entityNode("n1", "Forecast Workbook", "ORGANIZATION");
        n1.setConfidence(0.95);
        GraphNode n2 = entityNode("n2", "Forecast Workbook", "ORGANIZATION");
        n2.setConfidence(0.5);
        GraphNode table = entityNode("table", "Forecast Table", "TABLE");

        GraphEdge contains = GraphEdge.builder()
                .edgeId("e-contains")
                .sourceNode(n1)
                .targetNode(table)
                .edgeType(EdgeType.CONTAINS)
                .label("CONTAINS")
                .factSheetId(42L)
                .weight(1.0)
                .build();
        GraphEdge headerOf = GraphEdge.builder()
                .edgeId("e-header")
                .sourceNode(n1)
                .targetNode(table)
                .edgeType(EdgeType.USER_DEFINED)
                .label("HEADER_OF")
                .factSheetId(42L)
                .weight(0.8)
                .build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of());
        when(knowledgeGraphService.getEdgesForNode("n1"))
                .thenReturn(List.of(contains, headerOf));

        CompactionResult result = compactionService.compact(new CompactionConfig());

        assertEquals(0, result.edgesRedirected());
        verify(knowledgeGraphService, never()).deleteEdgesBulk(anyList());
    }

    // ─── Merge metadata ───────────────────────────────────────────────

    @Test
    void compact_mergesLongerDescription() {
        GraphNode n1 = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Apple Inc").description("Short")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\"}")
                .confidence(0.95).edgeCount(5).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        GraphNode n2 = GraphNode.builder()
                .nodeId("n2").nodeType(NodeLevel.ENTITY).externalId("ext-n2")
                .title("Apple Corp")
                .description("Apple is a multinational tech company headquartered in Cupertino, California")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\"}")
                .confidence(0.5).edgeCount(1).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of());

        compactionService.compact(new CompactionConfig());

        // updateNode called with the longer description from n2
        verify(knowledgeGraphService).updateNode(eq("n1"), isNull(),
                eq("Apple is a multinational tech company headquartered in Cupertino, California"),
                any(Map.class));
    }

    @Test
    void compact_deleteAfterMergeFalse_keepsNodes() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of());

        CompactionConfig config = new CompactionConfig(0.85, false);
        CompactionResult result = compactionService.compact(config);
        assertEquals(1, result.entitiesMerged());
        verify(knowledgeGraphService, never()).deleteNode(anyString());
    }

    // ─── Multiple independent components ──────────────────────────────

    @Test
    void compact_multipleComponents() {
        GraphNode a1 = entityNode("a1", "Apple Inc", "ORGANIZATION");
        GraphNode a2 = entityNode("a2", "Apple Corp", "ORGANIZATION");
        GraphNode g1 = entityNode("g1", "Google LLC", "ORGANIZATION");
        GraphNode g2 = entityNode("g2", "Google Ltd", "ORGANIZATION");
        GraphNode unrelated = entityNode("u", "Samsung", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(a1, a2, g1, g2, unrelated));
        when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

        CompactionResult result = compactionService.compact(new CompactionConfig());
        assertEquals(2, result.componentsFound());
        assertEquals(2, result.entitiesMerged());
        assertEquals(3, result.finalEntityCount()); // 5 - 2
    }

    // ─── Extract entity type fallbacks ────────────────────────────────

    @Test
    void compact_fallsBackToNodeTypeWhenNoEntityTypeInMetadata() {
        GraphNode n1 = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Apple Inc").metadataJson("{}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        GraphNode n2 = GraphNode.builder()
                .nodeId("n2").nodeType(NodeLevel.ENTITY).externalId("ext-n2")
                .title("Apple Corp").metadataJson("{}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

        // Both fall back to "ENTITY" type — same block — should match
        CompactionResult result = compactionService.compact(new CompactionConfig());
        assertEquals(1, result.entitiesMerged());
    }

    @Test
    void compact_nullMetadataJsonFallsBackToNodeType() {
        GraphNode n1 = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Apple Inc")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        GraphNode n2 = GraphNode.builder()
                .nodeId("n2").nodeType(NodeLevel.ENTITY).externalId("ext-n2")
                .title("Apple Corp")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

        CompactionResult result = compactionService.compact(new CompactionConfig());
        assertEquals(1, result.entitiesMerged());
    }

    // ─── Extract aliases edge cases ───────────────────────────────────

    @Test
    void preview_malformedAliasJsonDoesNotThrow() {
        GraphNode n1 = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Apple Inc")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\",\"aliases\":\"not-a-list\"}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        // Should not throw, just ignore bad aliases
        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertNotNull(candidates);
    }

    @Test
    void preview_sharedAliases() {
        GraphNode n1 = entityNodeWithAliases("n1", "JPM", "ORGANIZATION",
                List.of("JP Morgan", "JPMorgan Chase"));
        GraphNode n2 = entityNodeWithAliases("n2", "JP Morgan Chase", "ORGANIZATION",
                List.of("JPMorgan Chase", "J.P. Morgan"));

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).reasons().stream()
                .anyMatch(r -> r.contains("SHARED_ALIASES")));
    }

    @Test
    void preview_reverseAliasMatch() {
        // n2's title is in n1's aliases (reverse of the existing alias test)
        GraphNode n1 = entityNode("n1", "Google", "ORGANIZATION");
        GraphNode n2 = entityNodeWithAliases("n2", "Alphabet", "ORGANIZATION",
                List.of("Google"));

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).reasons().stream()
                .anyMatch(r -> r.contains("TITLE_IN_ALIAS")));
    }

    // ─── Extract properties edge cases ────────────────────────────────

    @Test
    void extractProperties_malformedJsonReturnsEmpty() {
        GraphNode node = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Test").metadataJson("{bad json")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        Map<String, String> props = compactionService.extractProperties(node);
        assertTrue(props.isEmpty());
    }

    @Test
    void extractProperties_nullMetadataReturnsEmpty() {
        GraphNode node = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Test")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        Map<String, String> props = compactionService.extractProperties(node);
        assertTrue(props.isEmpty());
    }

    @Test
    void extractProperties_nonStringValuesSkipped() {
        GraphNode node = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Test")
                .metadataJson("{\"entity_type\":\"ORG\",\"properties\":{\"name\":\"Acme\",\"count\":42}}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        Map<String, String> props = compactionService.extractProperties(node);
        assertEquals(1, props.size());
        assertEquals("Acme", props.get("name"));
    }

    @Test
    void extractProperties_keysAreLowercased() {
        GraphNode node = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Test")
                .metadataJson("{\"entity_type\":\"ORG\",\"properties\":{\"Email\":\"test@test.com\",\"URL\":\"https://x.com\"}}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        Map<String, String> props = compactionService.extractProperties(node);
        assertEquals("test@test.com", props.get("email"));
        assertEquals("https://x.com", props.get("url"));
    }

    @Test
    void extractProperties_readsKnownTopLevelAttributes() {
        GraphNode node = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Acme Corp")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\",\"email\":\"info@acme.com\",\"ticker\":\"ACME\"}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        Map<String, String> props = compactionService.extractProperties(node);

        assertEquals("info@acme.com", props.get("email"));
        assertEquals("ACME", props.get("ticker"));
    }

    @Test
    void extractProperties_readsFpnaStableIdentityAttributes() {
        GraphNode node = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Hydrate Daily Set")
                .metadataJson("{\"entity_type\":\"SKU_MASTER\",\"sku_id\":\"HYD-101\"," +
                        "\"currency_code\":\"USD\",\"properties\":{\"forecast_id\":\"FC-AMER-HYD-101\"}}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        Map<String, String> props = compactionService.extractProperties(node);

        assertEquals("HYD-101", props.get("sku_id"));
        assertEquals("USD", props.get("currency_code"));
        assertEquals("FC-AMER-HYD-101", props.get("forecast_id"));
    }

    @Test
    void compact_mergesViaTopLevelExclusiveAttribute() {
        GraphNode n1 = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Acme North America")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\",\"website\":\"https://acme.example\"}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        GraphNode n2 = GraphNode.builder()
                .nodeId("n2").nodeType(NodeLevel.ENTITY).externalId("ext-n2")
                .title("Acme Global")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\",\"website\":\"https://acme.example\"}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of());

        CompactionResult result = compactionService.compact(new CompactionConfig());

        assertEquals(1, result.entitiesMerged());
        assertTrue(result.decisions().get(0).matchReasons().stream()
                .anyMatch(r -> r.contains("ATTR_MATCH:website(EXCLUSIVE)")));
    }

    @Test
    void compact_mergesViaFpnaStableIdentityAttribute() {
        GraphNode n1 = entityNodeWithMetadata("n1", "Hydrate Daily Set",
                "{\"entity_type\":\"SKU_MASTER\",\"sku_id\":\"HYD-101\"}");
        GraphNode n2 = entityNodeWithMetadata("n2", "HYD-101 Hydrate Daily Set",
                "{\"entity_type\":\"SKU_MASTER\",\"sku_id\":\"HYD-101\"}");
        n1.setConfidence(0.95);
        n2.setConfidence(0.5);

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of());
        when(knowledgeGraphService.getEdgesForNode("n1")).thenReturn(List.of());

        CompactionResult result = compactionService.compact(new CompactionConfig());

        assertEquals(1, result.entitiesMerged());
        assertTrue(result.decisions().get(0).matchReasons().stream()
                .anyMatch(r -> r.contains("ATTR_MATCH:sku_id")));
    }

    // ─── Attribute scoring edge cases ─────────────────────────────────

    @Test
    void computeAttributeScore_unknownKeyUsesDefaultWeight() {
        Map<String, String> propsA = Map.of("custom_id", "XYZ-123");
        Map<String, String> propsB = Map.of("custom_id", "XYZ-123");

        double score = compactionService.computeAttributeScore(propsA, propsB);
        assertEquals(0.40, score, 0.01); // default weight for unknown keys
    }

    @Test
    void computeAttributeScore_caseInsensitiveMatch() {
        Map<String, String> propsA = Map.of("email", "Info@ACME.com");
        Map<String, String> propsB = Map.of("email", "info@acme.COM");

        double score = compactionService.computeAttributeScore(propsA, propsB);
        assertEquals(0.95, score, 0.01);
    }

    @Test
    void computeAttributeScore_disjointKeysReturnsZero() {
        Map<String, String> propsA = Map.of("email", "test@test.com");
        Map<String, String> propsB = Map.of("phone", "+1-555-1234");

        double score = compactionService.computeAttributeScore(propsA, propsB);
        assertEquals(0.0, score);
    }

    @Test
    void computeAttributeScore_multipleMatchesTakesHighest() {
        Map<String, String> propsA = Map.of("country", "USA", "email", "a@b.com");
        Map<String, String> propsB = Map.of("country", "USA", "email", "a@b.com");

        double score = compactionService.computeAttributeScore(propsA, propsB);
        assertEquals(0.95, score, 0.01); // email (0.95) > country (0.20)
    }

    @Test
    void scoreAttributes_unknownKeyLabelsAsUnknown() {
        Map<String, String> propsA = Map.of("custom_id", "ABC");
        Map<String, String> propsB = Map.of("custom_id", "ABC");

        List<String> reasons = compactionService.scoreAttributes(propsA, propsB);
        assertEquals(1, reasons.size());
        assertTrue(reasons.get(0).contains("UNKNOWN"));
    }

    @Test
    void scoreAttributes_differentValueProducesNoReason() {
        Map<String, String> propsA = Map.of("email", "a@test.com");
        Map<String, String> propsB = Map.of("email", "b@test.com");

        List<String> reasons = compactionService.scoreAttributes(propsA, propsB);
        assertTrue(reasons.isEmpty());
    }

    // ─── Explain with aliases ─────────────────────────────────────────

    @Test
    void explain_aliasMatchBoostsScore() {
        GraphNode n1 = entityNodeWithAliases("n1", "IBM", "ORGANIZATION",
                List.of("International Business Machines"));
        GraphNode n2 = entityNode("n2", "International Business Machines", "ORGANIZATION");

        when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

        MatchExplanation ex = compactionService.explain("n1", "n2");
        // Score is boosted by alias match, but Levenshtein blocker remains
        assertTrue(ex.score() >= 0.95);
        assertTrue(ex.matchReasons().stream().anyMatch(r -> r.contains("alias")));
    }

    @Test
    void explain_aliasMatchOnSimilarTitles() {
        // Titles that normalize identically (both → "apple") so no Levenshtein blocker,
        // plus alias match for extra verification
        GraphNode n1 = entityNodeWithAliases("n1", "Apple Inc", "ORGANIZATION",
                List.of("Apple Corporation"));
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");

        when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

        MatchExplanation ex = compactionService.explain("n1", "n2");
        assertTrue(ex.wouldMerge());
        assertTrue(ex.score() >= 1.0);
    }

    @Test
    void explain_sharedAliasesReported() {
        GraphNode n1 = entityNodeWithAliases("n1", "JPM", "ORGANIZATION",
                List.of("JPMorgan Chase"));
        GraphNode n2 = entityNodeWithAliases("n2", "JP Morgan", "ORGANIZATION",
                List.of("JPMorgan Chase"));

        when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

        MatchExplanation ex = compactionService.explain("n1", "n2");
        // Shared aliases reported as reason; score boosted
        assertTrue(ex.score() >= 0.9);
        assertTrue(ex.matchReasons().stream().anyMatch(r -> r.contains("Shared aliases")));
    }

    // ─── Explain with attributes ──────────────────────────────────────

    @Test
    void explain_attributeMatchReported() {
        GraphNode n1 = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Acme Industries")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\",\"properties\":{\"email\":\"info@acme.com\"}}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        GraphNode n2 = GraphNode.builder()
                .nodeId("n2").nodeType(NodeLevel.ENTITY).externalId("ext-n2")
                .title("Acme Holdings")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\",\"properties\":{\"email\":\"info@acme.com\"}}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

        MatchExplanation ex = compactionService.explain("n1", "n2");
        // Attribute match reason is reported; score boosted by email attribute
        assertTrue(ex.score() >= 0.95);
        assertTrue(ex.matchReasons().stream().anyMatch(r -> r.contains("ATTR_MATCH")));
    }

    @Test
    void explain_attributeMatchWouldMerge_similarTitles() {
        // Titles similar enough to avoid Levenshtein blocker
        GraphNode n1 = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                .title("Acme Inc")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\",\"properties\":{\"email\":\"info@acme.com\"}}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        GraphNode n2 = GraphNode.builder()
                .nodeId("n2").nodeType(NodeLevel.ENTITY).externalId("ext-n2")
                .title("Acme Corp")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\",\"properties\":{\"email\":\"info@acme.com\"}}")
                .confidence(0.9).edgeCount(2).childCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

        MatchExplanation ex = compactionService.explain("n1", "n2");
        assertTrue(ex.wouldMerge());
        assertTrue(ex.matchReasons().stream().anyMatch(r -> r.contains("ATTR_MATCH")));
    }

    // ─── Assembly steps detailed content ──────────────────────────────

    @Test
    void compact_assemblyStepsTransitiveChain() {
        GraphNode n1 = entityNode("n1", "Microsoft Corporation", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Microsoft Corp", "ORGANIZATION");
        GraphNode n3 = entityNode("n3", "Microsoft Co", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2, n3));
        when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

        CompactionResult result = compactionService.compact(new CompactionConfig());
        MergeDecision decision = result.decisions().get(0);

        // 1 canonical election + 2 merge steps + 1 final
        assertEquals(4, decision.assemblySteps().size());
        assertTrue(decision.assemblySteps().get(0).contains("Elected canonical"));
        assertTrue(decision.assemblySteps().get(1).contains("STEP 2"));
        assertTrue(decision.assemblySteps().get(1).contains("Merged"));
        assertTrue(decision.assemblySteps().get(2).contains("STEP 3"));
        assertTrue(decision.assemblySteps().get(3).contains("Final entity"));
        assertTrue(decision.assemblySteps().get(3).contains("2 merged aliases"));
    }

    @Test
    void compact_assemblyStepContainsScoreAndSignals() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

        CompactionResult result = compactionService.compact(new CompactionConfig());
        MergeDecision decision = result.decisions().get(0);

        // merge step should contain score and signal name
        String mergeStep = decision.assemblySteps().get(1);
        assertTrue(mergeStep.contains("score="));
        assertTrue(mergeStep.contains("EXACT_TITLE_MATCH"));
        assertTrue(mergeStep.contains("edges redirected"));
    }

    // ─── Preview edge cases ───────────────────────────────────────────

    @Test
    void preview_singleEntityReturnsEmpty() {
        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(entityNode("n1", "Apple", "ORGANIZATION")));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertTrue(candidates.isEmpty());
    }

    @Test
    void preview_emptyGraphReturnsEmpty() {
        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of());

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        assertTrue(candidates.isEmpty());
    }

    @Test
    void preview_multipleCandidatesReturned() {
        GraphNode a1 = entityNode("a1", "Apple Inc", "ORGANIZATION");
        GraphNode a2 = entityNode("a2", "Apple Corp", "ORGANIZATION");
        GraphNode a3 = entityNode("a3", "Apple Company", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(a1, a2, a3));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.85));
        // All three normalize to "apple" — 3 pairs: (a1,a2), (a1,a3), (a2,a3)
        assertEquals(3, candidates.size());
    }

    @Test
    void preview_levenshteinCandidateHasReason() {
        GraphNode n1 = entityNode("n1", "Microsft", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Microsoft", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));

        List<MatchCandidate> candidates = compactionService.previewCandidates(
                CompactionConfig.previewOnly(0.80));
        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).reasons().stream()
                .anyMatch(r -> r.contains("LEVENSHTEIN")));
    }

    // ─── CompactionResult.empty() ─────────────────────────────────────

    @Test
    void compactionResult_emptyHasZeroValues() {
        CompactionResult empty = CompactionResult.empty();
        assertEquals(0, empty.originalEntityCount());
        assertEquals(0, empty.finalEntityCount());
        assertEquals(0, empty.entitiesMerged());
        assertEquals(0, empty.edgesRedirected());
        assertEquals(0, empty.componentsFound());
        assertTrue(empty.decisions().isEmpty());
        assertEquals(0, empty.elapsedMs());
    }

    // ─── Compact with withoutEmbeddings config ────────────────────────

    @Test
    void compact_withoutEmbeddingsConfig() {
        GraphNode n1 = entityNode("n1", "Apple Inc", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Apple Corp", "ORGANIZATION");

        when(knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

        // Should still merge via title matching, just without embedding signal
        CompactionResult result = compactionService.compact(CompactionConfig.withoutEmbeddings(0.85));
        assertEquals(1, result.entitiesMerged());
    }

    // ─── Explain one node found, other not ────────────────────────────

    @Test
    void explain_oneNodeMissing() {
        GraphNode n1 = entityNode("n1", "Apple", "ORGANIZATION");
        when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.empty());

        MatchExplanation ex = compactionService.explain("n1", "n2");
        assertFalse(ex.wouldMerge());
        assertEquals(0.0, ex.score());
        assertTrue(ex.blockers().get(0).contains("not found"));
    }

    // ─── Explain Levenshtein match ────────────────────────────────────

    @Test
    void explain_levenshteinMatch() {
        GraphNode n1 = entityNode("n1", "Microsft", "ORGANIZATION");
        GraphNode n2 = entityNode("n2", "Microsoft", "ORGANIZATION");

        when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

        MatchExplanation ex = compactionService.explain("n1", "n2");
        assertTrue(ex.wouldMerge());
        assertTrue(ex.matchReasons().stream().anyMatch(r -> r.contains("Levenshtein")));
    }

    // ─── MatchCandidate record accessors ──────────────────────────────

    @Test
    void matchCandidate_recordFields() {
        MatchCandidate mc = new MatchCandidate("a", "b", "Apple", "Appl",
                "ORGANIZATION", 0.92, List.of("LEVENSHTEIN:0.920"));
        assertEquals("a", mc.nodeIdA());
        assertEquals("b", mc.nodeIdB());
        assertEquals("Apple", mc.titleA());
        assertEquals("Appl", mc.titleB());
        assertEquals("ORGANIZATION", mc.entityType());
        assertEquals(0.92, mc.score(), 0.001);
        assertEquals(1, mc.reasons().size());
    }

    // ─── MatchExplanation record ──────────────────────────────────────

    @Test
    void matchExplanation_recordFields() {
        MatchExplanation me = new MatchExplanation("n1", "n2", true, 0.95,
                List.of(), List.of("Same type", "Title match"));
        assertEquals("n1", me.nodeIdA());
        assertEquals("n2", me.nodeIdB());
        assertTrue(me.wouldMerge());
        assertEquals(0.95, me.score());
        assertTrue(me.blockers().isEmpty());
        assertEquals(2, me.matchReasons().size());
    }

    // ─── MergeDecision full constructor ───────────────────────────────

    @Test
    void mergeDecision_fullConstructorFields() {
        MergeDecision d = new MergeDecision("n1", "Apple", List.of("n2", "n3"), 5,
                List.of("EXACT_TITLE_MATCH"), 1.0,
                List.of("STEP 1: elected", "STEP 2: merged", "STEP 3: final"));
        assertEquals("n1", d.canonicalNodeId());
        assertEquals("Apple", d.canonicalTitle());
        assertEquals(2, d.mergedNodeIds().size());
        assertEquals(5, d.edgesRedirected());
        assertEquals(1.0, d.highestScore());
        assertEquals(3, d.assemblySteps().size());
    }

    // ─── AttributeBehavior record ─────────────────────────────────────

    @Test
    void attributeBehavior_recordFields() {
        AttributeBehavior ab = new AttributeBehavior(0.95, "EXCLUSIVE");
        assertEquals(0.95, ab.weight());
        assertEquals("EXCLUSIVE", ab.exclusivity());
    }

    @Test
    void attributeBehaviors_allBehaviorsHavePositiveWeight() {
        for (Map.Entry<String, AttributeBehavior> entry :
                GraphCompactionService.ATTRIBUTE_BEHAVIORS.entrySet()) {
            assertTrue(entry.getValue().weight() > 0,
                    entry.getKey() + " should have positive weight");
            assertNotNull(entry.getValue().exclusivity(),
                    entry.getKey() + " should have non-null exclusivity");
        }
    }

    @Test
    void attributeBehaviors_weightOrdering() {
        // EXCLUSIVE > CLOSE_EXCLUSIVE > STABLE > FREQUENT > VERY_FREQUENT
        assertTrue(GraphCompactionService.ATTRIBUTE_BEHAVIORS.get("email").weight() >
                GraphCompactionService.ATTRIBUTE_BEHAVIORS.get("address").weight());
        assertTrue(GraphCompactionService.ATTRIBUTE_BEHAVIORS.get("address").weight() >
                GraphCompactionService.ATTRIBUTE_BEHAVIORS.get("founded").weight());
        assertTrue(GraphCompactionService.ATTRIBUTE_BEHAVIORS.get("founded").weight() >
                GraphCompactionService.ATTRIBUTE_BEHAVIORS.get("industry").weight());
        assertTrue(GraphCompactionService.ATTRIBUTE_BEHAVIORS.get("industry").weight() >
                GraphCompactionService.ATTRIBUTE_BEHAVIORS.get("country").weight());
    }
}
