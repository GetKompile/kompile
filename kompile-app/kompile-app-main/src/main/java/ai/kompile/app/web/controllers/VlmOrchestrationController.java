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

import ai.kompile.app.config.VlmOrchestrationConfig;
import ai.kompile.app.services.VlmOrchestrationConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for VLM GPU orchestration configuration.
 * Manages vision encoder lifecycle, multi-GPU assignment, and Triton cache settings.
 */
@RestController
@RequestMapping("/api/vlm-orchestration")
public class VlmOrchestrationController {

    private static final Logger log = LoggerFactory.getLogger(VlmOrchestrationController.class);

    @Autowired
    private VlmOrchestrationConfigService configService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("config", configService.getConfig());
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody VlmOrchestrationConfig update) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            VlmOrchestrationConfig saved = configService.updateConfig(update);
            response.put("status", "success");
            response.put("message", "VLM orchestration configuration updated");
            response.put("config", saved);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to update VLM orchestration config", e);
            response.put("status", "error");
            response.put("message", "Failed to update configuration: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetConfig() {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            VlmOrchestrationConfig defaults = configService.resetToDefaults();
            response.put("status", "success");
            response.put("message", "VLM orchestration configuration reset to defaults");
            response.put("config", defaults);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to reset VLM orchestration config", e);
            response.put("status", "error");
            response.put("message", "Failed to reset configuration: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
