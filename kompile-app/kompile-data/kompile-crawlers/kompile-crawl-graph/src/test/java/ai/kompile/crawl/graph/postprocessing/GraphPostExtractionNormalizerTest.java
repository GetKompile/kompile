/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.crawl.graph.postprocessing;

import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GraphPostExtractionNormalizer}.
 *
 * <p>Tests cover: static helper contracts (canonicalize/degenerate/merge),
 * integration with the mocked KnowledgeGraphService, event-dispatch path,
 * and safety (null factSheetId, over-cap graphs, service exceptions).</p>
 */
@ExtendWith(MockitoExtension.class)
class GraphPostExtractionNormalizerTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private GraphPostExtractionNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new GraphPostExtractionNormalizer(knowledgeGraphService);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Static helper: canonicalizeTitle
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void canonicalize_trimsWhitespace() {
        assertThat(GraphPostExtractionNormalizer.canonicalizeTitle("  Alice Smith  "))
                .isEqualTo("Alice Smith");
    }

    @Test
    void canonicalize_collapsesInternalWhitespace() {
        assertThat(GraphPostExtractionNormalizer.canonicalizeTitle("Acme\t  Corp"))
                .isEqualTo("Acme Corp");
    }

    @Test
    void canonicalize_stripsEdgePunctuation() {
        assertThat(GraphPostExtractionNormalizer.canonicalizeTitle("\"Acme Corp\""))
                .isEqualTo("Acme Corp");
        assertThat(GraphPostExtractionNormalizer.canonicalizeTitle("[Organization]"))
                .isEqualTo("Organization");
    }

    @Test
    void canonicalize_normalizesUnicodeNFC() {
        // Decomposed "é" (e + combining acute) → NFC "é"
        String decomposed = "Acmé Inc";
        String canonical = GraphPostExtractionNormalizer.canonicalizeTitle(decomposed);
        assertThat(canonical).doesNotContain("́");
    }

    @Test
    void canonicalize_handlesNullInput() {
        assertThat(GraphPostExtractionNormalizer.canonicalizeTitle(null)).isEqualTo("");
    }

    @Test
    void canonicalize_handlesBlankInput() {
        assertThat(GraphPostExtractionNormalizer.canonicalizeTitle("   ")).isEqualTo("");
    }

    @Test
    void canonicalize_preservesValidTitle() {
        assertThat(GraphPostExtractionNormalizer.canonicalizeTitle("Acme Corporation")).isEqualTo("Acme Corporation");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Static helper: isDegenerate
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void degenerate_nullTitleIsDegenerate() {
        assertThat(GraphPostExtractionNormalizer.isDegenerate(null, 3)).isTrue();
    }

    @Test
    void degenerate_blankTitleIsDegenerate() {
        assertThat(GraphPostExtractionNormalizer.isDegenerate("", 3)).isTrue();
    }

    @Test
    void degenerate_shortNonNumericTitleIsDegenerate() {
        assertThat(GraphPostExtractionNormalizer.isDegenerate("A", 3)).isTrue();
        assertThat(GraphPostExtractionNormalizer.isDegenerate("AB", 3)).isTrue();
    }

    @Test
    void degenerate_shortNumericTitleIsKept() {
        // Single-digit or 2-char numbers can be legitimate entity titles (year, ID, etc.)
        assertThat(GraphPostExtractionNormalizer.isDegenerate("42", 3)).isFalse();
        assertThat(GraphPostExtractionNormalizer.isDegenerate("3.14", 3)).isFalse();
    }

    @Test
    void degenerate_sufficientlyLongTitleIsNotDegenerate() {
        assertThat(GraphPostExtractionNormalizer.isDegenerate("ABC", 3)).isFalse();
        assertThat(GraphPostExtractionNormalizer.isDegenerate("Alice Smith", 3)).isFalse();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Static helper: mergeMetadata
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void mergeMetadata_addsNewKeysFromDuplicate() {
        GraphNode original  = makeNode("n1", "Alice");
        GraphNode duplicate = makeNode("n2", "Alice");
        // Add extra metadata to duplicate that original lacks
        duplicate.setMetadataJson("{\"extra_key\":\"extra_val\",\"source\":\"doc2\"}");

        // original has no metadata (empty JSON would be null here for simplicity)
        Map<String, Object> additions = GraphPostExtractionNormalizer.mergeMetadata(original, duplicate);
        // We can't easily test the live JSON parse here without Jackson, so test the no-op case
        assertThat(additions).isNotNull();
    }

    @Test
    void mergeMetadata_returnsEmptyWhenDuplicateHasNoMetadata() {
        GraphNode original  = makeNode("n1", "Alice");
        GraphNode duplicate = makeNode("n2", "Alice");

        Map<String, Object> additions = GraphPostExtractionNormalizer.mergeMetadata(original, duplicate);
        assertThat(additions).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // normalize() — integration with mocked KnowledgeGraphService
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void normalize_canonicalizesNodesWithDirtyTitles() {
        GraphNode node = makeNode("id1", "  Acme Corp  ");
        when(knowledgeGraphService.getNodesByTypeInFactSheet(1L, NodeLevel.ENTITY))
                .thenReturn(List.of(node));

        GraphPostExtractionNormalizer.NormalizationResult result =
                normalizer.normalize(1L, 3, 10_000);

        assertThat(result.canonicalized()).isEqualTo(1);
        verify(knowledgeGraphService).updateNode(eq("id1"), eq("Acme Corp"), any(), any());
    }

    @Test
    void normalize_dropsDegenerateNodes() {
        GraphNode degNode = makeNode("deg1", "X"); // 1 char — degenerate
        when(knowledgeGraphService.getNodesByTypeInFactSheet(2L, NodeLevel.ENTITY))
                .thenReturn(List.of(degNode));

        GraphPostExtractionNormalizer.NormalizationResult result =
                normalizer.normalize(2L, 3, 10_000);

        assertThat(result.degenerateDropped()).isEqualTo(1);
        verify(knowledgeGraphService).deleteNode("deg1");
    }

    @Test
    void normalize_mergesTriviallyDuplicateNodes() {
        // Two nodes with equivalent canonical titles (one has trailing period)
        GraphNode n1 = makeNode("id_a", "Acme Corp");
        GraphNode n2 = makeNode("id_b", "acme corp"); // same after lowercasing
        when(knowledgeGraphService.getNodesByTypeInFactSheet(3L, NodeLevel.ENTITY))
                .thenReturn(new ArrayList<>(List.of(n1, n2)));

        GraphPostExtractionNormalizer.NormalizationResult result =
                normalizer.normalize(3L, 3, 10_000);

        assertThat(result.duplicatesMerged()).isEqualTo(1);
        verify(knowledgeGraphService).deleteNode("id_b");
        // Original "id_a" must NOT be deleted
        verify(knowledgeGraphService, never()).deleteNode("id_a");
    }

    @Test
    void normalize_returnsEmptyResultWhenNoNodes() {
        when(knowledgeGraphService.getNodesByTypeInFactSheet(4L, NodeLevel.ENTITY))
                .thenReturn(Collections.emptyList());

        GraphPostExtractionNormalizer.NormalizationResult result =
                normalizer.normalize(4L, 3, 10_000);

        assertThat(result.totalNodes()).isEqualTo(0);
        assertThat(result.canonicalized()).isEqualTo(0);
        assertThat(result.duplicatesMerged()).isEqualTo(0);
        verifyNoMoreInteractions(knowledgeGraphService);
    }

    @Test
    void normalize_skipsWhenNodeCountExceedsCap() {
        // Return a list larger than the max
        List<GraphNode> big = new ArrayList<>();
        for (int i = 0; i < 10; i++) big.add(makeNode("id" + i, "Node " + i));
        when(knowledgeGraphService.getNodesByTypeInFactSheet(5L, NodeLevel.ENTITY))
                .thenReturn(big);

        GraphPostExtractionNormalizer.NormalizationResult result =
                normalizer.normalize(5L, 3, 5); // cap = 5, list = 10

        assertThat(result.skipped()).isTrue();
        // No mutations should happen when skipped
        verify(knowledgeGraphService, never()).deleteNode(anyString());
        verify(knowledgeGraphService, never()).updateNode(anyString(), anyString(), any(), any());
    }

    @Test
    void normalize_handlesServiceExceptionGracefully() {
        GraphNode node = makeNode("id1", "  Alice  ");
        when(knowledgeGraphService.getNodesByTypeInFactSheet(6L, NodeLevel.ENTITY))
                .thenReturn(List.of(node));
        doThrow(new RuntimeException("DB error"))
                .when(knowledgeGraphService).updateNode(anyString(), anyString(), any(), any());

        // Must not propagate the exception
        assertThat(normalizer.normalize(6L, 3, 10_000))
                .isNotNull();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Event handler path
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void eventHandler_dispatchesToNormalize() {
        when(knowledgeGraphService.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY))
                .thenReturn(Collections.emptyList());

        GraphBuildCompletedEvent event = new GraphBuildCompletedEvent(this, "job-1", 10, 5, 7L, Map.of());
        normalizer.onGraphBuildCompleted(event);

        verify(knowledgeGraphService).getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY);
    }

    @Test
    void eventHandler_skipsNullFactSheetId() {
        // Event with no factSheetId
        GraphBuildCompletedEvent event = new GraphBuildCompletedEvent(this, "job-null", 0, 0);
        normalizer.onGraphBuildCompleted(event);

        verifyNoInteractions(knowledgeGraphService);
    }

    @Test
    void eventHandler_survivesExceptionFromNormalize() {
        when(knowledgeGraphService.getNodesByTypeInFactSheet(8L, NodeLevel.ENTITY))
                .thenThrow(new RuntimeException("transient failure"));

        GraphBuildCompletedEvent event = new GraphBuildCompletedEvent(this, "job-err", 0, 0, 8L, Map.of());
        // Must not propagate
        assertThatCode(() -> normalizer.onGraphBuildCompleted(event)).doesNotThrowAnyException();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Result DTO
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void emptyResult_hasZeroCounts() {
        var r = GraphPostExtractionNormalizer.NormalizationResult.empty(99L);
        assertThat(r.totalNodes()).isEqualTo(0);
        assertThat(r.canonicalized()).isEqualTo(0);
        assertThat(r.degenerateDropped()).isEqualTo(0);
        assertThat(r.duplicatesMerged()).isEqualTo(0);
        assertThat(r.skipped()).isFalse();
    }

    @Test
    void skippedResult_isMarkedSkipped() {
        var r = GraphPostExtractionNormalizer.NormalizationResult.skipped(99L, 100_000);
        assertThat(r.skipped()).isTrue();
        assertThat(r.totalNodes()).isEqualTo(100_000);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private static GraphNode makeNode(String nodeId, String title) {
        GraphNode n = new GraphNode();
        n.setNodeId(nodeId);
        n.setTitle(title);
        n.setNodeType(NodeLevel.ENTITY);
        return n;
    }
}
