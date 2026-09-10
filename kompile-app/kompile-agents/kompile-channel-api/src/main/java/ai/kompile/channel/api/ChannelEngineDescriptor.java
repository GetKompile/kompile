/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

/** Availability and purpose of one inbound channel chat engine. */
public record ChannelEngineDescriptor(
        ChannelChatEngine engine,
        String displayName,
        String description,
        boolean supportsAgent,
        boolean supportsModel,
        boolean available,
        String status) {
}
