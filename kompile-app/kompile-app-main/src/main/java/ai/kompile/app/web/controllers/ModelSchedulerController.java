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

import ai.kompile.app.config.ModelSchedulerConfig;
import ai.kompile.app.services.ContinuousBatcher;
import ai.kompile.app.services.ModelScheduler;
import ai.kompile.app.services.ModelSchedulerConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/scheduler")
public class ModelSchedulerController {

    private static final Logger log = LoggerFactory.getLogger(ModelSchedulerController.class);

    private final ModelSchedulerConfigService configService;
    private final ModelScheduler modelScheduler;
    private final ContinuousBatcher continuousBatcher;

    public ModelSchedulerController(ModelSchedulerConfigService configService,
                                     ModelScheduler modelScheduler,
                                     ContinuousBatcher continuousBatcher) {
        this.configService = configService;
        this.modelScheduler = modelScheduler;
        this.continuousBatcher = continuousBatcher;
    }

    @GetMapping("/config")
    public ResponseEntity<ModelSchedulerConfig> getConfig() {
        return ResponseEntity.ok(configService.getConfiguration());
    }

    @PutMapping("/config")
    public ResponseEntity<ModelSchedulerConfig> updateConfig(@RequestBody ModelSchedulerConfig config) {
        try {
            configService.saveConfiguration(config);
            return ResponseEntity.ok(configService.getConfiguration());
        } catch (Exception e) {
            log.error("Error saving scheduler config: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/config/reset")
    public ResponseEntity<ModelSchedulerConfig> resetConfig() {
        try {
            configService.resetToDefaults();
            return ResponseEntity.ok(configService.getConfiguration());
        } catch (Exception e) {
            log.error("Error resetting scheduler config: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("scheduler", modelScheduler.getStatus());
        status.put("continuousBatcher", continuousBatcher.getStatus());
        return ResponseEntity.ok(status);
    }
}
