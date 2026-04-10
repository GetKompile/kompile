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

import ai.kompile.app.config.ModelWarmupConfig;
import ai.kompile.app.services.ModelWarmupConfigService;
import ai.kompile.app.services.ModelWarmupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for model warmup configuration and triggering.
 */
@RestController
@RequestMapping("/api/model-warmup")
public class ModelWarmupController {

    private static final Logger log = LoggerFactory.getLogger(ModelWarmupController.class);

    @Autowired
    private ModelWarmupConfigService configService;

    @Autowired
    private ModelWarmupService warmupService;

    @GetMapping("/config")
    public ResponseEntity<ModelWarmupConfig> getConfig() {
        return ResponseEntity.ok(configService.getConfiguration());
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody ModelWarmupConfig config) {
        try {
            configService.saveConfiguration(config);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "updated");
            result.put("config", configService.getConfiguration());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/config/reset")
    public ResponseEntity<Map<String, Object>> resetConfig() {
        try {
            configService.resetToDefaults();
            return ResponseEntity.ok(Map.of("status", "reset", "config", configService.getConfiguration()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(warmupService.getStatus());
    }

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> triggerWarmupAll() {
        var results = warmupService.warmupAll();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "completed");
        response.put("results", results);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/trigger/{serviceType}")
    public ResponseEntity<Map<String, Object>> triggerWarmup(@PathVariable String serviceType) {
        var result = warmupService.warmupService(serviceType);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "completed");
        response.put("result", result);
        return ResponseEntity.ok(response);
    }
}
