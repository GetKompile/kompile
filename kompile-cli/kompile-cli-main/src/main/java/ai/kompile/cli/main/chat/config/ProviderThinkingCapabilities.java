/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/**
 * Provider-owned capability resolver.
 *
 * <p>Live model metadata is authoritative. A provider's documented classpath
 * resource is consulted only when live discovery returns inventory without
 * thinking metadata.</p>
 */
final class ProviderThinkingCapabilities {
    private ProviderThinkingCapabilities() {
    }

    static ThinkingCapabilityProvider forProvider(String providerId) {
        return ThinkingCapabilityProvider.liveThen(
                ProviderThinkingConfig.forProvider(providerId));
    }
}
