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

import ai.kompile.knowledgegraph.resolution.GraphCompactionService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link EntityResolutionController} — REST API for entity resolution
 * and graph compaction.
 */
class EntityResolutionControllerTest {

    private GraphCompactionService compactionService;
    private EntityResolutionService resolutionService;
    private EntityResolutionController controller;

    @BeforeEach
    void setUp() {
        compactionService = mock(GraphCompactionService.class);
        resolutionService = mock(EntityResolutionService.class);
        controller = new EntityResolutionController(compactionService, resolutionService);
    }

    @Test
    void compact_returnsResult() {
        CompactionResult mockResult = new CompactionResult(10, 7, 3, 5, 2,
                List.of(new MergeDecision("n1", "Apple", List.of("n2", "n3"), 5,
                        List.of("EXACT_TITLE_MATCH"), 1.0)),
                150L);

        when(compactionService.compact(any(), any())).thenReturn(mockResult);

        ResponseEntity<Map<String, Object>> response = controller.compact(0.85, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(10, body.get("originalEntityCount"));
        assertEquals(7, body.get("finalEntityCount"));
        assertEquals(3, body.get("entitiesMerged"));
        assertEquals(5, body.get("edgesRedirected"));
    }

    @Test
    void previewCompaction_returnsCandidates() {
        List<MatchCandidate> mockCandidates = List.of(
                new MatchCandidate("n1", "n2", "Apple Inc", "Apple Corp",
                        "ORGANIZATION", 0.95, List.of("EXACT_TITLE_MATCH"))
        );

        when(compactionService.previewCandidates(any(), any())).thenReturn(mockCandidates);

        ResponseEntity<Map<String, Object>> response = controller.previewCompaction(0.85, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.get("candidateCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) body.get("candidates");
        assertEquals(1, candidates.size());
        assertEquals("Apple Inc", candidates.get(0).get("titleA"));
    }

    @Test
    void explain_returnsExplanation() {
        MatchExplanation mockExplanation = new MatchExplanation(
                "n1", "n2", true, 0.95,
                List.of(),
                List.of("Same entity type: ORGANIZATION", "Exact normalized title match: \"apple\""));

        when(compactionService.explain("n1", "n2")).thenReturn(mockExplanation);

        ResponseEntity<Map<String, Object>> response = controller.explain("n1", "n2");
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("wouldMerge"));
        assertEquals(0.95, body.get("score"));
    }

    @Test
    void getConfig_returnsDescription() {
        ResponseEntity<Map<String, Object>> response = controller.getConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("principles-based", body.get("approach"));
        assertNotNull(body.get("signals"));

        @SuppressWarnings("unchecked")
        List<String> signals = (List<String>) body.get("signals");
        assertTrue(signals.size() >= 6, "Should have at least 6 signals including embedding and attribute");
        assertTrue(signals.stream().anyMatch(s -> s.contains("EMBEDDING_COSINE")));
        assertTrue(signals.stream().anyMatch(s -> s.contains("ATTR_MATCH")));
        assertNotNull(body.get("attributeBehaviors"));
    }

    // ─── Additional controller coverage ───────────────────────────────

    @Test
    void compact_includesAssemblyStepsInResponse() {
        List<String> steps = List.of("STEP 1: Elected canonical", "STEP 2: Merged", "STEP 3: Final");
        CompactionResult mockResult = new CompactionResult(5, 3, 2, 4, 1,
                List.of(new MergeDecision("n1", "Apple", List.of("n2", "n3"), 4,
                        List.of("EXACT_TITLE_MATCH"), 1.0, steps)),
                200L);

        when(compactionService.compact(any(), any())).thenReturn(mockResult);

        ResponseEntity<Map<String, Object>> response = controller.compact(0.85, null);
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) body.get("decisions");
        assertEquals(1, decisions.size());

        @SuppressWarnings("unchecked")
        List<String> assemblySteps = (List<String>) decisions.get(0).get("assemblySteps");
        assertNotNull(assemblySteps);
        assertEquals(3, assemblySteps.size());
    }

    @Test
    void compact_responseContainsAllFields() {
        CompactionResult mockResult = new CompactionResult(10, 7, 3, 5, 2,
                List.of(), 150L);

        when(compactionService.compact(any(), any())).thenReturn(mockResult);

        Map<String, Object> body = controller.compact(0.85, null).getBody();
        assertNotNull(body);
        assertEquals(10, body.get("originalEntityCount"));
        assertEquals(7, body.get("finalEntityCount"));
        assertEquals(3, body.get("entitiesMerged"));
        assertEquals(5, body.get("edgesRedirected"));
        assertEquals(2, body.get("componentsFound"));
        assertEquals(150L, body.get("elapsedMs"));
        assertNotNull(body.get("decisions"));
    }

    @Test
    void compact_decisionOmitsAssemblyStepsWhenEmpty() {
        CompactionResult mockResult = new CompactionResult(4, 2, 2, 1, 1,
                List.of(new MergeDecision("n1", "Apple", List.of("n2"), 1,
                        List.of("EXACT_TITLE_MATCH"), 1.0)),
                100L);

        when(compactionService.compact(any(), any())).thenReturn(mockResult);

        Map<String, Object> body = controller.compact(0.85, null).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) body.get("decisions");
        assertFalse(decisions.get(0).containsKey("assemblySteps"));
    }

