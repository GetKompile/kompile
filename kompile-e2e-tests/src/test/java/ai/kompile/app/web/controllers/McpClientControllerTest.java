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

import ai.kompile.app.config.McpClientConfiguration.McpClientRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpClientControllerTest {

    @Mock
    private McpClientRegistry clientRegistry;

    @Mock
    private McpSyncClient mcpSyncClient;

    private McpClientController controller;

    @BeforeEach
    void setUp() {
        controller = new McpClientController(clientRegistry);
    }

    @Test
    void connect_successfullyConnects() {
        when(clientRegistry.connect(eq("test-server"), eq("http://host/sse"), any()))
                .thenReturn(mcpSyncClient);

        McpClientController.ConnectRequest request =
                new McpClientController.ConnectRequest("test-server", "http://host/sse", null);
        ResponseEntity<Map<String, Object>> response = controller.connect(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("connected", response.getBody().get("status"));
        assertEquals("test-server", response.getBody().get("name"));
    }

    @Test
    void connect_whenExceptionThrown_returnsBadRequest() {
        when(clientRegistry.connect(any(), any(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        McpClientController.ConnectRequest request =
                new McpClientController.ConnectRequest("bad-server", "http://bad/sse", null);
        ResponseEntity<Map<String, Object>> response = controller.connect(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    void disconnect_callsRegistryAndReturnsDisconnected() {
        doNothing().when(clientRegistry).disconnect("test-server");

        ResponseEntity<Map<String, Object>> response = controller.disconnect("test-server");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("disconnected", response.getBody().get("status"));
        assertEquals("test-server", response.getBody().get("name"));
        verify(clientRegistry).disconnect("test-server");
    }

    @Test
    void listConnections_returnsConnectionList() {
        when(clientRegistry.listConnections()).thenReturn(List.of("server-1", "server-2"));
        McpClientRegistry.McpClientHolder holder1 = mock(McpClientRegistry.McpClientHolder.class);
        McpClientRegistry.McpClientHolder holder2 = mock(McpClientRegistry.McpClientHolder.class);
        when(holder1.sseUrl()).thenReturn("http://host1/sse");
        when(holder1.messageUrl()).thenReturn("http://host1/message");
        when(holder2.sseUrl()).thenReturn("http://host2/sse");
        when(holder2.messageUrl()).thenReturn("http://host2/message");
        when(clientRegistry.getConnectionInfo("server-1")).thenReturn(holder1);
        when(clientRegistry.getConnectionInfo("server-2")).thenReturn(holder2);

        ResponseEntity<Map<String, Object>> response = controller.listConnections();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().get("count"));
        List<?> connections = (List<?>) response.getBody().get("connections");
        assertEquals(2, connections.size());
    }

    @Test
    void listConnections_returnsEmptyWhenNoConnections() {
        when(clientRegistry.listConnections()).thenReturn(Collections.emptyList());

        ResponseEntity<Map<String, Object>> response = controller.listConnections();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().get("count"));
    }

    @Test
    void listTools_whenServerExists_returnsToolList() {
        McpSchema.Tool tool = new McpSchema.Tool("my-tool", "Does something", new McpSchema.JsonSchema("object", null, null, null, null, null));
        McpSchema.ListToolsResult result = new McpSchema.ListToolsResult(List.of(tool), null);
        when(clientRegistry.listTools("test-server")).thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.listTools("test-server");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("test-server", response.getBody().get("serverName"));
        assertEquals(1, response.getBody().get("count"));
    }

    @Test
    void listTools_whenExceptionThrown_returnsBadRequest() {
        when(clientRegistry.listTools("bad-server"))
                .thenThrow(new RuntimeException("Server not connected"));

        ResponseEntity<Map<String, Object>> response = controller.listTools("bad-server");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    void callTool_successfullyCalls() {
        McpSchema.TextContent textContent = new McpSchema.TextContent(null, null, "Hello from tool");
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(List.of(textContent), false);
        when(clientRegistry.callTool(eq("test-server"), eq("my-tool"), any()))
                .thenReturn(result);

        McpClientController.CallToolRequest request =
                new McpClientController.CallToolRequest("my-tool", Map.of("key", "value"));
        ResponseEntity<Map<String, Object>> response = controller.callTool("test-server", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("test-server", response.getBody().get("serverName"));
        assertEquals("my-tool", response.getBody().get("toolName"));
        assertEquals(false, response.getBody().get("isError"));
    }

    @Test
    void callTool_whenExceptionThrown_returnsBadRequest() {
        when(clientRegistry.callTool(any(), any(), any()))
                .thenThrow(new RuntimeException("Tool execution failed"));

        McpClientController.CallToolRequest request =
                new McpClientController.CallToolRequest("bad-tool", null);
        ResponseEntity<Map<String, Object>> response = controller.callTool("test-server", request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void listResources_whenClientIsNull_returnsBadRequest() {
        when(clientRegistry.getClient("unknown")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.listResources("unknown");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    void listResources_whenClientExists_returnsResourceList() {
        when(clientRegistry.getClient("test-server")).thenReturn(mcpSyncClient);
        McpSchema.Resource resource = new McpSchema.Resource("file:///docs/index.html", "index", "Main page", "text/html", null);
        McpSchema.ListResourcesResult result = new McpSchema.ListResourcesResult(List.of(resource), null);
        when(mcpSyncClient.listResources()).thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.listResources("test-server");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().get("count"));
    }

    @Test
    void listPrompts_whenClientIsNull_returnsBadRequest() {
        when(clientRegistry.getClient("unknown")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.listPrompts("unknown");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void listPrompts_whenClientExists_returnsPromptList() {
        when(clientRegistry.getClient("test-server")).thenReturn(mcpSyncClient);
        McpSchema.Prompt prompt = new McpSchema.Prompt("my-prompt", "A test prompt", null);
        McpSchema.ListPromptsResult result = new McpSchema.ListPromptsResult(List.of(prompt), null);
        when(mcpSyncClient.listPrompts()).thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.listPrompts("test-server");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().get("count"));
    }
}
