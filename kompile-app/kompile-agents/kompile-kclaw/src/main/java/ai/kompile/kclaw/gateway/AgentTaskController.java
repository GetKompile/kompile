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
package ai.kompile.kclaw.gateway;

import ai.kompile.kclaw.task.AgentTask;
import ai.kompile.kclaw.task.AgentTaskRequest;
import ai.kompile.kclaw.task.AgentTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for kclaw agent tasks — run an agent on a task ("do work") and save the output.
 *
 * <ul>
 *   <li>{@code POST   /api/kclaw/tasks}          — submit a task (async by default)</li>
 *   <li>{@code GET    /api/kclaw/tasks}          — list tasks (newest first)</li>
 *   <li>{@code GET    /api/kclaw/tasks/{id}}     — task record (status, output, outputFile)</li>
 *   <li>{@code GET    /api/kclaw/tasks/{id}/output} — the saved output text</li>
 *   <li>{@code DELETE /api/kclaw/tasks/{id}}     — delete a task and its artifact</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/kclaw/tasks")
public class AgentTaskController {

    private final AgentTaskService taskService;

    public AgentTaskController(@Autowired(required = false) AgentTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ResponseEntity<?> submit(@RequestBody AgentTaskRequest request) {
        if (taskService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Task service not available."));
        }
        try {
            AgentTask task = taskService.submit(request);
            return ResponseEntity.accepted().body(task);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<AgentTask>> list() {
        if (taskService == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(taskService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentTask> get(@PathVariable String id) {
        if (taskService == null) {
            return ResponseEntity.notFound().build();
        }
        return taskService.get(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/{id}/output", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> output(@PathVariable String id) {
        if (taskService == null) {
            return ResponseEntity.notFound().build();
        }
        return taskService.get(id)
                .map(t -> ResponseEntity.ok(t.getOutput() != null ? t.getOutput() : ""))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (taskService == null || !taskService.delete(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }
}
