/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

/** Result of an outbound channel test. */
public record ChannelTestResult(boolean accepted, String message) {
}
