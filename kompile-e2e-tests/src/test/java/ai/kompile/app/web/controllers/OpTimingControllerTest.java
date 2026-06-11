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

import ai.kompile.app.services.OpTimingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpTimingControllerTest {

    @Mock
    private OpTimingService opTimingService;

    private OpTimingController controller;

    @BeforeEach
    void setUp() {
        controller = new OpTimingController(opTimingService);
    }

    @Test
    void getStatus_returnsStatus() {
        Map<String, Object> status = Map.of("enabled", false);
        when(opTimingService.getStatus()).thenReturn(status);

        ResponseEntity<Map<String, Object>> resp = controller.getStatus();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(status, resp.getBody());
    }

    @Test
    void enableTiming_simple_delegates() {
        Map<String, Object> result = Map.of("enabled", true);
        when(opTimingService.enableTiming(false)).thenReturn(result);

        ResponseEntity<Map<String, Object>> resp = controller.enableTiming(false);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(result, resp.getBody());
    }

    @Test
    void enableTiming_detailed_delegates() {
        Map<String, Object> result = Map.of("enabled", true, "detailed", true);
        when(opTimingService.enableTiming(true)).thenReturn(result);

        ResponseEntity<Map<String, Object>> resp = controller.enableTiming(true);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(result, resp.getBody());
    }

    @Test
    void enableTimingWithTrace_delegates() {
        Map<String, Object> result = Map.of("trace", true);
        when(opTimingService.enableTimingWithTrace(true)).thenReturn(result);

        ResponseEntity<Map<String, Object>> resp = controller.enableTimingWithTrace(true);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(result, resp.getBody());
    }

    @Test
    void disableTiming_delegates() {
        Map<String, Object> result = Map.of("enabled", false);
        when(opTimingService.disableTiming()).thenReturn(result);

        ResponseEntity<Map<String, Object>> resp = controller.disableTiming();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(result, resp.getBody());
    }

    @Test
    void flushAndGetStats_delegates() {
        Map<String, Object> stats = Map.of("topOps", "matmul");
        when(opTimingService.flushAndGetStats(20)).thenReturn(stats);

        ResponseEntity<Map<String, Object>> resp = controller.flushAndGetStats(20);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(stats, resp.getBody());
    }

    @Test
    void getCachedStats_delegates() {
        Map<String, Object> stats = Map.of("cached", true);
        when(opTimingService.getCachedStats()).thenReturn(stats);

        ResponseEntity<Map<String, Object>> resp = controller.getCachedStats();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(stats, resp.getBody());
    }

    @Test
    void getOpBreakdown_delegates() {
        Map<String, Object> breakdown = Map.of("op", "matmul", "phases", "3");
        when(opTimingService.getOpBreakdown("matmul")).thenReturn(breakdown);

        ResponseEntity<Map<String, Object>> resp = controller.getOpBreakdown("matmul");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(breakdown, resp.getBody());
    }

    @Test
    void getOpHistogram_delegates() {
        Map<String, Object> histogram = Map.of("bins", "10");
        when(opTimingService.getOpHistogram("conv2d")).thenReturn(histogram);

        ResponseEntity<Map<String, Object>> resp = controller.getOpHistogram("conv2d");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(histogram, resp.getBody());
    }

    @Test
    void getThreadStats_delegates() {
        Map<String, Object> threadStats = Map.of("thread0", "100ms");
        when(opTimingService.getThreadStats()).thenReturn(threadStats);

        ResponseEntity<Map<String, Object>> resp = controller.getThreadStats();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(threadStats, resp.getBody());
    }

    @Test
    void exportChromeTrace_delegates() {
        Map<String, Object> trace = Map.of("trace", "[]");
        when(opTimingService.exportChromeTrace()).thenReturn(trace);

        ResponseEntity<Map<String, Object>> resp = controller.exportChromeTrace();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(trace, resp.getBody());
    }

    @Test
    void exportCSV_delegates() {
        Map<String, Object> csv = Map.of("csv", "op,time");
        when(opTimingService.exportCSV()).thenReturn(csv);

        ResponseEntity<Map<String, Object>> resp = controller.exportCSV();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(csv, resp.getBody());
    }

    @Test
    void reset_delegates() {
        Map<String, Object> result = Map.of("cleared", true);
        when(opTimingService.reset()).thenReturn(result);

        ResponseEntity<Map<String, Object>> resp = controller.reset();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(result, resp.getBody());
    }

    @Test
    void getActiveSubprocessTimings_delegates() {
        Map<String, Object> active = Map.of("active", 2);
        when(opTimingService.getActiveSubprocessTimings()).thenReturn(active);

        ResponseEntity<Map<String, Object>> resp = controller.getActiveSubprocessTimings();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(active, resp.getBody());
    }

    @Test
    void getSubprocessTimingHistory_delegates() {
        Map<String, Object> history = Map.of("entries", 10);
        when(opTimingService.getSubprocessTimingHistory(50)).thenReturn(history);

        ResponseEntity<Map<String, Object>> resp = controller.getSubprocessTimingHistory(50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(history, resp.getBody());
    }

    @Test
    void clearSubprocessTimingHistory_delegatesAndReturnsSuccess() {
        doNothing().when(opTimingService).clearSubprocessTimingHistory();

        ResponseEntity<Map<String, Object>> resp = controller.clearSubprocessTimingHistory();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("success", resp.getBody().get("status"));
        verify(opTimingService).clearSubprocessTimingHistory();
    }
}
