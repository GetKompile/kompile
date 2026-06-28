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
package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.CliAgentModelService;
import ai.kompile.app.services.agent.CliAgentModelService.AgentModelInfo;
import ai.kompile.app.services.agent.ModelFallbackConfigManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the model-fallback configuration.
 *
 * <ul>
 *   <li>{@code GET  /api/model-fallback/config} — current effective config</li>
 *   <li>{@code PUT  /api/model-fallback/config} — merge-update config (own keys only)</li>
 *   <li>{@code GET  /api/model-fallback/available-models} — all agent models (for UI chain editor)</li>
 * </ul>
 *
 * Covered by {@link ai.kompile.app.web.GlobalExceptionHandler} (already includes
 * {@code ai.kompile.app.web.controllers} in its {@code basePackages}).
 */
@RestController
@RequestMapping("/api/model-fallback")
public class ModelFallbackController {

    private final ModelFallbackConfigManager configManager;
    private final CliAgentModelService modelService;

    @Autowired
    public ModelFallbackController(ModelFallbackConfigManager configManager,
                                   CliAgentModelService modelService) {
        this.configManager = configManager;
        this.modelService = modelService;
    }

    /**
     * Returns the current effective model-fallback config as a plain map.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(configManager.currentConfig());
    }

    /**
     * Merge-update the model-fallback config.
     *
     * <p>Only keys that belong to {@link ModelFallbackConfigManager.ModelFallbackConfig}
     * are accepted; unknown keys are silently ignored.</p>
     *
     * @param updates partial config map to merge
     * @return the new effective config after the update
     */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> updates) {
        Map<String, Object> updated = configManager.updateConfig(updates);
        return ResponseEntity.ok(updated);
    }

    /**
     * Returns the list of all CLI agent models (cached discovery + well-known).
     *
     * <p>Intended for the UI's fallback-chain editor so the user can pick an
     * agent + model without manually typing names.</p>
     */
    @GetMapping("/available-models")
    public ResponseEntity<List<AgentModelInfo>> getAvailableModels() {
        List<AgentModelInfo> models = modelService.getAllAgentModels(false);
        return ResponseEntity.ok(models);
    }
}
