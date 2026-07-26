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

package ai.kompile.app.tools;

import ai.kompile.app.web.controllers.SubprocessEventHistoryController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for subprocess event history.
 *
 * Split out of {@code JobHistoryTool}, which used to cover indexing jobs, ingest events, job logs and
 * subprocess events in one bean. Subprocess lifecycle is an admin surface and
 * {@code SubprocessEventHistoryController} stays in this module, while the indexing/ingest/log
 * controllers moved to kompile-app-web-crawl; a single tool would have forced one of the two personas
 * to carry the other's controllers. Tool names are unchanged, so MCP clients see the same surface.
 *
 * See docs/architecture/app-persona-boundary.md.
 */
@Component
public class SubprocessEventHistoryTool {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessEventHistoryTool.class);

    private final SubprocessEventHistoryController subprocessEventController;

    @Autowired
    public SubprocessEventHistoryTool(
            @Autowired(required = false) SubprocessEventHistoryController subprocessEventController) {
        this.subprocessEventController = subprocessEventController;
    }

    // Input records
    public record GetSubprocessEventsInput(Integer page, Integer size) {}
    public record GetRecentSubprocessEventsInput(Integer hours) {}
    public record GetSubprocessRestartEventsInput() {}
    public record GetSubprocessEventsForTaskInput(String taskId) {}
    public record GetSubprocessStatisticsInput() {}

    // === Subprocess Events ===

    @Tool(name = "get_subprocess_events",
            description = "Gets paginated subprocess events.")
    public Map<String, Object> getSubprocessEvents(GetSubprocessEventsInput input) {
        try {
            if (subprocessEventController == null) return Map.of("status", "error", "error", "Subprocess event service not available");
            int page = input.page() != null ? input.page() : 0;
            int size = input.size() != null ? input.size() : 20;
            ResponseEntity<?> response = subprocessEventController.getEvents(page, size);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting subprocess events: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_recent_subprocess_events",
            description = "Gets recent subprocess events from the last N hours.")
    public Map<String, Object> getRecentSubprocessEvents(GetRecentSubprocessEventsInput input) {
        try {
            if (subprocessEventController == null) return Map.of("status", "error", "error", "Subprocess event service not available");
            int hours = input.hours() != null ? input.hours() : 24;
            ResponseEntity<?> response = subprocessEventController.getRecentEvents(hours);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting recent subprocess events: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_subprocess_restart_events",
            description = "Gets subprocess restart events only.")
    public Map<String, Object> getSubprocessRestartEvents(GetSubprocessRestartEventsInput input) {
        try {
            if (subprocessEventController == null) return Map.of("status", "error", "error", "Subprocess event service not available");
            ResponseEntity<?> response = subprocessEventController.getRestartEvents();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting subprocess restart events: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_subprocess_events_for_task",
            description = "Gets subprocess events for a specific task ID.")
    public Map<String, Object> getSubprocessEventsForTask(GetSubprocessEventsForTaskInput input) {
        try {
            if (subprocessEventController == null) return Map.of("status", "error", "error", "Subprocess event service not available");
            if (input.taskId() == null) return Map.of("status", "error", "error", "Task ID is required");
            ResponseEntity<?> response = subprocessEventController.getEventsForTask(input.taskId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting subprocess events for task: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_subprocess_statistics",
            description = "Gets subprocess event statistics.")
    public Map<String, Object> getSubprocessStatistics(GetSubprocessStatisticsInput input) {
        try {
            if (subprocessEventController == null) return Map.of("status", "error", "error", "Subprocess event service not available");
            ResponseEntity<?> response = subprocessEventController.getStatistics();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting subprocess statistics: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
