/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

import java.time.Instant;

/** One-time browser login code minted through the authenticated CLI control plane. */
public record ChannelBrowserLoginView(String code, Instant expiresAt) {
}
