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

import ai.kompile.app.config.ModelWeightCacheConfig;
import ai.kompile.app.services.ModelWeightCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for three-tier weight cache configuration and monitoring.
 */
@RestController
@RequestMapping("/api/weight-cache")
public class ModelWeightCacheController {

    @Autowired
    private ModelWeightCache weightCache;

    @GetMapping("/config")
    public ResponseEntity<ModelWeightCacheConfig> getConfig() {
        return ResponseEntity.ok(weightCache.getConfiguration());
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody ModelWeightCacheConfig config) {
        try {
            weightCache.saveConfiguration(config);
            return ResponseEntity.ok(Map.of("status", "updated", "config", weightCache.getConfiguration()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(weightCache.getStatus());
    }

    @PostMapping("/demote/{modelId}")
    public ResponseEntity<Map<String, Object>> demote(
            @PathVariable String modelId,
            @RequestParam(required = false) String layerName) {
        try {
            if (layerName != null) {
                weightCache.demoteToHost(modelId, layerName);
            } else {
                weightCache.demoteAllToHost(modelId);
            }
            return ResponseEntity.ok(Map.of("status", "demoted", "modelId", modelId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/promote/{modelId}")
    public ResponseEntity<Map<String, Object>> promote(
            @PathVariable String modelId,
            @RequestParam(required = false) String layerName) {
        try {
            if (layerName != null) {
                weightCache.promoteToGpu(modelId, layerName);
            } else {
                weightCache.promoteAllToGpu(modelId);
            }
            return ResponseEntity.ok(Map.of("status", "promoted", "modelId", modelId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
