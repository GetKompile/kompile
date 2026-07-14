/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.graph;

import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;

/**
 * Resolves the backend for HTTP contracts already owned by {@code kompile-graph-service}.
 *
 * <p>The graph service is authoritative for these contracts. A caller can select an instance with
 * {@code --graph-url}, {@code -Dkompile.graph.service.url},
 * {@code KOMPILE_GRAPH_SERVICE_URL}, or by starting the registered component with
 * {@code kompile manage start kompile-graph-service}. Otherwise the standalone service's default
 * URL is used. Routing never falls back to an app-main graph store.</p>
 */
public final class GraphServiceRouting {

    public static final String COMPONENT_ID = "kompile-graph-service";
    public static final String DEFAULT_URL = "http://localhost:8095";
    public static final String URL_PROPERTY = "kompile.graph.service.url";
    public static final String URL_ENVIRONMENT = "KOMPILE_GRAPH_SERVICE_URL";

    private GraphServiceRouting() {
    }

    /** Resolve the authoritative graph-service route without probing or changing backend. */
    public static Resolution resolve(String explicitGraphUrl) {
        return resolve(
                explicitGraphUrl,
                System.getProperty(URL_PROPERTY),
                System.getenv(URL_ENVIRONMENT),
                discoverManagedService());
    }

    static Resolution resolve(
            String explicitGraphUrl,
            String propertyGraphUrl,
            String environmentGraphUrl,
            String discoveredGraphUrl) {
        String normalized = normalize(explicitGraphUrl);
        if (normalized != null) {
            return new Resolution(normalized, Source.EXPLICIT);
        }
        normalized = normalize(propertyGraphUrl);
        if (normalized != null) {
            return new Resolution(normalized, Source.SYSTEM_PROPERTY);
        }
        normalized = normalize(environmentGraphUrl);
        if (normalized != null) {
            return new Resolution(normalized, Source.ENVIRONMENT);
        }
        normalized = normalize(discoveredGraphUrl);
        if (normalized != null) {
            return new Resolution(normalized, Source.MANAGED_INSTANCE);
        }
        return new Resolution(DEFAULT_URL, Source.DEFAULT);
    }

    private static String discoverManagedService() {
        try {
            InstanceInfo instance = InstanceRegistry.findByType(COMPONENT_ID);
            return instance != null ? instance.getUrl() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalize(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return url.trim().replaceAll("/+$", "");
    }

    public enum Source {
        EXPLICIT,
        SYSTEM_PROPERTY,
        ENVIRONMENT,
        MANAGED_INSTANCE,
        DEFAULT
    }

    public record Resolution(String baseUrl, Source source) {
    }
}
