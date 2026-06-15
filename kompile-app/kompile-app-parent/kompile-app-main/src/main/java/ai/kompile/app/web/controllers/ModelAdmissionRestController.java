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

import ai.kompile.app.config.ModelAdmissionConfig;
import ai.kompile.app.services.ModelAdmissionConfigService;
import ai.kompile.app.services.ModelAdmissionController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/model-admission")
public class ModelAdmissionRestController {

    private static final Logger log = LoggerFactory.getLogger(ModelAdmissionRestController.class);

    private final ModelAdmissionConfigService configService;
    private final ModelAdmissionController admissionController;

    public ModelAdmissionRestController(ModelAdmissionConfigService configService,
                                         ModelAdmissionController admissionController) {
        this.configService = configService;
        this.admissionController = admissionController;
    }

    @GetMapping("/config")
    public ResponseEntity<ModelAdmissionConfig> getConfig() {
        return ResponseEntity.ok(configService.getConfiguration());
    }

    @PutMapping("/config")
    public ResponseEntity<ModelAdmissionConfig> updateConfig(@RequestBody ModelAdmissionConfig config) {
        try {
            configService.saveConfiguration(config);
            return ResponseEntity.ok(configService.getConfiguration());
        } catch (Exception e) {
            log.error("Error saving admission config: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/config/reset")
    public ResponseEntity<ModelAdmissionConfig> resetConfig() {
        try {
            configService.resetToDefaults();
            return ResponseEntity.ok(configService.getConfiguration());
        } catch (Exception e) {
            log.error("Error resetting admission config: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(admissionController.getStatus());
    }

    @PostMapping("/check/{modelId}")
    public ResponseEntity<Map<String, Object>> checkAdmission(@PathVariable String modelId) {
        var decision = admissionController.canAdmit(modelId);
        return ResponseEntity.ok(Map.of(
                "admitted", decision.admitted(),
                "reason", decision.reason(),
                "estimatedMemoryBytes", decision.estimatedMemoryBytes(),
                "modelsToEvict", decision.modelsToEvict()
        ));
    }

    @PostMapping("/load/{modelId}")
    public ResponseEntity<Map<String, Object>> requestLoad(@PathVariable String modelId) {
        try {
            var future = admissionController.requestLoad(modelId);
            // Return immediately with status
            return ResponseEntity.ok(Map.of(
                    "status", "loading",
                    "modelId", modelId,
                    "message", "Model load initiated"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/unload/{modelId}")
    public ResponseEntity<Map<String, Object>> unloadModel(@PathVariable String modelId) {
        admissionController.unload(modelId);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Model unloaded: " + modelId
        ));
    }

    @PostMapping("/demote/{modelId}")
    public ResponseEntity<Map<String, Object>> demoteModel(@PathVariable String modelId) {
        admissionController.demoteToCpu(modelId);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Model demoted to CPU: " + modelId
        ));
    }

    @PostMapping("/promote/{modelId}")
    public ResponseEntity<Map<String, Object>> promoteModel(@PathVariable String modelId) {
        admissionController.promoteToGpu(modelId);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Model promoted to GPU: " + modelId
        ));
    }
}
