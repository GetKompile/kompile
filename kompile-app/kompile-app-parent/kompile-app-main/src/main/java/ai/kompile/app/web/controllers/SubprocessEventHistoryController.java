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

import ai.kompile.app.ingest.domain.SubprocessEventHistory;
import ai.kompile.app.ingest.service.SubprocessEventHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for querying subprocess event history.
 * Provides endpoints for viewing subprocess lifecycle events including
 * starts, stops, crashes, restarts, and model loading events.
 */
@RestController
@RequestMapping("/api/subprocess-events")
public class SubprocessEventHistoryController {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessEventHistoryController.class);

    private final SubprocessEventHistoryService eventHistoryService;

    @Autowired
    public SubprocessEventHistoryController(
            @Autowired(required = false) SubprocessEventHistoryService eventHistoryService) {
        this.eventHistoryService = eventHistoryService;
    }

    /**
     * Get paginated subprocess events.
     */
    @GetMapping
    public ResponseEntity<Page<SubprocessEventHistory>> getEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (eventHistoryService == null) {
            return ResponseEntity.ok(Page.empty());
        }
        return ResponseEntity.ok(eventHistoryService.getAllEvents(page, size));
    }

    /**
     * Get recent events (last N hours).
     */
    @GetMapping("/recent")
    public ResponseEntity<List<SubprocessEventHistory>> getRecentEvents(
            @RequestParam(defaultValue = "24") int hours) {
        if (eventHistoryService == null) {
            logger.warn("SubprocessEventHistory: eventHistoryService is null, returning empty list");
            return ResponseEntity.ok(List.of());
        }
        List<SubprocessEventHistory> events = eventHistoryService.getRecentEvents(hours);
        logger.info("SubprocessEventHistory: Returning {} events for last {} hours", events.size(), hours);
        return ResponseEntity.ok(events);
    }

    /**
     * Get latest 100 events.
     */
    @GetMapping("/latest")
    public ResponseEntity<List<SubprocessEventHistory>> getLatestEvents() {
        if (eventHistoryService == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(eventHistoryService.getLatestEvents());
    }

    /**
     * Get restart events only.
     */
    @GetMapping("/restarts")
    public ResponseEntity<List<SubprocessEventHistory>> getRestartEvents() {
        if (eventHistoryService == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(eventHistoryService.getRestartEvents());
    }

    /**
     * Get restart events for a specific model.
     */
    @GetMapping("/restarts/{modelId}")
    public ResponseEntity<List<SubprocessEventHistory>> getRestartEventsForModel(
            @PathVariable String modelId) {
        if (eventHistoryService == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(eventHistoryService.getRestartEventsForModel(modelId));
    }

    /**
     * Get events for a specific task.
     */
    @GetMapping("/task/{taskId}")
    public ResponseEntity<List<SubprocessEventHistory>> getEventsForTask(
            @PathVariable String taskId) {
        if (eventHistoryService == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(eventHistoryService.getEventsForTask(taskId));
    }

    /**
     * Get a specific subprocess event by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<SubprocessEventHistory> getEventById(@PathVariable Long id) {
        if (eventHistoryService == null) {
            return ResponseEntity.notFound().build();
        }
        return eventHistoryService.getEventById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get subprocess statistics.
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        if (eventHistoryService == null) {
            return ResponseEntity.ok(Map.of("available", false));
        }
        return ResponseEntity.ok(eventHistoryService.getStatistics());
    }

    /**
     * Force cleanup of old events.
     */
    @DeleteMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> forceCleanup(
            @RequestParam(defaultValue = "30") int olderThanDays) {
        if (eventHistoryService == null) {
            return ResponseEntity.ok(Map.of("deleted", 0, "message", "Service not available"));
        }
        int deleted = eventHistoryService.forceCleanup(olderThanDays);
        return ResponseEntity.ok(Map.of("deleted", deleted, "message", "Deleted " + deleted + " events older than " + olderThanDays + " days"));
    }
}
