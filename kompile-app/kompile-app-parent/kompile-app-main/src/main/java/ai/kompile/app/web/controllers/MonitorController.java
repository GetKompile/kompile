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

import ai.kompile.app.monitor.domain.MonitorRegistration;
import ai.kompile.app.monitor.dto.MonitorRequest;
import ai.kompile.app.monitor.service.MonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for chat monitors.
 *
 * Three monitor types:
 * <ul>
 *   <li>{@code POST /api/monitor/watch-task} — wake chat when a background task finishes</li>
 *   <li>{@code POST /api/monitor/schedule-once} — wake chat at a specific time</li>
 *   <li>{@code POST /api/monitor/schedule-cron} — wake chat on a cron schedule</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
@Slf4j
public class MonitorController {

    private final MonitorService monitorService;

    @PostMapping("/watch-task")
    public ResponseEntity<?> watchTask(@RequestBody MonitorRequest.WatchTask req) {
        try {
            MonitorRegistration m = monitorService.watchTask(
                    req.sessionId(), req.taskId(), req.description(), req.payload());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/schedule-once")
    public ResponseEntity<?> scheduleOnce(@RequestBody MonitorRequest.ScheduleOnce req) {
        try {
            MonitorRegistration m = monitorService.scheduleOnce(
                    req.sessionId(), req.fireAtEpochMs(), req.description(), req.payload());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.error("Failed to schedule one-shot monitor", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/schedule-cron")
    public ResponseEntity<?> scheduleCron(@RequestBody MonitorRequest.ScheduleCron req) {
        try {
            MonitorRegistration m = monitorService.scheduleCron(
                    req.sessionId(), req.cronExpression(), req.description(), req.payload());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.error("Failed to schedule cron monitor", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<MonitorRegistration>> list(
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "all", required = false, defaultValue = "false") boolean all) {
        if (sessionId != null && !sessionId.isBlank()) {
            return ResponseEntity.ok(monitorService.listBySession(sessionId));
        }
        return ResponseEntity.ok(all ? monitorService.listAll() : monitorService.listActive());
    }

    @GetMapping("/{monitorId}")
    public ResponseEntity<MonitorRegistration> get(@PathVariable String monitorId) {
        return monitorService.get(monitorId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{monitorId}")
    public ResponseEntity<?> cancel(@PathVariable String monitorId) {
        if (monitorService.cancel(monitorId)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
