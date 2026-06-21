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
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.service.CommunitySummaryService.CommunityReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CommunitySummaryService} — community detection + LLM summarization for GLOBAL search.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommunitySummaryServiceTest {

    @Mock
    private LLMChat llmChat;
    @Mock
    private LLMChat.ChatClientRequestSpec promptSpec;
    @Mock
    private LLMChat.CallResponseSpec callResponseSpec;

    private CommunitySummaryService service;
    private AdjacencyMatrixGraph graph;

    @BeforeEach
    void setUp() {
        service = new CommunitySummaryService(null, llmChat); // no embedding model

        // Two disconnected triangles: {a,b,c} and {x,y,z}.
        graph = new AdjacencyMatrixGraph("g1", 16);
        for (String id : List.of("a", "b", "c", "x", "y", "z")) {
            graph.addNode(node(id));
        }
        graph.addEdge("a", "b", 1.0, "RELATED_TO", false);
        graph.addEdge("b", "c", 1.0, "RELATED_TO", false);
        graph.addEdge("a", "c", 1.0, "RELATED_TO", false);
        graph.addEdge("x", "y", 1.0, "RELATED_TO", false);
        graph.addEdge("y", "z", 1.0, "RELATED_TO", false);
        graph.addEdge("x", "z", 1.0, "RELATED_TO", false);

        when(llmChat.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("SUMMARY");
    }

    @AfterEach
    void tearDown() {
        if (graph != null) {
            graph.close();
        }
    }

    private MatrixGraphNode node(String id) {
        return MatrixGraphNode.builder().nodeId(id).nodeType("CONCEPT").title(id).build();
    }

    @Test
    void buildsOneReportPerCommunityCoveringAllMembers() {
        List<CommunityReport> reports = service.getOrBuildReports(graph);

        assertEquals(2, reports.size(), "two disconnected clusters → two community reports");
        Set<String> members = reports.stream()
                .flatMap(r -> r.memberNodeIds().stream())
                .collect(Collectors.toSet());
        assertEquals(Set.of("a", "b", "c", "x", "y", "z"), members);
        reports.forEach(r -> assertEquals("SUMMARY", r.summary()));
    }

    @Test
    void getOrBuildCachesReports() {
        service.getOrBuildReports(graph);
        service.getOrBuildReports(graph); // second call should hit the cache
        // 2 communities summarized once each on first build; cache hit means no further LLM calls.
        verify(llmChat, times(2)).prompt();
    }

    @Test
    void rebuildBypassesCache() {
        service.getOrBuildReports(graph);
        service.rebuildReports(graph);
        verify(llmChat, times(4)).prompt(); // 2 communities x 2 builds
    }

    @Test
    void noLlmFallsBackToRawDigest() {
        CommunitySummaryService noLlm = new CommunitySummaryService(null, null);
        List<CommunityReport> reports = noLlm.getOrBuildReports(graph);

        assertEquals(2, reports.size());
        assertTrue(reports.stream().allMatch(r -> r.summary().contains("Entities:")),
                "without an LLM the report falls back to the raw digest");
    }
}
