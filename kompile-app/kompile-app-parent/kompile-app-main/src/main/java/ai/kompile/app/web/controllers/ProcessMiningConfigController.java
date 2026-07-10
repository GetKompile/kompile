/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.process.discovery.mining.ProcessMiningConfig;
import ai.kompile.process.discovery.mining.ProcessMiningConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for the process-mining configuration surface.
 *
 * <p>Exposes the {@link ProcessMiningConfigManager}-managed {@code mining*} tunables (clustering,
 * Declare floors, entailment thresholds, recency decay, weight learning, hybrid semantic blend,
 * actor-share gate, anchor type) to the web UI. Mirrors {@link KbConfigController}.</p>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/process-mining-config} — current effective config</li>
 *   <li>{@code GET /api/process-mining-config/defaults} — factory defaults (no file I/O)</li>
 *   <li>{@code POST /api/process-mining-config} — merge {@code mining*} updates, persist, return result</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/process-mining-config")
@CrossOrigin(origins = "*")
public class ProcessMiningConfigController {

    private static final Logger log = LoggerFactory.getLogger(ProcessMiningConfigController.class);

    private final ProcessMiningConfigManager configManager;

    @Autowired
    public ProcessMiningConfigController(ProcessMiningConfigManager configManager) {
        this.configManager = configManager;
    }

    /** Current effective process-mining configuration ({@code mining*} keys). */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        try {
            return ResponseEntity.ok(configManager.currentAsMap());
        } catch (Exception e) {
            log.error("Error reading process-mining config", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Factory defaults — for the UI's "Reset to defaults" preview without committing a write. */
    @GetMapping("/defaults")
    public ResponseEntity<Map<String, Object>> getDefaults() {
        try {
            return ResponseEntity.ok(ProcessMiningConfig.defaults().toMap());
        } catch (Exception e) {
            log.error("Error computing process-mining defaults", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Merges {@code mining*} updates into the persisted config and returns the effective result.
     * Keys outside {@link ProcessMiningConfig#keys()} are silently ignored so a partial update can
     * never clobber unrelated config sections.
     */
    @PostMapping
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, Object> updates) {
        try {
            Map<String, Object> result = configManager.update(updates);
            log.info("Process-mining config updated: {} keys applied", updates == null ? 0 : updates.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error updating process-mining config", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
