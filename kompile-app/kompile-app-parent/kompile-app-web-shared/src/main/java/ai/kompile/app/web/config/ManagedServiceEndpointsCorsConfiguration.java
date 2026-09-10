/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package ai.kompile.app.web.config;

import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Allows the browser UIs in one managed Kompile project to call the persona
 * that owns an API contract.
 *
 * <p>The allow-list is derived from the same service-endpoints.json topology
 * used by the UI and CLI. It is evaluated for every CORS request so endpoint
 * changes take effect without restarting a component. No Spring property or
 * {@code @Value} binding participates in this routing.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ManagedServiceEndpointsCorsConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(ManagedServiceEndpointsCorsConfiguration.class);
    private static final List<String> METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private final ServiceEndpointsConfigManager configManager;

    public ManagedServiceEndpointsCorsConfiguration() {
        this(ServiceEndpointsConfigManager.shared());
    }

    ManagedServiceEndpointsCorsConfiguration(ServiceEndpointsConfigManager configManager) {
        this.configManager = configManager;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    CorsFilter managedServiceEndpointsCorsFilter() {
        CorsConfigurationSource source = this::configurationFor;
        return new CorsFilter(source);
    }

    private CorsConfiguration configurationFor(HttpServletRequest request) {
        if (request == null || request.getRequestURI() == null
                || !request.getRequestURI().startsWith("/api/")) {
            return null;
        }
        return currentConfiguration();
    }

    CorsConfiguration currentConfiguration() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(new ArrayList<>(managedUiOrigins()));
        cors.setAllowedMethods(METHODS);
        cors.setAllowedHeaders(List.of("*"));
        cors.setExposedHeaders(List.of("Location", "Content-Disposition"));
        // Origins are exact values from the managed topology (never '*'), so path-scoped HttpOnly
        // integration cookies can safely cross persona ports on the same deployment.
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        return cors;
    }

    Set<String> managedUiOrigins() {
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        try {
            ServiceEndpointsConfigManager.ServiceEndpointsConfig endpoints = configManager.current();
            for (KompileService service : KompileService.values()) {
                addOrigin(origins, endpoints.effectiveUrl(service));
            }
            addOrigin(origins, endpoints.effectiveStagingUrl());
        } catch (Exception e) {
            log.warn("Unable to read managed service endpoints for CORS; using built-in origins", e);
            for (KompileService service : KompileService.values()) {
                addOrigin(origins, service.defaultUrl());
            }
            addOrigin(origins, ServiceEndpointsConfigManager.DEFAULT_STAGING_URL);
        }
        return Set.copyOf(origins);
    }

    private static void addOrigin(Set<String> origins, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getScheme() != null && uri.getRawAuthority() != null) {
                origins.add(uri.getScheme() + "://" + uri.getRawAuthority());
            }
        } catch (IllegalArgumentException ignored) {
            // The managed config surface validates URLs. A hand-edited bad
            // entry must not disable CORS for every other valid component.
        }
    }
}
