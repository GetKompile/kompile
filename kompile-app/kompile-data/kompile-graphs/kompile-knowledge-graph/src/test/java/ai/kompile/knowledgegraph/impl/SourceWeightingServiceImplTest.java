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

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.SourceWeight;
import ai.kompile.knowledgegraph.repository.SourceWeightRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ai.kompile.core.embeddings.EmbeddingModel;
import org.nd4j.linalg.factory.Nd4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SourceWeightingServiceImpl} — weight CRUD, query-time weighting,
 * topic relevance, quality feedback, recomputation, and topic management.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class SourceWeightingServiceImplTest {

    @Mock private SourceWeightRepository weightRepository;
    @Mock private KnowledgeGraphService knowledgeGraphService;
    @Mock private EmbeddingModel embeddingModel;

    private SourceWeightingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SourceWeightingServiceImpl(weightRepository, knowledgeGraphService);
        ReflectionTestUtils.setField(service, "defaultWeight", 1.0);
        ReflectionTestUtils.setField(service, "maxWeight", 3.0);
        ReflectionTestUtils.setField(service, "topicRelevanceFactor", 0.3);
    }

    private GraphNode stubNode(String nodeId, String title) {
        GraphNode node = new GraphNode();
        node.setNodeId(nodeId);
        node.setTitle(title);
        return node;
    }

    private SourceWeight stubWeight(double base, double effective) {
        return SourceWeight.builder()
                .baseWeight(base)
                .effectiveWeight(effective)
                .enabled(true)
                .build();
    }

    // ─── setSourceWeight ────────────────────────────────────────────

    @Test
    void setSourceWeight_newWeight_creates() {
        GraphNode node = stubNode("n-1", "Source");
        when(knowledgeGraphService.getNode("n-1")).thenReturn(Optional.of(node));
        when(weightRepository.findBySourceNodeAndTopicAndUserId(node, null, "user1"))
                .thenReturn(Optional.empty());
        when(weightRepository.save(any(SourceWeight.class))).thenAnswer(i -> i.getArgument(0));

        SourceWeight result = service.setSourceWeight("n-1", 2.0, null, "user1");

        assertNotNull(result);
        assertEquals(2.0, result.getBaseWeight());
        verify(weightRepository).save(any(SourceWeight.class));
    }

    @Test
    void setSourceWeight_existingWeight_updates() {
        GraphNode node = stubNode("n-1", "Source");
        SourceWeight existing = SourceWeight.builder()
                .sourceNode(node).baseWeight(1.0).enabled(true).build();
        when(knowledgeGraphService.getNode("n-1")).thenReturn(Optional.of(node));
        when(weightRepository.findBySourceNodeAndTopicAndUserId(node, null, "user1"))
                .thenReturn(Optional.of(existing));
        when(weightRepository.save(any(SourceWeight.class))).thenAnswer(i -> i.getArgument(0));

        SourceWeight result = service.setSourceWeight("n-1", 2.5, null, "user1");

        assertEquals(2.5, result.getBaseWeight());
    }

    @Test
    void setSourceWeight_nodeNotFound_throws() {
        when(knowledgeGraphService.getNode("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.setSourceWeight("missing", 1.0, null, "user1"));
    }

    // ─── getSourceWeight ────────────────────────────────────────────

    @Test
    void getSourceWeight_found_returnsWeight() {
        SourceWeight sw = stubWeight(2.0, 2.0);
        when(weightRepository.findWeightsForSourceAndTopic("n-1", "topic"))
                .thenReturn(List.of(sw));

        SourceWeight result = service.getSourceWeight("n-1", "topic");
        assertEquals(2.0, result.getBaseWeight());
    }

    @Test
    void getSourceWeight_notFound_returnsDefault() {
        when(weightRepository.findWeightsForSourceAndTopic("n-1", "topic"))
                .thenReturn(List.of());

        SourceWeight result = service.getSourceWeight("n-1", "topic");
        assertEquals(1.0, result.getBaseWeight());
        assertEquals(1.0, result.getEffectiveWeight());
    }

    // ─── getAllWeightsForSource ──────────────────────────────────────

    @Test
    void getAllWeightsForSource_delegatesToRepo() {
        when(weightRepository.findBySourceNodeId("n-1"))
                .thenReturn(List.of(stubWeight(1.0, 1.0), stubWeight(2.0, 2.0)));

        List<SourceWeight> result = service.getAllWeightsForSource("n-1");
        assertEquals(2, result.size());
    }

    // ─── removeWeight ───────────────────────────────────────────────

    @Test
    void removeWeight_found_deletes() {
        GraphNode node = stubNode("n-1", "Source");
        SourceWeight sw = stubWeight(1.0, 1.0);
        when(knowledgeGraphService.getNode("n-1")).thenReturn(Optional.of(node));
        when(weightRepository.findBySourceNodeAndTopicAndUserId(node, null, "user1"))
                .thenReturn(Optional.of(sw));

        service.removeWeight("n-1", null, "user1");
        verify(weightRepository).delete(sw);
    }

    @Test
    void removeWeight_notFound_noOp() {
        GraphNode node = stubNode("n-1", "Source");
        when(knowledgeGraphService.getNode("n-1")).thenReturn(Optional.of(node));
        when(weightRepository.findBySourceNodeAndTopicAndUserId(node, null, "user1"))
                .thenReturn(Optional.empty());

        service.removeWeight("n-1", null, "user1");
        verify(weightRepository, never()).delete(any());
    }

    @Test
    void removeWeight_nodeNotFound_throws() {
        when(knowledgeGraphService.getNode("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.removeWeight("missing", null, "user1"));
    }

    // ─── setWeightEnabled ───────────────────────────────────────────

    @Test
    void setWeightEnabled_updatesAndSaves() {
        GraphNode node = stubNode("n-1", "Source");
        SourceWeight sw = SourceWeight.builder()
                .sourceNode(node).baseWeight(1.0).enabled(true).build();
        when(knowledgeGraphService.getNode("n-1")).thenReturn(Optional.of(node));
        when(weightRepository.findBySourceNodeAndTopicAndUserId(node, null, "user1"))
                .thenReturn(Optional.of(sw));
        when(weightRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SourceWeight result = service.setWeightEnabled("n-1", null, "user1", false);
        assertFalse(result.getEnabled());
    }

    @Test
    void setWeightEnabled_weightNotFound_throws() {
        GraphNode node = stubNode("n-1", "Source");
        when(knowledgeGraphService.getNode("n-1")).thenReturn(Optional.of(node));
        when(weightRepository.findBySourceNodeAndTopicAndUserId(node, null, "user1"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.setWeightEnabled("n-1", null, "user1", false));
    }

    // ─── computeQueryWeights ────────────────────────────────────────

    @Test
    void computeQueryWeights_usesDefaultWhenNoWeightsExist() {
        when(weightRepository.findEnabledWeightsForSource("n-1")).thenReturn(List.of());

        Map<String, Double> weights = service.computeQueryWeights("query", List.of("n-1"));
        assertEquals(1.0, weights.get("n-1"));
    }

    @Test
    void computeQueryWeights_usesGlobalWeight() {
        SourceWeight globalWeight = SourceWeight.builder()
                .baseWeight(2.5).effectiveWeight(2.5).topic(null).enabled(true).build();
        when(weightRepository.findEnabledWeightsForSource("n-1"))
                .thenReturn(List.of(globalWeight));

        Map<String, Double> weights = service.computeQueryWeights("query", List.of("n-1"));
        assertEquals(2.5, weights.get("n-1"));
    }

    @Test
    void computeQueryWeights_clampsToMaxWeight() {
        SourceWeight highWeight = SourceWeight.builder()
                .baseWeight(10.0).effectiveWeight(10.0).topic(null).enabled(true).build();
        when(weightRepository.findEnabledWeightsForSource("n-1"))
                .thenReturn(List.of(highWeight));

        Map<String, Double> weights = service.computeQueryWeights("query", List.of("n-1"));
        assertEquals(3.0, weights.get("n-1")); // clamped to maxWeight
    }

    @Test
    void computeQueryWeights_multipleSources() {
        when(weightRepository.findEnabledWeightsForSource("n-1")).thenReturn(List.of());
        SourceWeight sw = SourceWeight.builder()
                .baseWeight(2.0).effectiveWeight(2.0).topic(null).enabled(true).build();
        when(weightRepository.findEnabledWeightsForSource("n-2")).thenReturn(List.of(sw));

        Map<String, Double> weights = service.computeQueryWeights("query", List.of("n-1", "n-2"));
        assertEquals(1.0, weights.get("n-1")); // default
        assertEquals(2.0, weights.get("n-2")); // user-defined
    }

    // ─── previewWeightedSearch ──────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void previewWeightedSearch_withoutEmbeddingModel_ranksByWeightOnly() {
        // No embedding model injected (field stays null) → relevance must be absent and the
        // ranking falls back to configured weight. Input order is low-then-high to prove sorting.
        GraphNode low = stubNode("n-2", "Low weight source");
        GraphNode high = stubNode("n-1", "High weight source");
        high.setSourceType("PDF");
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of(low, high));
        when(weightRepository.findEnabledWeightsForSource("n-1"))
                .thenReturn(List.of(SourceWeight.builder()
                        .baseWeight(2.5).effectiveWeight(2.5).topic(null).enabled(true).build()));
        when(weightRepository.findEnabledWeightsForSource("n-2")).thenReturn(List.of());

        Map<String, Object> result = service.previewWeightedSearch("anything", 10);

        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("sourceWeights");
        assertEquals(2, items.size());
        // Heavier source ranks first; with no embeddings, score == weight and relevance is null.
        assertEquals("n-1", items.get(0).get("sourceId"));
        assertEquals("PDF", items.get(0).get("sourceType"));
        assertEquals(2.5, (Double) items.get(0).get("weight"), 1e-9);
        assertEquals(2.5, (Double) items.get(0).get("score"), 1e-9);
        assertNull(items.get(0).get("relevance"), "relevance must be null when embeddings unavailable");
        assertEquals("n-2", items.get(1).get("sourceId"));
        assertTrue(((String) result.get("note")).toLowerCase().contains("weight only"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewWeightedSearch_withEmbeddingModel_ranksBySemanticRelevance() {
        // Equal weights so ordering is driven purely by query↔source cosine similarity.
        GraphNode aligned = stubNode("n-1", "aligned");
        GraphNode orthogonal = stubNode("n-2", "orthogonal");
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of(aligned, orthogonal));
        when(weightRepository.findEnabledWeightsForSource(anyString())).thenReturn(List.of());

        ReflectionTestUtils.setField(service, "embeddingModel", embeddingModel);
        when(embeddingModel.isInitialized()).thenReturn(true);
        // Query lies on the first axis; source rows: n-1 aligned (cos=1), n-2 orthogonal (cos=0).
        when(embeddingModel.embed(anyString())).thenReturn(Nd4j.create(new float[][]{{1f, 0f}}));
        when(embeddingModel.embed(anyList()))
                .thenReturn(Nd4j.create(new float[][]{{1f, 0f}, {0f, 1f}}));

        Map<String, Object> result = service.previewWeightedSearch("find the aligned one", 10);

        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("sourceWeights");
        assertEquals(2, items.size());
        assertEquals("n-1", items.get(0).get("sourceId"));
        assertEquals(1.0, (Double) items.get(0).get("relevance"), 1e-4);
        assertEquals(1.0, (Double) items.get(0).get("score"), 1e-4); // weight 1.0 × relevance 1.0
        assertEquals("n-2", items.get(1).get("sourceId"));
        assertEquals(0.0, (Double) items.get(1).get("relevance"), 1e-4);
        assertEquals(0.0, (Double) items.get(1).get("score"), 1e-4);
        assertTrue(((String) result.get("note")).toLowerCase().contains("relevance"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewWeightedSearch_embeddingFailure_fallsBackToWeightOnly() {
        // A failure inside the embedding path must not break the preview — it degrades to weights.
        GraphNode node = stubNode("n-1", "Source");
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of(node));
        when(weightRepository.findEnabledWeightsForSource("n-1")).thenReturn(List.of());

        ReflectionTestUtils.setField(service, "embeddingModel", embeddingModel);
        when(embeddingModel.isInitialized()).thenReturn(true);
        when(embeddingModel.embed(anyList())).thenThrow(new RuntimeException("embedding subprocess down"));

        Map<String, Object> result = service.previewWeightedSearch("q", 10);

        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("sourceWeights");
        assertEquals(1, items.size());
        assertNull(items.get(0).get("relevance"));
        assertEquals(1.0, (Double) items.get(0).get("score"), 1e-9);
        assertTrue(((String) result.get("note")).toLowerCase().contains("weight only"));
    }

    // ─── getDefaultWeight ───────────────────────────────────────────

    @Test
    void getDefaultWeight_returnsConfiguredValue() {
        assertEquals(1.0, service.getDefaultWeight());
    }

    // ─── computeTopicRelevance ──────────────────────────────────────

    @Test
    void computeTopicRelevance_topicInTitle_returnsHigh() {
        GraphNode node = stubNode("n-1", "Machine Learning Guide");
        node.setDescription("A comprehensive guide");
        when(knowledgeGraphService.getNode("n-1")).thenReturn(Optional.of(node));

        Double relevance = service.computeTopicRelevance("n-1", "Machine Learning");
        assertEquals(0.8, relevance);
    }

    @Test
    void computeTopicRelevance_topicNotInContent_returnsDefault() {
        GraphNode node = stubNode("n-1", "Finance Report");
        node.setDescription("Quarterly earnings");
        when(knowledgeGraphService.getNode("n-1")).thenReturn(Optional.of(node));

        Double relevance = service.computeTopicRelevance("n-1", "Kubernetes");
        assertEquals(0.5, relevance);
    }

    @Test
    void computeTopicRelevance_nodeNotFound_returnsDefault() {
        when(knowledgeGraphService.getNode("missing")).thenReturn(Optional.empty());

        Double relevance = service.computeTopicRelevance("missing", "topic");
        assertEquals(0.5, relevance);
    }

    @Test
    void computeTopicRelevance_nullTopic_returnsDefault() {
        GraphNode node = stubNode("n-1", "Title");
        when(knowledgeGraphService.getNode("n-1")).thenReturn(Optional.of(node));

        Double relevance = service.computeTopicRelevance("n-1", null);
        assertEquals(0.5, relevance);
    }

    // ─── updateQualityScore ─────────────────────────────────────────

    @Test
    void updateQualityScore_callsUpdateOnAllWeights() {
        SourceWeight sw1 = spy(SourceWeight.builder().baseWeight(1.0).enabled(true).build());
        SourceWeight sw2 = spy(SourceWeight.builder().baseWeight(1.5).enabled(true).build());
        when(weightRepository.findBySourceNodeId("n-1")).thenReturn(List.of(sw1, sw2));

        service.updateQualityScore("n-1", true);

        verify(sw1).updateQualityFromFeedback(true);
        verify(sw2).updateQualityFromFeedback(true);
        verify(weightRepository, times(2)).save(any());
    }

    // ─── recomputeAllWeights ────────────────────────────────────────

    @Test
    void recomputeAllWeights_recomputesEach() {
        SourceWeight sw = spy(SourceWeight.builder().baseWeight(1.0).enabled(true).build());
        when(weightRepository.findAll()).thenReturn(List.of(sw));

        service.recomputeAllWeights();

        verify(sw).computeEffectiveWeight();
        verify(weightRepository).save(sw);
    }

    // ─── Topic management ───────────────────────────────────────────

    @Test
    void getTopics_delegatesToRepo() {
        when(weightRepository.findDistinctTopics()).thenReturn(List.of("ML", "Finance"));
        assertEquals(List.of("ML", "Finance"), service.getTopics());
    }

    @Test
    void assignTopic_createsNewWeight() {
        GraphNode node = stubNode("n-1", "Source");
        when(knowledgeGraphService.getNode("n-1")).thenReturn(Optional.of(node));
        when(weightRepository.findBySourceNodeAndTopicAndUserId(node, "ML", null))
                .thenReturn(Optional.empty());

        service.assignTopic("n-1", "ML");
        verify(weightRepository).save(any(SourceWeight.class));
    }

    @Test
    void assignTopic_existingWeight_noOp() {
        GraphNode node = stubNode("n-1", "Source");
        when(knowledgeGraphService.getNode("n-1")).thenReturn(Optional.of(node));
        when(weightRepository.findBySourceNodeAndTopicAndUserId(node, "ML", null))
                .thenReturn(Optional.of(stubWeight(1.0, 1.0)));

        service.assignTopic("n-1", "ML");
        verify(weightRepository, never()).save(any());
    }

    @Test
    void getSourcesForTopic_returnsNodeIds() {
        GraphNode node1 = stubNode("n-1", "S1");
        GraphNode node2 = stubNode("n-2", "S2");
        SourceWeight sw1 = SourceWeight.builder().sourceNode(node1).build();
        SourceWeight sw2 = SourceWeight.builder().sourceNode(node2).build();
        when(weightRepository.findByTopic("ML")).thenReturn(List.of(sw1, sw2));

        List<String> sources = service.getSourcesForTopic("ML");
        assertEquals(2, sources.size());
        assertTrue(sources.contains("n-1"));
        assertTrue(sources.contains("n-2"));
    }
}
