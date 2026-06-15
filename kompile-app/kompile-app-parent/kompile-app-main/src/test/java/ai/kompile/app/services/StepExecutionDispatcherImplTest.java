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

package ai.kompile.app.services;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.ComputeNode;
import ai.kompile.compute.graph.model.ExecutionResult;
import ai.kompile.compute.graph.model.ExecutionStatus;
import ai.kompile.compute.graph.model.NodeExecutionType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.service.DispatchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link StepExecutionDispatcherImpl} — covers invokeTool, executeHttpCall,
 * executeScript, convertExcel, listAvailableTools, discoverTools.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StepExecutionDispatcherImplTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StepExecutionDispatcherImpl dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new StepExecutionDispatcherImpl(applicationContext, objectMapper, restTemplate);
    }

    // --- invokeTool ---

    @Test
    void invokeToolThrowsForUnknownTool() {
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.invokeTool("nonexistent-tool", Map.of()));
    }

    @Test
    void invokeToolErrorMessageIncludesToolName() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dispatcher.invokeTool("myMissingTool", Map.of()));
        assertTrue(ex.getMessage().contains("myMissingTool"));
    }

    @Test
    void invokeToolAfterDiscoverTools() throws Exception {
        SampleToolBean toolBean = new SampleToolBean();
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"sampleTool"});
        when(applicationContext.getBean("sampleTool")).thenReturn(toolBean);

        dispatcher.discoverTools();

        Map<String, Object> result = dispatcher.invokeTool("greetUser", Map.of("name", "Alice"));
        assertNotNull(result);
        assertEquals("Hello, Alice!", result.get("result"));
    }

    @Test
    void invokeToolReturnsMapDirectly() throws Exception {
        MapReturningToolBean toolBean = new MapReturningToolBean();
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"mapTool"});
        when(applicationContext.getBean("mapTool")).thenReturn(toolBean);

        dispatcher.discoverTools();

        Map<String, Object> result = dispatcher.invokeTool("getStatus", Map.of());
        assertEquals("ok", result.get("status"));
    }

    @Test
    void invokeToolReturnsErrorOnException() throws Exception {
        ThrowingToolBean toolBean = new ThrowingToolBean();
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"throwingTool"});
        when(applicationContext.getBean("throwingTool")).thenReturn(toolBean);

        dispatcher.discoverTools();

        Map<String, Object> result = dispatcher.invokeTool("failingTool", Map.of());
        assertTrue(result.containsKey("error"));
        assertEquals("failingTool", result.get("toolName"));
    }

    // --- executeHttpCall ---

    @Test
    void executeHttpCallGetSuccess() {
        ResponseEntity<String> mockResp = new ResponseEntity<>("{\"data\":\"value\"}", HttpStatus.OK);
        when(restTemplate.exchange(eq("http://example.com/api"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(mockResp);

        Map<String, Object> result = dispatcher.executeHttpCall("GET", "http://example.com/api", null, null);

        assertEquals(200, result.get("statusCode"));
        assertNotNull(result.get("body"));
        assertNotNull(result.get("bodyParsed"));
    }

    @Test
    void executeHttpCallPostWithHeaders() {
        ResponseEntity<String> mockResp = new ResponseEntity<>("created", HttpStatus.CREATED);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(mockResp);

        Map<String, String> headers = Map.of("Authorization", "Bearer token123");
        Map<String, Object> result = dispatcher.executeHttpCall("POST", "http://example.com/api",
                headers, Map.of("key", "value"));

        assertEquals(201, result.get("statusCode"));
    }

    @Test
    void executeHttpCallError() {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        Map<String, Object> result = dispatcher.executeHttpCall("GET", "http://dead-host/api", null, null);

        assertTrue(result.containsKey("error"));
        assertEquals("http://dead-host/api", result.get("url"));
    }

    // --- executeScript ---

    @Test
    void executeScriptThrowsWhenNoExecutorAvailable() {
        assertThrows(IllegalStateException.class,
                () -> dispatcher.executeScript("javascript", "var x = 1;", Map.of()));
    }

    @Test
    void executeScriptJavascriptSuccess() throws Exception {
        NodeExecutor mockExecutor = mock(NodeExecutor.class);
        ExecutionResult successResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("result", 42))
                .consoleOutput("log output")
                .build();
        when(mockExecutor.execute(any(ComputeNode.class), anyMap(), any(ExecutionContext.class)))
                .thenReturn(successResult);
        setPrivateField(dispatcher, "scriptingExecutor", mockExecutor);

        Map<String, Object> result = dispatcher.executeScript("javascript", "return 42;", Map.of("x", 1));

        assertEquals(42, result.get("result"));
        assertEquals("log output", result.get("_consoleOutput"));
    }

    @Test
    void executeScriptFailedExecution() throws Exception {
        NodeExecutor mockExecutor = mock(NodeExecutor.class);
        ExecutionResult failResult = ExecutionResult.builder()
                .status(ExecutionStatus.FAILED)
                .error("SyntaxError: unexpected token")
                .build();
        when(mockExecutor.execute(any(), any(), any())).thenReturn(failResult);
        setPrivateField(dispatcher, "scriptingExecutor", mockExecutor);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dispatcher.executeScript("javascript", "bad code", Map.of()));
        assertTrue(ex.getMessage().contains("failed"));
    }

    // --- convertExcel ---

    @Test
    void convertExcelThrowsWhenNoExecutorAvailable() {
        assertThrows(IllegalStateException.class,
                () -> dispatcher.convertExcel("{}", "python"));
    }

    // --- listAvailableTools ---

    @Test
    void listAvailableToolsEmptyByDefault() {
        List<Map<String, Object>> tools = dispatcher.listAvailableTools();
        assertTrue(tools.isEmpty());
    }

    @Test
    void listAvailableToolsAfterDiscovery() {
        SampleToolBean toolBean = new SampleToolBean();
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"sampleTool"});
        when(applicationContext.getBean("sampleTool")).thenReturn(toolBean);

        dispatcher.discoverTools();

        List<Map<String, Object>> tools = dispatcher.listAvailableTools();
        assertEquals(1, tools.size());
        assertEquals("greetUser", tools.get(0).get("name"));
    }

    // --- discoverTools ---

    @Test
    void discoverToolsSkipsBadBeans() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"badBean"});
        when(applicationContext.getBean("badBean")).thenThrow(new RuntimeException("Cannot create bean"));

        dispatcher.discoverTools();

        assertEquals(0, dispatcher.listAvailableTools().size());
    }

    @Test
    void discoverToolsIgnoresBeansWithNoToolMethods() {
        Object plainBean = new Object();
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"plainBean"});
        when(applicationContext.getBean("plainBean")).thenReturn(plainBean);

        dispatcher.discoverTools();

        assertEquals(0, dispatcher.listAvailableTools().size());
    }

    // --- afterSingletonsInstantiated ---

    @Test
    void afterSingletonsInstantiatedResolvesExcelExecutor() {
        NodeExecutor excelExecutor = mock(NodeExecutor.class);
        when(excelExecutor.supportedTypes()).thenReturn(Set.of(NodeExecutionType.EXCEL));
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{});
        when(applicationContext.getBeansOfType(NodeExecutor.class))
                .thenReturn(Map.of("excelExec", excelExecutor));

        dispatcher.afterSingletonsInstantiated();

        ExecutionResult successResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("total", 100))
                .build();
        when(excelExecutor.execute(any(), any(), any())).thenReturn(successResult);

        Map<String, Object> result = dispatcher.executeExcel("{}", Map.of(), null, null);
        assertEquals(100, result.get("total"));
    }

    // --- resolveExcelGraphJson ---

    @Test
    void resolveExcelGraphJsonReturnsNullForNullInput() throws Exception {
        KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        setPrivateField(dispatcher, "knowledgeGraphService", kgService);

        assertNull(dispatcher.resolveExcelGraphJson(null));
        assertNull(dispatcher.resolveExcelGraphJson(List.of()));
    }

    @Test
    void resolveExcelGraphJsonReturnsNullWhenNoKgService() {
        assertNull(dispatcher.resolveExcelGraphJson(List.of("node-1")));
    }

    @Test
    void resolveExcelGraphJsonFindsFormulaGraphInMetadata() throws Exception {
        KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        setPrivateField(dispatcher, "knowledgeGraphService", kgService);

        String formulaJson = "{\"entities\":[],\"relationships\":[]}";
        String metaJson = objectMapper.writeValueAsString(Map.of("formulaGraph", formulaJson));
        GraphNode node = GraphNode.builder()
                .nodeId("node-1").nodeType(NodeLevel.DOCUMENT).metadataJson(metaJson).build();
        when(kgService.getNode("node-1")).thenReturn(Optional.of(node));

        String result = dispatcher.resolveExcelGraphJson(List.of("node-1"));
        assertNotNull(result);
        // Result is normalized to SpreadsheetGraph format by TableGraphAdapter
        assertTrue(result.contains("\"cells\""), "Should be converted to SpreadsheetGraph format");
    }

    @Test
    void resolveExcelGraphJsonReturnsNullWhenNodeNotFound() throws Exception {
        KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        setPrivateField(dispatcher, "knowledgeGraphService", kgService);
        when(kgService.getNode(anyString())).thenReturn(Optional.empty());

        assertNull(dispatcher.resolveExcelGraphJson(List.of("missing-node")));
    }

    // --- executeExcelWithResult ---

    @Test
    void executeExcelWithResultReturnsDispatchResult() throws Exception {
        NodeExecutor excelExec = mock(NodeExecutor.class);
        KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        setPrivateField(dispatcher, "excelExecutor", excelExec);
        setPrivateField(dispatcher, "knowledgeGraphService", kgService);

        ExecutionResult execResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("total", 42))
                .build();
        when(excelExec.execute(any(), any(), any())).thenReturn(execResult);

        // Graph JSON with one entity — mock the KG lookup to find it
        String graphJson = "{\"entities\":[{\"id\":\"cell:A1\",\"type\":\"CELL\"}]}";
        GraphNode cellNode = GraphNode.builder().nodeId("kg-cell-1").build();
        when(kgService.getNodeByExternalId("cell:A1", NodeLevel.ENTITY))
                .thenReturn(Optional.of(cellNode));

        DispatchResult result = dispatcher.executeExcelWithResult(graphJson, Map.of(), null, null);

        assertNotNull(result);
        assertEquals(42, result.getOutputs().get("total"));
        assertTrue(result.getDiscoveredGraphNodeIds().contains("kg-cell-1"));
    }

    @Test
    void executeExcelWithResultHandlesNullGraphJson() throws Exception {
        NodeExecutor excelExec = mock(NodeExecutor.class);
        setPrivateField(dispatcher, "excelExecutor", excelExec);

        ExecutionResult execResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("result", "ok"))
                .build();
        when(excelExec.execute(any(), any(), any())).thenReturn(execResult);

        DispatchResult result = dispatcher.executeExcelWithResult(null, Map.of(), null, null);

        assertNotNull(result);
        assertEquals("ok", result.getOutputs().get("result"));
        assertTrue(result.getDiscoveredGraphNodeIds().isEmpty());
    }

    @Test
    void executeExcelWithResultHandlesKgException() throws Exception {
        NodeExecutor excelExec = mock(NodeExecutor.class);
        KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        setPrivateField(dispatcher, "excelExecutor", excelExec);
        setPrivateField(dispatcher, "knowledgeGraphService", kgService);

        ExecutionResult execResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("val", 1))
                .build();
        when(excelExec.execute(any(), any(), any())).thenReturn(execResult);
        when(kgService.getNodeByExternalId(anyString(), any()))
                .thenThrow(new RuntimeException("KG unavailable"));

        String graphJson = "{\"entities\":[{\"id\":\"cell:B2\",\"type\":\"CELL\"}]}";
        DispatchResult result = dispatcher.executeExcelWithResult(graphJson, Map.of(), null, null);

        assertNotNull(result);
        assertEquals(1, result.getOutputs().get("val"));
        // Should still succeed — KG failures are swallowed
        assertTrue(result.getDiscoveredGraphNodeIds().isEmpty());
    }

    // --- resolveExcelGraphJson: tableGraph support ---

    @Test
    void resolveExcelGraphJsonFindsTableGraphKey() throws Exception {
        KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        setPrivateField(dispatcher, "knowledgeGraphService", kgService);

        String tableGraphJson = "{\"entities\":[{\"type\":\"TABLE\"}],\"relationships\":[]}";
        String metaJson = objectMapper.writeValueAsString(Map.of("tableGraph", tableGraphJson));
        GraphNode node = GraphNode.builder()
                .nodeId("node-html-table").nodeType(NodeLevel.DOCUMENT).metadataJson(metaJson).build();
        when(kgService.getNode("node-html-table")).thenReturn(Optional.of(node));

        String result = dispatcher.resolveExcelGraphJson(List.of("node-html-table"));
        assertNotNull(result);
        // TableGraphAdapter normalizes Graph format to SpreadsheetGraph
        assertTrue(result.contains("\"cells\""), "Should be converted to SpreadsheetGraph format");
    }

    @Test
    void resolveExcelGraphJsonPrefersFormulaGraphOverTableGraph() throws Exception {
        KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        setPrivateField(dispatcher, "knowledgeGraphService", kgService);

        // Use SpreadsheetGraph format for formulaGraph so we can identify which was chosen
        String formulaJson = "{\"workbookName\":\"Budget\",\"cells\":{\"Budget!A1\":{\"cellReference\":\"Budget!A1\"}},\"dependencies\":[]}";
        String tableJson = "{\"entities\":[{\"type\":\"TABLE\",\"title\":\"OtherTable\"}],\"relationships\":[]}";
        String metaJson = objectMapper.writeValueAsString(
                Map.of("formulaGraph", formulaJson, "tableGraph", tableJson));
        GraphNode node = GraphNode.builder()
                .nodeId("node-1").nodeType(NodeLevel.DOCUMENT).metadataJson(metaJson).build();
        when(kgService.getNode("node-1")).thenReturn(Optional.of(node));

        String result = dispatcher.resolveExcelGraphJson(List.of("node-1"));
        assertNotNull(result);
        // formulaGraph is already SpreadsheetGraph format, should be returned as-is
        assertTrue(result.contains("\"Budget\""), "Should prefer formulaGraph when both keys are present");
    }

    @Test
    void resolveExcelGraphJsonWalksFromTableNodeToParentDocument() throws Exception {
        KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        setPrivateField(dispatcher, "knowledgeGraphService", kgService);

        // TABLE node with entity_subtype:table
        String tableMeta = objectMapper.writeValueAsString(Map.of("entity_subtype", "table"));
        GraphNode tableNode = GraphNode.builder()
                .nodeId("table-node-1").nodeType(NodeLevel.TABLE).metadataJson(tableMeta).build();

        // Parent DOCUMENT node with graph JSON in Graph format
        String graphJson = "{\"entities\":[{\"type\":\"TABLE\",\"title\":\"Revenue\"}],\"relationships\":[]}";
        String docMeta = objectMapper.writeValueAsString(Map.of("formulaGraph", graphJson));
        GraphNode docNode = GraphNode.builder()
                .nodeId("doc-node-1").nodeType(NodeLevel.DOCUMENT).metadataJson(docMeta).build();

        // Edge from doc -> table (CONTAINS)
        GraphEdge edge = GraphEdge.builder()
                .edgeId("edge-1").sourceNode(docNode).targetNode(tableNode).build();

        when(kgService.getNode("table-node-1")).thenReturn(Optional.of(tableNode));
        when(kgService.getEdgesForNode("table-node-1")).thenReturn(List.of(edge));
        when(kgService.getNode("doc-node-1")).thenReturn(Optional.of(docNode));

        String result = dispatcher.resolveExcelGraphJson(List.of("table-node-1"));
        assertNotNull(result);
        // Walked to parent DOCUMENT and normalized to SpreadsheetGraph format
        assertTrue(result.contains("\"cells\""), "Should be converted to SpreadsheetGraph format");
    }

    @Test
    void resolveExcelGraphJsonRecognizesTableSubtype() throws Exception {
        KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        setPrivateField(dispatcher, "knowledgeGraphService", kgService);

        // TABLE node with entity_subtype:table (not sheet)
        String tableMeta = objectMapper.writeValueAsString(Map.of("entity_subtype", "table"));
        GraphNode tableNode = GraphNode.builder()
                .nodeId("tbl-1").nodeType(NodeLevel.TABLE).metadataJson(tableMeta).build();

        when(kgService.getNode("tbl-1")).thenReturn(Optional.of(tableNode));
        when(kgService.getEdgesForNode("tbl-1")).thenReturn(List.of());

        // Should not throw — should recognize entity_subtype:table and attempt parent walk
        String result = dispatcher.resolveExcelGraphJson(List.of("tbl-1"));
        assertNull(result, "Should return null when no parent DOCUMENT found, but not throw");
    }

    // --- Helpers ---

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    static class SampleToolBean {
        @Tool(description = "Says hello to a user")
        public String greetUser(String name) {
            return "Hello, " + name + "!";
        }
    }

    static class MapReturningToolBean {
        @Tool(description = "Returns a status map")
        public Map<String, Object> getStatus() {
            return Map.of("status", "ok");
        }
    }

    static class ThrowingToolBean {
        @Tool(description = "Always fails")
        public String failingTool() {
            throw new RuntimeException("Intentional failure");
        }
    }
}
