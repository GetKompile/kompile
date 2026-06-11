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

import ai.kompile.app.sync.domain.NoteSyncRecord;
import ai.kompile.app.sync.dto.SyncConnectionRequest;
import ai.kompile.app.sync.dto.SyncConnectionResponse;
import ai.kompile.app.sync.repository.NoteSyncRecordRepository;
import ai.kompile.app.sync.service.NoteSyncConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteSyncControllerTest {

    @Mock
    private NoteSyncConnectionService connectionService;

    @Mock
    private NoteSyncRecordRepository syncRecordRepository;

    private NoteSyncController controller;

    @BeforeEach
    void setUp() {
        controller = new NoteSyncController();
        ReflectionTestUtils.setField(controller, "connectionService", connectionService);
        ReflectionTestUtils.setField(controller, "syncRecordRepository", syncRecordRepository);
    }

    private SyncConnectionResponse makeResponse(Long id) {
        SyncConnectionResponse resp = new SyncConnectionResponse();
        resp.setId(id);
        resp.setFactSheetId(10L);
        return resp;
    }

    // ── createConnection ──────────────────────────────────────────────────

    @Test
    void createConnection_returns201() {
        SyncConnectionRequest req = new SyncConnectionRequest();
        SyncConnectionResponse created = makeResponse(1L);
        when(connectionService.createConnection(any())).thenReturn(created);

        ResponseEntity<SyncConnectionResponse> resp = controller.createConnection(req);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertSame(created, resp.getBody());
    }

    // ── listConnections ───────────────────────────────────────────────────

    @Test
    void listConnections_returnsList() {
        List<SyncConnectionResponse> list = List.of(makeResponse(1L), makeResponse(2L));
        when(connectionService.listConnectionsForFactSheet(10L)).thenReturn(list);

        ResponseEntity<List<SyncConnectionResponse>> resp = controller.listConnections(10L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size());
    }

    // ── getConnection ─────────────────────────────────────────────────────

    @Test
    void getConnection_returnsConnection() {
        SyncConnectionResponse conn = makeResponse(1L);
        when(connectionService.getConnection(1L)).thenReturn(conn);

        ResponseEntity<SyncConnectionResponse> resp = controller.getConnection(1L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(conn, resp.getBody());
    }

    // ── updateConnection ──────────────────────────────────────────────────

    @Test
    void updateConnection_returnsUpdated() {
        SyncConnectionRequest req = new SyncConnectionRequest();
        SyncConnectionResponse updated = makeResponse(1L);
        when(connectionService.updateConnection(eq(1L), any())).thenReturn(updated);

        ResponseEntity<SyncConnectionResponse> resp = controller.updateConnection(1L, req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(updated, resp.getBody());
    }

    // ── deleteConnection ──────────────────────────────────────────────────

    @Test
    void deleteConnection_returns204() {
        doNothing().when(connectionService).deleteConnection(1L);

        ResponseEntity<Void> resp = controller.deleteConnection(1L);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(connectionService).deleteConnection(1L);
    }

    // ── triggerSync ───────────────────────────────────────────────────────

    @Test
    void triggerSync_returnsStartedStatus() {
        // triggerSync returns a CompletableFuture — controller ignores the return value,
        // so no stub is needed; Mockito returns null by default for non-void methods.
        ResponseEntity<Map<String, Object>> resp = controller.triggerSync(1L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("STARTED", resp.getBody().get("status"));
        assertTrue(resp.getBody().containsKey("sessionId"));
    }

    // ── enableConnection ──────────────────────────────────────────────────

    @Test
    void enableConnection_returnsEnabled() {
        SyncConnectionResponse enabled = makeResponse(1L);
        when(connectionService.enableConnection(1L)).thenReturn(enabled);

        ResponseEntity<SyncConnectionResponse> resp = controller.enableConnection(1L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(enabled, resp.getBody());
    }

    // ── disableConnection ─────────────────────────────────────────────────

    @Test
    void disableConnection_returnsDisabled() {
        SyncConnectionResponse disabled = makeResponse(1L);
        when(connectionService.disableConnection(1L)).thenReturn(disabled);

        ResponseEntity<SyncConnectionResponse> resp = controller.disableConnection(1L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(disabled, resp.getBody());
    }

    // ── listSyncRecords ───────────────────────────────────────────────────

    @Test
    void listSyncRecords_noStatus_returnsAll() {
        List<NoteSyncRecord> records = List.of(new NoteSyncRecord(), new NoteSyncRecord());
        when(syncRecordRepository.findByConnectionId(1L)).thenReturn(records);

        ResponseEntity<List<NoteSyncRecord>> resp = controller.listSyncRecords(1L, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size());
    }

    @Test
    void listSyncRecords_withStatus_filtersRecords() {
        List<NoteSyncRecord> records = List.of(new NoteSyncRecord());
        when(syncRecordRepository.findByConnectionIdAndStatus(1L, "SYNCED")).thenReturn(records);

        ResponseEntity<List<NoteSyncRecord>> resp = controller.listSyncRecords(1L, "SYNCED");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().size());
    }

    // ── resolveConflict ───────────────────────────────────────────────────

    @Test
    void resolveConflict_conflictRecord_resolvesAndReturns200() {
        NoteSyncRecord record = new NoteSyncRecord();
        record.setId(10L);
        record.setStatus("CONFLICT");
        when(syncRecordRepository.findById(10L)).thenReturn(Optional.of(record));
        when(syncRecordRepository.save(any())).thenReturn(record);

        Map<String, String> body = Map.of("resolution", "KEEP_BOTH");
        ResponseEntity<Map<String, Object>> resp = controller.resolveConflict(1L, 10L, body);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("resolved"));
        assertEquals("KEEP_BOTH", resp.getBody().get("resolution"));
    }

    @Test
    void resolveConflict_nonConflictRecord_returns400() {
        NoteSyncRecord record = new NoteSyncRecord();
        record.setId(10L);
        record.setStatus("SYNCED");
        when(syncRecordRepository.findById(10L)).thenReturn(Optional.of(record));

        Map<String, String> body = Map.of("resolution", "KEEP_BOTH");
        ResponseEntity<Map<String, Object>> resp = controller.resolveConflict(1L, 10L, body);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().containsKey("error"));
    }

    @Test
    void resolveConflict_recordNotFound_throwsIllegalArgument() {
        when(syncRecordRepository.findById(999L)).thenReturn(Optional.empty());

        Map<String, String> body = Map.of("resolution", "KEEP_BOTH");
        // Exception handler converts to 400
        assertThrows(IllegalArgumentException.class,
                () -> controller.resolveConflict(1L, 999L, body));
    }

    // ── handleBadRequest ──────────────────────────────────────────────────

    @Test
    void handleBadRequest_returnsBadRequestWithError() {
        ResponseEntity<Map<String, String>> resp = controller.handleBadRequest(new IllegalArgumentException("bad"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("bad", resp.getBody().get("error"));
    }
}
