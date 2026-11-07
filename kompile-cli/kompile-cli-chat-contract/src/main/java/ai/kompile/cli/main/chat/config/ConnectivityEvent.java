/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import java.time.Duration;

/** Connectivity observation for a provider attempt, for status surfaces. */
public record ConnectivityEvent(
        String provider, int attempt, int maxAttempts, Duration delay, String reason) {
}
