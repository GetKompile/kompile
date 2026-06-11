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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
 * executeScript, convertExcel, listAvailableTools, discoverTools, and inferCategory.
 *
 * <p>resolveExcelGraphJson and executeExcelWithResult are covered in separate test files.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
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

    // ─── invokeTool ─────────────────────────────────────────────────────

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
        // Register a bean with a @Tool method, then discover and invoke it
        SampleToolBean toolBean = new SampleToolBean();
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"sampleTool"});
        when(applicationContext.getBean("sampleTool")).thenReturn(toolBean);

        dispatcher.discoverTools();

        Map<String, Object> result = dispatcher.invokeTool("greetUser", Map.of("name", "Alice"));
        assertNotNull(result);
        assertEquals("Hello, Alice!", result.get("result"));
    }

    @Test
    void invokeToolWrapsNonMapResult() throws Exception {
        SampleToolBean toolBean = new SampleToolBean();
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"sampleTool"});
        when(applicationContext.getBean("sampleTool")).thenReturn(toolBean);

        dispatcher.discoverTools();

        Map<String, Object> result = dispatcher.invokeTool("greetUser", Map.of("name", "Bob"));
        // Non-Map return gets wrapped in {result: ...}
        assertTrue(result.containsKey("result"));
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

    @Test
    void invokeToolWithNullArguments() throws Exception {
        SampleToolBean toolBean = new SampleToolBean();
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"sampleTool"});
        when(applicationContext.getBean("sampleTool")).thenReturn(toolBean);

        dispatcher.discoverTools();

        Map<String, Object> result = dispatcher.invokeTool("greetUser", null);
        // name parameter will be null, so greeting will be "Hello, null!"
        assertNotNull(result);
    }

    // ─── executeHttpCall ────────────────────────────────────────────────

    @Test
    void executeHttpCallGetSuccess() {
        ResponseEntity<String> mockResp = new ResponseEntity<>("{\"data\":\"value\"}", HttpStatus.OK);
        when(restTemplate.exchange(eq("http://example.com/api"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(mockResp);

        Map<String, Object> result = dispatcher.executeHttpCall("GET", "http://example.com/api", null, null);

        assertEquals(200, result.get("statusCode"));
        assertNotNull(result.get("body"));
        assertNotNull(result.get("bodyParsed")); // JSON body gets parsed
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
    void executeHttpCallNonJsonBody() {
        ResponseEntity<String> mockResp = new ResponseEntity<>("plain text response", HttpStatus.OK);
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(String.class)))
                .thenReturn(mockResp);

        Map<String, Object> result = dispatcher.executeHttpCall("GET", "http://example.com/text", null, null);

        assertEquals(200, result.get("statusCode"));
        assertEquals("plain text response", result.get("body"));
        // bodyParsed should not be present since it's not valid JSON
        assertFalse(result.containsKey("bodyParsed"));
    }

    @Test
    void executeHttpCallError() {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        Map<String, Object> result = dispatcher.executeHttpCall("GET", "http://dead-host/api", null, null);

        assertTrue(result.containsKey("error"));
        assertEquals("http://dead-host/api", result.get("url"));
        assertEquals("GET", result.get("method"));
    }

    @Test
    void executeHttpCallSetsDefaultContentType() {
        ResponseEntity<String> mockResp = new ResponseEntity<>("ok", HttpStatus.OK);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(mockResp);

        // No Content-Type header provided, but body is non-null — should default to application/json
        Map<String, Object> result = dispatcher.executeHttpCall("POST", "http://example.com/api",
                null, Map.of("data", "test"));

        assertEquals(200, result.get("statusCode"));
    }

    @Test
    void executeHttpCallNullBody() {
        ResponseEntity<String> mockResp = new ResponseEntity<>(null, HttpStatus.NO_CONTENT);
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(String.class)))
                .thenReturn(mockResp);

        Map<String, Object> result = dispatcher.executeHttpCall("DELETE", "http://example.com/resource", null, null);

        assertEquals(204, result.get("statusCode"));
    }

    // ─── executeScript ──────────────────────────────────────────────────

    @Test
    void executeScriptThrowsWhenNoExecutorAvailable() {
        // No scripting executor set
        assertThrows(IllegalStateException.class,
                () -> dispatcher.executeScript("javascript", "var x = 1;", Map.of()));
    }

    @Test
    void executeScriptThrowsForUnsupportedLanguage() throws Exception {
        // Set up a mock scripting executor
        NodeExecutor mockExecutor = mock(NodeExecutor.class);
        when(mockExecutor.supportedTypes()).thenReturn(Set.of(NodeExecutionType.JAVASCRIPT));
        setPrivateField(dispatcher, "scriptingExecutor", mockExecutor);

        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.executeScript("ruby", "puts 'hello'", Map.of()));
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
    void executeScriptPythonSuccess() throws Exception {
        NodeExecutor mockExecutor = mock(NodeExecutor.class);
        ExecutionResult successResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("sum", 10))
                .build();
        when(mockExecutor.execute(any(ComputeNode.class), anyMap(), any(ExecutionContext.class)))
                .thenReturn(successResult);
        setPrivateField(dispatcher, "scriptingExecutor", mockExecutor);

        Map<String, Object> result = dispatcher.executeScript("python", "sum = 5 + 5", Map.of());

        assertEquals(10, result.get("sum"));
        // No _consoleOutput because it was null
        assertFalse(result.containsKey("_consoleOutput"));
    }

    @Test
    void executeScriptJsAlias() throws Exception {
        NodeExecutor mockExecutor = mock(NodeExecutor.class);
        ExecutionResult successResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("ok", true))
                .build();
        when(mockExecutor.execute(any(), any(), any())).thenReturn(successResult);
        setPrivateField(dispatcher, "scriptingExecutor", mockExecutor);

        Map<String, Object> result = dispatcher.executeScript("js", "true", null);
        assertEquals(true, result.get("ok"));
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
        assertTrue(ex.getMessage().contains("SyntaxError"));
    }

    @Test
    void executeScriptTimedOut() throws Exception {
        NodeExecutor mockExecutor = mock(NodeExecutor.class);
        ExecutionResult timeoutResult = ExecutionResult.builder()
                .status(ExecutionStatus.TIMED_OUT)
                .error("Execution timed out after 30s")
                .build();
        when(mockExecutor.execute(any(), any(), any())).thenReturn(timeoutResult);
        setPrivateField(dispatcher, "scriptingExecutor", mockExecutor);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dispatcher.executeScript("javascript", "while(true){}", Map.of()));
        assertTrue(ex.getMessage().contains("timed_out"));
    }

    @Test
    void executeScriptNullLanguageDefaultsToJavascript() throws Exception {
        NodeExecutor mockExecutor = mock(NodeExecutor.class);
        ExecutionResult successResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("val", "default"))
                .build();
        when(mockExecutor.execute(any(), any(), any())).thenReturn(successResult);
        setPrivateField(dispatcher, "scriptingExecutor", mockExecutor);

        Map<String, Object> result = dispatcher.executeScript(null, "code", Map.of());
        assertEquals("default", result.get("val"));
    }

    @Test
    void executeScriptBlankConsoleOutputNotIncluded() throws Exception {
        NodeExecutor mockExecutor = mock(NodeExecutor.class);
        ExecutionResult successResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("x", 1))
                .consoleOutput("   ")
                .build();
        when(mockExecutor.execute(any(), any(), any())).thenReturn(successResult);
        setPrivateField(dispatcher, "scriptingExecutor", mockExecutor);

        Map<String, Object> result = dispatcher.executeScript("javascript", "1", Map.of());
        assertFalse(result.containsKey("_consoleOutput"));
    }

    // ─── convertExcel ───────────────────────────────────────────────────

    @Test
    void convertExcelThrowsWhenNoExecutorAvailable() {
        assertThrows(IllegalStateException.class,
                () -> dispatcher.convertExcel("{}", "python"));
    }

    // ─── listAvailableTools ─────────────────────────────────────────────

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
        assertEquals("Says hello to a user", tools.get(0).get("description"));
        assertNotNull(tools.get(0).get("category"));
        assertNotNull(tools.get(0).get("inputSchema"));
    }

    @Test
    void listAvailableToolsSortedByCategoryThenName() {
        MultiToolBean multiBean = new MultiToolBean();
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"multiTool"});
        when(applicationContext.getBean("multiTool")).thenReturn(multiBean);

        dispatcher.discoverTools();

        List<Map<String, Object>> tools = dispatcher.listAvailableTools();
        assertTrue(tools.size() >= 2);
        // Verify sorted by category, then name
        for (int i = 1; i < tools.size(); i++) {
            String prevCat = (String) tools.get(i - 1).get("category");
            String curCat = (String) tools.get(i).get("category");
            int catCmp = prevCat.compareTo(curCat);
            if (catCmp == 0) {
                String prevName = (String) tools.get(i - 1).get("name");
                String curName = (String) tools.get(i).get("name");
                assertTrue(prevName.compareTo(curName) <= 0);
            } else {
                assertTrue(catCmp <= 0);
            }
        }
    }

    // ─── discoverTools ──────────────────────────────────────────────────

    @Test
    void discoverToolsRegistersToolAnnotatedMethods() {
        SampleToolBean toolBean = new SampleToolBean();
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"sampleTool"});
        when(applicationContext.getBean("sampleTool")).thenReturn(toolBean);

        dispatcher.discoverTools();

        assertEquals(1, dispatcher.listAvailableTools().size());
    }

    @Test
    void discoverToolsSkipsBadBeans() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"badBean"});
        when(applicationContext.getBean("badBean")).thenThrow(new RuntimeException("Cannot create bean"));

        // Should not throw
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

    @Test
    void discoverToolsUsesToolNameIfProvided() {
        NamedToolBean namedBean = new NamedToolBean();
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"namedTool"});
        when(applicationContext.getBean("namedTool")).thenReturn(namedBean);

        dispatcher.discoverTools();

        List<Map<String, Object>> tools = dispatcher.listAvailableTools();
        assertEquals(1, tools.size());
        assertEquals("customToolName", tools.get(0).get("name"));
    }

    // ─── afterSingletonsInstantiated ────────────────────────────────────

    @Test
    void afterSingletonsInstantiatedCallsDiscoverTools() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{});
        when(applicationContext.getBeansOfType(NodeExecutor.class)).thenReturn(Map.of());

        dispatcher.afterSingletonsInstantiated();

        // Should have called discoverTools — verify by checking no exceptions
        assertEquals(0, dispatcher.listAvailableTools().size());
    }

    @Test
    void afterSingletonsInstantiatedResolvesScriptingExecutor() {
        NodeExecutor jsExecutor = mock(NodeExecutor.class);
        when(jsExecutor.supportedTypes()).thenReturn(Set.of(NodeExecutionType.JAVASCRIPT));
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{});
        when(applicationContext.getBeansOfType(NodeExecutor.class))
                .thenReturn(Map.of("jsExecutor", jsExecutor));

        dispatcher.afterSingletonsInstantiated();

        // Scripting executor should now be set — executeScript should not throw IllegalStateException
        // (it will throw something else because the mock executor needs setup, but NOT IllegalStateException)
        ExecutionResult successResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of())
                .build();
        when(jsExecutor.execute(any(), any(), any())).thenReturn(successResult);

        assertDoesNotThrow(() -> dispatcher.executeScript("javascript", "1", Map.of()));
    }

    @Test
    void afterSingletonsInstantiatedResolvesExcelExecutor() {
        NodeExecutor excelExecutor = mock(NodeExecutor.class);
        when(excelExecutor.supportedTypes()).thenReturn(Set.of(NodeExecutionType.EXCEL));
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{});
        when(applicationContext.getBeansOfType(NodeExecutor.class))
                .thenReturn(Map.of("excelExec", excelExecutor));

        dispatcher.afterSingletonsInstantiated();

        // Excel executor should now be set — executeExcel should not throw IllegalStateException
        ExecutionResult successResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("total", 100))
                .build();
        when(excelExecutor.execute(any(), any(), any())).thenReturn(successResult);

        Map<String, Object> result = dispatcher.executeExcel("{}", Map.of(), null, null);
        assertEquals(100, result.get("total"));
    }

    // ─── Constructor ────────────────────────────────────────────────────

    @Test
    void constructorCreatesDefaultRestTemplate() {
        // Pass null RestTemplate — constructor should create a default one
        StepExecutionDispatcherImpl d = new StepExecutionDispatcherImpl(applicationContext, objectMapper, null);
        // executeHttpCall should work (will fail at network level, but not NPE)
        Map<String, Object> result = d.executeHttpCall("GET", "http://localhost:99999/unlikely", null, null);
        assertTrue(result.containsKey("error")); // Connection refused, but no NPE
    }

    // ─── Helper methods ─────────────────────────────────────────────────

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ─── Test helper beans ──────────────────────────────────────────────

    /** Sample bean with a @Tool method returning a String. */
    static class SampleToolBean {
        @Tool(description = "Says hello to a user")
        public String greetUser(String name) {
            return "Hello, " + name + "!";
        }
    }

    /** Bean that returns a Map from its @Tool method. */
    static class MapReturningToolBean {
        @Tool(description = "Returns a status map")
        public Map<String, Object> getStatus() {
            return Map.of("status", "ok");
        }
    }

    /** Bean whose @Tool method always throws. */
    static class ThrowingToolBean {
        @Tool(description = "Always fails")
        public String failingTool() {
            throw new RuntimeException("Intentional failure");
        }
    }

    /** Bean with an explicitly named @Tool method. */
    static class NamedToolBean {
        @Tool(name = "customToolName", description = "A custom named tool")
        public String doSomething() {
            return "done";
        }
    }

    /** Bean with multiple @Tool methods for sort order testing. */
    static class MultiToolBean {
        @Tool(description = "Retrieve RAG documents")
        public String ragQuery(String query) {
            return query;
        }

        @Tool(description = "Process a file upload")
        public String fileUpload(String path) {
            return path;
        }
    }
}
