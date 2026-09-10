/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, 2.0.
 */
package ai.kompile.channel.api;

import java.util.Locale;
import java.util.Map;

/**
 * Runtime credential handoff for one named channel connection, consumed by authenticated source
 * ingestion so a crawl can reuse the secrets the channel runtime already resolves (stored,
 * environment, or OAuth-derived). Served only from the admin-token-gated single-connection
 * endpoint; never appears in listings, views, or provider auth summaries.
 */
public record ChannelCredentialView(
        String providerId,
        Map<String, String> secrets,
        Map<String, Object> properties) {

    public ChannelCredentialView {
        providerId = providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
