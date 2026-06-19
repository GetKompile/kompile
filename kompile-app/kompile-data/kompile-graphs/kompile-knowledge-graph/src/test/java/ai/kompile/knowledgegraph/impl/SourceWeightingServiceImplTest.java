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
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.repository.SourceWeightRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    @Mock private GraphNodeRepository nodeRepository;
    @Mock private KnowledgeGraphService knowledgeGraphService;

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
        when(nodeRepository.findByNodeId("n-1")).thenReturn(Optional.of(node));
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
        when(nodeRepository.findByNodeId("n-1")).thenReturn(Optional.of(node));
        when(weightRepository.findBySourceNodeAndTopicAndUserId(node, null, "user1"))
                .thenReturn(Optional.of(existing));
        when(weightRepository.save(any(SourceWeight.class))).thenAnswer(i -> i.getArgument(0));

        SourceWeight result = service.setSourceWeight("n-1", 2.5, null, "user1");

        assertEquals(2.5, result.getBaseWeight());
    }

    @Test
    void setSourceWeight_nodeNotFound_throws() {
        when(nodeRepository.findByNodeId("missing")).thenReturn(Optional.empty());

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
        when(nodeRepository.findByNodeId("n-1")).thenReturn(Optional.of(node));
        when(weightRepository.findBySourceNodeAndTopicAndUserId(node, null, "user1"))
                .thenReturn(Optional.of(sw));

        service.removeWeight("n-1", null, "user1");
        verify(weightRepository).delete(sw);
    }

    @Test
    void removeWeight_notFound_noOp() {
        GraphNode node = stubNode("n-1", "Source");
        when(nodeRepository.findByNodeId("n-1")).thenReturn(Optional.of(node));
        when(weightRepository.findBySourceNodeAndTopicAndUserId(node, null, "user1"))
                .thenReturn(Optional.empty());

        service.removeWeight("n-1", null, "user1");
        verify(weightRepository, never()).delete(any());
    }

    @Test
    void removeWeight_nodeNotFound_throws() {
        when(nodeRepository.findByNodeId("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.removeWeight("missing", null, "user1"));
    }

    // ─── setWeightEnabled ───────────────────────────────────────────

    @Test
    void setWeightEnabled_updatesAndSaves() {
        GraphNode node = stubNode("n-1", "Source");
        SourceWeight sw = SourceWeight.builder()
                .sourceNode(node).baseWeight(1.0).enabled(true).build();
        when(nodeRepository.findByNodeId("n-1")).thenReturn(Optional.of(node));
        when(weightRepository.findBySourceNodeAndTopicAndUserId(node, null, "user1"))
                .thenReturn(Optional.of(sw));
        when(weightRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SourceWeight result = service.setWeightEnabled("n-1", null, "user1", false);
        assertFalse(result.getEnabled());
    }

    @Test
    void setWeightEnabled_weightNotFound_throws() {
        GraphNode node = stubNode("n-1", "Source");
        when(nodeRepository.findByNodeId("n-1")).thenReturn(Optional.of(node));
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
        when(nodeRepository.findByNodeId("n-1")).thenReturn(Optional.of(node));

        Double relevance = service.computeTopicRelevance("n-1", "Machine Learning");
        assertEquals(0.8, relevance);
    }

    @Test
    void computeTopicRelevance_topicNotInContent_returnsDefault() {
        GraphNode node = stubNode("n-1", "Finance Report");
        node.setDescription("Quarterly earnings");
        when(nodeRepository.findByNodeId("n-1")).thenReturn(Optional.of(node));

        Double relevance = service.computeTopicRelevance("n-1", "Kubernetes");
        assertEquals(0.5, relevance);
    }

    @Test
    void computeTopicRelevance_nodeNotFound_returnsDefault() {
        when(nodeRepository.findByNodeId("missing")).thenReturn(Optional.empty());

        Double relevance = service.computeTopicRelevance("missing", "topic");
        assertEquals(0.5, relevance);
    }

    @Test
    void computeTopicRelevance_nullTopic_returnsDefault() {
        GraphNode node = stubNode("n-1", "Title");
        when(nodeRepository.findByNodeId("n-1")).thenReturn(Optional.of(node));

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
        when(nodeRepository.findByNodeId("n-1")).thenReturn(Optional.of(node));
        when(weightRepository.findBySourceNodeAndTopicAndUserId(node, "ML", null))
                .thenReturn(Optional.empty());

        service.assignTopic("n-1", "ML");
        verify(weightRepository).save(any(SourceWeight.class));
    }

    @Test
    void assignTopic_existingWeight_noOp() {
        GraphNode node = stubNode("n-1", "Source");
        when(nodeRepository.findByNodeId("n-1")).thenReturn(Optional.of(node));
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
