/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/**
 * Provider-owned capability resolver.
 *
 * <p>Live model metadata is authoritative. When it carries no thinking metadata,
 * the CLI's on-disk models.dev catalog ({@code reasoning_options} — provider-native,
 * hot-reloaded) is consulted next, and only then the documented classpath fallback
 * resource.</p>
 */
final class ProviderThinkingCapabilities {
    private ProviderThinkingCapabilities() {
    }

    static ThinkingCapabilityProvider forProvider(String providerId) {
        ThinkingCapabilityProvider catalogThenDocumented = model -> {
            ThinkingCapabilityProvider.ThinkingCapabilities fromCatalog =
                    CatalogThinkingCapabilities.forProvider(providerId).resolve(model);
            if (fromCatalog.supported()) {
                return fromCatalog;
            }
            return ProviderThinkingConfig.forProvider(providerId).resolve(model);
        };
        return ThinkingCapabilityProvider.liveThen(catalogThenDocumented);
    }
}
