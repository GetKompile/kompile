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

package ai.kompile.core.crawl.graph;

import ai.kompile.core.crawl.graph.ProcessingRouteConfig.PdfRoutingMode;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackend;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackendType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcessingRouteConfigTest {

    @Test
    void testBuilderDefaults() {
        ProcessingRouteConfig config = ProcessingRouteConfig.builder().build();

        assertEquals(PdfRoutingMode.AUTO, config.getPdfRoutingMode());
        assertFalse(config.isFallbackEnabled());
        assertNotNull(config.getBackends());
        assertTrue(config.getBackends().isEmpty());
        assertNull(config.getVlmModelId());
        assertTrue(config.isExtractTablesFromTextPdfs());
        assertEquals(50, config.getTextThresholdCharsPerPage());
    }

    @Test
    void testPdfRoutingModeValues() {
        PdfRoutingMode[] modes = PdfRoutingMode.values();
        assertEquals(4, modes.length);
        assertEquals(PdfRoutingMode.AUTO, PdfRoutingMode.valueOf("AUTO"));
        assertEquals(PdfRoutingMode.FORCE_VLM, PdfRoutingMode.valueOf("FORCE_VLM"));
        assertEquals(PdfRoutingMode.FORCE_TEXT, PdfRoutingMode.valueOf("FORCE_TEXT"));
        assertEquals(PdfRoutingMode.DISABLED, PdfRoutingMode.valueOf("DISABLED"));
    }

    @Test
    void testProcessingBackendTypeValues() {
        ProcessingBackendType[] types = ProcessingBackendType.values();
        assertEquals(3, types.length);
        assertEquals(ProcessingBackendType.LOCAL_MODEL, ProcessingBackendType.valueOf("LOCAL_MODEL"));
        assertEquals(ProcessingBackendType.CLI_AGENT, ProcessingBackendType.valueOf("CLI_AGENT"));
        assertEquals(ProcessingBackendType.API_AGENT, ProcessingBackendType.valueOf("API_AGENT"));
    }

    @Test
    void testProcessingBackendDefaults() {
        ProcessingBackend backend = ProcessingBackend.builder()
                .id("test-backend")
                .type(ProcessingBackendType.LOCAL_MODEL)
                .build();

        assertEquals("test-backend", backend.getId());
        assertEquals(ProcessingBackendType.LOCAL_MODEL, backend.getType());
        assertEquals(100, backend.getPriority());
        assertEquals(0, backend.getMaxConcurrent());
        assertEquals(0, backend.getRequestsPerMinute());
        assertEquals(0, backend.getMaxMemoryBytes());
        assertTrue(backend.isEnabled());
        assertNotNull(backend.getCapabilities());
        assertTrue(backend.getCapabilities().isEmpty());
        assertNull(backend.getDisplayName());
        assertNull(backend.getAgentName());
        assertNull(backend.getEndpointUrl());
        assertNull(backend.getApiKey());
        assertNull(backend.getModelName());
    }

    @Test
    void testProcessingBackendFullConfig() {
        ProcessingBackend backend = ProcessingBackend.builder()
                .id("openai-api")
                .displayName("OpenAI GPT-4o")
                .type(ProcessingBackendType.API_AGENT)
                .priority(3)
                .maxConcurrent(10)
                .requestsPerMinute(60)
                .endpointUrl("https://api.openai.com/v1")
                .apiKey("sk-test")
                .modelName("gpt-4o")
                .enabled(true)
                .capabilities(List.of("llm", "vlm"))
                .build();

        assertEquals("openai-api", backend.getId());
        assertEquals("OpenAI GPT-4o", backend.getDisplayName());
        assertEquals(ProcessingBackendType.API_AGENT, backend.getType());
        assertEquals(3, backend.getPriority());
        assertEquals(10, backend.getMaxConcurrent());
        assertEquals(60, backend.getRequestsPerMinute());
        assertEquals("https://api.openai.com/v1", backend.getEndpointUrl());
        assertEquals("sk-test", backend.getApiKey());
        assertEquals("gpt-4o", backend.getModelName());
        assertTrue(backend.isEnabled());
        assertEquals(List.of("llm", "vlm"), backend.getCapabilities());
    }

    @Test
    void testFullConfigWithBackends() {
        ProcessingRouteConfig config = ProcessingRouteConfig.builder()
                .pdfRoutingMode(PdfRoutingMode.FORCE_VLM)
                .fallbackEnabled(true)
                .vlmModelId("my-vlm-model")
                .extractTablesFromTextPdfs(false)
                .textThresholdCharsPerPage(100)
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
                                .build()
                ))
                .build();

        assertEquals(PdfRoutingMode.FORCE_VLM, config.getPdfRoutingMode());
        assertTrue(config.isFallbackEnabled());
        assertEquals("my-vlm-model", config.getVlmModelId());
        assertFalse(config.isExtractTablesFromTextPdfs());
        assertEquals(100, config.getTextThresholdCharsPerPage());
        assertEquals(2, config.getBackends().size());
        assertEquals("local-vlm", config.getBackends().get(0).getId());
        assertEquals("claude-cli", config.getBackends().get(1).getId());
    }

    @Test
    void testJsonSerializationRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        ProcessingRouteConfig original = ProcessingRouteConfig.builder()
                .pdfRoutingMode(PdfRoutingMode.AUTO)
                .fallbackEnabled(true)
                .extractTablesFromTextPdfs(true)
                .textThresholdCharsPerPage(50)
                .backends(List.of(
                        ProcessingBackend.builder()
                                .id("local")
                                .type(ProcessingBackendType.LOCAL_MODEL)
                                .priority(1)
                                .build()
                ))
                .build();

        String json = mapper.writeValueAsString(original);
        ProcessingRouteConfig deserialized = mapper.readValue(json, ProcessingRouteConfig.class);

        assertEquals(original.getPdfRoutingMode(), deserialized.getPdfRoutingMode());
        assertEquals(original.isFallbackEnabled(), deserialized.isFallbackEnabled());
        assertEquals(original.isExtractTablesFromTextPdfs(), deserialized.isExtractTablesFromTextPdfs());
        assertEquals(original.getTextThresholdCharsPerPage(), deserialized.getTextThresholdCharsPerPage());
        assertEquals(1, deserialized.getBackends().size());
        assertEquals("local", deserialized.getBackends().get(0).getId());
    }

    @Test
    void testJsonIgnoresUnknownProperties() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"pdfRoutingMode\":\"AUTO\",\"unknownField\":\"value\",\"fallbackEnabled\":false}";

        ProcessingRouteConfig config = mapper.readValue(json, ProcessingRouteConfig.class);
        assertEquals(PdfRoutingMode.AUTO, config.getPdfRoutingMode());
        assertFalse(config.isFallbackEnabled());
    }

    @Test
    void testCapacitySnapshotBuilder() {
        ProcessingRouteConfig.CapacitySnapshot snapshot = ProcessingRouteConfig.CapacitySnapshot.builder()
                .backendId("local-vlm")
                .type(ProcessingBackendType.LOCAL_MODEL)
                .activeRequests(1)
                .maxConcurrent(2)
                .requestsThisMinute(5)
                .requestsPerMinute(60)
                .gpuMemoryUsed(8_000_000_000L)
                .gpuMemoryTotal(24_000_000_000L)
                .available(true)
                .statusMessage("Ready")
                .build();

        assertEquals("local-vlm", snapshot.getBackendId());
        assertEquals(ProcessingBackendType.LOCAL_MODEL, snapshot.getType());
        assertEquals(1, snapshot.getActiveRequests());
        assertEquals(2, snapshot.getMaxConcurrent());
        assertEquals(5, snapshot.getRequestsThisMinute());
        assertEquals(60, snapshot.getRequestsPerMinute());
        assertEquals(8_000_000_000L, snapshot.getGpuMemoryUsed());
        assertEquals(24_000_000_000L, snapshot.getGpuMemoryTotal());
        assertTrue(snapshot.isAvailable());
        assertEquals("Ready", snapshot.getStatusMessage());
    }
}
