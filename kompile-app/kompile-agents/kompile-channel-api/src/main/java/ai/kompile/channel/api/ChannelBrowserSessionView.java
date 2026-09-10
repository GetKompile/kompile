/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

import java.time.Instant;

/** Browser-session metadata; the session credential itself is delivered only as HttpOnly cookie. */
public record ChannelBrowserSessionView(String csrfToken, Instant expiresAt) {
}
