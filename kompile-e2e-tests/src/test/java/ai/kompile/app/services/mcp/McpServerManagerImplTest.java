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

package ai.kompile.app.services.mcp;

import ai.kompile.core.mcp.server.McpServerConfig;
import ai.kompile.core.mcp.server.McpToolConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link McpServerManagerImpl} CRUD and validation paths.
 * Actual server start/stop are not exercised (those require a running MCP runtime).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpServerManagerImplTest {

    @TempDir
    Path tempDir;

    private McpServerManagerImpl manager;

    @BeforeEach
    void setUp() throws Exception {
        manager = new McpServerManagerImpl();
        // Inject tempDir as the config directory via reflection
        Field configDirField = McpServerManagerImpl.class.getDeclaredField("configDirectory");
        configDirField.setAccessible(true);
        configDirField.set(manager, tempDir.toString());
        manager.init();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private McpServerConfig buildConfig(String name, int port) {
        return McpServerConfig.builder()
                .name(name)
                .port(port)
                .transportType(McpServerConfig.TransportType.SSE)
                .build();
    }

    private McpServerConfig buildStdioConfig(String name) {
        return McpServerConfig.builder()
                .name(name)
                .transportType(McpServerConfig.TransportType.STDIO)
                .build();
    }

    // ─── createServer ─────────────────────────────────────────────────────────

    @Test
    void createServer_assignsIdWhenAbsent() {
        McpServerConfig cfg = buildConfig("my-server", 9100);
        McpServerConfig created = manager.createServer(cfg);

        assertNotNull(created.getId());
        assertFalse(created.getId().isBlank());
    }

    @Test
    void createServer_keepsExplicitId() {
        McpServerConfig cfg = buildConfig("my-server", 9101);
        String id = UUID.randomUUID().toString();
        cfg.setId(id);
        McpServerConfig created = manager.createServer(cfg);

        assertEquals(id, created.getId());
    }

    @Test
    void createServer_setsStoppedStatus() {
        McpServerConfig created = manager.createServer(buildConfig("s1", 9102));
        assertEquals(McpServerConfig.ServerStatus.STOPPED, created.getStatus());
    }

    @Test
    void createServer_throwsOnDuplicateId() {
        McpServerConfig cfg = buildConfig("s1", 9103);
        cfg.setId("dup-id");
        manager.createServer(cfg);

        McpServerConfig cfg2 = buildConfig("s2", 9104);
        cfg2.setId("dup-id");
        assertThrows(IllegalArgumentException.class, () -> manager.createServer(cfg2));
    }

    @Test
    void createServer_throwsWhenNameIsBlank() {
        McpServerConfig cfg = McpServerConfig.builder()
                .name("")
                .port(9105)
                .transportType(McpServerConfig.TransportType.SSE)
                .build();
        assertThrows(IllegalArgumentException.class, () -> manager.createServer(cfg));
    }

    @Test
    void createServer_throwsWhenPortMissingForHttpTransport() {
        McpServerConfig cfg = McpServerConfig.builder()
                .name("no-port-server")
                .port(null)
                .transportType(McpServerConfig.TransportType.SSE)
                .build();
        assertThrows(IllegalArgumentException.class, () -> manager.createServer(cfg));
    }

    @Test
    void createServer_stdioDoesNotRequirePort() {
        McpServerConfig cfg = buildStdioConfig("stdio-server");
        assertDoesNotThrow(() -> manager.createServer(cfg));
    }

    // ─── getServer / listServers ───────────────────────────────────────────────

    @Test
    void getServer_returnsEmptyForUnknownId() {
        Optional<McpServerConfig> result = manager.getServer("nonexistent");
        assertTrue(result.isEmpty());
    }

    @Test
    void getServer_returnsServerAfterCreate() {
        McpServerConfig created = manager.createServer(buildConfig("s1", 9106));
        Optional<McpServerConfig> found = manager.getServer(created.getId());

        assertTrue(found.isPresent());
        assertEquals("s1", found.get().getName());
    }

    @Test
    void listServers_returnsAllCreatedServers() {
        manager.createServer(buildConfig("s1", 9107));
        manager.createServer(buildConfig("s2", 9108));
        manager.createServer(buildConfig("s3", 9109));

        assertEquals(3, manager.listServers().size());
    }

    @Test
    void listServers_returnsEmptyWhenNoneCreated() {
        assertTrue(manager.listServers().isEmpty());
    }

    // ─── updateServer ─────────────────────────────────────────────────────────

    @Test
    void updateServer_updatesConfig() {
        McpServerConfig created = manager.createServer(buildConfig("original", 9110));
        McpServerConfig update = buildConfig("updated-name", 9111);

        McpServerConfig updated = manager.updateServer(created.getId(), update);
        assertEquals("updated-name", updated.getName());
        assertEquals(9111, updated.getPort());
    }

    @Test
    void updateServer_throwsForUnknownId() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.updateServer("unknown", buildConfig("x", 9112)));
    }

    // ─── deleteServer ─────────────────────────────────────────────────────────

    @Test
    void deleteServer_removesServer() {
        McpServerConfig created = manager.createServer(buildConfig("s1", 9113));
        manager.deleteServer(created.getId());

        assertTrue(manager.getServer(created.getId()).isEmpty());
        assertEquals(0, manager.listServers().size());
    }

    @Test
    void deleteServer_throwsForUnknownId() {
        assertThrows(IllegalArgumentException.class, () -> manager.deleteServer("unknown"));
    }

    // ─── getServerStatus ──────────────────────────────────────────────────────

    @Test
    void getServerStatus_returnsStoppedAfterCreate() {
        McpServerConfig created = manager.createServer(buildConfig("s1", 9114));
        assertEquals(McpServerConfig.ServerStatus.STOPPED, manager.getServerStatus(created.getId()));
    }

    @Test
    void getServerStatus_throwsForUnknownId() {
        assertThrows(IllegalArgumentException.class, () -> manager.getServerStatus("unknown"));
    }

    // ─── stopServer on non-running server ─────────────────────────────────────

    @Test
    void stopServer_throwsWhenNotRunning() {
        McpServerConfig created = manager.createServer(buildConfig("s1", 9115));
        assertThrows(IllegalStateException.class, () -> manager.stopServer(created.getId()));
    }

    // ─── validateConfig ───────────────────────────────────────────────────────

    @Test
    void validateConfig_noErrorsForValidSseConfig() {
        McpServerConfig cfg = buildConfig("valid", 9116);
        cfg.setId(UUID.randomUUID().toString());
        List<String> errors = manager.validateConfig(cfg);
        assertTrue(errors.isEmpty());
    }

    @Test
    void validateConfig_errorForBlankName() {
        McpServerConfig cfg = McpServerConfig.builder()
                .name("")
                .port(9117)
                .transportType(McpServerConfig.TransportType.SSE)
                .build();
        List<String> errors = manager.validateConfig(cfg);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("name")));
    }

    @Test
    void validateConfig_errorForMissingTransportType() {
        McpServerConfig cfg = McpServerConfig.builder()
                .name("t1")
                .port(9118)
                .transportType(null)
                .build();
        List<String> errors = manager.validateConfig(cfg);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Transport type")));
    }

    @Test
    void validateConfig_errorForDuplicateToolNames() {
        McpServerConfig cfg = buildConfig("t1", 9119);
        cfg.setId(UUID.randomUUID().toString());
        cfg.setTools(List.of(
                McpToolConfig.builder().name("tool1").enabled(true).build(),
                McpToolConfig.builder().name("tool1").enabled(true).build()
        ));
        List<String> errors = manager.validateConfig(cfg);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate tool name")));
    }

    @Test
    void validateConfig_errorForBlankToolName() {
        McpServerConfig cfg = buildConfig("t1", 9120);
        cfg.setId(UUID.randomUUID().toString());
        cfg.setTools(List.of(McpToolConfig.builder().name("").enabled(true).build()));
        List<String> errors = manager.validateConfig(cfg);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Tool name")));
    }

    // ─── exportConfig / importConfig ──────────────────────────────────────────

    @Test
    void exportConfig_producesValidJson() {
        McpServerConfig created = manager.createServer(buildConfig("export-test", 9121));
        String json = manager.exportConfig(created.getId());

        assertNotNull(json);
        assertTrue(json.contains("export-test"));
    }

    @Test
    void exportConfig_throwsForUnknownId() {
        assertThrows(IllegalArgumentException.class, () -> manager.exportConfig("unknown"));
    }

    @Test
    void importConfig_createsNewServerWithNewId() {
        McpServerConfig original = manager.createServer(buildConfig("orig", 9122));
        String json = manager.exportConfig(original.getId());

        McpServerConfig imported = manager.importConfig(json);
        assertNotEquals(original.getId(), imported.getId(), "Imported config should have a new ID");
    }
}
