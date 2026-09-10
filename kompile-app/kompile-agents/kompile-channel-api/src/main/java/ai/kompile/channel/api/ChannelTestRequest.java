/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

/** Requests an outbound connection test. */
public record ChannelTestRequest(String target, String message) {
}
