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

package ai.kompile.staging.web;

import ai.kompile.staging.training.PeftService;
import ai.kompile.staging.web.dto.PeftConfigRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for PEFT (Parameter-Efficient Fine-Tuning) operations.
 * Provides endpoints for querying PEFT types, creating PEFT configurations,
 * inspecting PEFT info, and merging adapter weights.
 */
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/peft")
@CrossOrigin(origins = "*")
public class PeftController {

    private static final Logger log = LoggerFactory.getLogger(PeftController.class);

    private final PeftService peftService;

    public PeftController(PeftService peftService) {
        this.peftService = peftService;
    }

    // ==================== PEFT Types ====================

    /**
     * Get available PEFT types.
     */
    @GetMapping("/types")
    public ResponseEntity<List<Map<String, String>>> getAvailablePeftTypes() {
        try {
            return ResponseEntity.ok(peftService.getAvailablePeftTypes());
        } catch (Exception e) {
            log.error("Failed to get available PEFT types", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Create PEFT Config ====================

    /**
     * Create a PEFT configuration for a model.
     */
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createPeftConfig(@RequestBody PeftConfigRequest request) {
        try {
            log.info("Creating PEFT config: type={}, model={}", request.getPeftType(), request.getBaseModelId());
            Map<String, Object> result = peftService.createPeftModel(request.getBaseModelId(), request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to create PEFT config", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== PEFT Info ====================

    /**
     * Get PEFT information for a model.
     */
    @GetMapping("/{modelId}/info")
    public ResponseEntity<Map<String, Object>> getPeftInfo(@PathVariable String modelId) {
        try {
            Map<String, Object> info = peftService.getPeftInfo(modelId);
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            log.error("Failed to get PEFT info for model: {}", modelId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Merge Weights ====================

    /**
     * Merge PEFT adapter weights back into the base model.
     */
    @PostMapping("/{modelId}/merge")
    public ResponseEntity<Map<String, Object>> mergeWeights(@PathVariable String modelId) {
        try {
            log.info("Merging PEFT weights for model: {}", modelId);
            Map<String, Object> result = peftService.mergeWeights(modelId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to merge PEFT weights for model: {}", modelId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
