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

import ai.kompile.app.services.SetupStatusService;
import ai.kompile.app.services.StagingServerLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for the setup wizard.
 * Provides a unified endpoint that the frontend setup wizard polls
 * to determine the current initialization state and which steps
 * still need user action.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupStatusController {

    private static final Logger log = LoggerFactory.getLogger(SetupStatusController.class);

    private final SetupStatusService setupStatusService;
    private final StagingServerLifecycleService stagingServerLifecycleService;

    @Autowired
    public SetupStatusController(
            SetupStatusService setupStatusService,
            @Autowired(required = false) StagingServerLifecycleService stagingServerLifecycleService) {
        this.setupStatusService = setupStatusService;
        this.stagingServerLifecycleService = stagingServerLifecycleService;
    }

    /**
     * Get the current setup status for all initialization steps.
     */
    @GetMapping("/status")
    public ResponseEntity<SetupStatusService.SetupStatus> getSetupStatus() {
        return ResponseEntity.ok(setupStatusService.getStatus());
    }

    /**
     * Dismiss the setup wizard for this session.
     * The user can still access it from the UI if needed.
     */
    @PostMapping("/dismiss")
    public ResponseEntity<Map<String, Object>> dismissWizard() {
        setupStatusService.dismissWizard();
        return ResponseEntity.ok(Map.of("dismissed", true));
    }

    /**
     * Reset the wizard dismissed state so it shows again.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetWizard() {
        setupStatusService.resetWizardDismissed();
        return ResponseEntity.ok(Map.of("dismissed", false));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STAGING SERVER LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get the status of the staging server.
     */
    @GetMapping("/staging-server/status")
    public ResponseEntity<?> getStagingServerStatus() {
        if (stagingServerLifecycleService == null) {
            return ResponseEntity.ok(Map.of("status", "unavailable",
                    "message", "Staging server lifecycle management not available"));
        }
        return ResponseEntity.ok(stagingServerLifecycleService.getStatus());
    }

    /**
     * Start the staging server.
     *
     * @param port optional port override (default: 8090)
     */
    @PostMapping("/staging-server/start")
    public ResponseEntity<?> startStagingServer(
            @RequestParam(required = false, defaultValue = "8090") int port) {
        if (stagingServerLifecycleService == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "Staging server lifecycle management not available"));
        }
        log.info("Starting staging server on port {} (requested via setup wizard)", port);
        StagingServerLifecycleService.StartResult result = stagingServerLifecycleService.startServer(port);
        return ResponseEntity.ok(result);
    }

    /**
     * Stop the staging server.
     */
    @PostMapping("/staging-server/stop")
    public ResponseEntity<?> stopStagingServer() {
        if (stagingServerLifecycleService == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "Staging server lifecycle management not available"));
        }
        log.info("Stopping staging server (requested via setup wizard)");
        StagingServerLifecycleService.StopResult result = stagingServerLifecycleService.stopServer();
        return ResponseEntity.ok(result);
    }

    /**
     * Stage a model from the catalog on the staging server.
     *
     * @param modelId the model ID to stage (e.g. "bge-base-en-v1.5")
     * @param port    the staging server port
     */
    @PostMapping("/staging-server/stage-model/{modelId}")
    public ResponseEntity<?> stageModel(
            @PathVariable String modelId,
            @RequestParam(required = false, defaultValue = "8090") int port) {
        if (stagingServerLifecycleService == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "Staging server lifecycle management not available"));
        }
        try {
            String result = stagingServerLifecycleService.stageModelFromCatalog(port, modelId);
            return ResponseEntity.ok(Map.of("success", true, "result", result));
        } catch (Exception e) {
            log.error("Failed to stage model {}", modelId, e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * Get the model catalog from the staging server.
     *
     * @param port the staging server port
     */
    @GetMapping("/staging-server/catalog")
    public ResponseEntity<?> getStagingCatalog(
            @RequestParam(required = false, defaultValue = "8090") int port) {
        if (stagingServerLifecycleService == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "Staging server lifecycle management not available"));
        }
        try {
            String catalog = stagingServerLifecycleService.getCatalog(port);
            return ResponseEntity.ok(catalog);
        } catch (Exception e) {
            log.error("Failed to get staging catalog", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
