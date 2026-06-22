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

import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.reasoning.CommunitySummaryService.CommunitySummaryResult;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Plain JUnit 5 + Mockito unit tests for {@link CommunitySummaryService}.
 *
 * <p>No Spring context is loaded. {@link GraphCommunityService} and
 * {@link KnowledgeGraphService} are mocked so that the service can be tested
 * without graph infrastructure. {@link LLMChat} is also mocked to verify
 * that the prompt is sent and the response forwarded correctly.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommunitySummaryServiceTest {

    @Mock
    private GraphCommunityService communityService;

    @Mock
    private KnowledgeGraphService graphService;

    @Mock
    private LLMChat llmChat;

    @Mock
    private LLMChat.ChatClientRequestSpec promptSpec;

    @Mock
    private LLMChat.CallResponseSpec callResponseSpec;

    private CommunitySummaryService service;

    /** A minimal CommunityResult with two communities: 0 → [node1, node2], 1 → [node3]. */
    private GraphCommunityService.CommunityResult twoCommunitiesResult;

    @BeforeEach
    void setUp() {
        service = new CommunitySummaryService(communityService, graphService, llmChat);

        Map<String, Integer> assignments = Map.of(
                "node1", 0,
                "node2", 0,
                "node3", 1
        );
        Map<Integer, List<String>> members = Map.of(
                0, List.of("node1", "node2"),
                1, List.of("node3")
        );

        twoCommunitiesResult = new GraphCommunityService.CommunityResult(
                assignments, 0.42, 2, members, 1L, "louvain");

        // Wire LLMChat chain: llmChat.prompt().user(…).call().content()
        when(llmChat.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Happy path: LLM returns a summary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("summarise_withLlm_returnsLlmSummary")
    void summarise_withLlm_returnsLlmSummary() {
        long factSheetId = 1L;
        int communityId = 0;

        when(communityService.detectCommunities(eq(factSheetId), anyString(), anyDouble(), anyLong(), anyInt()))
                .thenReturn(twoCommunitiesResult);

        GraphNode n1 = nodeWithTitle("node1", "Alpha", NodeLevel.ENTITY);
        GraphNode n2 = nodeWithTitle("node2", "Beta", NodeLevel.ENTITY);
        when(graphService.getNode("node1")).thenReturn(Optional.of(n1));
        when(graphService.getNode("node2")).thenReturn(Optional.of(n2));
        when(callResponseSpec.content()).thenReturn("Alpha and Beta form a tight cluster of entities.");

        CommunitySummaryResult result = service.summarise(factSheetId, communityId);

        assertThat(result).isNotNull();
        assertThat(result.communityId()).isEqualTo(communityId);
        assertThat(result.memberCount()).isEqualTo(2);
        assertThat(result.summary()).isEqualTo("Alpha and Beta form a tight cluster of entities.");

        // Verify the LLM was invoked exactly once
        verify(llmChat, times(1)).prompt();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. No LLM configured → falls back to raw digest
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("summarise_noLlm_returnsRawDigest")
    void summarise_noLlm_returnsRawDigest() {
        long factSheetId = 1L;
        int communityId = 0;

        CommunitySummaryService noLlmService =
                new CommunitySummaryService(communityService, graphService, null);

        when(communityService.detectCommunities(eq(factSheetId), anyString(), anyDouble(), anyLong(), anyInt()))
                .thenReturn(twoCommunitiesResult);

        GraphNode n1 = nodeWithTitle("node1", "Alpha", NodeLevel.ENTITY);
        GraphNode n2 = nodeWithTitle("node2", "Beta", NodeLevel.ENTITY);
        when(graphService.getNode("node1")).thenReturn(Optional.of(n1));
        when(graphService.getNode("node2")).thenReturn(Optional.of(n2));

        CommunitySummaryResult result = noLlmService.summarise(factSheetId, communityId);

        assertThat(result).isNotNull();
        assertThat(result.summary()).contains("Alpha");
        assertThat(result.summary()).contains("Beta");
        // Should not call llmChat at all
        verifyNoInteractions(llmChat);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. LLM throws → graceful fallback, no exception propagated
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("summarise_llmThrows_returnsFallback")
    void summarise_llmThrows_returnsFallback() {
        long factSheetId = 1L;
        int communityId = 0;

        when(communityService.detectCommunities(eq(factSheetId), anyString(), anyDouble(), anyLong(), anyInt()))
                .thenReturn(twoCommunitiesResult);

        when(graphService.getNode("node1")).thenReturn(Optional.empty());
        when(graphService.getNode("node2")).thenReturn(Optional.empty());
        when(callResponseSpec.content()).thenThrow(new RuntimeException("LLM unreachable"));

        CommunitySummaryResult result = service.summarise(factSheetId, communityId);

        assertThat(result).isNotNull();
        assertThat(result.communityId()).isEqualTo(communityId);
        assertThat(result.summary()).contains("LLM summary unavailable");
        // memberCount still correct
        assertThat(result.memberCount()).isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Community has no members → special message, no LLM call
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("summarise_emptyMembership_returnsNoMembersMessage")
    void summarise_emptyMembership_returnsNoMembersMessage() {
        long factSheetId = 1L;
        int communityId = 99; // non-existent community id

        when(communityService.detectCommunities(eq(factSheetId), anyString(), anyDouble(), anyLong(), anyInt()))
                .thenReturn(twoCommunitiesResult);

        CommunitySummaryResult result = service.summarise(factSheetId, communityId);

        assertThat(result).isNotNull();
        assertThat(result.memberCount()).isEqualTo(0);
        assertThat(result.summary()).contains("no members");
        verifyNoInteractions(llmChat);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Community detection itself fails → graceful error message
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("summarise_detectionFails_returnsUnavailableMessage")
    void summarise_detectionFails_returnsUnavailableMessage() {
        long factSheetId = 42L;
        int communityId = 0;

        when(communityService.detectCommunities(eq(factSheetId), anyString(), anyDouble(), anyLong(), anyInt()))
                .thenThrow(new RuntimeException("Graph store offline"));

        CommunitySummaryResult result = service.summarise(factSheetId, communityId);

        assertThat(result).isNotNull();
        assertThat(result.memberCount()).isEqualTo(0);
        assertThat(result.summary()).contains("unavailable");
        verifyNoInteractions(llmChat);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Node resolution fails for one member → skipped gracefully
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("summarise_oneNodeResolutionFails_otherMembersStillIncluded")
    void summarise_oneNodeResolutionFails_otherMembersStillIncluded() {
        long factSheetId = 1L;
        int communityId = 0;

        when(communityService.detectCommunities(eq(factSheetId), anyString(), anyDouble(), anyLong(), anyInt()))
                .thenReturn(twoCommunitiesResult);

        // node1 resolves fine; node2 throws
        GraphNode n1 = nodeWithTitle("node1", "Alpha", NodeLevel.ENTITY);
        when(graphService.getNode("node1")).thenReturn(Optional.of(n1));
        when(graphService.getNode("node2")).thenThrow(new RuntimeException("store error"));
        when(callResponseSpec.content()).thenReturn("Alpha cluster.");

        CommunitySummaryResult result = service.summarise(factSheetId, communityId);

        assertThat(result).isNotNull();
        // Should still get a result — LLM was called despite node2 failure
        assertThat(result.summary()).isEqualTo("Alpha cluster.");
        assertThat(result.memberCount()).isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static GraphNode nodeWithTitle(String nodeId, String title, NodeLevel type) {
        GraphNode node = mock(GraphNode.class);
        when(node.getNodeId()).thenReturn(nodeId);
        when(node.getTitle()).thenReturn(title);
        when(node.getNodeType()).thenReturn(type);
        return node;
    }
}
