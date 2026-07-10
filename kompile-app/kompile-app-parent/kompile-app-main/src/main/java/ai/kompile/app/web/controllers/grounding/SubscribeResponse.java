/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.grounding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Response for {@code POST /api/kb-grounding/subscribe}.
 *
 * <p>{@code subscriptionId} is the UUID to pass to the SSE stream ({@code /subscribe/{id}/events})
 * or the long-poll endpoint ({@code /subscribe/{id}/poll}).</p>
 * <p>{@code eventsUrl} is the SSE URL for browser/UI consumers.</p>
 * <p>{@code pollUrl} is the long-poll URL for MCP tools.</p>
 * <p>{@code expiresAt} is the server-side TTL (idleness resets it; the field is informational).</p>
 * <p>{@code message} is set only on error responses.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubscribeResponse(
        String subscriptionId,
        String eventsUrl,
        String pollUrl,
        Instant expiresAt,
        String message
) {
    /** Convenience constructor for error responses (keeps subscriptionId + eventsUrl null). */
    public SubscribeResponse(String message) {
        this(null, null, null, null, message);
    }
}
