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

import ai.kompile.app.services.mcp.ExternalMcpServerManager;
import ai.kompile.core.mcp.server.ExternalMcpServerConfig;
import ai.kompile.core.mcp.server.ExternalMcpServerConfig.ServerStatus;
import ai.kompile.core.mcp.server.UnifiedMcpConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExternalMcpServerControllerTest {

    @Mock
    private ExternalMcpServerManager serverManager;

    private ExternalMcpServerController controller;

    @BeforeEach
    void setUp() {
        controller = new ExternalMcpServerController(serverManager);
    }

    // ─── listServers ───────────────────────────────────────────────────────────

    @Test
    void listServers_returnsOkWithList() {
        ExternalMcpServerConfig cfg = new ExternalMcpServerConfig();
        cfg.setId("server1");
        when(serverManager.listServers()).thenReturn(List.of(cfg));

        ResponseEntity<List<ExternalMcpServerConfig>> response = controller.listServers();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getId()).isEqualTo("server1");
    }

    @Test
    void listServers_returnsEmptyList() {
        when(serverManager.listServers()).thenReturn(List.of());

        ResponseEntity<List<ExternalMcpServerConfig>> response = controller.listServers();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    // ─── getServer ────────────────────────────────────────────────────────────

    @Test
    void getServer_found_returnsOk() {
        ExternalMcpServerConfig cfg = new ExternalMcpServerConfig();
        cfg.setId("server1");
        when(serverManager.getServer("server1")).thenReturn(Optional.of(cfg));

        ResponseEntity<?> response = controller.getServer("server1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getServer_notFound_returnsNotFound() {
        when(serverManager.getServer("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getServer("missing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── addServer ────────────────────────────────────────────────────────────

    @Test
    void addServer_success_returnsCreated() {
        ExternalMcpServerConfig cfg = new ExternalMcpServerConfig();
        cfg.setId("new-server");
        when(serverManager.addServer(eq("new-server"), any())).thenReturn(cfg);

        ResponseEntity<?> response = controller.addServer(cfg);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(cfg);
    }

    @Test
    void addServer_duplicate_returnsBadRequest() {
        ExternalMcpServerConfig cfg = new ExternalMcpServerConfig();
        cfg.setId("dup");
        when(serverManager.addServer(eq("dup"), any())).thenThrow(new IllegalArgumentException("already exists"));

        ResponseEntity<?> response = controller.addServer(cfg);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(Map.class);
    }

    // ─── updateServer ─────────────────────────────────────────────────────────

    @Test
    void updateServer_success_returnsOk() {
        ExternalMcpServerConfig cfg = new ExternalMcpServerConfig();
        cfg.setId("s1");
        when(serverManager.updateServer(eq("s1"), any())).thenReturn(cfg);

        ResponseEntity<?> response = controller.updateServer("s1", cfg);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateServer_notFound_returnsNotFound() {
        ExternalMcpServerConfig cfg = new ExternalMcpServerConfig();
        cfg.setId("ghost");
        when(serverManager.updateServer(eq("ghost"), any())).thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<?> response = controller.updateServer("ghost", cfg);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateServer_conflict_returnsConflict() {
        ExternalMcpServerConfig cfg = new ExternalMcpServerConfig();
        cfg.setId("running");
        when(serverManager.updateServer(eq("running"), any()))
                .thenThrow(new IllegalStateException("server is running"));

        ResponseEntity<?> response = controller.updateServer("running", cfg);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ─── deleteServer ─────────────────────────────────────────────────────────

    @Test
    void deleteServer_success_returnsNoContent() {
        doNothing().when(serverManager).deleteServer("s1");

        ResponseEntity<?> response = controller.deleteServer("s1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteServer_notFound_returnsNotFound() {
        doThrow(new IllegalArgumentException("not found")).when(serverManager).deleteServer("ghost");

        ResponseEntity<?> response = controller.deleteServer("ghost");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteServer_conflict_returnsConflict() {
        doThrow(new IllegalStateException("running")).when(serverManager).deleteServer("running");

        ResponseEntity<?> response = controller.deleteServer("running");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ─── startServer ──────────────────────────────────────────────────────────

    @Test
    void startServer_success_returnsOk() {
        ExternalMcpServerConfig cfg = new ExternalMcpServerConfig();
        cfg.setId("s1");
        when(serverManager.startServer("s1")).thenReturn(cfg);

        ResponseEntity<?> response = controller.startServer("s1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void startServer_notFound_returnsNotFound() {
        when(serverManager.startServer("ghost")).thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<?> response = controller.startServer("ghost");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void startServer_conflict_returnsConflict() {
        when(serverManager.startServer("already")).thenThrow(new IllegalStateException("already running"));

        ResponseEntity<?> response = controller.startServer("already");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void startServer_runtimeError_returnsInternalServerError() {
        when(serverManager.startServer("bad")).thenThrow(new RuntimeException("failed to start"));

        ResponseEntity<?> response = controller.startServer("bad");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ─── stopServer ───────────────────────────────────────────────────────────

    @Test
    void stopServer_success_returnsOk() {
        ExternalMcpServerConfig cfg = new ExternalMcpServerConfig();
        cfg.setId("s1");
        when(serverManager.stopServer("s1")).thenReturn(cfg);

        ResponseEntity<?> response = controller.stopServer("s1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void stopServer_notFound_returnsNotFound() {
        when(serverManager.stopServer("ghost")).thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<?> response = controller.stopServer("ghost");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── restartServer ────────────────────────────────────────────────────────

    @Test
    void restartServer_success_returnsOk() {
        ExternalMcpServerConfig cfg = new ExternalMcpServerConfig();
        cfg.setId("s1");
        when(serverManager.restartServer("s1")).thenReturn(cfg);

        ResponseEntity<?> response = controller.restartServer("s1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void restartServer_notFound_returnsNotFound() {
        when(serverManager.restartServer("ghost")).thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<?> response = controller.restartServer("ghost");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── getServerStatus ──────────────────────────────────────────────────────

    @Test
    void getServerStatus_found_returnsOk() {
        ExternalMcpServerConfig cfg = new ExternalMcpServerConfig();
        cfg.setId("s1");
        when(serverManager.getServerStatus("s1")).thenReturn(ServerStatus.RUNNING);
        when(serverManager.getServer("s1")).thenReturn(Optional.of(cfg));

        ResponseEntity<?> response = controller.getServerStatus("s1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<Object, Object> body = (Map<Object, Object>) response.getBody();
        assertThat(body).containsKey("status");
        assertThat(body.get("id")).isEqualTo("s1");
    }

    @Test
    void getServerStatus_notFound_returnsNotFound() {
        when(serverManager.getServerStatus("ghost")).thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<?> response = controller.getServerStatus("ghost");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── getConfig / replaceConfig / importConfig / exportConfig / validateConfig / reloadConfig ──

    @Test
    void getConfig_returnsOk() {
        when(serverManager.exportConfig()).thenReturn("{\"mcpServers\":{}}");

        ResponseEntity<String> response = controller.getConfig();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("mcpServers");
    }

    @Test
    void replaceConfig_success_returnsOk() {
        UnifiedMcpConfig cfg = new UnifiedMcpConfig();
        when(serverManager.replaceConfig(anyString())).thenReturn(cfg);

        ResponseEntity<?> response = controller.replaceConfig("{\"mcpServers\":{}}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<Object, Object> body = (Map<Object, Object>) response.getBody();
        assertThat(body).containsKey("message");
    }

    @Test
    void replaceConfig_invalid_returnsBadRequest() {
        when(serverManager.replaceConfig(anyString())).thenThrow(new IllegalArgumentException("invalid JSON"));

        ResponseEntity<?> response = controller.replaceConfig("bad");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void importConfig_success_returnsOk() {
        UnifiedMcpConfig cfg = new UnifiedMcpConfig();
        when(serverManager.importConfig(anyString())).thenReturn(cfg);

        ResponseEntity<?> response = controller.importConfig("{\"mcpServers\":{}}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void exportConfig_returnsOkWithAttachmentHeader() {
        when(serverManager.exportConfig()).thenReturn("{\"mcpServers\":{}}");

        ResponseEntity<String> response = controller.exportConfig();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("mcp-config.json");
    }

    @Test
    void validateConfig_valid_returnsOkWithValidTrue() {
        when(serverManager.validateConfig(anyString())).thenReturn(List.of());

        ResponseEntity<?> response = controller.validateConfig("{\"mcpServers\":{}}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<Object, Object> bodyValid = (Map<Object, Object>) response.getBody();
        assertThat(bodyValid.get("valid")).isEqualTo(true);
    }

    @Test
    void validateConfig_invalid_returnsOkWithErrors() {
        when(serverManager.validateConfig(anyString())).thenReturn(List.of("bad field"));

        ResponseEntity<?> response = controller.validateConfig("bad");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<Object, Object> body = (Map<Object, Object>) response.getBody();
        assertThat(body.get("valid")).isEqualTo(false);
        assertThat(body).containsKey("errors");
    }

    @Test
    void reloadConfig_returnsOk() {
        UnifiedMcpConfig cfg = new UnifiedMcpConfig();
        doNothing().when(serverManager).loadConfig();
        when(serverManager.getConfig()).thenReturn(cfg);

        ResponseEntity<?> response = controller.reloadConfig();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<Object, Object> bodyReload = (Map<Object, Object>) response.getBody();
        assertThat(bodyReload).containsKey("message");
    }
}
