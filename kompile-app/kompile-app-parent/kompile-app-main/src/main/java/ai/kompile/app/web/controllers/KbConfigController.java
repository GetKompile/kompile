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

import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for the KB confidence / learning configuration surface.
 *
 * <p>Exposes the {@link KbConfigManager}-managed {@code kb*} tunables (PSL weight learner,
 * Beta evidence priors, source-trust tiers, MEBN interval, etc.) to the web UI.
 * Mirrors the convention of {@link BatchSizeConfigController} — constructor-injected
 * service, try/catch per endpoint, {@code ResponseEntity} return types.</p>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/kb-config} — current effective config as a {@code Map<String,Object>}</li>
 *   <li>{@code GET /api/kb-config/defaults} — factory defaults (no file I/O)</li>
 *   <li>{@code POST /api/kb-config} — merge {@code kb*} updates, persist, re-read, return result</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/kb-config")
@CrossOrigin(origins = "*")
public class KbConfigController {

    private static final Logger log = LoggerFactory.getLogger(KbConfigController.class);

    private final KbConfigManager kbConfigManager;

    @Autowired
    public KbConfigController(KbConfigManager kbConfigManager) {
        this.kbConfigManager = kbConfigManager;
    }

    /**
     * Returns the current effective KB confidence / learning configuration.
     *
     * <p>Keys are all prefixed {@code kb*} (e.g. {@code kbPslLearningRate},
     * {@code kbTrustLlmExtraction}). The values reflect the live config file;
     * a missing or unreadable file returns {@link KbConfig#defaults()}.</p>
     *
     * @return {@code 200 OK} with the config map; {@code 500} on unexpected error
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        try {
            return ResponseEntity.ok(kbConfigManager.currentAsMap());
        } catch (Exception e) {
            log.error("Error reading KB confidence config", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Returns the factory default KB confidence / learning configuration.
     *
     * <p>Useful for the web UI's "Reset to defaults" preview without committing a write.</p>
     *
     * @return {@code 200 OK} with the defaults map
     */
    @GetMapping("/defaults")
    public ResponseEntity<Map<String, Object>> getDefaults() {
        try {
            return ResponseEntity.ok(KbConfig.defaults().toMap());
        } catch (Exception e) {
            log.error("Error computing KB confidence defaults", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Merges {@code kb*} updates into the persisted config and returns the resulting effective config.
     *
     * <p>Only keys owned by {@link KbConfig#keys()} are accepted; any other keys in the request
     * body are silently ignored so a partial update can never clobber unrelated config sections.</p>
     *
     * @param updates map of {@code kb*} key/value pairs to apply
     * @return {@code 200 OK} with the updated effective config map; {@code 500} if the file cannot
     *         be written
     */
    @PostMapping
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, Object> updates) {
        try {
            Map<String, Object> result = kbConfigManager.update(updates);
            log.info("KB confidence config updated: {} keys applied", updates == null ? 0 : updates.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error updating KB confidence config", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
