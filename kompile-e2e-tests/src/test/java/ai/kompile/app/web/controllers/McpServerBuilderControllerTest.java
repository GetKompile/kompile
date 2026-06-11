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

import ai.kompile.core.mcp.server.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpServerBuilderControllerTest {

    @Mock
    private McpServerManager serverManager;

    private McpServerBuilderController controller;

    @BeforeEach
    void setUp() {
        controller = new McpServerBuilderController(serverManager);
    }

    private McpServerConfig buildConfig(String id) {
        McpServerConfig config = new McpServerConfig();
        config.setId(id);
        config.setName("Server " + id);
        config.setTools(new ArrayList<>());
        config.setResources(new ArrayList<>());
        config.setPrompts(new ArrayList<>());
        config.setStatus(McpServerConfig.ServerStatus.STOPPED);
        return config;
    }

    @Test
    void listServers_returnsOkWithList() {
        McpServerConfig cfg = buildConfig("srv-1");
        when(serverManager.listServers()).thenReturn(List.of(cfg));

        ResponseEntity<List<McpServerConfig>> response = controller.listServers();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("srv-1", response.getBody().get(0).getId());
    }

    @Test
    void listServers_returnsEmptyListWhenNone() {
        when(serverManager.listServers()).thenReturn(Collections.emptyList());

        ResponseEntity<List<McpServerConfig>> response = controller.listServers();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void getServer_whenFound_returnsOk() {
        McpServerConfig cfg = buildConfig("srv-1");
        when(serverManager.getServer("srv-1")).thenReturn(Optional.of(cfg));

        ResponseEntity<?> response = controller.getServer("srv-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getServer_whenNotFound_returnsNotFound() {
        when(serverManager.getServer("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getServer("missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void createServer_successfullyCreates() {
        McpServerConfig inputConfig = buildConfig("new-srv");
        McpServerConfig created = buildConfig("new-srv");
        when(serverManager.createServer(inputConfig)).thenReturn(created);

        ResponseEntity<?> response = controller.createServer(inputConfig);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void createServer_withIllegalArgument_returnsBadRequest() {
        McpServerConfig inputConfig = buildConfig("bad-srv");
        when(serverManager.createServer(inputConfig))
                .thenThrow(new IllegalArgumentException("Name already taken"));

        ResponseEntity<?> response = controller.createServer(inputConfig);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void updateServer_whenFound_returnsOk() {
        McpServerConfig inputConfig = buildConfig("srv-1");
        McpServerConfig updated = buildConfig("srv-1");
        when(serverManager.updateServer("srv-1", inputConfig)).thenReturn(updated);

        ResponseEntity<?> response = controller.updateServer("srv-1", inputConfig);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void updateServer_whenNotFound_returnsNotFound() {
        McpServerConfig inputConfig = buildConfig("missing");
        when(serverManager.updateServer("missing", inputConfig))
                .thenThrow(new IllegalArgumentException("Not found"));

        ResponseEntity<?> response = controller.updateServer("missing", inputConfig);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void updateServer_whenConflict_returnsConflict() {
        McpServerConfig inputConfig = buildConfig("srv-1");
        when(serverManager.updateServer("srv-1", inputConfig))
                .thenThrow(new IllegalStateException("Server is running"));

        ResponseEntity<?> response = controller.updateServer("srv-1", inputConfig);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void deleteServer_successfullyDeletes() {
        doNothing().when(serverManager).deleteServer("srv-1");

        ResponseEntity<?> response = controller.deleteServer("srv-1");
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void deleteServer_whenNotFound_returnsNotFound() {
        doThrow(new IllegalArgumentException("Not found")).when(serverManager).deleteServer("missing");

        ResponseEntity<?> response = controller.deleteServer("missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void deleteServer_whenConflict_returnsConflict() {
        doThrow(new IllegalStateException("Cannot delete running server"))
                .when(serverManager).deleteServer("running-srv");

        ResponseEntity<?> response = controller.deleteServer("running-srv");
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void startServer_returnsOk() {
        McpServerConfig cfg = buildConfig("srv-1");
        cfg.setStatus(McpServerConfig.ServerStatus.RUNNING);
        when(serverManager.startServer("srv-1")).thenReturn(cfg);

        ResponseEntity<?> response = controller.startServer("srv-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void startServer_whenNotFound_returnsNotFound() {
        when(serverManager.startServer("missing"))
                .thenThrow(new IllegalArgumentException("Not found"));

        ResponseEntity<?> response = controller.startServer("missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void startServer_whenAlreadyRunning_returnsConflict() {
        when(serverManager.startServer("srv-1"))
                .thenThrow(new IllegalStateException("Already running"));

        ResponseEntity<?> response = controller.startServer("srv-1");
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void stopServer_returnsOk() {
        McpServerConfig cfg = buildConfig("srv-1");
        when(serverManager.stopServer("srv-1")).thenReturn(cfg);

        ResponseEntity<?> response = controller.stopServer("srv-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void restartServer_returnsOk() {
        McpServerConfig cfg = buildConfig("srv-1");
        when(serverManager.restartServer("srv-1")).thenReturn(cfg);

        ResponseEntity<?> response = controller.restartServer("srv-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void restartServer_whenNotFound_returnsNotFound() {
        when(serverManager.restartServer("missing"))
                .thenThrow(new IllegalArgumentException("Not found"));

        ResponseEntity<?> response = controller.restartServer("missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getServerStatus_returnsStatus() {
        when(serverManager.getServerStatus("srv-1"))
                .thenReturn(McpServerConfig.ServerStatus.RUNNING);

        ResponseEntity<?> response = controller.getServerStatus("srv-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals("srv-1", body.get("id"));
        assertEquals(McpServerConfig.ServerStatus.RUNNING, body.get("status"));
    }

    @Test
    void getServerStatus_whenNotFound_returnsNotFound() {
        when(serverManager.getServerStatus("missing"))
                .thenThrow(new IllegalArgumentException("Not found"));

        ResponseEntity<?> response = controller.getServerStatus("missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void validateConfig_withValidConfig_returnsValidTrue() {
        McpServerConfig cfg = buildConfig("srv-1");
        when(serverManager.validateConfig(cfg)).thenReturn(Collections.emptyList());

        ResponseEntity<?> response = controller.validateConfig(cfg);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("valid"));
    }

    @Test
    void validateConfig_withErrors_returnsValidFalseWithErrors() {
        McpServerConfig cfg = buildConfig("bad");
        cfg.setName(null);
        when(serverManager.validateConfig(cfg)).thenReturn(List.of("Name is required"));

        ResponseEntity<?> response = controller.validateConfig(cfg);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("valid"));
        assertNotNull(body.get("errors"));
    }

    @Test
    void exportConfig_whenFound_returnsJson() {
        when(serverManager.exportConfig("srv-1")).thenReturn("{\"id\":\"srv-1\"}");

        ResponseEntity<?> response = controller.exportConfig("srv-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void exportConfig_whenNotFound_returnsNotFound() {
        when(serverManager.exportConfig("missing"))
                .thenThrow(new IllegalArgumentException("Not found"));

        ResponseEntity<?> response = controller.exportConfig("missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void importConfig_successfullyImports() {
        McpServerConfig cfg = buildConfig("imported");
        when(serverManager.importConfig("{\"id\":\"imported\"}")).thenReturn(cfg);

        ResponseEntity<?> response = controller.importConfig("{\"id\":\"imported\"}");
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    void importConfig_whenInvalidJson_returnsBadRequest() {
        when(serverManager.importConfig("invalid-json"))
                .thenThrow(new RuntimeException("JSON parse error"));

        ResponseEntity<?> response = controller.importConfig("invalid-json");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void getTransportTypes_returnsEnumValues() {
        ResponseEntity<McpServerConfig.TransportType[]> response = controller.getTransportTypes();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
    }

    @Test
    void getToolTypes_returnsEnumValues() {
        ResponseEntity<McpToolConfig.ToolImplementationType[]> response = controller.getToolTypes();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
    }

    @Test
    void getResourceTypes_returnsEnumValues() {
        ResponseEntity<McpResourceConfig.ResourceType[]> response = controller.getResourceTypes();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
    }

    @Test
    void addTool_whenServerFound_addsToolAndReturnsOk() {
        McpServerConfig cfg = buildConfig("srv-1");
        McpToolConfig tool = new McpToolConfig();
        tool.setName("my-tool");
        tool.setEnabled(true);
        tool.setImplementationType(McpToolConfig.ToolImplementationType.BUILT_IN);

        when(serverManager.getServer("srv-1")).thenReturn(Optional.of(cfg));
        when(serverManager.updateServer(eq("srv-1"), any())).thenReturn(cfg);

        ResponseEntity<?> response = controller.addTool("srv-1", tool);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void addTool_whenServerNotFound_returnsNotFound() {
        when(serverManager.getServer("missing")).thenReturn(Optional.empty());
        McpToolConfig tool = new McpToolConfig();

        ResponseEntity<?> response = controller.addTool("missing", tool);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void removeTool_whenServerFound_removesToolAndReturnsOk() {
        McpServerConfig cfg = buildConfig("srv-1");
        McpToolConfig tool = new McpToolConfig();
        tool.setName("my-tool");
        tool.setEnabled(true);
        tool.setImplementationType(McpToolConfig.ToolImplementationType.BUILT_IN);
        cfg.getTools().add(tool);

        when(serverManager.getServer("srv-1")).thenReturn(Optional.of(cfg));
        when(serverManager.updateServer(eq("srv-1"), any())).thenReturn(cfg);

        ResponseEntity<?> response = controller.removeTool("srv-1", "my-tool");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
