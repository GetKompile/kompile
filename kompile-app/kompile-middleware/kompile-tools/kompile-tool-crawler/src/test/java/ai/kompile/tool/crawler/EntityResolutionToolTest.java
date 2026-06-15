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
package ai.kompile.tool.crawler;

import ai.kompile.knowledgegraph.resolution.GraphCompactionService;
import ai.kompile.knowledgegraph.resolution.GraphCompactionService.*;
import ai.kompile.tool.crawler.EntityResolutionTool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link EntityResolutionTool} — MCP tool for entity resolution.
 */
class EntityResolutionToolTest {

    private GraphCompactionService compactionService;
    private EntityResolutionTool tool;

    @BeforeEach
    void setUp() {
        compactionService = mock(GraphCompactionService.class);
        tool = new EntityResolutionTool(compactionService);
    }

    // ─── compactGraph ─────────────────────────────────────────────────

    @Test
    void compactGraph_executeMode() {
        CompactionResult mockResult = new CompactionResult(10, 7, 3, 5, 2,
                List.of(new MergeDecision("n1", "Apple", List.of("n2"), 2,
                        List.of("EXACT_TITLE_MATCH"), 1.0,
                        List.of("STEP 1: elected", "STEP 2: merged"))),
                200L);

        when(compactionService.compact(any(), any())).thenReturn(mockResult);

        Map<String, Object> result = tool.compactGraph(
                new CompactGraphInput(0.85, false, true, 0.88, null));

        assertEquals("executed", result.get("mode"));
        assertEquals(10, result.get("originalEntityCount"));
        assertEquals(7, result.get("finalEntityCount"));
        assertEquals(3, result.get("entitiesMerged"));
        assertEquals(5, result.get("edgesRedirected"));
        assertEquals(2, result.get("componentsFound"));
        assertEquals(200L, result.get("elapsedMs"));
    }

    @Test
    void compactGraph_dryRunMode() {
        List<MatchCandidate> mockCandidates = List.of(
                new MatchCandidate("n1", "n2", "Apple Inc", "Apple Corp",
                        "ORGANIZATION", 0.95, List.of("EXACT_TITLE_MATCH"))
        );

        when(compactionService.previewCandidates(any(), any())).thenReturn(mockCandidates);

        Map<String, Object> result = tool.compactGraph(
                new CompactGraphInput(0.85, true, true, 0.88, null));

        assertEquals("preview", result.get("mode"));
        assertEquals(1, result.get("candidateCount"));
        assertEquals(0.85, result.get("threshold"));
    }

    @Test
    void compactGraph_defaultValues() {
        CompactionResult mockResult = CompactionResult.empty();
        when(compactionService.compact(any(), any())).thenReturn(mockResult);

        // All nulls — should use defaults
        Map<String, Object> result = tool.compactGraph(
                new CompactGraphInput(null, null, null, null, null));

        assertEquals("executed", result.get("mode"));
        verify(compactionService).compact(isNull(), any());
    }

    @Test
    void compactGraph_executedDecisionContainsAssemblySteps() {
        List<String> steps = List.of("STEP 1: elected", "STEP 2: merged", "STEP 3: final");
        CompactionResult mockResult = new CompactionResult(4, 2, 2, 1, 1,
                List.of(new MergeDecision("n1", "Apple", List.of("n2"), 1,
                        List.of("EXACT_TITLE_MATCH"), 1.0, steps)),
                50L);

        when(compactionService.compact(any(), any())).thenReturn(mockResult);

        Map<String, Object> result = tool.compactGraph(
                new CompactGraphInput(0.85, false, null, null, null));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) result.get("decisions");
        assertNotNull(decisions);
        assertEquals(1, decisions.size());

