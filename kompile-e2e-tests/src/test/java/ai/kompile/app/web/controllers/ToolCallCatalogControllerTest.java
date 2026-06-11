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

import ai.kompile.app.services.ToolCallCatalogService;
import ai.kompile.app.services.TranscriptIndexingService;
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

/**
 * Tests for {@link ToolCallCatalogController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolCallCatalogControllerTest {

    @Mock
    private ToolCallCatalogService catalogService;

    @Mock
    private TranscriptIndexingService indexingService;

    private ToolCallCatalogController controller;

    @BeforeEach
    void setUp() {
        controller = new ToolCallCatalogController(catalogService, indexingService);
    }

    @Test
    void search_returnsOk() {
        Map<String, Object> result = Map.of("items", java.util.List.of(), "total", 0);
        when(catalogService.search(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyInt(), anyInt())).thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.search(
                null, null, null, null, null, null, null, "timestamp", "desc", 0, 50);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(result, response.getBody());
    }

    @Test
    void stats_returnsOk() {
        Map<String, Object> stats = Map.of("total", 100L);
        when(catalogService.getStats()).thenReturn(stats);

        ResponseEntity<Map<String, Object>> response = controller.stats();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(stats, response.getBody());
    }

    @Test
    void filters_returnsOk() {
        Map<String, Object> filters = Map.of("tools", java.util.List.of());
        when(catalogService.getFilterOptions()).thenReturn(filters);

        ResponseEntity<Map<String, Object>> response = controller.filters();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(filters, response.getBody());
    }

    @Test
    void getById_found_returnsOk() {
        ToolCallCatalogService.ToolCallEntry entry = new ToolCallCatalogService.ToolCallEntry();
        entry.id = "call-1";
        when(catalogService.getById("call-1")).thenReturn(entry);

        ResponseEntity<?> response = controller.getById("call-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getById_notFound_returnsNotFound() {
        when(catalogService.getById("missing")).thenReturn(null);

        ResponseEntity<?> response = controller.getById("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void indexTranscripts_returnsOk() {
        Map<String, Object> result = Map.of("indexed", 5);
        when(indexingService.indexTranscripts(anyString(), anyBoolean())).thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.indexTranscripts("all", false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(result, response.getBody());
    }
}
