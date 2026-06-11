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

import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.core.mcp.bridge.RestMcpBridgeConfig;
import ai.kompile.core.mcp.bridge.RestMcpBridgeManager;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestMcpBridgeControllerTest {

    @Mock
    private RestMcpBridgeManager bridgeManager;

    @Mock
    private BuiltInToolDiscoveryService toolDiscoveryService;

    private RestMcpBridgeController controller;

    @BeforeEach
    void setUp() {
        controller = new RestMcpBridgeController(bridgeManager, toolDiscoveryService);
    }

    private RestMcpBridgeConfig makeConfig(String id, String name) {
        RestMcpBridgeConfig config = new RestMcpBridgeConfig();
        config.setId(id);
        config.setName(name);
        config.setMappings(new ArrayList<>());
        return config;
    }

    // ── listBridges ───────────────────────────────────────────────────────

    @Test
    void listBridges_returnsList() {
        List<RestMcpBridgeConfig> bridges = List.of(makeConfig("b1", "Bridge 1"));
        when(bridgeManager.listBridges()).thenReturn(bridges);

        ResponseEntity<List<RestMcpBridgeConfig>> resp = controller.listBridges();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().size());
    }

    // ── getBridge ─────────────────────────────────────────────────────────

    @Test
    void getBridge_found_returns200() {
        RestMcpBridgeConfig config = makeConfig("b1", "Bridge 1");
        when(bridgeManager.getBridge("b1")).thenReturn(Optional.of(config));

        ResponseEntity<?> resp = controller.getBridge("b1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(config, resp.getBody());
    }

    @Test
    void getBridge_notFound_returns404() {
        when(bridgeManager.getBridge("unknown")).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.getBridge("unknown");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── createBridge ──────────────────────────────────────────────────────

    @Test
    void createBridge_success_returns201() {
        RestMcpBridgeConfig input = makeConfig(null, "New Bridge");
        RestMcpBridgeConfig created = makeConfig("b1", "New Bridge");
        when(bridgeManager.createBridge(any())).thenReturn(created);

        ResponseEntity<?> resp = controller.createBridge(input);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertSame(created, resp.getBody());
    }

    @Test
    void createBridge_illegalArgument_returns400() {
        when(bridgeManager.createBridge(any())).thenThrow(new IllegalArgumentException("invalid config"));

        ResponseEntity<?> resp = controller.createBridge(makeConfig(null, "Bad Bridge"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    // ── updateBridge ──────────────────────────────────────────────────────

    @Test
    void updateBridge_success_returns200() {
        RestMcpBridgeConfig updated = makeConfig("b1", "Updated");
        when(bridgeManager.updateBridge(eq("b1"), any())).thenReturn(updated);

        ResponseEntity<?> resp = controller.updateBridge("b1", makeConfig("b1", "Updated"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(updated, resp.getBody());
    }

    @Test
    void updateBridge_notFound_returns404() {
        when(bridgeManager.updateBridge(eq("unknown"), any()))
                .thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<?> resp = controller.updateBridge("unknown", makeConfig("unknown", "X"));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── deleteBridge ──────────────────────────────────────────────────────

    @Test
    void deleteBridge_success_returns204() {
        doNothing().when(bridgeManager).deleteBridge("b1");

        ResponseEntity<?> resp = controller.deleteBridge("b1");

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    @Test
    void deleteBridge_notFound_returns404() {
        doThrow(new IllegalArgumentException("not found")).when(bridgeManager).deleteBridge("unknown");

        ResponseEntity<?> resp = controller.deleteBridge("unknown");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── startBridge ───────────────────────────────────────────────────────

    @Test
    void startBridge_success_returns200() {
        RestMcpBridgeConfig started = makeConfig("b1", "Bridge");
        when(bridgeManager.startBridge("b1")).thenReturn(started);

        ResponseEntity<?> resp = controller.startBridge("b1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(started, resp.getBody());
    }

    @Test
    void startBridge_notFound_returns404() {
        when(bridgeManager.startBridge("unknown")).thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<?> resp = controller.startBridge("unknown");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── stopBridge ────────────────────────────────────────────────────────

    @Test
    void stopBridge_success_returns200() {
        RestMcpBridgeConfig stopped = makeConfig("b1", "Bridge");
        when(bridgeManager.stopBridge("b1")).thenReturn(stopped);

        ResponseEntity<?> resp = controller.stopBridge("b1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    // ── getBridgeStatus ───────────────────────────────────────────────────

    @Test
    void getBridgeStatus_found_returnsStatus() {
        when(bridgeManager.getBridgeStatus("b1")).thenReturn(RestMcpBridgeConfig.BridgeStatus.RUNNING);

        ResponseEntity<?> resp = controller.getBridgeStatus("b1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("b1", body.get("id"));
        assertEquals(RestMcpBridgeConfig.BridgeStatus.RUNNING, body.get("status"));
    }

    @Test
    void getBridgeStatus_notFound_returns404() {
        when(bridgeManager.getBridgeStatus("unknown")).thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<?> resp = controller.getBridgeStatus("unknown");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── discoverFromOpenApi ───────────────────────────────────────────────

    @Test
    void discoverFromOpenApi_missingUrl_returns400() {
        ResponseEntity<?> resp = controller.discoverFromOpenApi(Map.of());

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void discoverFromOpenApi_success_returnsMappings() throws Exception {
        List<RestMcpBridgeConfig.EndpointMapping> mappings = List.of();
        when(bridgeManager.discoverEndpoints("http://api.example.com/openapi.json")).thenReturn(mappings);

        ResponseEntity<?> resp = controller.discoverFromOpenApi(
                Map.of("openApiUrl", "http://api.example.com/openapi.json"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    // ── validateConfig ────────────────────────────────────────────────────

    @Test
    void validateConfig_valid_returnsTrue() {
        when(bridgeManager.validateConfig(any())).thenReturn(List.of());

        ResponseEntity<?> resp = controller.validateConfig(makeConfig("b1", "Bridge"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(true, body.get("valid"));
    }

    @Test
    void validateConfig_invalid_returnsFalseWithErrors() {
        when(bridgeManager.validateConfig(any())).thenReturn(List.of("name is required"));

        ResponseEntity<?> resp = controller.validateConfig(makeConfig(null, null));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(false, body.get("valid"));
        assertNotNull(body.get("errors"));
    }

    // ── getBridgeDirections ───────────────────────────────────────────────

    @Test
    void getBridgeDirections_returnsEnumValues() {
        ResponseEntity<RestMcpBridgeConfig.BridgeDirection[]> resp = controller.getBridgeDirections();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().length > 0);
    }

    // ── getAuthTypes ──────────────────────────────────────────────────────

    @Test
    void getAuthTypes_returnsEnumValues() {
        ResponseEntity<RestMcpBridgeConfig.AuthType[]> resp = controller.getAuthTypes();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
    }

    // ── getTransformTypes ─────────────────────────────────────────────────

    @Test
    void getTransformTypes_returnsEnumValues() {
        ResponseEntity<RestMcpBridgeConfig.TransformType[]> resp = controller.getTransformTypes();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
    }
}
