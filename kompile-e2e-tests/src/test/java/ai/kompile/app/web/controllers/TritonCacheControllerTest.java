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

import ai.kompile.app.services.TritonCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link TritonCacheController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TritonCacheControllerTest {

    @Mock
    private TritonCacheService tritonCacheService;

    @InjectMocks
    private TritonCacheController controller;

    @Test
    void getStatus_returnsOk() {
        Map<String, Object> stats = Map.of("available", true, "cacheSize", 0L);
        when(tritonCacheService.getCacheStats()).thenReturn(stats);

        ResponseEntity<Map<String, Object>> response = controller.getStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(stats, response.getBody());
    }

    @Test
    void exportCache_success_returnsOk() {
        when(tritonCacheService.exportCache("myModel")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.exportCache("myModel");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
    }

    @Test
    void exportCache_failure_returns500() {
        when(tritonCacheService.exportCache("badModel")).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.exportCache("badModel");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    void importCache_success_returnsOk() {
        when(tritonCacheService.importCache("myModel")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.importCache("myModel");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
    }

    @Test
    void invalidateAll_returnsOk() {
        doNothing().when(tritonCacheService).invalidateAll();

        ResponseEntity<Map<String, Object>> response = controller.invalidateAll();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        verify(tritonCacheService).invalidateAll();
    }

    @Test
    void listBundles_returnsOk() {
        List<Map<String, Object>> bundles = List.of(Map.of("id", "bundle-1"));
        when(tritonCacheService.listBundles()).thenReturn(bundles);

        ResponseEntity<List<Map<String, Object>>> response = controller.listBundles();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(bundles, response.getBody());
    }
}
