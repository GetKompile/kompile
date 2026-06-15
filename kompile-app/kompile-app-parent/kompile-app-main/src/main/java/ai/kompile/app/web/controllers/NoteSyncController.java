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
import ai.kompile.app.sync.dto.SyncConnectionTestResponse;
import ai.kompile.app.sync.repository.NoteSyncRecordRepository;
import ai.kompile.app.sync.service.NoteSyncConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sync")
public class NoteSyncController {

    private static final Logger log = LoggerFactory.getLogger(NoteSyncController.class);

    @Autowired
    private NoteSyncConnectionService connectionService;

    @Autowired
    private NoteSyncRecordRepository syncRecordRepository;

    // ── Connection CRUD ──────────────────────────────────────────────────

    @PostMapping("/connections")
    public ResponseEntity<SyncConnectionResponse> createConnection(@RequestBody SyncConnectionRequest req) {
        SyncConnectionResponse resp = connectionService.createConnection(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping("/connections")
    public ResponseEntity<List<SyncConnectionResponse>> listConnections(
            @RequestParam Long factSheetId) {
        return ResponseEntity.ok(connectionService.listConnectionsForFactSheet(factSheetId));
    }

    @GetMapping("/connections/{id}")
    public ResponseEntity<SyncConnectionResponse> getConnection(@PathVariable Long id) {
        return ResponseEntity.ok(connectionService.getConnection(id));
    }

    @PutMapping("/connections/{id}")
    public ResponseEntity<SyncConnectionResponse> updateConnection(
            @PathVariable Long id, @RequestBody SyncConnectionRequest req) {
        return ResponseEntity.ok(connectionService.updateConnection(id, req));
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<Void> deleteConnection(@PathVariable Long id) {
        connectionService.deleteConnection(id);
        return ResponseEntity.noContent().build();
    }

    // ── Sync Actions ──────────────────────────────────────────────────

    @PostMapping("/connections/{id}/trigger")
    public ResponseEntity<Map<String, Object>> triggerSync(@PathVariable Long id) {
        String sessionId = "sync-" + id + "-" + System.currentTimeMillis();
        connectionService.triggerSync(id);
        return ResponseEntity.ok(Map.of("sessionId", sessionId, "status", "STARTED"));
    }

    @PostMapping("/connections/{id}/enable")
    public ResponseEntity<SyncConnectionResponse> enableConnection(@PathVariable Long id) {
        return ResponseEntity.ok(connectionService.enableConnection(id));
    }

    @PostMapping("/connections/{id}/disable")
    public ResponseEntity<SyncConnectionResponse> disableConnection(@PathVariable Long id) {
        return ResponseEntity.ok(connectionService.disableConnection(id));
    }

    @PostMapping("/connections/{id}/test-auth")
    public ResponseEntity<SyncConnectionTestResponse> testConnectionAuth(@PathVariable Long id) {
        return ResponseEntity.ok(connectionService.testConnection(id));
    }

    // ── Sync Records (read-only) ──────────────────────────────────────

    @GetMapping("/connections/{id}/records")
    public ResponseEntity<List<NoteSyncRecord>> listSyncRecords(
            @PathVariable Long id,
            @RequestParam(required = false) String status) {
        List<NoteSyncRecord> records;
        if (status != null && !status.isBlank()) {
            records = syncRecordRepository.findByConnectionIdAndStatus(id, status);
        } else {
            records = syncRecordRepository.findByConnectionId(id);
        }
        return ResponseEntity.ok(records);
    }

    @PostMapping("/connections/{connectionId}/records/{recordId}/resolve-conflict")
    public ResponseEntity<Map<String, Object>> resolveConflict(
            @PathVariable Long connectionId,
            @PathVariable Long recordId,
            @RequestBody Map<String, String> body) {
        String resolution = body.getOrDefault("resolution", "KEEP_BOTH");
        NoteSyncRecord record = syncRecordRepository.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("Record not found: " + recordId));

        if (!"CONFLICT".equals(record.getStatus())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Record is not in CONFLICT status"));
        }

        // Mark as resolved
        record.setStatus("SYNCED");
        record.setErrorMessage(null);
        syncRecordRepository.save(record);

        return ResponseEntity.ok(Map.of("resolved", true, "resolution", resolution));
    }

    // ── Exception Handling ──────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
