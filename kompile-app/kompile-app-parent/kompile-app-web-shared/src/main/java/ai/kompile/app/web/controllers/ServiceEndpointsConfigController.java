/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Shared UI/CLI surface for the managed {@code service-endpoints.json} deployment topology. */
@RestController
@RequestMapping("/api/service-endpoints")
@CrossOrigin(origins = "*")
public class ServiceEndpointsConfigController {

    private static final Logger log = LoggerFactory.getLogger(ServiceEndpointsConfigController.class);
    private static final Duration DEPENDENCY_TIMEOUT = Duration.ofSeconds(2);
    private static final Set<String> URL_KEYS = Set.of(
            KompileService.ADMIN.configKey(),
            KompileService.CHAT.configKey(),
            KompileService.CRAWL.configKey(),
            ServiceEndpointsConfigManager.STAGING_URL_KEY,
            ServiceEndpointsConfigManager.SERVING_URL_KEY);

    private final ServiceEndpointsConfigManager configManager;
    private final HttpClient dependencyHttpClient;

    /**
     * Runtime entry point. A fresh manager resolves the launcher-provided project root when this
     * persona belongs to a project, while a standalone Chat/Admin/Crawl process keeps using the
     * user's global managed configuration.
     */
    public ServiceEndpointsConfigController() {
        this(new ServiceEndpointsConfigManager());
    }

    /** Test seam for an isolated managed-config file. */
    ServiceEndpointsConfigController(ServiceEndpointsConfigManager configManager) {
        this(configManager, HttpClient.newBuilder().connectTimeout(DEPENDENCY_TIMEOUT).build());
    }

    /** Test seam for deterministic dependency probes. */
    ServiceEndpointsConfigController(ServiceEndpointsConfigManager configManager,
                                     HttpClient dependencyHttpClient) {
        this.configManager = configManager;
        this.dependencyHttpClient = dependencyHttpClient;
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

    /**
     * Shared, persona-local health view of a configured service dependency. The browser calls this
     * same-origin endpoint, and this process probes the managed target server-side, so a standalone
     * persona never needs to issue failing cross-origin requests merely to discover that an optional
     * Admin, Chat, Crawl, or Model Staging integration is offline.
     */
    @GetMapping("/dependencies/{dependency}")
    public ResponseEntity<Map<String, Object>> getDependencyStatus(
            @PathVariable("dependency") String dependency) {
        ServiceEndpointsConfigManager.ServiceEndpointsConfig config = configManager.current();
        if (ServiceEndpointsConfigManager.STAGING_URL_KEY
                .replace("Url", "").equalsIgnoreCase(dependency)) {
            return probeDependency(
                    "staging",
                    config.stagingUrl() != null,
                    config.effectiveStagingUrl(),
                    "/api/staging/status");
        }

        KompileService service = KompileService.fromId(dependency);
        if (service == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "dependency", dependency,
                    "error", "Unknown managed dependency: " + dependency));
        }
        return probeDependency(
                service.id(),
                config.url(service) != null,
                config.effectiveUrl(service),
                "/api/service-endpoints");
    }

    private ResponseEntity<Map<String, Object>> probeDependency(
            String dependency,
            boolean configured,
            String endpointUrl,
            String healthPath) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("dependency", dependency);
        status.put("configured", configured);
        status.put("endpointUrl", endpointUrl);

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpointUrl + healthPath))
                    .timeout(DEPENDENCY_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = dependencyHttpClient.send(
                    request, HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();
            status.put("reachable", statusCode >= 200 && statusCode < 300);
            status.put("statusCode", statusCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status.put("reachable", false);
            status.put("statusCode", 0);
            status.put("error", dependency + " dependency probe was interrupted");
        } catch (Exception e) {
            status.put("reachable", false);
            status.put("statusCode", 0);
            status.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return ResponseEntity.ok(status);
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
