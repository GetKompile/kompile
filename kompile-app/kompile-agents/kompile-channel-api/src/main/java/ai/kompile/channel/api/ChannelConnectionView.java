/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Secret-free view of a persisted channel connection. */
public record ChannelConnectionView(
        UUID id,
        String name,
        String providerId,
        ChannelChatEngine engine,
        String agentId,
        String model,
        boolean enabled,
        RuntimeState runtimeState,
        Map<String, Object> settings,
        Set<String> configuredSecrets,
        Instant createdAt,
        Instant updatedAt,
        String lastError) {

    public ChannelConnectionView {
        engine = engine == null ? ChannelChatEngine.REACT : engine;
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        configuredSecrets = configuredSecrets == null ? Set.of() : Set.copyOf(configuredSecrets);
    }

    public enum RuntimeState {
        DISABLED,
        STARTING,
        RUNNING,
        ERROR
    }
}
