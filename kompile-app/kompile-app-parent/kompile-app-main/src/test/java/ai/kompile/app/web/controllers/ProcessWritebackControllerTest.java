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

import ai.kompile.app.services.ProcessGraphWritebackService;
import ai.kompile.app.services.ProcessWritebackDeadLetterStore;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProcessWritebackController}.
 *
 * <p>Drives the controller directly (no Spring MVC layer) to keep tests fast.
 * Uses a real {@link ProcessWritebackDeadLetterStore} backed by a {@link TempDir},
 * so file operations are tested end-to-end.</p>
 */
class ProcessWritebackControllerTest {

    @TempDir
    java.nio.file.Path tempDir;

    private ProcessGraphWritebackService writebackService;
    private ProcessWritebackController controller;
    private KnowledgeGraphService kg;

    @BeforeEach
    void setUp() {
        kg = mock(KnowledgeGraphService.class);
        ObjectMapper mapper = new ObjectMapper();
        writebackService = new ProcessGraphWritebackService(kg, mapper, tempDir);

        GraphNode stub = GraphNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title("stub")
                .build();
        when(kg.createNode(any(), any(), any(), any(), any())).thenReturn(stub);
        when(kg.getNodeByExternalId(anyString(), any())).thenReturn(Optional.empty());
        when(kg.getNodesByExternalIds(any())).thenReturn(List.of());

        controller = new ProcessWritebackController(writebackService);
    }

    // ── GET /api/process/writeback/dead-letters ───────────────────────────────────

    @Test
    void listDeadLetters_emptyStore_returnsZeroCount() {
        ResponseEntity<Map<String, Object>> response = controller.listDeadLetters();
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(0, body.get("count"));
        assertTrue((Boolean) body.get("enabled"));
        assertTrue(((List<?>) body.get("entries")).isEmpty());
    }

    @Test
    void listDeadLetters_withEntries_returnsSummaries() {
        ProcessWritebackDeadLetterStore store = writebackService.deadLetterStore();
        store.append(makeStepEntry("run-A", "step-A"));
        store.append(makeRunEntry("run-B"));

        ResponseEntity<Map<String, Object>> response = controller.listDeadLetters();
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.get("count"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entries");
        assertEquals(2, entries.size());

        // Verify summary fields present (no full payload)
        Map<String, Object> first = entries.get(0);
        assertTrue(first.containsKey("id"));
        assertTrue(first.containsKey("callbackType"));
        assertTrue(first.containsKey("runId"));
        assertTrue(first.containsKey("ts"));
        assertTrue(first.containsKey("attemptNumber"));
    }

    @Test
    void listDeadLetters_disabled_returnsEnabledFalse() {
        ProcessGraphWritebackService noDataDirService =
                new ProcessGraphWritebackService(kg, new ObjectMapper(), null);
        noDataDirService.setProjectDataDir(null);
        ProcessWritebackController ctrl = new ProcessWritebackController(noDataDirService);

        ResponseEntity<Map<String, Object>> response = ctrl.listDeadLetters();
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertFalse((Boolean) body.get("enabled"));
        assertEquals(0, body.get("count"));
    }

    // ── POST /api/process/writeback/dead-letters/retry ────────────────────────────

    @Test
    void retryAll_emptyStore_returnsZeroTotals() {
        ResponseEntity<Map<String, Object>> response = controller.retryAll();
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(0, body.get("total"));
    }

    @Test
    void retryAll_successfulReplay_drainStore() {
        // Populate one step entry
        ProcessWritebackDeadLetterStore store = writebackService.deadLetterStore();
        ProcessWritebackDeadLetterStore.DeadLetterEntry e = makeStepEntry("run-retry", "step-R");
        store.append(e);
        assertEquals(1, store.size());

        // KG is healthy
        GraphNode stub = GraphNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title("stub")
                .build();
        when(kg.createNode(any(), any(), any(), any(), any())).thenReturn(stub);

        ResponseEntity<Map<String, Object>> response = controller.retryAll();
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.get("total"));
        assertEquals(1, body.get("succeeded"));
        assertEquals(0, body.get("failed"));

        // Store drained
        assertEquals(0, store.size());
    }

    @Test
    void retryAll_disabled_returnsEnabledFalse() {
        ProcessGraphWritebackService noDataDirService =
                new ProcessGraphWritebackService(kg, new ObjectMapper(), null);
        noDataDirService.setProjectDataDir(null);
        ProcessWritebackController ctrl = new ProcessWritebackController(noDataDirService);

        ResponseEntity<Map<String, Object>> response = ctrl.retryAll();
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertFalse((Boolean) body.get("enabled"));
    }

    // ── DELETE /api/process/writeback/dead-letters/{id} ───────────────────────────

    @Test
    void deleteEntry_existingId_returns200Removed() {
        ProcessWritebackDeadLetterStore store = writebackService.deadLetterStore();
        ProcessWritebackDeadLetterStore.DeadLetterEntry e = makeStepEntry("run-del", "step-del");
        store.append(e);

        ResponseEntity<Map<String, Object>> response = controller.deleteEntry(e.id);
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("removed"));
        assertEquals(e.id, body.get("id"));
        assertEquals(0, store.size());
    }

    @Test
    void deleteEntry_unknownId_returns404() {
        ResponseEntity<Map<String, Object>> response = controller.deleteEntry(UUID.randomUUID().toString());
        assertEquals(404, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertFalse((Boolean) body.get("removed"));
    }

    @Test
    void deleteEntry_doesNotRemoveWrongEntry() {
        ProcessWritebackDeadLetterStore store = writebackService.deadLetterStore();
        ProcessWritebackDeadLetterStore.DeadLetterEntry e1 = makeStepEntry("run-1", "step-1");
        ProcessWritebackDeadLetterStore.DeadLetterEntry e2 = makeStepEntry("run-2", "step-2");
        store.append(e1);
        store.append(e2);

        controller.deleteEntry(e1.id);
        assertEquals(1, store.size());
        assertEquals("run-2", store.readAll().get(0).runId);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    private ProcessWritebackDeadLetterStore.DeadLetterEntry makeStepEntry(String runId, String stepId) {
        return ProcessWritebackDeadLetterStore.DeadLetterEntry.forStep(
                runId, stepId, "proc-test", "Test Step", "COMPLETED",
                List.of(UUID.randomUUID().toString()),
                null, null, null, null, null, null,
                "test failure", 3);
    }

    private ProcessWritebackDeadLetterStore.DeadLetterEntry makeRunEntry(String runId) {
        return ProcessWritebackDeadLetterStore.DeadLetterEntry.forRun(
                runId, "proc-test", "COMPLETED", null, null,
                null, "test failure", 3);
    }
}
