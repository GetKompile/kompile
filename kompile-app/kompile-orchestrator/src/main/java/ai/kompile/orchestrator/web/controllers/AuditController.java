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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * REST controller for audit log access.
 */
@Slf4j
@RestController
@RequestMapping("/api/orchestrator/{instanceId}/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    /**
     * Get audit log for an orchestrator.
     */
    @GetMapping
    public ResponseEntity<List<AuditLogEntry>> getAuditLog(@PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(auditService.getAuditLog(instanceId));
    }

    /**
     * Get paginated audit log for an orchestrator.
     */
    @GetMapping("/paged")
    public ResponseEntity<Page<AuditLogEntry>> getAuditLogPaged(
            @PathVariable("instanceId") String instanceId,
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
            @PathVariable("instanceId") String instanceId,
            @RequestParam(name = "start") String start,
            @RequestParam(name = "end") String end) {
        LocalDateTime startTime = LocalDateTime.parse(start);
        LocalDateTime endTime = LocalDateTime.parse(end);
        return ResponseEntity.ok(auditService.getAuditLogByTimeRange(instanceId, startTime, endTime));
    }

    /**
     * Get error entries for an orchestrator.
     */
    @GetMapping("/errors")
    public ResponseEntity<List<AuditLogEntry>> getErrors(@PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(auditService.getErrors(instanceId));
    }

    /**
     * Get audit entries by event type.
     */
    @GetMapping("/type/{eventType}")
    public ResponseEntity<List<AuditLogEntry>> getByEventType(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("eventType") AuditEventType eventType) {
        return ResponseEntity.ok(auditService.getByEventType(eventType));
    }

    /**
     * Get audit entries by entity type and ID.
     */
    @GetMapping("/entity/{entityType}/{entityId}")
    public ResponseEntity<List<AuditLogEntry>> getByEntity(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("entityType") AuditEntityType entityType,
            @PathVariable("entityId") String entityId) {
        return ResponseEntity.ok(auditService.getByEntity(entityType, entityId));
    }

    /**
     * Get recent entries across all orchestrators.
     */
    @GetMapping("/recent")
    public ResponseEntity<Page<AuditLogEntry>> getRecentEntries(
            @PathVariable("instanceId") String instanceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auditService.getRecentEntries(pageable));
    }

    // ==================== Advanced Search Endpoints ====================

    /**
     * Search audit logs with multiple filters.
     * GET /api/orchestrator/{instanceId}/audit/search?eventType=&entityType=&from=&to=&search=&errorsOnly=&page=&size=
     */
    @GetMapping("/search")
    public ResponseEntity<Page<AuditLogEntry>> searchAuditLogs(
            @PathVariable("instanceId") String instanceId,
            @RequestParam(required = false) AuditEventType eventType,
            @RequestParam(required = false) AuditEntityType entityType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String actorId,
            @RequestParam(defaultValue = "false") boolean errorsOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        LocalDateTime fromTime = from != null ? LocalDateTime.parse(from) : null;
        LocalDateTime toTime = to != null ? LocalDateTime.parse(to) : null;

        AuditService.AuditSearchCriteria criteria = AuditService.AuditSearchCriteria.builder()
                .eventType(eventType)
                .entityType(entityType)
                .fromTime(fromTime)
                .toTime(toTime)
                .search(search)
                .actorId(actorId)
                .errorsOnly(errorsOnly)
                .build();

        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auditService.searchAuditLogs(instanceId, criteria, pageable));
    }

    /**
     * Get audit log statistics for an instance.
     */
    @GetMapping("/stats")
    public ResponseEntity<AuditService.AuditStats> getStats(
            @PathVariable("instanceId") String instanceId) {
        log.debug("Getting audit stats for instance: {}", instanceId);
        return ResponseEntity.ok(auditService.getStats(instanceId));
    }

    /**
     * Export audit logs as JSON or CSV.
     * GET /api/orchestrator/{instanceId}/audit/export?format=json|csv&eventType=&entityType=&from=&to=&search=
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAuditLogs(
            @PathVariable("instanceId") String instanceId,
            @RequestParam(defaultValue = "json") String format,
            @RequestParam(required = false) AuditEventType eventType,
            @RequestParam(required = false) AuditEntityType entityType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean errorsOnly) {

        log.info("Exporting audit logs for instance {} in {} format", instanceId, format);

        LocalDateTime fromTime = from != null ? LocalDateTime.parse(from) : null;
        LocalDateTime toTime = to != null ? LocalDateTime.parse(to) : null;

        AuditService.AuditSearchCriteria criteria = AuditService.AuditSearchCriteria.builder()
                .eventType(eventType)
                .entityType(entityType)
                .fromTime(fromTime)
                .toTime(toTime)
                .search(search)
                .errorsOnly(errorsOnly)
                .build();

        String content;
        String filename;
        MediaType mediaType;

        if ("csv".equalsIgnoreCase(format)) {
            content = auditService.exportToCsv(instanceId, criteria);
            filename = "audit-log-" + instanceId + ".csv";
            mediaType = MediaType.parseMediaType("text/csv");
        } else {
            content = auditService.exportToJson(instanceId, criteria);
            filename = "audit-log-" + instanceId + ".json";
            mediaType = MediaType.APPLICATION_JSON;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDispositionFormData("attachment", filename);

        return ResponseEntity.ok()
                .headers(headers)
                .body(content.getBytes());
    }

    /**
     * Get audit entries by actor ID.
     */
    @GetMapping("/actor/{actorId}")
    public ResponseEntity<List<AuditLogEntry>> getByActor(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("actorId") String actorId) {
        return ResponseEntity.ok(auditService.getByActor(instanceId, actorId));
    }

    /**
     * Get available event types.
     */
    @GetMapping("/event-types")
    public ResponseEntity<AuditEventType[]> getEventTypes(
            @PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(AuditEventType.values());
    }

    /**
     * Get available entity types.
     */
    @GetMapping("/entity-types")
    public ResponseEntity<AuditEntityType[]> getEntityTypes(
            @PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(AuditEntityType.values());
    }
}
