/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package ai.kompile.app.config;

import ai.kompile.app.staging.domain.StagingServiceConfig;
import ai.kompile.app.staging.service.StagingServiceConfigService;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import ai.kompile.embedding.anserini.AnseriniEncoderFactory;
import ai.kompile.vectorstore.anserini.reranking.CrossEncoderRerankerAdapter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Wires model consumers to the UI/CLI-managed model-staging endpoint.
 *
 * <p>The canonical endpoint is {@code service-endpoints.json}, not a Spring property. When an old
 * active staging record is found and the managed file has no explicit staging URL, its endpoint is
 * migrated into that file; matching legacy records may still supply credentials and retry policy.
 * The scheduled refresh lets independently distributed personas pick up UI or CLI edits without a
 * restart.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ModelStagingWiringConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelStagingWiringConfiguration.class);
    private static final int DEFAULT_RETRY_SECONDS = 30;

    private final StagingServiceConfigService stagingConfigService;
    private final ServiceEndpointsConfigManager endpointConfigManager;
    private volatile AppliedConfig appliedConfig;

    @Autowired
    public ModelStagingWiringConfiguration(
            @Autowired(required = false) StagingServiceConfigService stagingConfigService) {
        this(stagingConfigService, ServiceEndpointsConfigManager.shared());
    }

    ModelStagingWiringConfiguration(StagingServiceConfigService stagingConfigService,
                                    ServiceEndpointsConfigManager endpointConfigManager) {
        this.stagingConfigService = stagingConfigService;
        this.endpointConfigManager = Objects.requireNonNull(endpointConfigManager, "endpointConfigManager");
    }

    @PostConstruct
    public void configureModelSources() {
        refreshConfiguration();
    }

    /** Re-read the managed JSON endpoint and apply it only when the effective connection changed. */
    @Scheduled(fixedDelay = 5_000L)
    public synchronized void refreshConfiguration() {
        ServiceEndpointsConfigManager.ServiceEndpointsConfig endpoints = endpointConfigManager.current();
        Optional<StagingServiceConfig> legacy = activeLegacyConfig();

        if (endpoints.stagingUrl() == null || endpoints.stagingUrl().isBlank()) {
            Optional<String> legacyUrl = legacy.map(StagingServiceConfig::getEndpointUrl)
                    .filter(value -> value != null && !value.isBlank());
            if (legacyUrl.isPresent()) {
                try {
                    String migrated = ServiceEndpointsConfigManager.requireHttpBaseUrl(
                            ServiceEndpointsConfigManager.STAGING_URL_KEY, legacyUrl.get());
                    endpointConfigManager.update(Map.of(
                            ServiceEndpointsConfigManager.STAGING_URL_KEY, migrated));
                    endpoints = endpointConfigManager.current();
                    log.info("Migrated legacy model-staging endpoint into managed configuration: {}", migrated);
                } catch (Exception e) {
                    log.warn("Could not migrate legacy model-staging endpoint: {}", e.getMessage());
                }
            }
        }

        String selectedUrl = normalize(endpoints.effectiveStagingUrl());
        Optional<StagingServiceConfig> matchingLegacy = legacy.filter(
                config -> sameEndpoint(selectedUrl, config.getEndpointUrl()));
        String apiKey = matchingLegacy.map(StagingServiceConfig::getApiKey).orElse(null);
        int retrySeconds = matchingLegacy.map(StagingServiceConfig::getRetryPollIntervalSeconds)
                .orElse(DEFAULT_RETRY_SECONDS);

        AppliedConfig next = new AppliedConfig(selectedUrl, apiKey, retrySeconds);
        if (next.equals(appliedConfig)) {
            return;
        }
        configureStagingService(selectedUrl, apiKey, retrySeconds);
    }

    /** Configure all model managers immediately, retained as an explicit runtime/test seam. */
    public void configureStagingService(String url, String apiKey) {
        configureStagingService(url, apiKey, DEFAULT_RETRY_SECONDS);
    }

    /** Configure all model managers immediately with a retry interval. */
    public synchronized void configureStagingService(String url, String apiKey,
                                                      int retryPollIntervalSeconds) {
        if (url == null || url.isBlank()) {
            log.debug("No model-staging URL provided");
            return;
        }

        String normalizedUrl = normalize(url);
        int retrySeconds = Math.max(1, retryPollIntervalSeconds);
        log.info("Configuring managed model-staging dependency at {} (retry poll interval: {}s)",
                normalizedUrl, retrySeconds);

        try {
            AnseriniEncoderFactory.configureStagingService(normalizedUrl, apiKey, retrySeconds);
        } catch (Exception e) {
            log.warn("Failed to configure AnseriniEncoderFactory: {}", e.getMessage());
        }

        try {
            CrossEncoderRerankerAdapter.configureStagingService(normalizedUrl, apiKey);
        } catch (Exception e) {
            log.warn("Failed to configure CrossEncoderRerankerAdapter: {}", e.getMessage());
        }

        appliedConfig = new AppliedConfig(normalizedUrl, apiKey, retrySeconds);
    }

    private Optional<StagingServiceConfig> activeLegacyConfig() {
        if (stagingConfigService == null) {
            return Optional.empty();
        }
        try {
            return stagingConfigService.getActiveConfig();
        } catch (Exception e) {
            log.debug("Legacy staging metadata is unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean sameEndpoint(String left, String right) {
        return left != null && right != null && left.equals(normalize(right));
    }

    private static String normalize(String url) {
        return url == null ? null : url.trim().replaceAll("/+$", "");
    }

    private record AppliedConfig(String url, String apiKey, int retrySeconds) {}
}
