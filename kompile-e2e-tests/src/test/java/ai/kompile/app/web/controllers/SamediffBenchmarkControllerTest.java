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

import ai.kompile.app.config.SamediffBenchmarkConfig;
import ai.kompile.app.services.SamediffBenchmarkService;
import ai.kompile.app.web.dto.SamediffBenchmarkResult;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SamediffBenchmarkControllerTest {

    @Mock
    private SamediffBenchmarkService benchmarkService;

    private SamediffBenchmarkController controller;

    @BeforeEach
    void setUp() {
        controller = new SamediffBenchmarkController(benchmarkService);
    }

    private SamediffBenchmarkConfig makeConfig(String name) {
        return new SamediffBenchmarkConfig(
                name, false, java.time.Instant.now().toString(), null,
                4, true, false, false,
                4, 2, 1, true,
                "/tmp/triton-cache", null, null,
                true, true,
                128, 10,
                0.0, List.of(), false
        );
    }

    private SamediffBenchmarkResult makeResult(String configName) {
        return new SamediffBenchmarkResult(
                configName, true, null,
                0L, 0L, 0L, 0L, 100L,
                10, 50.0, 50.0, 20L,
                2, 1,
                "Hello world preview", "stop",
                java.time.Instant.now().toString()
        );
    }

    // ── listConfigs ───────────────────────────────────────────────────────

    @Test
    void listConfigs_returnsList() {
        List<SamediffBenchmarkConfig> configs = List.of(makeConfig("cfg1"));
        when(benchmarkService.listConfigs()).thenReturn(configs);

        ResponseEntity<List<SamediffBenchmarkConfig>> resp = controller.listConfigs();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().size());
    }

    // ── getConfig ─────────────────────────────────────────────────────────

    @Test
    void getConfig_found_returns200() {
        SamediffBenchmarkConfig cfg = makeConfig("cfg1");
        when(benchmarkService.getConfig("cfg1")).thenReturn(cfg);

        ResponseEntity<SamediffBenchmarkConfig> resp = controller.getConfig("cfg1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(cfg, resp.getBody());
    }

    @Test
    void getConfig_notFound_returns404() {
        when(benchmarkService.getConfig("missing")).thenReturn(null);

        ResponseEntity<SamediffBenchmarkConfig> resp = controller.getConfig("missing");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── saveConfig ────────────────────────────────────────────────────────

    @Test
    void saveConfig_success_returns200() {
        SamediffBenchmarkConfig cfg = makeConfig("cfg1");
        when(benchmarkService.saveConfig(any())).thenReturn(cfg);

        ResponseEntity<SamediffBenchmarkConfig> resp = controller.saveConfig(cfg);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(cfg, resp.getBody());
    }

    @Test
    void saveConfig_illegalArgument_returns400() {
        when(benchmarkService.saveConfig(any())).thenThrow(new IllegalArgumentException("invalid"));

        ResponseEntity<SamediffBenchmarkConfig> resp = controller.saveConfig(makeConfig("bad"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    // ── updateConfig ──────────────────────────────────────────────────────

    @Test
    void updateConfig_notFound_returns404() {
        when(benchmarkService.getConfig("missing")).thenReturn(null);

        ResponseEntity<SamediffBenchmarkConfig> resp = controller.updateConfig("missing", makeConfig("missing"));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void updateConfig_found_returns200() {
        SamediffBenchmarkConfig existing = makeConfig("cfg1");
        SamediffBenchmarkConfig updated = makeConfig("cfg1");
        when(benchmarkService.getConfig("cfg1")).thenReturn(existing);
        when(benchmarkService.saveConfig(any())).thenReturn(updated);

        ResponseEntity<SamediffBenchmarkConfig> resp = controller.updateConfig("cfg1", makeConfig("cfg1"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    // ── deleteConfig ──────────────────────────────────────────────────────

    @Test
    void deleteConfig_success_returns200() {
        when(benchmarkService.deleteConfig("cfg1")).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.deleteConfig("cfg1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("success", resp.getBody().get("status"));
    }

    @Test
    void deleteConfig_notFound_returns404() {
        when(benchmarkService.deleteConfig("missing")).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp = controller.deleteConfig("missing");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── activateConfig ────────────────────────────────────────────────────

    @Test
    void activateConfig_success_returns200() {
        SamediffBenchmarkConfig cfg = makeConfig("cfg1");
        when(benchmarkService.activateConfig("cfg1")).thenReturn(cfg);

        ResponseEntity<Map<String, Object>> resp = controller.activateConfig("cfg1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("success", resp.getBody().get("status"));
    }

    @Test
    void activateConfig_notFound_returns400() {
        when(benchmarkService.activateConfig("missing"))
                .thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<Map<String, Object>> resp = controller.activateConfig("missing");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("error", resp.getBody().get("status"));
    }

    // ── getActiveConfig ───────────────────────────────────────────────────

    @Test
    void getActiveConfig_present_returns200() {
        SamediffBenchmarkConfig cfg = makeConfig("cfg1");
        when(benchmarkService.getActiveConfig()).thenReturn(cfg);

        ResponseEntity<SamediffBenchmarkConfig> resp = controller.getActiveConfig();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(cfg, resp.getBody());
    }

    @Test
    void getActiveConfig_none_returns204() {
        when(benchmarkService.getActiveConfig()).thenReturn(null);

        ResponseEntity<SamediffBenchmarkConfig> resp = controller.getActiveConfig();

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    // ── runBenchmark ──────────────────────────────────────────────────────

    @Test
    void runBenchmark_returns200WithResult() {
        SamediffBenchmarkResult result = makeResult("cfg1");
        when(benchmarkService.runBenchmark("cfg1")).thenReturn(result);

        ResponseEntity<SamediffBenchmarkResult> resp = controller.runBenchmark("cfg1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(result, resp.getBody());
    }

    // ── runMatrix ─────────────────────────────────────────────────────────

    @Test
    void runMatrix_withRanges_returns200() {
        List<SamediffBenchmarkResult> results = List.of(makeResult("cfg1"), makeResult("cfg2"));
        when(benchmarkService.runMatrix(any(), any(), any())).thenReturn(results);

        SamediffBenchmarkController.MatrixRequest req = new SamediffBenchmarkController.MatrixRequest();
        req.warpsRange = List.of(4, 8);
        req.stagesRange = List.of(2, 3);
        req.fpFusionRange = List.of(true, false);

        ResponseEntity<List<SamediffBenchmarkResult>> resp = controller.runMatrix(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size());
    }

    @Test
    void runMatrix_withNullRanges_usesDefaults() {
        List<SamediffBenchmarkResult> results = List.of(makeResult("cfg1"));
        when(benchmarkService.runMatrix(any(), any(), any())).thenReturn(results);

        SamediffBenchmarkController.MatrixRequest req = new SamediffBenchmarkController.MatrixRequest();

        ResponseEntity<List<SamediffBenchmarkResult>> resp = controller.runMatrix(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(benchmarkService).runMatrix(List.of(4, 8), List.of(2, 3), List.of(true, false));
    }

    // ── searchOptimalProfile ──────────────────────────────────────────────

    @Test
    void searchOptimalProfile_found_returnsSuccess() {
        SamediffBenchmarkResult best = makeResult("optimal-cfg");
        when(benchmarkService.searchOptimalProfile(any(), any(), any())).thenReturn(best);

        SamediffBenchmarkController.MatrixRequest req = new SamediffBenchmarkController.MatrixRequest();

        ResponseEntity<Map<String, Object>> resp = controller.searchOptimalProfile(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("success", resp.getBody().get("status"));
        assertEquals("optimal-cfg", resp.getBody().get("bestConfig"));
    }

    @Test
    void searchOptimalProfile_notFound_returnsError() {
        when(benchmarkService.searchOptimalProfile(any(), any(), any())).thenReturn(null);

        SamediffBenchmarkController.MatrixRequest req = new SamediffBenchmarkController.MatrixRequest();

        ResponseEntity<Map<String, Object>> resp = controller.searchOptimalProfile(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("error", resp.getBody().get("status"));
    }

    // ── getResults ────────────────────────────────────────────────────────

    @Test
    void getResults_returnsList() {
        List<SamediffBenchmarkResult> results = List.of(makeResult("cfg1"));
        when(benchmarkService.getResults()).thenReturn(results);

        ResponseEntity<List<SamediffBenchmarkResult>> resp = controller.getResults();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().size());
    }

    // ── clearResults ──────────────────────────────────────────────────────

    @Test
    void clearResults_returns200() {
        doNothing().when(benchmarkService).clearResults();

        ResponseEntity<Map<String, Object>> resp = controller.clearResults();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("success", resp.getBody().get("status"));
        verify(benchmarkService).clearResults();
    }

    // ── applyOptimalDefaults ──────────────────────────────────────────────

    @Test
    void applyOptimalDefaults_returns200() {
        SamediffBenchmarkConfig cfg = makeConfig("optimal");
        when(benchmarkService.applyOptimalDefaults()).thenReturn(cfg);

        ResponseEntity<Map<String, Object>> resp = controller.applyOptimalDefaults();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("success", resp.getBody().get("status"));
        assertSame(cfg, resp.getBody().get("config"));
    }
}
