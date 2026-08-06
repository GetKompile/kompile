/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.staging.web;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Model Staging's UI/CLI surface for the project-managed service topology.
 *
 * <p>The serving child is an independently launched dependency, so both the UI
 * and the OpenAI compatibility bridge read the same {@code service-endpoints.json}
 * file instead of relying on Spring-only configuration.</p>
 */
@RestController
@RequestMapping("/api/service-endpoints")
public class ServiceEndpointsConfigController {

    private static final Logger log = LoggerFactory.getLogger(ServiceEndpointsConfigController.class);
    private static final Set<String> URL_KEYS = Set.of(
            KompileService.ADMIN.configKey(),
            KompileService.CHAT.configKey(),
            KompileService.CRAWL.configKey(),
            ServiceEndpointsConfigManager.STAGING_URL_KEY,
            ServiceEndpointsConfigManager.SERVING_URL_KEY);

    private final ServiceEndpointsConfigManager configManager;

    public ServiceEndpointsConfigController() {
        this(ServiceEndpointsConfigManager.forProjectDirectory(
                KompileHome.resolvedProjectDirectory().toPath()));
    }

    ServiceEndpointsConfigController(ServiceEndpointsConfigManager configManager) {
        this.configManager = configManager;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        try {
            return ResponseEntity.ok(configManager.currentAsMap());
        } catch (Exception e) {
            log.error("Error reading service endpoints", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping
    public ResponseEntity<?> updateConfig(@RequestBody(required = false) Map<String, Object> updates) {
        try {
            Map<String, Object> normalized = new LinkedHashMap<>();
            if (updates != null) {
                for (String key : URL_KEYS) {
                    if (!updates.containsKey(key)) {
                        continue;
                    }
                    String url = ServiceEndpointsConfigManager.SERVING_URL_KEY.equals(key)
                            ? ServiceEndpointsConfigManager.requireLoopbackServingBaseUrl(updates.get(key))
                            : ServiceEndpointsConfigManager.requireHttpBaseUrl(key, updates.get(key));
                    normalized.put(key, url);
                }
            }
            return ResponseEntity.ok(configManager.update(normalized));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating service endpoints", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
