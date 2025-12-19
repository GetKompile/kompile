/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.web.controllers;

import ai.kompile.orchestrator.model.audit.AuditEntityType;
import ai.kompile.orchestrator.model.audit.AuditEventType;
import ai.kompile.orchestrator.model.audit.AuditLogEntry;
import ai.kompile.orchestrator.service.impl.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST controller for audit log access.
 */
@Slf4j
@RestController
@RequestMapping("/api/orchestrator/{instanceId}/audit")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "kompile.orchestrator", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuditController {

    private final AuditService auditService;

    /**
     * Get audit log for an orchestrator.
     */
    @GetMapping
    public ResponseEntity<List<AuditLogEntry>> getAuditLog(@PathVariable String instanceId) {
        return ResponseEntity.ok(auditService.getAuditLog(instanceId));
    }

    /**
     * Get paginated audit log for an orchestrator.
     */
    @GetMapping("/paged")
    public ResponseEntity<Page<AuditLogEntry>> getAuditLogPaged(
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auditService.getAuditLog(instanceId, pageable));
    }

    /**
     * Get audit log entries by time range.
     */
    @GetMapping("/timerange")
    public ResponseEntity<List<AuditLogEntry>> getAuditLogByTimeRange(
            @PathVariable String instanceId,
            @RequestParam String start,
            @RequestParam String end) {
        LocalDateTime startTime = LocalDateTime.parse(start);
        LocalDateTime endTime = LocalDateTime.parse(end);
        return ResponseEntity.ok(auditService.getAuditLogByTimeRange(instanceId, startTime, endTime));
    }

    /**
     * Get error entries for an orchestrator.
     */
    @GetMapping("/errors")
    public ResponseEntity<List<AuditLogEntry>> getErrors(@PathVariable String instanceId) {
        return ResponseEntity.ok(auditService.getErrors(instanceId));
    }

    /**
     * Get audit entries by event type.
     */
    @GetMapping("/type/{eventType}")
    public ResponseEntity<List<AuditLogEntry>> getByEventType(
            @PathVariable String instanceId,
            @PathVariable AuditEventType eventType) {
        return ResponseEntity.ok(auditService.getByEventType(eventType));
    }

    /**
     * Get audit entries by entity type and ID.
     */
    @GetMapping("/entity/{entityType}/{entityId}")
    public ResponseEntity<List<AuditLogEntry>> getByEntity(
            @PathVariable String instanceId,
            @PathVariable AuditEntityType entityType,
            @PathVariable String entityId) {
        return ResponseEntity.ok(auditService.getByEntity(entityType, entityId));
    }

    /**
     * Get recent entries across all orchestrators.
     */
    @GetMapping("/recent")
    public ResponseEntity<Page<AuditLogEntry>> getRecentEntries(
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auditService.getRecentEntries(pageable));
    }
}
