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

import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.core.util.FieldNames;
import ai.kompile.app.ingest.service.IngestEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for querying and managing ingest event logs.
 */
@RestController
@RequestMapping("/api/ingest/events")
public class IngestEventController {

    private static final Logger logger = LoggerFactory.getLogger(IngestEventController.class);

    private final IngestEventService ingestEventService;
    private final ObjectMapper objectMapper;

    @Autowired
    public IngestEventController(@Autowired(required = false) IngestEventService ingestEventService,
                                  ObjectMapper objectMapper) {
        this.ingestEventService = ingestEventService;
        this.objectMapper = objectMapper;
    }

    /**
     * Check if event logging is enabled.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        if (ingestEventService == null) {
            status.put("enabled", false);
            status.put("serviceAvailable", false);
            status.put("message", "IngestEventService not available - check if kompile.ingest.eventlog.enabled=true in application.properties");
            return ResponseEntity.ok(status);
        }

        status.put("serviceAvailable", true);
        status.put("enabled", ingestEventService.isEnabled());
        status.put("totalEvents", ingestEventService.getTotalEventCount());

        // Get recent task IDs for debugging
        try {
            List<String> recentTaskIds = ingestEventService.getTaskIds(
                    java.time.Instant.now().minus(java.time.Duration.ofHours(1)),
                    java.time.Instant.now()
            );
            status.put("recentTaskCount", recentTaskIds.size());
            status.put("recentTaskIds", recentTaskIds.size() > 10 ? recentTaskIds.subList(0, 10) : recentTaskIds);
        } catch (Exception e) {
            status.put("recentTaskError", e.getMessage());
        }

        return ResponseEntity.ok(status);
    }

    /**
     * Get all events for a specific task.
     */
    @GetMapping("/task/{taskId}")
    public ResponseEntity<?> getEventsForTask(@PathVariable String taskId) {
        if (ingestEventService == null || !ingestEventService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "events", List.of(),
                    "message", "Event logging is not enabled"
            ));
        }

        List<IngestEvent> events = ingestEventService.getEventsForTask(taskId);
        return ResponseEntity.ok(Map.of(
                FieldNames.TASK_ID, taskId,
                "eventCount", events.size(),
                "events", events
        ));
    }

    /**
     * Get the ND4J environment snapshot captured at job start for a specific task.
     * This is useful for reproducing environment-specific issues.
     *
     * @param taskId The task identifier
     * @return The ND4J environment configuration that was active when the job started
     */
    @GetMapping("/task/{taskId}/environment")
    public ResponseEntity<?> getTaskEnvironmentSnapshot(@PathVariable String taskId) {
        logger.debug("getTaskEnvironmentSnapshot called for taskId: {}", taskId);

        if (ingestEventService == null) {
            logger.warn("IngestEventService is NULL");
            return ResponseEntity.ok(Map.of(
                    FieldNames.TASK_ID, taskId,
                    "environmentCaptured", false,
                    "message", "IngestEventService is not available"
            ));
        }

        if (!ingestEventService.isEnabled()) {
            logger.warn("IngestEventService is disabled");
            return ResponseEntity.ok(Map.of(
                    FieldNames.TASK_ID, taskId,
                    "environmentCaptured", false,
                    "message", "Event logging is not enabled"
            ));
        }

        List<IngestEvent> events = ingestEventService.getEventsForTask(taskId);
        logger.debug("Found {} events for taskId: {}", events.size(), taskId);

        // Find the QUEUED event which contains the environment snapshot
        IngestEvent queuedEvent = events.stream()
                .filter(e -> e.getEventType() == IngestEvent.EventType.QUEUED)
                .findFirst()
                .orElse(null);

        if (queuedEvent == null) {
            logger.debug("No QUEUED event found for taskId: {}. Event types found: {}",
                    taskId,
                    events.stream().map(e -> e.getEventType().name()).toList());
            return ResponseEntity.ok(Map.of(
                    FieldNames.TASK_ID, taskId,
                    "fileName", "",
                    FieldNames.TIMESTAMP, "",
                    "environmentCaptured", false,
                    "message", "No QUEUED event found for this task. Total events: " + events.size()
            ));
        }

        String snapshot = queuedEvent.getNd4jEnvironmentSnapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    FieldNames.TASK_ID, taskId,
                    "fileName", queuedEvent.getFileName(),
                    FieldNames.TIMESTAMP, queuedEvent.getTimestamp().toString(),
                    "environmentCaptured", false,
                    "message", "No ND4J environment snapshot was captured for this job"
            ));
        }

        // Parse the JSON snapshot and return it along with metadata
        try {
            Object environmentConfig = objectMapper.readValue(snapshot, Object.class);

            return ResponseEntity.ok(Map.of(
                    FieldNames.TASK_ID, taskId,
                    "fileName", queuedEvent.getFileName(),
                    FieldNames.TIMESTAMP, queuedEvent.getTimestamp().toString(),
                    "environmentCaptured", true,
                    "nd4jEnvironment", environmentConfig
            ));
        } catch (Exception e) {
            logger.warn("Failed to parse ND4J environment snapshot for task {}: {}", taskId, e.getMessage());
            return ResponseEntity.ok(Map.of(
                    FieldNames.TASK_ID, taskId,
                    "fileName", queuedEvent.getFileName(),
                    FieldNames.TIMESTAMP, queuedEvent.getTimestamp().toString(),
                    "environmentCaptured", true,
                    "nd4jEnvironmentRaw", snapshot,
                    "parseError", e.getMessage()
            ));
        }
    }

    /**
     * Get the latest event for a task.
     */
    @GetMapping("/task/{taskId}/latest")
    public ResponseEntity<?> getLatestEvent(@PathVariable String taskId) {
        if (ingestEventService == null || !ingestEventService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "message", "Event logging is not enabled"
            ));
        }

        IngestEvent event = ingestEventService.getLatestEvent(taskId);
        if (event == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(event);
    }

    /**
     * Get recent terminal events (completions, failures, cancellations).
     *
     * @param hours Number of hours to look back (default: 24)
     */
    @GetMapping("/recent")
    public ResponseEntity<?> getRecentEvents(
            @RequestParam(defaultValue = "24") int hours) {
        if (ingestEventService == null || !ingestEventService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "events", List.of(),
                    "message", "Event logging is not enabled"
            ));
        }

        List<IngestEvent> events = ingestEventService.getRecentTerminalEvents(Duration.ofHours(hours));
        return ResponseEntity.ok(Map.of(
                "lookbackHours", hours,
                "eventCount", events.size(),
                "events", events
        ));
    }

    /**
     * Get events in a time range.
     *
     * @param start Start time (ISO-8601)
     * @param end   End time (ISO-8601)
     */
    @GetMapping("/range")
    public ResponseEntity<?> getEventsInRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        if (ingestEventService == null || !ingestEventService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "events", List.of(),
                    "message", "Event logging is not enabled"
            ));
        }

        Instant startInstant = start.atZone(ZoneId.systemDefault()).toInstant();
        Instant endInstant = end.atZone(ZoneId.systemDefault()).toInstant();

        List<IngestEvent> events = ingestEventService.getEventsBetween(startInstant, endInstant);
        return ResponseEntity.ok(Map.of(
                "start", start.toString(),
                "end", end.toString(),
                "eventCount", events.size(),
                "events", events
        ));
    }

    /**
     * Get error events in a time range.
     *
     * @param hours Number of hours to look back (default: 24)
     */
    @GetMapping("/errors")
    public ResponseEntity<?> getErrorEvents(
            @RequestParam(defaultValue = "24") int hours) {
        if (ingestEventService == null || !ingestEventService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "events", List.of(),
                    "message", "Event logging is not enabled"
            ));
        }

        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofHours(hours));

        List<IngestEvent> errors = ingestEventService.getErrorEvents(start, end);
        return ResponseEntity.ok(Map.of(
                "lookbackHours", hours,
                "errorCount", errors.size(),
                "errors", errors
        ));
    }

    /**
     * Get distinct task IDs with events in a time range.
     *
     * @param hours Number of hours to look back (default: 24)
     */
    @GetMapping("/tasks")
    public ResponseEntity<?> getTaskIds(
            @RequestParam(defaultValue = "24") int hours) {
        if (ingestEventService == null || !ingestEventService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "tasks", List.of(),
                    "message", "Event logging is not enabled"
            ));
        }

        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofHours(hours));

        List<String> taskIds = ingestEventService.getTaskIds(start, end);
        return ResponseEntity.ok(Map.of(
                "lookbackHours", hours,
                "taskCount", taskIds.size(),
                "tasks", taskIds
        ));
    }

    /**
     * Delete events for a specific task.
     */
    @DeleteMapping("/task/{taskId}")
    public ResponseEntity<?> deleteTaskEvents(@PathVariable String taskId) {
        if (ingestEventService == null || !ingestEventService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "message", "Event logging is not enabled"
            ));
        }

        ingestEventService.deleteTaskEvents(taskId);
        logger.info("Deleted events for task: {}", taskId);
        return ResponseEntity.ok(Map.of(
                FieldNames.TASK_ID, taskId,
                "deleted", true
        ));
    }

    /**
     * Force cleanup of old events.
     *
     * @param days Delete events older than this many days (default: 7)
     */
    @PostMapping("/cleanup")
    public ResponseEntity<?> forceCleanup(
            @RequestParam(defaultValue = "7") int days) {
        if (ingestEventService == null || !ingestEventService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "message", "Event logging is not enabled"
            ));
        }

        int deleted = ingestEventService.forceCleanup(days);
        logger.info("Force cleanup: deleted {} events older than {} days", deleted, days);
        return ResponseEntity.ok(Map.of(
                "olderThanDays", days,
                "deletedCount", deleted
        ));
    }

    /**
     * Get summary statistics for a time range.
     */
    @GetMapping("/summary")
    public ResponseEntity<?> getSummary(
            @RequestParam(defaultValue = "24") int hours) {
        if (ingestEventService == null || !ingestEventService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "message", "Event logging is not enabled"
            ));
        }

        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofHours(hours));

        List<IngestEvent> terminalEvents = ingestEventService.getRecentTerminalEvents(Duration.ofHours(hours));
        List<IngestEvent> errors = ingestEventService.getErrorEvents(start, end);

        // Count by type
        long completed = terminalEvents.stream()
                .filter(e -> e.getEventType() == IngestEvent.EventType.COMPLETED)
                .count();
        long failed = terminalEvents.stream()
                .filter(e -> e.getEventType() == IngestEvent.EventType.FAILED)
                .count();
        long cancelled = terminalEvents.stream()
                .filter(e -> e.getEventType() == IngestEvent.EventType.CANCELLED)
                .count();

        // Calculate average duration for completed tasks
        double avgDurationMs = terminalEvents.stream()
                .filter(e -> e.getEventType() == IngestEvent.EventType.COMPLETED && e.getDurationMs() != null)
                .mapToLong(IngestEvent::getDurationMs)
                .average()
                .orElse(0.0);

        Map<String, Object> summary = new HashMap<>();
        summary.put("lookbackHours", hours);
        summary.put("totalTasks", terminalEvents.size());
        summary.put("completed", completed);
        summary.put("failed", failed);
        summary.put("cancelled", cancelled);
        summary.put("errorCount", errors.size());
        summary.put("averageDurationMs", avgDurationMs);
        summary.put("totalEventsInDb", ingestEventService.getTotalEventCount());

        return ResponseEntity.ok(summary);
    }
}
