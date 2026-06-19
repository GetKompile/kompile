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

import ai.kompile.app.services.agent.CliAgentModelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for the CLI LLM runtime config persisted in {@code cli-llm-config.json}:
 * the active CLI agent ({@code command}), {@code processPoolSize}, per-call {@code timeoutSeconds},
 * {@code enabled}, {@code skipPermissions}. Per-agent model selection lives in
 * {@link CliAgentModelController}; updates here are merge-preserving so {@code agentModels} and
 * any other keys are retained.
 *
 * <ul>
 *   <li>{@code GET  /api/agents/cli-config} — current config</li>
 *   <li>{@code PUT  /api/agents/cli-config} — merge-update the given keys</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agents/cli-config")
public class CliLlmConfigController {

    private final CliAgentModelService modelService;

    public CliLlmConfigController(CliAgentModelService modelService) {
        this.modelService = modelService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(modelService.getCliLlmConfig());
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> updates) {
        return ResponseEntity.ok(modelService.updateCliLlmConfig(updates));
    }
}
