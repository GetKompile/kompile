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

import ai.kompile.app.web.dto.ProcessingSettingsRequest;
import ai.kompile.app.web.dto.ProcessingSettingsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Alias controller for processing settings that handles alternative URL patterns.
 * Delegates to ProcessingSettingsController for actual functionality.
 *
 * This handles the following alternative URL patterns:
 * - /api/settings/processing -> delegates to /api/processing/settings
 * - /api/processing-settings -> delegates to /api/processing/settings
 */
@RestController
@CrossOrigin(origins = "*")
public class ProcessingSettingsAliasController {

    private static final Logger logger = LoggerFactory.getLogger(ProcessingSettingsAliasController.class);

    private final ProcessingSettingsController processingSettingsController;

    @Autowired
    public ProcessingSettingsAliasController(ProcessingSettingsController processingSettingsController) {
        this.processingSettingsController = processingSettingsController;
    }

    /**
     * Alias for /api/processing/settings - handles /api/settings/processing
     */
    @GetMapping("/api/settings/processing")
    public ResponseEntity<ProcessingSettingsResponse> getSettingsAliasReversed() {
        logger.debug("Handling aliased endpoint /api/settings/processing -> /api/processing/settings");
        return processingSettingsController.getSettings();
    }

    /**
     * Alias for /api/processing/settings - handles /api/settings/processing (PUT)
     */
    @PutMapping("/api/settings/processing")
    public ResponseEntity<ProcessingSettingsResponse> updateSettingsAliasReversed(@RequestBody ProcessingSettingsRequest request) {
        logger.debug("Handling aliased endpoint PUT /api/settings/processing -> /api/processing/settings");
        return processingSettingsController.updateSettings(request);
    }

    /**
     * Alias for /api/processing/settings - handles /api/processing-settings
     */
    @GetMapping("/api/processing-settings")
    public ResponseEntity<ProcessingSettingsResponse> getSettingsAliasHyphenated() {
        logger.debug("Handling aliased endpoint /api/processing-settings -> /api/processing/settings");
        return processingSettingsController.getSettings();
    }

    /**
     * Alias for /api/processing/settings - handles /api/processing-settings (PUT)
     */
    @PutMapping("/api/processing-settings")
    public ResponseEntity<ProcessingSettingsResponse> updateSettingsAliasHyphenated(@RequestBody ProcessingSettingsRequest request) {
        logger.debug("Handling aliased endpoint PUT /api/processing-settings -> /api/processing/settings");
        return processingSettingsController.updateSettings(request);
    }
}
