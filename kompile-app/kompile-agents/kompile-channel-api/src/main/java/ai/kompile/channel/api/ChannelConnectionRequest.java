/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

import java.util.Map;

/** Creates one named channel connection. Secret values are accepted only on write APIs. */
public record ChannelConnectionRequest(
        String name,
        String providerId,
        ChannelChatEngine engine,
        String agentId,
        String model,
        Map<String, Object> settings,
        Map<String, String> secrets,
        boolean enabled) {

    public ChannelConnectionRequest {
        engine = engine == null ? ChannelChatEngine.REACT : engine;
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
    }
}
