/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

import java.util.Map;

/** Patches non-secret settings and/or rotates selected secrets on a named connection. */
public record ChannelConnectionUpdate(
        ChannelChatEngine engine,
        String agentId,
        String model,
        Map<String, Object> settings,
        Map<String, String> secrets,
        Boolean enabled) {

    public ChannelConnectionUpdate {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
    }
}