    @Test
    void previewCompaction_candidateMapContainsAllFields() {
        List<MatchCandidate> mockCandidates = List.of(
                new MatchCandidate("n1", "n2", "Apple Inc", "Apple Corp",
                        "ORGANIZATION", 0.95, List.of("EXACT_TITLE_MATCH", "ATTR_MATCH:email(EXCLUSIVE)=\"info@apple.com\""))
        );

        when(compactionService.previewCandidates(any(), any())).thenReturn(mockCandidates);

        Map<String, Object> body = controller.previewCompaction(0.85, null).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) body.get("candidates");

        Map<String, Object> c = candidates.get(0);
        assertEquals("n1", c.get("nodeIdA"));
        assertEquals("n2", c.get("nodeIdB"));
        assertEquals("Apple Inc", c.get("titleA"));
        assertEquals("Apple Corp", c.get("titleB"));
        assertEquals("ORGANIZATION", c.get("entityType"));
        assertEquals(0.95, c.get("score"));
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) c.get("reasons");
        assertEquals(2, reasons.size());
    }

    @Test
    void previewCompaction_emptyCandidates() {
        when(compactionService.previewCandidates(any(), any())).thenReturn(List.of());

        Map<String, Object> body = controller.previewCompaction(0.90, null).getBody();
        assertNotNull(body);
        assertEquals(0, body.get("candidateCount"));
        assertEquals(0.90, body.get("threshold"));
    }

    @Test
    void explain_responseContainsAllFields() {
        MatchExplanation mockEx = new MatchExplanation(
                "n1", "n2", false, 0.42,
                List.of("Levenshtein too low: 0.42"),
                List.of("Same entity type: ORGANIZATION"));

        when(compactionService.explain("n1", "n2")).thenReturn(mockEx);

        Map<String, Object> body = controller.explain("n1", "n2").getBody();
        assertNotNull(body);
        assertEquals("n1", body.get("nodeIdA"));
        assertEquals("n2", body.get("nodeIdB"));
        assertEquals(false, body.get("wouldMerge"));
        assertEquals(0.42, body.get("score"));

        @SuppressWarnings("unchecked")
        List<String> blockers = (List<String>) body.get("blockers");
        assertEquals(1, blockers.size());
        assertTrue(blockers.get(0).contains("Levenshtein"));

        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) body.get("matchReasons");
        assertEquals(1, reasons.size());
    }

    @Test
    void getConfig_containsThresholds() {
        Map<String, Object> body = controller.getConfig().getBody();
        assertNotNull(body);
        assertEquals(0.85, body.get("defaultSimilarityThreshold"));
        assertEquals(0.88, body.get("defaultEmbeddingThreshold"));
    }

    @Test
    void getConfig_containsAllSignalTypes() {
        Map<String, Object> body = controller.getConfig().getBody();
        @SuppressWarnings("unchecked")
        List<String> signals = (List<String>) body.get("signals");

        assertTrue(signals.stream().anyMatch(s -> s.contains("EXACT_TITLE_MATCH")));
        assertTrue(signals.stream().anyMatch(s -> s.contains("TITLE_IN_ALIAS")));
        assertTrue(signals.stream().anyMatch(s -> s.contains("SHARED_ALIASES")));
        assertTrue(signals.stream().anyMatch(s -> s.contains("LEVENSHTEIN")));
        assertTrue(signals.stream().anyMatch(s -> s.contains("EMBEDDING_COSINE")));
        assertTrue(signals.stream().anyMatch(s -> s.contains("ATTR_MATCH")));
    }

    @Test
    void getConfig_attributeBehaviorsContainsAllTiers() {
        Map<String, Object> body = controller.getConfig().getBody();
        @SuppressWarnings("unchecked")
        Map<String, String> behaviors = (Map<String, String>) body.get("attributeBehaviors");

        assertTrue(behaviors.containsKey("EXCLUSIVE"));
        assertTrue(behaviors.containsKey("CLOSE_EXCLUSIVE"));
        assertTrue(behaviors.containsKey("STABLE"));
        assertTrue(behaviors.containsKey("FREQUENT"));
        assertTrue(behaviors.containsKey("VERY_FREQUENT"));
    }
}
