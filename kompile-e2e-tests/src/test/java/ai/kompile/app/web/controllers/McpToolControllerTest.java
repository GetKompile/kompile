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
package ai.kompile.app.web.controllers;

import ai.kompile.app.services.mcp.McpActionLogService;
import ai.kompile.core.mcp.bridge.RestMcpBridgeManager;
import ai.kompile.core.mcp.server.McpServerManager;
import ai.kompile.tool.filesystem.FilesystemToolImpl;
import ai.kompile.tool.rag.RagToolImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpToolControllerTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private McpActionLogService actionLogService;

    @Mock
    private RagToolImpl ragToolImpl;

    @Mock
    private FilesystemToolImpl filesystemToolImpl;

    @Mock
    private McpServerManager mcpServerManager;

    @Mock
    private RestMcpBridgeManager restMcpBridgeManager;

    private ObjectMapper objectMapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private McpToolController controller;

    @BeforeEach
    void setUp() {
        // Return empty bean names to avoid complex bean scanning in unit test
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{});
        when(mcpServerManager.listServers()).thenReturn(java.util.Collections.emptyList());
        when(restMcpBridgeManager.listBridges()).thenReturn(java.util.Collections.emptyList());

        controller = new McpToolController(
                applicationContext,
                objectMapper,
                actionLogService,
                ragToolImpl,
                filesystemToolImpl,
                mcpServerManager,
                restMcpBridgeManager
        );
    }

    @Test
    void listAvailableTools_returnsOkWithList() {
        ResponseEntity<?> response = controller.listAvailableTools();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertInstanceOf(java.util.List.class, response.getBody());
    }

    @Test
    void invokeToolDirectly_withNullToolName_returnsBadRequest() {
        McpToolController.FrontendToolCallRequest request =
                new McpToolController.FrontendToolCallRequest(null, null);

        ResponseEntity<?> response = controller.invokeToolDirectly(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void invokeToolDirectly_withBlankToolName_returnsBadRequest() {
        McpToolController.FrontendToolCallRequest request =
                new McpToolController.FrontendToolCallRequest("  ", null);

        ResponseEntity<?> response = controller.invokeToolDirectly(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void invokeToolDirectly_withRagQuery_delegatesToRagTool() {
        Map<String, Object> queryResult = Map.of("documents", java.util.List.of("doc1"));
        when(ragToolImpl.executeRagQuery(any(RagToolImpl.RagQueryInput.class))).thenReturn(queryResult);
        when(actionLogService.logAction(any(), any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(null);

        Map<String, Object> args = Map.of("query", "test query");
        McpToolController.FrontendToolCallRequest request =
                new McpToolController.FrontendToolCallRequest("rag_query", args);

        ResponseEntity<?> response = controller.invokeToolDirectly(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals("rag_query", body.get("toolName"));
        verify(ragToolImpl).executeRagQuery(any(RagToolImpl.RagQueryInput.class));
    }

    @Test
    void invokeToolDirectly_withListFiles_delegatesToFilesystemTool() {
        Map<String, Object> listResult = Map.of("files", java.util.List.of());
        when(filesystemToolImpl.listFiles(any(FilesystemToolImpl.ListFilesInput.class))).thenReturn(listResult);
        when(actionLogService.logAction(any(), any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(null);

        Map<String, Object> args = Map.of("rootAlias", "default", "subPath", "/tmp");
        McpToolController.FrontendToolCallRequest request =
                new McpToolController.FrontendToolCallRequest("list_files", args);

        ResponseEntity<?> response = controller.invokeToolDirectly(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(filesystemToolImpl).listFiles(any(FilesystemToolImpl.ListFilesInput.class));
    }

    @Test
    void invokeToolDirectly_withReadFile_delegatesToFilesystemTool() {
        Map<String, Object> readResult = Map.of("content", "file content");
        when(filesystemToolImpl.readFile(any(FilesystemToolImpl.ReadFileInput.class))).thenReturn(readResult);
        when(actionLogService.logAction(any(), any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(null);

        Map<String, Object> args = Map.of("rootAlias", "default", "filePath", "/tmp/test.txt");
        McpToolController.FrontendToolCallRequest request =
                new McpToolController.FrontendToolCallRequest("read_file", args);

        ResponseEntity<?> response = controller.invokeToolDirectly(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(filesystemToolImpl).readFile(any(FilesystemToolImpl.ReadFileInput.class));
    }

    @Test
    void invokeToolDirectly_withUnknownTool_returnsNotFound() {
        McpToolController.FrontendToolCallRequest request =
                new McpToolController.FrontendToolCallRequest("nonexistent_tool", Map.of());

        ResponseEntity<?> response = controller.invokeToolDirectly(request);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void invokeToolDirectly_withWriteFile_requiresArguments() {
        Map<String, Object> args = Map.of("rootAlias", "default", "filePath", "/tmp/test.txt", "content", "hello");
        Map<String, Object> writeResult = Map.of("success", true);
        when(filesystemToolImpl.writeFile(any(FilesystemToolImpl.WriteFileInput.class))).thenReturn(writeResult);
        when(actionLogService.logAction(any(), any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(null);

        McpToolController.FrontendToolCallRequest request =
                new McpToolController.FrontendToolCallRequest("write_file", args);

        ResponseEntity<?> response = controller.invokeToolDirectly(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void invokeToolDirectly_withWriteFileAndNullArgs_returnsBadRequest() {
        McpToolController.FrontendToolCallRequest request =
                new McpToolController.FrontendToolCallRequest("write_file", null);

        ResponseEntity<?> response = controller.invokeToolDirectly(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
