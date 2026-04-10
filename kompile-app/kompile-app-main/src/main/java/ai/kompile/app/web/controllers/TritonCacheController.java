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

import ai.kompile.app.services.TritonCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for Triton JIT compilation cache management.
 * Provides endpoints for importing, exporting, and invalidating Triton cached modules.
 */
@RestController
@RequestMapping("/api/triton-cache")
public class TritonCacheController {

    private static final Logger log = LoggerFactory.getLogger(TritonCacheController.class);

    @Autowired
    private TritonCacheService tritonCacheService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(tritonCacheService.getCacheStats());
    }

    @PostMapping("/export")
    public ResponseEntity<Map<String, Object>> exportCache(@RequestParam String modelId) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean success = tritonCacheService.exportCache(modelId);
        result.put("status", success ? "success" : "error");
        result.put("modelId", modelId);
        result.put("message", success
                ? "Triton cache exported for model: " + modelId
                : "Failed to export Triton cache for model: " + modelId);
        return success ? ResponseEntity.ok(result) : ResponseEntity.internalServerError().body(result);
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importCache(@RequestParam String modelId) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean success = tritonCacheService.importCache(modelId);
        result.put("status", success ? "success" : "not_found");
        result.put("modelId", modelId);
        result.put("message", success
                ? "Triton cache imported for model: " + modelId
                : "No cache bundle found for model: " + modelId);
        return ResponseEntity.ok(result);
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> invalidateAll() {
        tritonCacheService.invalidateAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("message", "All in-memory Triton compiled modules invalidated");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/bundles")
    public ResponseEntity<List<Map<String, Object>>> listBundles() {
        return ResponseEntity.ok(tritonCacheService.listBundles());
    }
}
