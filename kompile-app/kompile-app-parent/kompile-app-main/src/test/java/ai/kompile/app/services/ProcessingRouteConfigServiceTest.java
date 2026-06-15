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

import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.PdfRoutingMode;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackend;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackendType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcessingRouteConfigServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void testDefaultConfigOnFirstInit() {
        ProcessingRouteConfigService service = new ProcessingRouteConfigService(tempDir);
        service.init();

        ProcessingRouteConfig config = service.getConfig();
        assertNotNull(config);
        assertEquals(PdfRoutingMode.AUTO, config.getPdfRoutingMode());
        assertFalse(config.isFallbackEnabled());
        assertTrue(config.isExtractTablesFromTextPdfs());
        assertEquals(50, config.getTextThresholdCharsPerPage());
    }

    @Test
    void testDefaultConfigPersistedToDisk() {
        ProcessingRouteConfigService service = new ProcessingRouteConfigService(tempDir);
        service.init();

        // Verify the config file was created
        Path configFile = tempDir.resolve("config").resolve("processing-route-config.json");
        assertTrue(configFile.toFile().exists());
    }

    @Test
    void testUpdateConfig() {
        ProcessingRouteConfigService service = new ProcessingRouteConfigService(tempDir);
        service.init();

        ProcessingRouteConfig updated = ProcessingRouteConfig.builder()
                .pdfRoutingMode(PdfRoutingMode.FORCE_VLM)
                .fallbackEnabled(true)
                .extractTablesFromTextPdfs(false)
                .textThresholdCharsPerPage(100)
                .backends(List.of(
                        ProcessingBackend.builder()
                                .id("local-vlm")
                                .type(ProcessingBackendType.LOCAL_MODEL)
                                .priority(1)
                                .maxConcurrent(1)
                                .build()
                ))
                .build();

        service.updateConfig(updated);

        ProcessingRouteConfig retrieved = service.getConfig();
        assertEquals(PdfRoutingMode.FORCE_VLM, retrieved.getPdfRoutingMode());
        assertTrue(retrieved.isFallbackEnabled());
        assertFalse(retrieved.isExtractTablesFromTextPdfs());
        assertEquals(100, retrieved.getTextThresholdCharsPerPage());
        assertEquals(1, retrieved.getBackends().size());
        assertEquals("local-vlm", retrieved.getBackends().get(0).getId());
    }

    @Test
    void testPersistenceAcrossInstances() {
        // First instance — update config
        ProcessingRouteConfigService service1 = new ProcessingRouteConfigService(tempDir);
        service1.init();

        ProcessingRouteConfig updated = ProcessingRouteConfig.builder()
                .pdfRoutingMode(PdfRoutingMode.FORCE_TEXT)
                .fallbackEnabled(true)
                .extractTablesFromTextPdfs(true)
                .textThresholdCharsPerPage(75)
                .build();
        service1.updateConfig(updated);

        // Second instance — should load persisted config
        ProcessingRouteConfigService service2 = new ProcessingRouteConfigService(tempDir);
        service2.init();

        ProcessingRouteConfig loaded = service2.getConfig();
        assertEquals(PdfRoutingMode.FORCE_TEXT, loaded.getPdfRoutingMode());
        assertTrue(loaded.isFallbackEnabled());
        assertEquals(75, loaded.getTextThresholdCharsPerPage());
    }

    @Test
    void testReload() {
        ProcessingRouteConfigService service = new ProcessingRouteConfigService(tempDir);
        service.init();

        // Update via another instance
        ProcessingRouteConfigService other = new ProcessingRouteConfigService(tempDir);
        other.init();
        other.updateConfig(ProcessingRouteConfig.builder()
                .pdfRoutingMode(PdfRoutingMode.DISABLED)
                .build());

        // Original instance should still have old config
        assertEquals(PdfRoutingMode.AUTO, service.getConfig().getPdfRoutingMode());

        // After reload, should pick up the change
        service.reload();
        assertEquals(PdfRoutingMode.DISABLED, service.getConfig().getPdfRoutingMode());
    }

    @Test
    void testResolveForJobWithPerJobConfig() {
        ProcessingRouteConfigService service = new ProcessingRouteConfigService(tempDir);
        service.init();

        ProcessingRouteConfig perJob = ProcessingRouteConfig.builder()
                .pdfRoutingMode(PdfRoutingMode.FORCE_VLM)
                .fallbackEnabled(true)
                .build();

        ProcessingRouteConfig resolved = service.resolveForJob(perJob);

        // Per-job config should be returned directly
        assertEquals(PdfRoutingMode.FORCE_VLM, resolved.getPdfRoutingMode());
        assertTrue(resolved.isFallbackEnabled());
    }

    @Test
    void testResolveForJobWithNull() {
        ProcessingRouteConfigService service = new ProcessingRouteConfigService(tempDir);
        service.init();

        ProcessingRouteConfig resolved = service.resolveForJob(null);

        // Should fall back to global default
        assertEquals(PdfRoutingMode.AUTO, resolved.getPdfRoutingMode());
        assertFalse(resolved.isFallbackEnabled());
    }

    @Test
    void testUpdateConfigWithBackends() {
        ProcessingRouteConfigService service = new ProcessingRouteConfigService(tempDir);
        service.init();

        ProcessingRouteConfig config = ProcessingRouteConfig.builder()
                .pdfRoutingMode(PdfRoutingMode.AUTO)
                .fallbackEnabled(true)
                .backends(List.of(
                        ProcessingBackend.builder()
                                .id("local-vlm")
                                .type(ProcessingBackendType.LOCAL_MODEL)
                                .priority(1)
                                .maxConcurrent(1)
                                .maxMemoryBytes(18_000_000_000L)
                                .build(),
                        ProcessingBackend.builder()
                                .id("claude-cli")
                                .type(ProcessingBackendType.CLI_AGENT)
                                .priority(2)
                                .maxConcurrent(3)
                                .requestsPerMinute(30)
                                .agentName("claude-cli")
                                .build(),
                        ProcessingBackend.builder()
                                .id("openai-api")
                                .type(ProcessingBackendType.API_AGENT)
                                .priority(3)
                                .maxConcurrent(10)
                                .requestsPerMinute(60)
                                .endpointUrl("https://api.openai.com/v1")
                                .modelName("gpt-4o")
                                .build()
                ))
                .build();

        service.updateConfig(config);

        // Reload to verify persistence
        ProcessingRouteConfigService service2 = new ProcessingRouteConfigService(tempDir);
        service2.init();

        ProcessingRouteConfig loaded = service2.getConfig();
        assertEquals(3, loaded.getBackends().size());
        assertEquals("local-vlm", loaded.getBackends().get(0).getId());
        assertEquals(ProcessingBackendType.LOCAL_MODEL, loaded.getBackends().get(0).getType());
        assertEquals("claude-cli", loaded.getBackends().get(1).getId());
        assertEquals(ProcessingBackendType.CLI_AGENT, loaded.getBackends().get(1).getType());
        assertEquals("openai-api", loaded.getBackends().get(2).getId());
        assertEquals(ProcessingBackendType.API_AGENT, loaded.getBackends().get(2).getType());
        assertEquals("gpt-4o", loaded.getBackends().get(2).getModelName());
    }
}
