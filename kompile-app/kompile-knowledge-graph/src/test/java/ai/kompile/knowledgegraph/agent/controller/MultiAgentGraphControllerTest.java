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
package ai.kompile.knowledgegraph.agent.controller;

import ai.kompile.app.core.chunking.HtmlChunker;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.AgentContribution;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.GraphMergeStrategy;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.MergedGraphResult;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.knowledgegraph.agent.MultiAgentExtractionService;
import ai.kompile.knowledgegraph.agent.MultiAgentExtractionService.AgentInfo;
import ai.kompile.knowledgegraph.agent.MultiAgentExtractionService.PersistenceSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc unit tests for {@link MultiAgentGraphController}.
 *
 * <p>All tests use {@code MockMvcBuilders.standaloneSetup(controller)} — no Spring Boot
 * context is started.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiAgentGraphControllerTest {

    @Mock
    private MultiAgentExtractionService extractionService;

    @Mock
    private HtmlChunker htmlChunker;

    @Mock
    private ExtractionLlmServiceRegistry llmServiceRegistry;

    private MultiAgentGraphController controller;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        controller = new MultiAgentGraphController(extractionService, htmlChunker);
        // Inject optional fields via reflection-free setter approach
        // (fields are @Autowired(required = false) so we set them directly)
        setField(controller, "llmServiceRegistry", llmServiceRegistry);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Could not inject field " + fieldName, e);
        }
    }

    private static MergedGraphResult emptyResult(GraphMergeStrategy strategy) {
        Graph g = new Graph();
        g.setEntities(List.of());
        g.setRelationships(List.of());
        return new MergedGraphResult(g, Map.of(), 0, 0, 10L, strategy);
    }

    private static MergedGraphResult resultWithEntities(List<Entity> entities, GraphMergeStrategy strategy) {
        Graph g = new Graph();
        g.setEntities(new ArrayList<>(entities));
        g.setRelationships(List.of());
        AgentContribution contrib = new AgentContribution(
                "test-agent", entities.size(), 0, entities.size(), 0, 10L,
                Set.of("PERSON"), Set.of());
        Map<String, AgentContribution> contribs = new LinkedHashMap<>();
        contribs.put("test-agent", contrib);
        return new MergedGraphResult(g, contribs, entities.size(), 0, 10L, strategy);
    }

    private static Entity entity(String id, String type, Double confidence) {
        Entity e = new Entity();
        e.setId(id);
        e.setTitle(id);
        e.setType(type);
        e.setConfidence(confidence);
        return e;
    }

    // ─── GET /agents ──────────────────────────────────────────────────────────

    @Test
    void listAgents_returnsAgentList() throws Exception {
        List<AgentInfo> agents = List.of(
                new AgentInfo("pattern-ner", "Pattern NER", Set.of()),
                new AgentInfo("llm-active", "LLM extraction", Set.of())
        );
        when(extractionService.getAvailableAgents()).thenReturn(agents);

        mockMvc.perform(get("/api/graph/multi-agent/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("pattern-ner"))
                .andExpect(jsonPath("$[1].id").value("llm-active"));
    }

    @Test
    void listAgents_emptyList_returns200WithEmptyArray() throws Exception {
        when(extractionService.getAvailableAgents()).thenReturn(List.of());

        mockMvc.perform(get("/api/graph/multi-agent/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ─── GET /strategies ─────────────────────────────────────────────────────

    @Test
    void listStrategies_returnsAllStrategies() throws Exception {
        mockMvc.perform(get("/api/graph/multi-agent/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(GraphMergeStrategy.values().length));
    }

    @Test
    void listStrategies_eachEntryHasNameAndDescription() throws Exception {
        mockMvc.perform(get("/api/graph/multi-agent/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].description").exists());
    }

    @Test
    void listStrategies_containsUnionStrategy() throws Exception {
        mockMvc.perform(get("/api/graph/multi-agent/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='UNION')]").exists());
    }

    // ─── GET /providers ──────────────────────────────────────────────────────

    @Test
    void listProviders_withRegistry_returnsProviderList() throws Exception {
        List<ExtractionLlmServiceRegistry.ProviderInfo> providers = List.of(
                new ExtractionLlmServiceRegistry.ProviderInfo("llm-chat", "Local LLM", true, null)
        );
        when(llmServiceRegistry.listProviders()).thenReturn(providers);

        mockMvc.perform(get("/api/graph/multi-agent/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void listProviders_noRegistry_returnsEmptyList() throws Exception {
        // Remove the registry
        setField(controller, "llmServiceRegistry", null);

        mockMvc.perform(get("/api/graph/multi-agent/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ─── POST /extract ────────────────────────────────────────────────────────

    @Test
    void extract_withSingleChunk_returnsExtractionResponse() throws Exception {
        MergedGraphResult result = resultWithEntities(
                List.of(entity("alice", "PERSON", 0.9)), GraphMergeStrategy.UNION);
        doReturn(result).when(extractionService).runExtraction(anyList(), any(), anyString(), any());

        String body = """
                {
                  "chunkTexts": [{"id":"c1","text":"Alice founded Acme Corp.","metadata":{}}],
                  "mergeStrategy": "UNION",
                  "agentIds": ["pattern-ner"]
                }
                """;

        mockMvc.perform(post("/api/graph/multi-agent/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEntities").value(1))
                .andExpect(jsonPath("$.strategy").value("UNION"));
    }

    @Test
    void extract_emptyChunks_returnsZeroEntities() throws Exception {
        MergedGraphResult result = emptyResult(GraphMergeStrategy.UNION);
        doReturn(result).when(extractionService).runExtraction(anyList(), any(), anyString(), any());

        String body = """
                {
                  "chunkTexts": [],
                  "mergeStrategy": "UNION"
                }
                """;

        mockMvc.perform(post("/api/graph/multi-agent/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEntities").value(0));
    }

    @Test
    void extract_withNullStrategy_defaultsToUnion() throws Exception {
        MergedGraphResult result = emptyResult(GraphMergeStrategy.UNION);
        doReturn(result).when(extractionService).runExtraction(anyList(), any(), isNull(), any());

        String body = """
                {
                  "chunkTexts": [{"id":"c1","text":"Some text.","metadata":{}}]
                }
                """;

        mockMvc.perform(post("/api/graph/multi-agent/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void extract_withMultipleEntitiesAndRelations_returnsFullGraph() throws Exception {
        Entity alice = entity("alice", "PERSON", 0.9);
        Entity acme = entity("acme", "ORGANIZATION", 0.85);

        Relationship rel = new Relationship();
        rel.setSource("alice");
        rel.setTarget("acme");
        rel.setType("FOUNDED");
        rel.setConfidence(0.8);

        Graph g = new Graph();
        g.setEntities(List.of(alice, acme));
        g.setRelationships(List.of(rel));

        MergedGraphResult result = new MergedGraphResult(g, Map.of(), 2, 1, 50L, GraphMergeStrategy.UNION);
        doReturn(result).when(extractionService).runExtraction(anyList(), any(), anyString(), any());

        String body = """
                {
                  "chunkTexts": [{"id":"c1","text":"Alice founded Acme.","metadata":{}}],
                  "mergeStrategy": "UNION"
                }
                """;

        mockMvc.perform(post("/api/graph/multi-agent/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEntities").value(2))
                .andExpect(jsonPath("$.totalRelations").value(1))
                .andExpect(jsonPath("$.entities.length()").value(2))
                .andExpect(jsonPath("$.relations.length()").value(1));
    }

    // ─── POST /extract-and-persist ────────────────────────────────────────────

    @Test
    void extractAndPersist_noKnowledgeGraphService_returns503() throws Exception {
        // knowledgeGraphService is null (not injected)
        String body = """
                {
                  "chunkTexts": [{"id":"c1","text":"Alice founded Acme.","metadata":{}}],
                  "mergeStrategy": "UNION"
                }
                """;

        mockMvc.perform(post("/api/graph/multi-agent/extract-and-persist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }

    // ─── POST /extract-from-html ──────────────────────────────────────────────

    @Test
    void extractFromHtml_missingFilePath_returns400() throws Exception {
        String body = """
                {
                  "mergeStrategy": "UNION"
                }
                """;

        mockMvc.perform(post("/api/graph/multi-agent/extract-from-html")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void extractFromHtml_nonExistentFile_returns400() throws Exception {
        String body = """
                {
                  "filePath": "/tmp/this-file-does-not-exist-12345.html",
                  "mergeStrategy": "UNION"
                }
                """;

        mockMvc.perform(post("/api/graph/multi-agent/extract-from-html")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void extractFromHtml_noHtmlChunker_returns503() throws Exception {
        // Remove htmlChunker from the controller by creating a new instance without it
        MultiAgentGraphController noChunkerController =
                new MultiAgentGraphController(extractionService, null);
        MockMvc noChunkerMvc = MockMvcBuilders.standaloneSetup(noChunkerController).build();

        String body = """
                {
                  "filePath": "/tmp/some.html",
                  "mergeStrategy": "UNION"
                }
                """;

        noChunkerMvc.perform(post("/api/graph/multi-agent/extract-from-html")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }

    // ─── Strategy descriptions ────────────────────────────────────────────────

    @Test
    void listStrategies_unionDescriptionMentionsDeduplication() throws Exception {
        mockMvc.perform(get("/api/graph/multi-agent/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='UNION')].description")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("deduplicated"))));
    }

    @Test
    void listStrategies_intersectionDescriptionMentionsTwoAgents() throws Exception {
        mockMvc.perform(get("/api/graph/multi-agent/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='INTERSECTION')].description")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("two"))));
    }
}
