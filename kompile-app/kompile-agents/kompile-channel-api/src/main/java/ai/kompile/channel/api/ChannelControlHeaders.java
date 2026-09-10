/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

/** Shared wire constants for the authenticated channel administration surface. */
public final class ChannelControlHeaders {

    public static final String TOKEN_HEADER = "X-Kompile-Channel-Token";
    public static final String REQUEST_HEADER = "X-Kompile-Channel-Request";
    public static final String CSRF_HEADER = "X-Kompile-Channel-CSRF";
    public static final String CHANNEL_SESSION_COOKIE = "KOMPILE_CHANNEL_SESSION";
    public static final String KCLAW_SESSION_COOKIE = "KOMPILE_KCLAW_SESSION";
    public static final String KCLAW_WS_SESSION_COOKIE = "KOMPILE_KCLAW_WS_SESSION";
    public static final String SOURCE_SYNC_SESSION_COOKIE = "KOMPILE_SOURCE_SYNC_SESSION";
    public static final String OAUTH_SESSION_COOKIE = "KOMPILE_OAUTH_SESSION";
    public static final String SOURCE_PROVIDER_SESSION_COOKIE = "KOMPILE_SOURCE_PROVIDER_SESSION";
    public static final String CRAWL_SESSION_COOKIE = "KOMPILE_CRAWL_SESSION";
    public static final String DOCUMENT_SESSION_COOKIE = "KOMPILE_DOCUMENT_SESSION";
    public static final String TOKEN_ENVIRONMENT = "KOMPILE_CHANNEL_ADMIN_TOKEN";
    public static final String TOKEN_FILE_NAME = "channel-admin.token";
    public static final int MINIMUM_TOKEN_BYTES = 32;

    private ChannelControlHeaders() {
    }
}
