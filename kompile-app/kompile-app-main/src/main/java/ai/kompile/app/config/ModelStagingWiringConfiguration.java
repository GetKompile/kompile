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

package ai.kompile.app.config;

import ai.kompile.app.staging.domain.StagingServiceConfig;
import ai.kompile.app.staging.service.StagingServiceConfigService;
import ai.kompile.embedding.anserini.AnseriniEncoderFactory;
import ai.kompile.vectorstore.anserini.reranking.CrossEncoderRerankerAdapter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.Optional;

/**
 * Configuration that wires the UI-configured staging service to the model managers.
 *
 * When the app starts, this reads the active staging config from the database.
 * If no DB config exists, falls back to kompile.staging.url property.
 */
@Configuration(proxyBeanMethods = false)
public class ModelStagingWiringConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelStagingWiringConfiguration.class);

    private final StagingServiceConfigService stagingConfigService;

    @Value("${kompile.staging.url:}")
    private String stagingUrlProperty;

    @Value("${kompile.staging.api-key:}")
    private String stagingApiKeyProperty;

    @Autowired
    public ModelStagingWiringConfiguration(@Autowired(required = false) StagingServiceConfigService stagingConfigService) {
        this.stagingConfigService = stagingConfigService;
    }

    @PostConstruct
    public void configureModelSources() {
        // Try DB config first
        if (stagingConfigService != null) {
            Optional<StagingServiceConfig> activeConfig = stagingConfigService.getActiveConfig();
            if (activeConfig.isPresent()) {
                StagingServiceConfig config = activeConfig.get();
                configureStagingService(
                        config.getEndpointUrl(),
                        config.getApiKey(),
                        config.getRetryPollIntervalSeconds()
                );
                return;
            }
        }

        // Fall back to Spring property
        if (stagingUrlProperty != null && !stagingUrlProperty.isBlank()) {
            log.info("No DB staging config found. Using property kompile.staging.url={}", stagingUrlProperty);
            configureStagingService(stagingUrlProperty, stagingApiKeyProperty, 30);
            return;
        }

        log.info("No staging service configured (DB or property). Models will be loaded from local registry.");
    }

    /**
     * Configure the staging service for all model managers.
     */
    public void configureStagingService(String url, String apiKey) {
        configureStagingService(url, apiKey, 30); // Default 30 seconds
    }

    /**
     * Configure the staging service for all model managers with retry poll interval.
     *
     * @param url the staging service URL
     * @param apiKey the API key for authentication (may be null)
     * @param retryPollIntervalSeconds the interval in seconds to poll when service is unavailable
     */
    public void configureStagingService(String url, String apiKey, int retryPollIntervalSeconds) {
        if (url == null || url.isBlank()) {
            log.debug("No staging URL provided");
            return;
        }

        log.info("Configuring model source: staging service at {} (retry poll interval: {}s)", url, retryPollIntervalSeconds);

        // Configure encoder factory
        try {
            AnseriniEncoderFactory.configureStagingService(url, apiKey, retryPollIntervalSeconds);
        } catch (Exception e) {
            log.warn("Failed to configure AnseriniEncoderFactory: {}", e.getMessage());
        }

        // Configure cross-encoder adapter
        try {
            CrossEncoderRerankerAdapter.configureStagingService(url, apiKey);
        } catch (Exception e) {
            log.warn("Failed to configure CrossEncoderRerankerAdapter: {}", e.getMessage());
        }
    }

    /**
     * Refresh the model source configuration.
     * Call this when the staging config changes in the UI.
     */
    public void refreshConfiguration() {
        configureModelSources();
    }
}
