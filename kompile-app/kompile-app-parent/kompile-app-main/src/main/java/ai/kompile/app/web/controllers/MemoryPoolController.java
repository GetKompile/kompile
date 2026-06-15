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

import ai.kompile.app.config.MemoryPoolConfig;
import ai.kompile.app.services.MemoryPoolManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for GPU memory pool configuration and monitoring.
 */
@RestController
@RequestMapping("/api/memory-pools")
public class MemoryPoolController {

    @Autowired
    private MemoryPoolManager memoryPoolManager;

    @GetMapping("/config")
    public ResponseEntity<MemoryPoolConfig> getConfig() {
        return ResponseEntity.ok(memoryPoolManager.getConfiguration());
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody MemoryPoolConfig config) {
        try {
            memoryPoolManager.saveConfiguration(config);
            return ResponseEntity.ok(Map.of("status", "updated", "config", memoryPoolManager.getConfiguration()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/config/reset")
    public ResponseEntity<Map<String, Object>> resetConfig() {
        try {
            memoryPoolManager.resetToDefaults();
            return ResponseEntity.ok(Map.of("status", "reset", "config", memoryPoolManager.getConfiguration()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(memoryPoolManager.getStatus());
    }
}