        Map<String, Object> d = decisions.get(0);
        assertEquals("n1", d.get("canonicalNodeId"));
        assertEquals("Apple", d.get("canonicalTitle"));
        assertEquals(1, d.get("mergedCount"));
        assertEquals(1.0, d.get("highestScore"));
        assertNotNull(d.get("assemblySteps"));
    }

    @Test
    void compactGraph_decisionOmitsAssemblyStepsWhenEmpty() {
        CompactionResult mockResult = new CompactionResult(4, 2, 2, 1, 1,
                List.of(new MergeDecision("n1", "Apple", List.of("n2"), 1,
                        List.of("EXACT_TITLE_MATCH"), 1.0)),
                50L);

        when(compactionService.compact(any(), any())).thenReturn(mockResult);

        Map<String, Object> result = tool.compactGraph(
                new CompactGraphInput(0.85, false, null, null, null));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) result.get("decisions");
        assertFalse(decisions.get(0).containsKey("assemblySteps"));
    }

    @Test
    void compactGraph_dryRunTruncatesAt50() {
        // Create 60 candidates
        List<MatchCandidate> bigList = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            bigList.add(new MatchCandidate("a" + i, "b" + i, "T" + i, "T" + i,
                    "ORGANIZATION", 0.9, List.of("EXACT_TITLE_MATCH")));
        }

        when(compactionService.previewCandidates(any(), any())).thenReturn(bigList);

        Map<String, Object> result = tool.compactGraph(
                new CompactGraphInput(0.85, true, null, null, null));

        assertEquals(60, result.get("candidateCount"));
        @SuppressWarnings("unchecked")
        List<?> candidates = (List<?>) result.get("candidates");
        assertEquals(50, candidates.size());
        assertNotNull(result.get("note"));
    }

    @Test
    void compactGraph_dryRunNoNoteWhenUnder50() {
        List<MatchCandidate> smallList = List.of(
                new MatchCandidate("n1", "n2", "A", "B", "ORG", 0.9, List.of()));

        when(compactionService.previewCandidates(any(), any())).thenReturn(smallList);

        Map<String, Object> result = tool.compactGraph(
                new CompactGraphInput(0.85, true, null, null, null));

        assertFalse(result.containsKey("note"));
    }

    @Test
    void compactGraph_useEmbeddingsFalse() {
        CompactionResult mockResult = CompactionResult.empty();
        when(compactionService.compact(any(), any())).thenReturn(mockResult);

        tool.compactGraph(new CompactGraphInput(0.85, false, false, null, null));

        verify(compactionService).compact(isNull(), argThat(config ->
                !config.useEmbeddings()));
    }

    @Test
    void compactGraph_exceptionReturnsError() {
        when(compactionService.compact(any(), any())).thenThrow(new RuntimeException("DB down"));

        Map<String, Object> result = tool.compactGraph(
                new CompactGraphInput(0.85, false, null, null, null));

        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("DB down"));
    }

    // ─── explainMatch ─────────────────────────────────────────────────

    @Test
    void explainMatch_returnsExplanation() {
        MatchExplanation mockEx = new MatchExplanation(
                "n1", "n2", true, 0.95,
                List.of(), List.of("Same entity type", "Title match"));

        when(compactionService.explain("n1", "n2")).thenReturn(mockEx);

        Map<String, Object> result = tool.explainMatch(new ExplainMatchInput("n1", "n2"));

        assertEquals("n1", result.get("nodeIdA"));
        assertEquals("n2", result.get("nodeIdB"));
        assertEquals(true, result.get("wouldMerge"));
        assertEquals(0.95, result.get("similarityScore"));
    }

    @Test
    void explainMatch_nullNodeIdReturnsError() {
        Map<String, Object> result = tool.explainMatch(new ExplainMatchInput(null, "n2"));
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("required"));
    }

    @Test
    void explainMatch_bothNullReturnsError() {
        Map<String, Object> result = tool.explainMatch(new ExplainMatchInput(null, null));
        assertTrue(result.containsKey("error"));
    }

    @Test
    void explainMatch_exceptionReturnsError() {
        when(compactionService.explain(any(), any()))
                .thenThrow(new RuntimeException("Service error"));

        Map<String, Object> result = tool.explainMatch(new ExplainMatchInput("n1", "n2"));
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("Service error"));
    }

    // ─── previewCandidates ────────────────────────────────────────────

    @Test
    void previewCandidates_returnsGroupedByType() {
        List<MatchCandidate> mockCandidates = List.of(
                new MatchCandidate("n1", "n2", "A", "B", "ORGANIZATION", 0.9, List.of()),
                new MatchCandidate("n3", "n4", "C", "D", "ORGANIZATION", 0.88, List.of()),
                new MatchCandidate("n5", "n6", "E", "F", "PERSON", 0.92, List.of())
        );

        when(compactionService.previewCandidates(any(), any())).thenReturn(mockCandidates);

        Map<String, Object> result = tool.previewCandidates(
                new PreviewCandidatesInput(0.85, null, null, null));

        assertEquals(3, result.get("totalCandidates"));

        @SuppressWarnings("unchecked")
        Map<String, Long> byType = (Map<String, Long>) result.get("candidatesByType");
        assertEquals(2L, byType.get("ORGANIZATION"));
        assertEquals(1L, byType.get("PERSON"));
    }

    @Test
    void previewCandidates_respectsMaxResults() {
        List<MatchCandidate> mockCandidates = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            mockCandidates.add(new MatchCandidate("a" + i, "b" + i, "T", "T",
                    "ORG", 0.9, List.of()));
        }

        when(compactionService.previewCandidates(any(), any())).thenReturn(mockCandidates);

        Map<String, Object> result = tool.previewCandidates(
                new PreviewCandidatesInput(0.85, 3, null, null));

        assertEquals(10, result.get("totalCandidates"));
        @SuppressWarnings("unchecked")
        List<?> candidates = (List<?>) result.get("candidates");
        assertEquals(3, candidates.size());
    }

    @Test
    void previewCandidates_defaultValues() {
        when(compactionService.previewCandidates(any(), any())).thenReturn(List.of());

        Map<String, Object> result = tool.previewCandidates(
                new PreviewCandidatesInput(null, null, null, null));

        assertEquals(0, result.get("totalCandidates"));
        assertEquals(0.85, result.get("threshold"));
    }

    @Test
    void previewCandidates_exceptionReturnsError() {
        when(compactionService.previewCandidates(any(), any()))
                .thenThrow(new RuntimeException("Timeout"));

        Map<String, Object> result = tool.previewCandidates(
                new PreviewCandidatesInput(0.85, null, null, null));

        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("Timeout"));
    }

    @Test
    void previewCandidates_candidateMapAllFields() {
        List<MatchCandidate> mockCandidates = List.of(
                new MatchCandidate("n1", "n2", "Apple Inc", "Apple Corp",
                        "ORGANIZATION", 0.95, List.of("EXACT_TITLE_MATCH"))
        );

        when(compactionService.previewCandidates(any(), any())).thenReturn(mockCandidates);

        Map<String, Object> result = tool.previewCandidates(
                new PreviewCandidatesInput(0.85, 50, true, null));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("candidates");
        Map<String, Object> c = candidates.get(0);

        assertEquals("n1", c.get("nodeIdA"));
        assertEquals("n2", c.get("nodeIdB"));
        assertEquals("Apple Inc", c.get("titleA"));
        assertEquals("Apple Corp", c.get("titleB"));
        assertEquals("ORGANIZATION", c.get("entityType"));
        assertEquals(0.95, c.get("score"));
        assertEquals(List.of("EXACT_TITLE_MATCH"), c.get("reasons"));
    }

    @Test
    void previewCandidates_useEmbeddingsFalse() {
        when(compactionService.previewCandidates(any(), any())).thenReturn(List.of());

        tool.previewCandidates(new PreviewCandidatesInput(0.85, null, false, null));

        verify(compactionService).previewCandidates(isNull(), argThat(config ->
                !config.useEmbeddings()));
    }
}
