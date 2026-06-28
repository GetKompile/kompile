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
package ai.kompile.knowledgegraph.controller;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.SourceWeight;
import ai.kompile.knowledgegraph.domain.SourceWeightView;
import ai.kompile.knowledgegraph.service.GraphBuildingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.service.SourceLinkingService;
import ai.kompile.knowledgegraph.service.SourceWeightingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the source-weight endpoints of {@link KnowledgeGraphController}.
 *
 * <p>These drive the real Spring MVC stack via {@code MockMvcBuilders.standaloneSetup(controller)}
 * — request mapping, query/body binding, and Jackson JSON serialization — and assert on the
 * wire-format JSON. That is what guards the flat-vs-nested {@link SourceWeightView} contract that
 * a plain method-call (ResponseEntity) test cannot see. No live server, no Spring Boot context.
 *
 * <p>Mirrors the house pattern in {@code MultiAgentGraphControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeGraphControllerWeightsMockMvcTest {

    @Mock private KnowledgeGraphService graphService;
    @Mock private SourceWeightingService weightingService;
    @Mock private GraphBuildingService graphBuildingService;
    @Mock private SourceLinkingService sourceLinkingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        KnowledgeGraphController controller = new KnowledgeGraphController(
                graphService, weightingService, graphBuildingService, sourceLinkingService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getWeights_noSourceId_returnsFlatSourceWeightViewJson() throws Exception {
        SourceWeightView view = new SourceWeightView(
                7L, "node-1", "Quarterly Report", "PDF", null, null,
                2.0, 2.0, 0.9, 0.5, 1.0, true);
        when(weightingService.listAllSourcesWithWeights()).thenReturn(List.of(view));

        mockMvc.perform(get("/api/knowledge-graph/weights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceNodeId").value("node-1"))
                .andExpect(jsonPath("$[0].sourceName").value("Quarterly Report"))
                .andExpect(jsonPath("$[0].sourceType").value("PDF"))
                .andExpect(jsonPath("$[0].baseWeight").value(2.0))
                .andExpect(jsonPath("$[0].enabled").value(true))
                // The flattened DTO must not leak a nested sourceNode object (the original bug).
                .andExpect(jsonPath("$[0].sourceNode").doesNotExist());

        verify(weightingService).listAllSourcesWithWeights();
        verify(weightingService, never()).getAllWeightsForSource(anyString());
    }

    @Test
    void getWeights_withSourceId_flattensEntityThroughView() throws Exception {
        GraphNode node = new GraphNode();
        node.setNodeId("node-9");
        node.setTitle("Annual 10-K");
        node.setSourceType("HTML");
        SourceWeight sw = SourceWeight.builder()
                .sourceNode(node).baseWeight(1.5).effectiveWeight(1.5).enabled(true).build();
        when(weightingService.getAllWeightsForSource("node-9")).thenReturn(List.of(sw));

        mockMvc.perform(get("/api/knowledge-graph/weights").param("sourceId", "node-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceNodeId").value("node-9"))
                .andExpect(jsonPath("$[0].sourceName").value("Annual 10-K"))
                .andExpect(jsonPath("$[0].sourceType").value("HTML"))
                .andExpect(jsonPath("$[0].baseWeight").value(1.5))
                .andExpect(jsonPath("$[0].sourceNode").doesNotExist());

        verify(weightingService).getAllWeightsForSource("node-9");
        verify(weightingService, never()).listAllSourcesWithWeights();
    }

    @Test
    void previewWeightedSearch_serializesRelevanceScoreAndNote() throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sourceId", "node-1");
        item.put("sourceName", "Aligned source");
        item.put("sourceType", "PDF");
        item.put("weight", 2.0);
        item.put("relevance", 0.8);
        item.put("score", 1.6);

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("query", "revenue growth");
        preview.put("maxResults", 5);
        preview.put("sourceWeights", List.of(item));
        preview.put("note", "Ranked by semantic relevance to your query × the configured source weight.");
        when(weightingService.previewWeightedSearch("revenue growth", 5)).thenReturn(preview);

        mockMvc.perform(post("/api/knowledge-graph/weights/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"revenue growth\",\"maxResults\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("revenue growth"))
                .andExpect(jsonPath("$.sourceWeights[0].sourceId").value("node-1"))
                .andExpect(jsonPath("$.sourceWeights[0].weight").value(2.0))
                .andExpect(jsonPath("$.sourceWeights[0].relevance").value(0.8))
                .andExpect(jsonPath("$.sourceWeights[0].score").value(1.6))
                .andExpect(jsonPath("$.note").value(containsString("relevance")));

        verify(weightingService).previewWeightedSearch("revenue growth", 5);
    }

    @Test
    void previewWeightedSearch_defaultsMaxResultsWhenOmitted() throws Exception {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("query", "q");
        preview.put("sourceWeights", List.of());
        when(weightingService.previewWeightedSearch("q", 10)).thenReturn(preview);

        mockMvc.perform(post("/api/knowledge-graph/weights/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"q\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("q"));

        // No maxResults in the body → controller substitutes its default of 10.
        verify(weightingService).previewWeightedSearch("q", 10);
    }
}
