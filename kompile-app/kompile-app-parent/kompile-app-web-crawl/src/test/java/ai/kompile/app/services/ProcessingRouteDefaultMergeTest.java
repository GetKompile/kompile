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
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the default-merge behaviour wired in
 * {@code UnifiedCrawlController#applyDefaultProcessingRoute} and
 * {@code CrawlProfileAutoStartService#buildRequestFromProfile}:
 *
 * <ol>
 *   <li>Request without processingRoute → global default from {@link ProcessingRouteConfigService} applied.</li>
 *   <li>Request WITH an explicit processingRoute → untouched.</li>
 *   <li>Service not available (null) → processingRoute remains null.</li>
 * </ol>
 *
 * The logic under test is extracted as a plain static helper
 * ({@link #applyDefaultProcessingRoute}) mirroring what the controller does,
 * so this test is framework-free and fast.
 */
class ProcessingRouteDefaultMergeTest {

    @TempDir
    Path tempDir;

    // ── helper that mirrors UnifiedCrawlController#applyDefaultProcessingRoute ──

    /**
     * Replica of the controller helper — kept here so the test exercises the logic
     * without needing a full Spring context.
     */
    static void applyDefaultProcessingRoute(UnifiedCrawlRequest request,
                                             ProcessingRouteConfigService service) {
        if (service == null) {
            return;
        }
        ProcessingRouteConfig defaultRoute = service.getConfig();
        if (defaultRoute == null) {
            return;
        }
        ProcessingRouteConfig route = request.getProcessingRoute();
        if (route == null) {
            request.setProcessingRoute(defaultRoute);
            return;
        }
        boolean routeHasBackends = route.getBackends() != null && !route.getBackends().isEmpty();
        boolean defaultHasBackends = defaultRoute.getBackends() != null && !defaultRoute.getBackends().isEmpty();
        if (routeHasBackends || !defaultHasBackends) {
            return;
        }
        ProcessingRouteConfig merged = new ProcessingRouteConfig();
        merged.setPdfRoutingMode(route.getPdfRoutingMode() != null ? route.getPdfRoutingMode() : defaultRoute.getPdfRoutingMode());
        merged.setFallbackEnabled(defaultRoute.isFallbackEnabled());
        merged.setBackends(new ArrayList<>(defaultRoute.getBackends()));
        merged.setVlmModelId(route.getVlmModelId() != null && !route.getVlmModelId().isBlank()
                ? route.getVlmModelId() : defaultRoute.getVlmModelId());
        merged.setExtractTablesFromTextPdfs(route.isExtractTablesFromTextPdfs());
        merged.setTextThresholdCharsPerPage(route.getTextThresholdCharsPerPage() > 0
                ? route.getTextThresholdCharsPerPage() : defaultRoute.getTextThresholdCharsPerPage());
        merged.setServingLaneEnabled(defaultRoute.isServingLaneEnabled());
        request.setProcessingRoute(merged);
    }

    private static ProcessingRouteConfig.ProcessingBackend backend(String id) {
        return ProcessingRouteConfig.ProcessingBackend.builder()
                .id(id)
                .type(ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL)
                .agentName("serving")
                .capabilities(List.of("llm"))
                .build();
    }

    // ── test cases ─────────────────────────────────────────────────────────────

    /** Case 1: request has no processingRoute → the global default is applied. */
    @Test
    void requestWithoutRoute_getsDefaultApplied() {
        ProcessingRouteConfigService service = new ProcessingRouteConfigService(tempDir);
        service.init();

        UnifiedCrawlRequest request = new UnifiedCrawlRequest();
        assertNull(request.getProcessingRoute(), "precondition: no route set");

        applyDefaultProcessingRoute(request, service);

        assertNotNull(request.getProcessingRoute(), "default route must be applied");
        assertEquals(PdfRoutingMode.AUTO, request.getProcessingRoute().getPdfRoutingMode(),
                "default pdfRoutingMode must be AUTO");
    }

    /** Case 2: complete explicit processingRoute with backends is not overwritten. */
    @Test
    void requestWithCompleteExplicitRoute_isUntouched() {
        ProcessingRouteConfigService service = new ProcessingRouteConfigService(tempDir);
        service.init();

        ProcessingRouteConfig explicit = ProcessingRouteConfig.builder()
                .pdfRoutingMode(PdfRoutingMode.FORCE_VLM)
                .fallbackEnabled(true)
                .backends(List.of(backend("explicit")))
                .build();

        UnifiedCrawlRequest request = new UnifiedCrawlRequest();
        request.setProcessingRoute(explicit);

        applyDefaultProcessingRoute(request, service);

        assertSame(explicit, request.getProcessingRoute(),
                "complete explicit processingRoute must not be replaced");
        assertEquals(PdfRoutingMode.FORCE_VLM, request.getProcessingRoute().getPdfRoutingMode(),
                "explicit pdfRoutingMode must be preserved");
    }

    /** Case 3: partial processingRoute inherits managed backends. */
    @Test
    void requestWithPartialRoute_mergesDefaultBackends() {
        ProcessingRouteConfigService service = new ProcessingRouteConfigService(tempDir);
        service.init();
        service.updateConfig(ProcessingRouteConfig.builder()
                .pdfRoutingMode(PdfRoutingMode.AUTO)
                .fallbackEnabled(true)
                .backends(List.of(backend("local-serving")))
                .servingLaneEnabled(true)
                .build());

        ProcessingRouteConfig partial = ProcessingRouteConfig.builder()
                .pdfRoutingMode(PdfRoutingMode.FORCE_VLM)
                .vlmModelId("smoldocling-256m")
                .build();

        UnifiedCrawlRequest request = new UnifiedCrawlRequest();
        request.setProcessingRoute(partial);

        applyDefaultProcessingRoute(request, service);

        assertNotSame(partial, request.getProcessingRoute(),
                "partial route should be replaced with a merged route");
        assertEquals(PdfRoutingMode.FORCE_VLM, request.getProcessingRoute().getPdfRoutingMode());
        assertEquals("smoldocling-256m", request.getProcessingRoute().getVlmModelId());
        assertEquals(List.of("local-serving"), request.getProcessingRoute().getBackends().stream()
                .map(ProcessingRouteConfig.ProcessingBackend::getId)
                .toList());
    }

    /** Case 4: service not available (null) → processingRoute remains null. */
    @Test
    void serviceNotAvailable_processingRouteRemainsNull() {
        UnifiedCrawlRequest request = new UnifiedCrawlRequest();
        assertNull(request.getProcessingRoute(), "precondition: no route set");

        applyDefaultProcessingRoute(request, /* service= */ null);

        assertNull(request.getProcessingRoute(),
                "processingRoute must remain null when service is unavailable");
    }

    /** Sanity: resolveForJob honours per-request config over the global default. */
    @Test
    void resolveForJob_perRequestConfigTakesPrecedence() {
        ProcessingRouteConfigService service = new ProcessingRouteConfigService(tempDir);
        service.init();

        ProcessingRouteConfig perJob = ProcessingRouteConfig.builder()
                .pdfRoutingMode(PdfRoutingMode.FORCE_TEXT)
                .build();

        ProcessingRouteConfig resolved = service.resolveForJob(perJob);
        assertSame(perJob, resolved, "per-request config must be returned as-is");
        assertEquals(PdfRoutingMode.FORCE_TEXT, resolved.getPdfRoutingMode());
    }

    /** Sanity: resolveForJob falls back to global default when per-request config is null. */
    @Test
    void resolveForJob_nullPerRequestFallsBackToGlobal() {
        ProcessingRouteConfigService service = new ProcessingRouteConfigService(tempDir);
        service.init();

        ProcessingRouteConfig resolved = service.resolveForJob(null);
        assertNotNull(resolved, "must return the global default, never null");
        assertEquals(PdfRoutingMode.AUTO, resolved.getPdfRoutingMode());
    }
}
