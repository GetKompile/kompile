/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.integration;

import ai.kompile.channel.api.ChannelChatEngine;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Internal persistence model. Secret values are always encrypted before reaching this type. */
@RegisterReflectionForBinding
public record StoredChannelConnection(
        UUID id,
        String name,
        String providerId,
        ChannelChatEngine engine,
        String agentId,
        String model,
        boolean enabled,
        Map<String, Object> settings,
        Map<String, String> encryptedSecrets,
        Instant createdAt,
        Instant updatedAt) {

    public StoredChannelConnection {
        engine = engine == null ? ChannelChatEngine.REACT : engine;
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        encryptedSecrets = encryptedSecrets == null ? Map.of() : Map.copyOf(encryptedSecrets);
    }
}
