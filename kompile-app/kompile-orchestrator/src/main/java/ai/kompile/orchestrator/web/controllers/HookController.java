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

import ai.kompile.orchestrator.api.OrchestratorHook;
import ai.kompile.orchestrator.service.impl.DefaultOrchestratorService;
import ai.kompile.orchestrator.service.registry.HookRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for hook management.
 */
@Slf4j
@RestController
@RequestMapping("/api/orchestrator/hooks")
@RequiredArgsConstructor
public class HookController {

    private final HookRegistry hookRegistry;

    /**
     * Get all registered hooks.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllHooks() {
        List<Map<String, Object>> hooks = hookRegistry.getAll().stream()
                .map(hook -> Map.<String, Object>of(
                        "id", hook.getId(),
                        "priority", hook.getPriority(),
                        "class", hook.getClass().getSimpleName()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(hooks);
    }

    /**
     * Get a hook by ID.
     */
    @GetMapping("/{hookId}")
    public ResponseEntity<Map<String, Object>> getHook(@PathVariable String hookId) {
        return hookRegistry.get(hookId)
                .map(hook -> ResponseEntity.ok(Map.<String, Object>of(
                        "id", hook.getId(),
                        "priority", hook.getPriority(),
                        "class", hook.getClass().getSimpleName())))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Unregister a hook.
     */
    @DeleteMapping("/{hookId}")
    public ResponseEntity<Map<String, Object>> unregisterHook(@PathVariable String hookId) {
        log.info("Unregistering hook: {}", hookId);
        hookRegistry.unregister(hookId);
        return ResponseEntity.ok(Map.of(
                "hookId", hookId,
                "message", "Hook unregistered successfully"));
    }
}
