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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.community.CommunityAssignment;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain JUnit 5 + Mockito unit tests for {@link GraphCommunityService}.
 *
 * <p>No Spring context is loaded. All KnowledgeGraphService calls are mocked.</p>
 */
@ExtendWith(MockitoExtension.class)
class GraphCommunityServiceTest {

    @Mock
    private KnowledgeGraphService graphService;

    private GraphCommunityService service;

    @BeforeEach
    void setUp() {
        service = new GraphCommunityService(graphService);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Empty fact-sheet → trivial / empty result
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("detectCommunities_louvain_emptyFactSheet_returnsEmptyCommunityResult")
    void detectCommunities_louvain_emptyFactSheet_returnsEmptyCommunityResult() {
        long factSheetId = 99L;

        // No nodes in the fact sheet
        when(graphService.getNodesInFactSheet(anyLong())).thenReturn(List.of());
        // The adapter calls getNode for each seed — but with no seeds it is never called.
        // Edges are also never fetched for an empty graph.

        GraphCommunityService.CommunityResult result =
                service.detectCommunities(factSheetId, "louvain", 1.0, 42L, 500);

        assertThat(result).isNotNull();
        assertThat(result.factSheetId()).isEqualTo(factSheetId);
        // An empty graph yields 0 assignments and communityCount 0
        // (CommunityAssignment.communityCount = maxId + 1, maxId = -1 for empty → 0)
        assertThat(result.nodeToCommunit()).isEmpty();
        assertThat(result.communityCount()).isLessThanOrEqualTo(1);
        // Modularity for an empty graph is 0.0
        assertThat(result.modularity()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Three disconnected nodes → all assigned (at least 1 community)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("detectCommunities_louvain_threeDisconnectedNodes_returnsThreeCommunities")
    void detectCommunities_louvain_threeDisconnectedNodes_returnsThreeCommunities() {
        long factSheetId = 1L;

        GraphNode n1 = mock(GraphNode.class);
        GraphNode n2 = mock(GraphNode.class);
        GraphNode n3 = mock(GraphNode.class);
        when(n1.getNodeId()).thenReturn("node1");
        when(n2.getNodeId()).thenReturn("node2");
        when(n3.getNodeId()).thenReturn("node3");

        when(graphService.getNodesInFactSheet(factSheetId)).thenReturn(List.of(n1, n2, n3));

        // The adapter does a BFS: for each seed it calls getNode then getEdgesForNode.
        // Return each node present but with no edges (disconnected).
        when(graphService.getNode("node1")).thenReturn(Optional.of(n1));
        when(graphService.getNode("node2")).thenReturn(Optional.of(n2));
        when(graphService.getNode("node3")).thenReturn(Optional.of(n3));
        when(graphService.getEdgesForNode(anyString())).thenReturn(List.of());

        // Stub fields accessed by KnowledgeGraphReasoningAdapter.toEntity()
        when(n1.getConfidence()).thenReturn(1.0);
        when(n2.getConfidence()).thenReturn(1.0);
        when(n3.getConfidence()).thenReturn(1.0);
        when(n1.getTitle()).thenReturn("Node 1");
        when(n2.getTitle()).thenReturn("Node 2");
        when(n3.getTitle()).thenReturn("Node 3");
        when(n1.getNodeType()).thenReturn(null);
        when(n2.getNodeType()).thenReturn(null);
        when(n3.getNodeType()).thenReturn(null);
        when(n1.getKgEmbedding()).thenReturn(null);
        when(n2.getKgEmbedding()).thenReturn(null);
        when(n3.getKgEmbedding()).thenReturn(null);
        when(n1.getOccurredAt()).thenReturn(null);
        when(n2.getOccurredAt()).thenReturn(null);
        when(n3.getOccurredAt()).thenReturn(null);

        GraphCommunityService.CommunityResult result =
                service.detectCommunities(factSheetId, "louvain", 1.0, 0L, 500);

        assertThat(result).isNotNull();
        assertThat(result.communityCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.nodeToCommunit().keySet())
                .containsExactlyInAnyOrder("node1", "node2", "node3");
        assertThat(result.method()).isEqualTo("louvain");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Label-propagation dispatch
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("detectCommunities_labelPropagation_dispatchesCorrectDetector")
    void detectCommunities_labelPropagation_dispatchesCorrectDetector() {
        long factSheetId = 2L;

        GraphNode n1 = mock(GraphNode.class);
        GraphNode n2 = mock(GraphNode.class);
        GraphNode n3 = mock(GraphNode.class);
        when(n1.getNodeId()).thenReturn("a");
        when(n2.getNodeId()).thenReturn("b");
        when(n3.getNodeId()).thenReturn("c");

        when(graphService.getNodesInFactSheet(factSheetId)).thenReturn(List.of(n1, n2, n3));
        when(graphService.getNode("a")).thenReturn(Optional.of(n1));
        when(graphService.getNode("b")).thenReturn(Optional.of(n2));
        when(graphService.getNode("c")).thenReturn(Optional.of(n3));
        when(graphService.getEdgesForNode(anyString())).thenReturn(List.of());

        when(n1.getConfidence()).thenReturn(1.0);
        when(n2.getConfidence()).thenReturn(1.0);
        when(n3.getConfidence()).thenReturn(1.0);
        when(n1.getTitle()).thenReturn("A");
        when(n2.getTitle()).thenReturn("B");
        when(n3.getTitle()).thenReturn("C");
        when(n1.getNodeType()).thenReturn(null);
        when(n2.getNodeType()).thenReturn(null);
        when(n3.getNodeType()).thenReturn(null);
        when(n1.getKgEmbedding()).thenReturn(null);
        when(n2.getKgEmbedding()).thenReturn(null);
        when(n3.getKgEmbedding()).thenReturn(null);
        when(n1.getOccurredAt()).thenReturn(null);
        when(n2.getOccurredAt()).thenReturn(null);
        when(n3.getOccurredAt()).thenReturn(null);

        GraphCommunityService.CommunityResult result =
                service.detectCommunities(factSheetId, "label_propagation", 1.0, 7L, 500);

        assertThat(result).isNotNull();
        assertThat(result.method()).isEqualTo("label_propagation");
        assertThat(result.nodeToCommunit().keySet()).containsExactlyInAnyOrder("a", "b", "c");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Unknown method → defaults to louvain
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("detectCommunities_unknownMethod_defaultsToLouvain")
    void detectCommunities_unknownMethod_defaultsToLouvain() {
        long factSheetId = 3L;

        when(graphService.getNodesInFactSheet(factSheetId)).thenReturn(List.of());

        GraphCommunityService.CommunityResult result =
                service.detectCommunities(factSheetId, "unknown_algo", 1.0, 0L, 500);

        assertThat(result).isNotNull();
        assertThat(result.method()).isEqualTo("louvain");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. communityMembers() static helper inverts the assignment correctly
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("communityMembers_static_invertsCommunityAssignment")
    void communityMembers_static_invertsCommunityAssignment() {
        // node1 and node2 → community 0; node3 → community 1
        Map<String, Integer> assignments = Map.of(
                "node1", 0,
                "node2", 0,
                "node3", 1
        );
        CommunityAssignment communityAssignment = new CommunityAssignment(assignments, 0.35);

        Map<Integer, List<String>> members = GraphCommunityService.communityMembers(communityAssignment);

        assertThat(members).containsKey(0);
        assertThat(members).containsKey(1);
        assertThat(members.get(0)).containsExactlyInAnyOrder("node1", "node2");
        assertThat(members.get(1)).containsExactlyInAnyOrder("node3");
    }
}
