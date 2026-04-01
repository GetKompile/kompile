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

import ai.kompile.app.services.sdx.SdxServingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sdx")
public class SdxServingController {

    private final SdxServingService service;

    public SdxServingController(SdxServingService service) {
        this.service = service;
    }

    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> listModels() {
        return ResponseEntity.ok(service.listAvailableModels());
    }

    @PostMapping("/models/{modelId}/load")
    public ResponseEntity<Map<String, Object>> loadModel(@PathVariable String modelId) {
        try {
            return ResponseEntity.ok(service.loadModel(modelId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "error", e.getMessage()));
        }
    }

    @PostMapping("/models/{modelId}/unload")
    public ResponseEntity<Map<String, Object>> unloadModel(@PathVariable String modelId) {
        boolean unloaded = service.unloadModel(modelId);
        return ResponseEntity.ok(Map.of("modelId", modelId, "unloaded", unloaded));
    }

    @GetMapping("/models/{modelId}/schema")
    public ResponseEntity<Map<String, Object>> getSchema(@PathVariable String modelId) {
        try {
            return ResponseEntity.ok(service.getModelSchema(modelId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "error", e.getMessage()));
        }
    }

    @PostMapping("/models/{modelId}/infer")
    public ResponseEntity<Map<String, Object>> infer(
            @PathVariable String modelId,
            @RequestBody(required = false) Map<String, Object> input) {
        try {
            return ResponseEntity.ok(service.infer(modelId, input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "error", e.getMessage()));
        }
    }

    @GetMapping("/models/{modelId}/input-template")
    public ResponseEntity<Map<String, Object>> getInputTemplate(@PathVariable String modelId) {
        try {
            return ResponseEntity.ok(service.getInputTemplate(modelId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(service.getStatus());
    }
}
