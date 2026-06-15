/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.diagram.service;

import ai.kompile.app.diagram.domain.DiagramSession;
import ai.kompile.app.diagram.repository.DiagramSessionRepository;
import ai.kompile.app.mcp.McpToolRegistry;
import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.agent.AgentChatService;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.core.mcp.EnhancedToolDefinition;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiagramGenerationServiceTest {

    @Mock
    private DiagramSessionRepository sessionRepository;

    @Mock
    private AgentChatService agentChatService;

    @Mock
    private ServerPortService serverPortService;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private McpToolRegistry mcpToolRegistry;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private MermaidProcessConverter mermaidConverter;
    private DiagramGenerationService service;

    @BeforeEach
    void setUp() throws Exception {
        mermaidConverter = new MermaidProcessConverter();
        service = new DiagramGenerationService(
                sessionRepository, agentChatService, mermaidConverter, serverPortService);

        // Inject optional KnowledgeGraphService
        Field kgField = DiagramGenerationService.class.getDeclaredField("knowledgeGraphService");
        kgField.setAccessible(true);
        kgField.set(service, knowledgeGraphService);

        // Inject optional McpToolRegistry
        Field registryField = DiagramGenerationService.class.getDeclaredField("mcpToolRegistry");
        registryField.setAccessible(true);
        registryField.set(service, mcpToolRegistry);

        // Default: registry returns empty so fallback catalog is used (matches existing tests)
        lenient().when(mcpToolRegistry.getToolsByCategory()).thenReturn(Map.of());

        // Default port service behavior
        lenient().when(serverPortService.getBaseUrl()).thenReturn("http://localhost:8080");
        lenient().when(serverPortService.getMcpApiUrl()).thenReturn("http://localhost:8080/api/mcp");
        lenient().when(serverPortService.getActualPort()).thenReturn(8080);
    }

    private String invokeBuildDiagramPrompt(String userPrompt, Long factSheetId) throws Exception {
        Method method = DiagramGenerationService.class.getDeclaredMethod(
                "buildDiagramPrompt", String.class, Long.class);
        method.setAccessible(true);
        return (String) method.invoke(service, userPrompt, factSheetId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Kompile instance location
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void promptContainsInstanceBaseUrl() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", 1L);

        assertTrue(prompt.contains("http://localhost:8080"), "prompt must include the instance base URL");
        assertTrue(prompt.contains("`http://localhost:8080`"), "base URL should be in code formatting");
    }

    @Test
    void promptContainsMcpEndpoint() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("http://localhost:8080/api/mcp"),
                "prompt must include the MCP endpoint URL");
    }

    @Test
    void promptReflectsCustomPort() throws Exception {
        when(serverPortService.getBaseUrl()).thenReturn("http://localhost:9090");
        when(serverPortService.getMcpApiUrl()).thenReturn("http://localhost:9090/api/mcp");
        when(serverPortService.getActualPort()).thenReturn(9090);
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 5L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("http://localhost:9090"),
                "prompt must reflect the actual port, not hardcoded 8080");
        assertFalse(prompt.contains("http://localhost:8080"),
                "prompt must not contain default port when running on custom port");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pre-flight status / tool availability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void promptShowsKnowledgeGraphAvailable() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 50L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("| Knowledge Graph | AVAILABLE |"),
                "KG should show as AVAILABLE in pre-flight status");
    }

    @Test
    void promptShowsKnowledgeGraphUnavailableWhenNull() throws Exception {
        // Remove the KG service to simulate unavailability
        Field kgField = DiagramGenerationService.class.getDeclaredField("knowledgeGraphService");
        kgField.setAccessible(true);
        kgField.set(service, null);

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("| Knowledge Graph | UNAVAILABLE |"),
                "KG should show as UNAVAILABLE when service is null");
        assertTrue(prompt.contains("**WARNING**: Knowledge graph is not available"),
                "prompt must warn that graph tools will fail");
        assertTrue(prompt.contains("knowledge_search") && prompt.contains("rag_query"),
                "prompt must instruct agent to use knowledge_search and rag_query as fallback");
    }

    @Test
    void promptWarnsWhenGraphIsEmpty() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 0L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("knowledge graph is empty"),
                "prompt must warn when graph has 0 nodes");
    }

    @Test
    void promptHandlesGraphStatisticsError() throws Exception {
        when(knowledgeGraphService.getGraphStatistics())
                .thenThrow(new RuntimeException("DB connection failed"));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        // Should not throw — error is caught and reported in the prompt
        assertTrue(prompt.contains("ERROR"), "prompt should report the stats error");
    }

    @Test
    void promptIncludesFactSheetSourceCount() throws Exception {
        GraphNode source1 = new GraphNode();
        source1.setNodeType(NodeLevel.SOURCE);
        source1.setFactSheetId(42L);
        GraphNode source2 = new GraphNode();
        source2.setNodeType(NodeLevel.SOURCE);
        source2.setFactSheetId(42L);
        GraphNode otherSource = new GraphNode();
        otherSource.setNodeType(NodeLevel.SOURCE);
        otherSource.setFactSheetId(99L);

        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 100L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of(source1, source2, otherSource));

        String prompt = invokeBuildDiagramPrompt("Find processes", 42L);

        assertTrue(prompt.contains("| Fact Sheet Sources | 2 |"),
                "prompt should show the count of sources for the given fact sheet");
    }

    @Test
    void promptShowsProcessEngineAvailable() throws Exception {
        // Process engine is not injected by default (null), need to set it
        Field peField = DiagramGenerationService.class.getDeclaredField("processEngineService");
        peField.setAccessible(true);
        // Leave it null — default state
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("| Process Engine |"),
                "prompt must show process engine status");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MCP tool documentation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void promptListsAllMcpToolNames() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        // Discovery tools
        assertTrue(prompt.contains("`graph_get_overview`"), "must list graph_get_overview");
        assertTrue(prompt.contains("`knowledge_search`"), "must list knowledge_search");
        assertTrue(prompt.contains("`knowledge_status`"), "must list knowledge_status");

        // Graph tools
        assertTrue(prompt.contains("`graph_search_nodes`"), "must list graph_search_nodes");
        assertTrue(prompt.contains("`graph_search_by_entity`"), "must list graph_search_by_entity");
        assertTrue(prompt.contains("`graph_get_document_entities`"), "must list graph_get_document_entities");
        assertTrue(prompt.contains("`graph_get_related_documents`"), "must list graph_get_related_documents");
        assertTrue(prompt.contains("`graph_find_connected`"), "must list graph_find_connected");
        assertTrue(prompt.contains("`graph_rag_query`"), "must list graph_rag_query");

        // RAG tool
        assertTrue(prompt.contains("`rag_query`"), "must list rag_query");
    }

    @Test
    void promptOmitsGraphToolsWhenKgUnavailable() throws Exception {
        Field kgField = DiagramGenerationService.class.getDeclaredField("knowledgeGraphService");
        kgField.setAccessible(true);
        kgField.set(service, null);

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        // Knowledge search and RAG should still appear
        assertTrue(prompt.contains("`knowledge_search`"), "knowledge_search must remain available");
        assertTrue(prompt.contains("`rag_query`"), "rag_query must remain available");

        // Graph tools should be noted as unavailable
        assertTrue(prompt.contains("Knowledge graph is not available") || prompt.contains("graph_*") && prompt.contains("not available"),
                "prompt must tell agent that graph tools are not available");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REST API reference
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void promptIncludesRestApiEndpoints() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", 42L);

        // Fact sheet endpoints
        assertTrue(prompt.contains("/api/fact-sheets"), "must include fact sheet API");
        assertTrue(prompt.contains("/api/fact-sheets/active"), "must include active fact sheet endpoint");
        assertTrue(prompt.contains("/api/fact-sheets/42"), "must include fact-sheet-specific endpoint");
        assertTrue(prompt.contains("/api/fact-sheets/42/facts"), "must include facts listing endpoint");
        assertTrue(prompt.contains("/api/fact-sheets/42/indexing-stats"),
                "must include indexing stats endpoint");

        // Knowledge graph endpoints
        assertTrue(prompt.contains("/api/knowledge-graph/statistics"), "must include KG stats endpoint");
        assertTrue(prompt.contains("/api/knowledge-graph/nodes"), "must include KG nodes endpoint");
        assertTrue(prompt.contains("/api/knowledge-graph/topics"), "must include KG topics endpoint");

        // Fact-sheet-scoped graph endpoints
        assertTrue(prompt.contains("/api/fact-sheets/42/graph/statistics"),
                "must include fact-sheet-scoped graph stats");
        assertTrue(prompt.contains("/api/fact-sheets/42/graph/search"),
                "must include fact-sheet-scoped graph search");

        // Index and RAG endpoints
        assertTrue(prompt.contains("/api/indexer/status"), "must include indexer status endpoint");
        assertTrue(prompt.contains("/api/rag/query"), "must include RAG query endpoint");
        assertTrue(prompt.contains("/api/graph-rag/search"), "must include graph-rag search endpoint");
        assertTrue(prompt.contains("/api/graph-rag/info"), "must include graph-rag info endpoint");
    }

    @Test
    void promptRestUrlsUseActualBaseUrl() throws Exception {
        when(serverPortService.getBaseUrl()).thenReturn("http://localhost:9090");
        when(serverPortService.getMcpApiUrl()).thenReturn("http://localhost:9090/api/mcp");
        when(serverPortService.getActualPort()).thenReturn(9090);
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", 1L);

        // Base URL must appear in the REST API section header
        assertTrue(prompt.contains("http://localhost:9090"),
                "REST section must reference actual base URL, not hardcoded port");
    }

    @Test
    void promptOmitsFactSheetScopedEndpointsWhenNoFactSheet() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        // General endpoints should still be present
        assertTrue(prompt.contains("/api/fact-sheets"),
                "must include general fact sheet endpoint");
        assertTrue(prompt.contains("/api/knowledge-graph/statistics"),
                "must include global KG stats");

        // Fact-sheet-specific endpoints should NOT be present
        assertFalse(prompt.contains("/api/fact-sheets/null"),
                "must not generate endpoints with null fact sheet ID");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error handling guidance
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void promptIncludesErrorHandlingGuidance() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("isError=true"), "must explain isError flag");
        assertTrue(prompt.contains("denied by permission policy"),
                "must explain permission denial errors");
        assertTrue(prompt.contains("blocked by gateway"),
                "must explain gateway block errors");
        assertTrue(prompt.contains("method not found"),
                "must explain unregistered tool errors");
        assertTrue(prompt.contains("fall back to"),
                "must provide fallback guidance when tools fail");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Research workflow
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void promptIncludesAssessDataAvailabilityStep() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("Step 1: Assess data availability"),
                "workflow must begin with data availability check");
        assertTrue(prompt.contains("knowledge_status"),
                "step 1 must reference knowledge_status tool");
    }

    @Test
    void promptAdaptsWorkflowWhenKgUnavailable() throws Exception {
        Field kgField = DiagramGenerationService.class.getDeclaredField("knowledgeGraphService");
        kgField.setAccessible(true);
        kgField.set(service, null);

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        // Graph tools should not be recommended when KG is down
        assertFalse(prompt.contains("`graph_get_overview`** — Returns source nodes"),
                "graph_get_overview usage details must not appear when KG is unavailable");
        assertTrue(prompt.contains("knowledge_search") && prompt.contains("rag_query"),
                "must suggest knowledge_search and rag_query as alternatives");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mermaid shape-to-StepType mapping
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void promptDocumentsAllShapeToStepTypeMappings() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        // Verify all shape-to-type mappings are documented
        assertTrue(prompt.contains("AUTO") && prompt.contains("`A[Label]`"),
                "must document rectangle → AUTO mapping");
        assertTrue(prompt.contains("HUMAN") && prompt.contains("`A([Label])`"),
                "must document stadium → HUMAN mapping");
        assertTrue(prompt.contains("CONTROL_GATE") && prompt.contains("`A{Label}`"),
                "must document diamond → CONTROL_GATE mapping");
        assertTrue(prompt.contains("PIPELINE") && prompt.contains("`A[[Label]]`"),
                "must document subroutine → PIPELINE mapping");
        assertTrue(prompt.contains("SCRIPT") && prompt.contains("`A[/Label/]`"),
                "must document parallelogram → SCRIPT mapping");
        assertTrue(prompt.contains("TOOL_CALL") && prompt.contains("`A>Label]`"),
                "must document asymmetric → TOOL_CALL mapping");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Output format
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void promptSpecifiesRequiredOutputSections() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("## Process Title"), "must require Process Title section");
        assertTrue(prompt.contains("## Description"), "must require Description section");
        assertTrue(prompt.contains("## Diagram"), "must require Diagram section");
        assertTrue(prompt.contains("## Sources"), "must require Sources section");
        assertTrue(prompt.contains("```mermaid"), "must include mermaid code fence in example");
        assertTrue(prompt.contains("flowchart TD"), "example must use flowchart TD");
        assertTrue(prompt.contains("subgraph"), "example must demonstrate subgraphs");
        assertTrue(prompt.contains("classDef"), "example must demonstrate classDef styles");
    }

    @Test
    void promptRequiresStructuredSourceCitations() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("\"nodeId\""), "sources must require nodeId field");
        assertTrue(prompt.contains("\"sourceId\""), "sources must require sourceId field");
        assertTrue(prompt.contains("\"extractedText\""), "sources must require extractedText field");
        assertTrue(prompt.contains("JSON array"), "sources format must be JSON array");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // startGeneration — E2E request construction
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void startGenerationSetsAllChatRequestFlags() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());
        when(sessionRepository.save(any(DiagramSession.class)))
                .thenAnswer(inv -> {
                    DiagramSession s = inv.getArgument(0);
                    s.setId(1L);
                    return s;
                });

        SseEmitter emitter = new SseEmitter();
        service.startGeneration("Find purchase order flow", "claude", 42L, emitter);

        ArgumentCaptor<AgentChatRequest> captor = ArgumentCaptor.forClass(AgentChatRequest.class);
        verify(agentChatService).executeChat(captor.capture(), eq(emitter));

        AgentChatRequest req = captor.getValue();
        assertTrue(req.isInjectMcpTools(), "MCP tools must be injected");
        assertTrue(req.isEnableRag(), "RAG must be enabled");
        assertTrue(req.isEnableGraphRag(), "GraphRAG must be enabled");
        assertTrue(req.isIncludeKeywordSearch(), "keyword search must be enabled");
        assertTrue(req.isIncludeSemanticSearch(), "semantic search must be enabled");
        assertTrue(req.isSkipPermissions(), "permissions must be skipped for diagram agent");
        assertEquals("claude", req.getAgentName());
        assertEquals(600, req.getTimeoutSeconds());
    }

    @Test
    void startGenerationAugmentsPromptWithContextAndTools() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 25L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());
        when(sessionRepository.save(any(DiagramSession.class)))
                .thenAnswer(inv -> {
                    DiagramSession s = inv.getArgument(0);
                    s.setId(1L);
                    return s;
                });

        SseEmitter emitter = new SseEmitter();
        service.startGeneration("Map the invoice approval process", "claude", 7L, emitter);

        ArgumentCaptor<AgentChatRequest> captor = ArgumentCaptor.forClass(AgentChatRequest.class);
        verify(agentChatService).executeChat(captor.capture(), eq(emitter));

        String msg = captor.getValue().getMessage();

        // The augmented prompt must contain the user's original prompt
        assertTrue(msg.contains("Map the invoice approval process"),
                "augmented prompt must contain the user's original prompt");

        // Must contain operating context
        assertTrue(msg.contains("Pre-flight Status"), "augmented prompt must include pre-flight status");
        assertTrue(msg.contains("Operating Environment"), "augmented prompt must include instance info");

        // Must contain tool documentation
        assertTrue(msg.contains("knowledge_search"), "augmented prompt must document primary tools");

        // Must contain REST API reference
        assertTrue(msg.contains("/api/fact-sheets/7"), "augmented prompt must include fact-sheet-specific REST URLs");

        // Must contain Mermaid requirements
        assertTrue(msg.contains("flowchart TD"), "augmented prompt must include Mermaid requirements");

        // Must contain error handling
        assertTrue(msg.contains("Error Handling"), "augmented prompt must include error handling section");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dynamic MCP tool catalog from McpToolRegistry
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, List<EnhancedToolDefinition>> buildSampleToolsByCategory() throws Exception {
        JsonNode ragSchema = MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"maxResults\":{\"type\":\"integer\"}},\"required\":[\"query\"]}");
        JsonNode graphSchema = MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"nodeId\":{\"type\":\"string\"},\"depth\":{\"type\":\"integer\",\"enum\":[1,2,3]}},\"required\":[\"nodeId\"]}");

        EnhancedToolDefinition ragQuery = EnhancedToolDefinition.builder()
                .name("rag_query").description("Query the document corpus").category("rag")
                .inputSchema(ragSchema).enabled(true).build();
        EnhancedToolDefinition knowledgeSearch = EnhancedToolDefinition.builder()
                .name("knowledge_search").description("Fan-out search").category("rag")
                .inputSchema(null).enabled(true).build();
        EnhancedToolDefinition graphFind = EnhancedToolDefinition.builder()
                .name("graph_find_connected").description("Find connected nodes").category("knowledge_graph")
                .inputSchema(graphSchema).enabled(true).build();
        EnhancedToolDefinition disabledTool = EnhancedToolDefinition.builder()
                .name("disabled_tool").description("Should not appear").category("rag")
                .enabled(false).build();

        Map<String, List<EnhancedToolDefinition>> result = new LinkedHashMap<>();
        result.put("rag", List.of(ragQuery, knowledgeSearch, disabledTool));
        result.put("knowledge_graph", List.of(graphFind));
        return result;
    }

    @Test
    void dynamicCatalogListsToolsFromRegistry() throws Exception {
        when(mcpToolRegistry.getToolsByCategory()).thenReturn(buildSampleToolsByCategory());
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("`rag_query`"), "dynamic catalog must list rag_query");
        assertTrue(prompt.contains("`knowledge_search`"), "dynamic catalog must list knowledge_search");
        assertTrue(prompt.contains("`graph_find_connected`"), "dynamic catalog must list graph_find_connected");
        assertTrue(prompt.contains("Query the document corpus"), "dynamic catalog must include tool descriptions");
    }

    @Test
    void dynamicCatalogOmitsDisabledTools() throws Exception {
        when(mcpToolRegistry.getToolsByCategory()).thenReturn(buildSampleToolsByCategory());
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertFalse(prompt.contains("`disabled_tool`"), "disabled tools must be omitted from catalog");
        assertFalse(prompt.contains("Should not appear"), "disabled tool description must not appear");
    }

    @Test
    void dynamicCatalogExtractsParamsFromSchema() throws Exception {
        when(mcpToolRegistry.getToolsByCategory()).thenReturn(buildSampleToolsByCategory());
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        // rag_query has query (string, required) and maxResults (integer)
        assertTrue(prompt.contains("`query` (string, required)"), "must extract required param from schema");
        assertTrue(prompt.contains("`maxResults` (integer)"), "must extract optional param from schema");
    }

    @Test
    void dynamicCatalogShowsEnumValues() throws Exception {
        when(mcpToolRegistry.getToolsByCategory()).thenReturn(buildSampleToolsByCategory());
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        // graph_find_connected has depth with enum [1,2,3]
        assertTrue(prompt.contains("1|2|3"), "must show enum values from schema");
    }

    @Test
    void dynamicCatalogMarksGraphToolsUnavailableWhenKgDown() throws Exception {
        // Set KG to null
        Field kgField = DiagramGenerationService.class.getDeclaredField("knowledgeGraphService");
        kgField.setAccessible(true);
        kgField.set(service, null);

        when(mcpToolRegistry.getToolsByCategory()).thenReturn(buildSampleToolsByCategory());

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("`graph_find_connected`** **(UNAVAILABLE)**"),
                "graph tools must be marked UNAVAILABLE when KG is down");
        // Non-graph tools should not be marked unavailable
        assertFalse(prompt.contains("`rag_query`** **(UNAVAILABLE)**"),
                "non-graph tools should not be marked unavailable");
    }

    @Test
    void dynamicCatalogFormatsCategoryNames() throws Exception {
        Map<String, List<EnhancedToolDefinition>> cats = new LinkedHashMap<>();
        cats.put("rag", List.of(EnhancedToolDefinition.builder()
                .name("rag_query").description("Query").category("rag").enabled(true).build()));
        cats.put("knowledge_graph", List.of(EnhancedToolDefinition.builder()
                .name("graph_search").description("Search").category("knowledge_graph").enabled(true).build()));
        cats.put("process_engine", List.of(EnhancedToolDefinition.builder()
                .name("process_list").description("List").category("process_engine").enabled(true).build()));

        when(mcpToolRegistry.getToolsByCategory()).thenReturn(cats);
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("### RAG"), "rag should be formatted as RAG");
        assertTrue(prompt.contains("### Knowledge Graph"), "knowledge_graph should be formatted as Knowledge Graph");
        assertTrue(prompt.contains("### Process Engine"), "process_engine should be formatted as Process Engine");
    }

    @Test
    void fallbackCatalogUsedWhenRegistryNull() throws Exception {
        // Set registry to null
        Field registryField = DiagramGenerationService.class.getDeclaredField("mcpToolRegistry");
        registryField.setAccessible(true);
        registryField.set(service, null);

        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        // Fallback catalog has specific sections
        assertTrue(prompt.contains("### Discovery Tools"), "fallback must include Discovery Tools section");
        assertTrue(prompt.contains("### Knowledge Graph Tools"), "fallback must include KG tools section");
        assertTrue(prompt.contains("### RAG Tool"), "fallback must include RAG tool section");
        assertTrue(prompt.contains("Additional tools"), "fallback must note additional tools may exist");
    }

    @Test
    void fallbackCatalogUsedWhenRegistryThrows() throws Exception {
        when(mcpToolRegistry.getToolsByCategory()).thenThrow(new RuntimeException("registry error"));
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        // Should not throw — falls back gracefully
        assertTrue(prompt.contains("### Discovery Tools"), "fallback must be used when registry throws");
    }

    @Test
    void dynamicCatalogSkipsNullParamSchema() throws Exception {
        // knowledge_search has null inputSchema — should not crash
        when(mcpToolRegistry.getToolsByCategory()).thenReturn(buildSampleToolsByCategory());
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        // knowledge_search should appear without a Params line
        assertTrue(prompt.contains("`knowledge_search`"), "tool with null schema must still appear");
        assertTrue(prompt.contains("Fan-out search"), "tool description must appear even without schema");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Autonomy instructions
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void promptDeclaresAgentAutonomous() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("fully autonomous"),
                "prompt must declare the agent is autonomous");
        assertTrue(prompt.contains("Do not ask the user"),
                "prompt must instruct agent not to ask for user input");
    }

    @Test
    void promptExplainsPreFetchedContext() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("Context above this message"),
                "prompt must explain that RAG context may appear above");
        assertTrue(prompt.contains("Retrieved Context"),
                "prompt must reference the Retrieved Context section name");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data model / crawl explanation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void promptExplainsCrawledDataHierarchy() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("How Crawled Data Is Organized"),
                "prompt must explain the data model");
        assertTrue(prompt.contains("FactSheet"),
                "prompt must mention FactSheet as top-level concept");
        assertTrue(prompt.contains("Facts"),
                "prompt must mention Facts (documents)");
        assertTrue(prompt.contains("Chunks"),
                "prompt must mention Chunks");
        assertTrue(prompt.contains("SOURCE") && prompt.contains("DOCUMENT") && prompt.contains("SNIPPET") && prompt.contains("ENTITY"),
                "prompt must show the graph node hierarchy");
    }

    @Test
    void promptExplainsThreeSearchBackends() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("Keyword index"),
                "prompt must explain keyword search backend");
        assertTrue(prompt.contains("Vector store"),
                "prompt must explain vector search backend");
        assertTrue(prompt.contains("Knowledge graph"),
                "prompt must explain graph search backend");
        assertTrue(prompt.contains("fans out across ALL three backends"),
                "prompt must explain knowledge_search fans out to all backends");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Key tool return shapes
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void promptDocumentsKnowledgeSearchReturnShape() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("results: [{content, source, relevance}]"),
                "prompt must document knowledge_search return shape");
        assertTrue(prompt.contains("graph_context"),
                "prompt must mention graph_context in knowledge_search response");
    }

    @Test
    void promptDocumentsRagQueryReturnShape() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("retrieved_documents"),
                "prompt must document rag_query return shape");
        assertTrue(prompt.contains("source_filename"),
                "prompt must mention source_filename in rag_query metadata");
        assertTrue(prompt.contains("content_type=table"),
                "prompt must explain table content handling");
        assertTrue(prompt.contains("fetch_result"),
                "prompt must explain truncation + fetch_result follow-up");
    }

    @Test
    void promptDocumentsFactSheetScopingWhenIdPresent() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));
        when(knowledgeGraphService.getAllSources()).thenReturn(List.of());

        String prompt = invokeBuildDiagramPrompt("Find processes", 42L);

        assertTrue(prompt.contains("Fact Sheet ID: 42"),
                "prompt must show the active fact sheet ID");
        assertTrue(prompt.contains("scoped to this fact sheet"),
                "prompt must explain fact sheet scoping");
        assertTrue(prompt.contains("vector store"),
                "prompt must mention per-sheet vector store");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ground rules
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void promptContainsGroundRules() throws Exception {
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 10L));

        String prompt = invokeBuildDiagramPrompt("Find processes", null);

        assertTrue(prompt.contains("DO NOT fabricate steps"),
                "prompt must forbid fabrication");
        assertTrue(prompt.contains("DO NOT ask the user"),
                "prompt must forbid user interaction");
        assertTrue(prompt.contains("grounded in data from the tools"),
                "prompt must require tool-grounded steps");
    }
}
