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

package ai.kompile.knowledgegraph.io.controller;

import ai.kompile.knowledgegraph.io.GraphIOService;
import ai.kompile.knowledgegraph.io.model.ExportResult;
import ai.kompile.knowledgegraph.io.model.ImportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GraphIOController} — import and export endpoints.
 */
@ExtendWith(MockitoExtension.class)
class GraphIOControllerTest {

    @Mock
    private GraphIOService ioService;

    private GraphIOController controller;

    @BeforeEach
    void setUp() {
        controller = new GraphIOController(ioService);
    }

    // ─── Import ──────────────────────────────────────────────────────────

    @Test
    void importGraph_delegatesToService() throws Exception {
        ImportResult importResult = new ImportResult("json", 5, 0, 3, 0, null);
        when(ioService.importGraph(eq("json"), any(byte[].class), isNull())).thenReturn(importResult);

        MockMultipartFile file = new MockMultipartFile("file", "graph.json",
                "application/json", "{\"nodes\":[]}".getBytes());

        ResponseEntity<ImportResult> response = controller.importGraph("json", file, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(5, response.getBody().nodesCreated());
        assertEquals(3, response.getBody().edgesCreated());
        verify(ioService).importGraph(eq("json"), any(byte[].class), isNull());
    }

    @Test
    void importGraph_withEdgesFile() throws Exception {
        ImportResult importResult = new ImportResult("csv", 3, 0, 2, 0, null);
        when(ioService.importGraph(eq("csv"), any(byte[].class), any(byte[].class))).thenReturn(importResult);

        MockMultipartFile nodesFile = new MockMultipartFile("file", "nodes.csv",
                "text/csv", "id,label\n1,Node1".getBytes());
        MockMultipartFile edgesFile = new MockMultipartFile("edgesFile", "edges.csv",
                "text/csv", "source,target\n1,2".getBytes());

        ResponseEntity<ImportResult> response = controller.importGraph("csv", nodesFile, edgesFile, null);

        assertEquals(200, response.getStatusCode().value());
        verify(ioService).importGraph(eq("csv"), any(byte[].class), any(byte[].class));
    }

    @Test
    void importGraph_propagatesServiceException() throws Exception {
        when(ioService.importGraph(anyString(), any(), any()))
                .thenThrow(new RuntimeException("Parse error"));

        MockMultipartFile file = new MockMultipartFile("file", "bad.json",
                "application/json", "invalid".getBytes());

        assertThrows(RuntimeException.class, () ->
                controller.importGraph("json", file, null, null));
    }

    // ─── Export ──────────────────────────────────────────────────────────

    @Test
    void exportGraph_returnsDataWithHeaders() throws Exception {
        byte[] data = "{\"nodes\":[],\"edges\":[]}".getBytes();
        ExportResult exportResult = new ExportResult("json", 10, 5, data,
                "application/json", "graph-export.json");
        when(ioService.exportGraph("json", null)).thenReturn(exportResult);

        ResponseEntity<byte[]> response = controller.exportGraph("json", null);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(data, response.getBody());
        assertTrue(response.getHeaders().get("Content-Disposition").get(0)
                .contains("graph-export.json"));
        assertEquals("application/json",
                response.getHeaders().get("Content-Type").get(0));
    }

    @Test
    void exportGraph_withFactSheetId() throws Exception {
        byte[] data = "<graphml/>".getBytes();
        ExportResult exportResult = new ExportResult("graphml", 3, 1, data,
                "application/xml", "graph.graphml");
        when(ioService.exportGraph("graphml", 42L)).thenReturn(exportResult);

        ResponseEntity<byte[]> response = controller.exportGraph("graphml", 42L);

        assertEquals(200, response.getStatusCode().value());
        verify(ioService).exportGraph("graphml", 42L);
    }

    @Test
    void exportGraph_propagatesServiceException() throws Exception {
        when(ioService.exportGraph(anyString(), any()))
                .thenThrow(new RuntimeException("Export failed"));

        assertThrows(RuntimeException.class, () ->
                controller.exportGraph("unknown", null));
    }
}
