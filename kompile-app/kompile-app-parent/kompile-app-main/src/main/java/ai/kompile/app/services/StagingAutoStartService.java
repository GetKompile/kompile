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

package ai.kompile.app.services;

import ai.kompile.app.config.ModelStagingWiringConfiguration;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Automatically starts the kompile-model-staging server on application boot
 * if it isn't already running and an executable can be found (project-local
 * or global install).
 *
 * <p>Fires on {@link ApplicationReadyEvent} — after all beans are initialized
 * but before the first {@code ModelAutoInitializationService} poll (which has
 * a 10-second initial delay). This ensures the staging server is available
 * by the time the embedding model tries to load.</p>
 *
 * <p>Controlled by {@code kompile.staging.auto-start=true} (default). Set to
 * {@code false} to disable and manage the staging server manually.</p>
 *
 * <p>After starting the staging server, this service triggers a refresh of
 * {@link ModelStagingWiringConfiguration} so the encoder factory and
 * cross-encoder adapter pick up the staging service URL immediately.</p>
 */
@Service
public class StagingAutoStartService {

    private static final Logger log = LoggerFactory.getLogger(StagingAutoStartService.class);

    private final StagingServerLifecycleService lifecycleService;
    private final ModelStagingWiringConfiguration wiringConfiguration;

    @Autowired
    public StagingAutoStartService(
            @Autowired(required = false) StagingServerLifecycleService lifecycleService,
            @Autowired(required = false) ModelStagingWiringConfiguration wiringConfiguration) {
        this.lifecycleService = lifecycleService;
        this.wiringConfiguration = wiringConfiguration;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0) // Run before other ApplicationReadyEvent handlers
    public void onApplicationReady() {
        if (lifecycleService == null) {
            log.debug("StagingServerLifecycleService not available, skipping auto-start");
            return;
        }

        if (!lifecycleService.isAutoStartEnabled()) {
            log.info("Staging server auto-start is disabled (kompile.staging.auto-start=false)");
            return;
        }

        int port = lifecycleService.getConfiguredPort();

        // Check if already running (e.g., user started it manually or a previous instance)
        if (lifecycleService.isRunning(port)) {
            log.info("Staging server already running on port {}", port);
            ensureWiringConfigured(port);
            return;
        }

        // Check if an executable is available
        StagingServerLifecycleService.StagingExecutable exe = lifecycleService.findStagingExecutable();
        if (exe == null) {
            // Surface searched locations so operators know where to put the binary
            List<String> searched = resolveSearchedLocations();
            log.warn("No staging server executable found. Embedding models will load from local cache. " +
                    "Searched locations: {}", searched);
            return;
        }

        log.info("Auto-starting staging server ({} {}) on port {}",
                exe.getType(), exe.isProjectLocal() ? "project-local" : "global", port);

        StagingServerLifecycleService.StartResult result = lifecycleService.startServer(port);

        if (result.isSuccess()) {
            log.info("Staging server auto-started: {}", result.getMessage());
            ensureWiringConfigured(port);
        } else {
            log.warn("Failed to auto-start staging server: {}", result.getMessage());
        }
    }

    /**
     * Returns a human-readable list of paths the lifecycle service would search for
     * the staging executable.  Used in the WARN log when no executable is found.
     */
    private List<String> resolveSearchedLocations() {
        List<String> locations = new ArrayList<>();
        // Priority 1 & 2: project-local native image / JAR
        locations.add("./staging/kompile-model-staging (native image)");
        locations.add("./staging/kompile-model-staging*.jar");
        // Priority 3: global install under ~/.kompile/components
        String home = System.getProperty("user.home", "~");
        locations.add(Paths.get(home, ".kompile", "components", "kompile-model-staging").toString()
                + "/<version>/*.jar");
        return locations;
    }

    /**
     * Ensure the model source wiring is configured to point at the staging server.
     * This handles the case where the staging server was started after
     * ModelStagingWiringConfiguration's @PostConstruct already ran.
     */
    private void ensureWiringConfigured(int port) {
        if (wiringConfiguration == null) {
            log.debug("ModelStagingWiringConfiguration not available, cannot configure wiring");
            return;
        }

        ServiceEndpointsConfigManager manager = ServiceEndpointsConfigManager.shared();
        String configuredUrl = manager.current().stagingUrl();
        if (configuredUrl == null || configuredUrl.isBlank()) {
            String localUrl = "http://localhost:" + port;
            try {
                manager.update(java.util.Map.of(ServiceEndpointsConfigManager.STAGING_URL_KEY, localUrl));
                log.info("Recorded auto-started model-staging dependency at {} in managed configuration",
                        localUrl);
            } catch (Exception e) {
                log.warn("Could not persist auto-started model-staging endpoint {}: {}", localUrl,
                        e.getMessage());
            }
        } else {
            log.info("Preserving managed model-staging dependency at {}", configuredUrl);
        }
        wiringConfiguration.refreshConfiguration();
    }
}
