/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.auth;

import java.util.Map;
import java.util.Objects;

/**
 * Provider-scoped credential shared by Kompile's OAuth consumers.
 *
 * <p>The representation intentionally matches Pi's canonical credential shapes:
 * API keys use {@code type/api_key/key}; OAuth credentials use
 * {@code type/oauth/access/refresh/expires}. Persistence and provider protocol
 * details remain outside this value object so file- and database-backed managers
 * use the same validation and expiry semantics.</p>
 */
public final class ManagedCredential {
    public static final String API_KEY = "api_key";
    public static final String OAUTH = "oauth";

    private final String type;
    private final String key;
    private final String access;
    private final String refresh;
    private final long expires;
    private final Map<String, String> metadata;

    private ManagedCredential(
            String type,
            String key,
            String access,
            String refresh,
            long expires,
            Map<String, String> metadata) {
        this.type = type;
        this.key = key;
        this.access = access;
        this.refresh = refresh;
        this.expires = expires;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ManagedCredential apiKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("API key must not be blank");
        }
        return new ManagedCredential(API_KEY, key, null, null, 0L, Map.of());
    }

    public static ManagedCredential oauth(String access, String refresh, long expires) {
        return oauth(access, refresh, expires, Map.of());
    }

    /**
     * OAuth credentials may carry provider-specific string metadata such as a
     * ChatGPT account id, GitHub Enterprise domain, or Radius gateway. A blank
     * refresh token is valid for non-expiring OAuth-minted keys such as OpenRouter.
     */
    public static ManagedCredential oauth(
            String access,
            String refresh,
            long expires,
            Map<String, String> metadata) {
        if (access == null || access.isBlank()) {
            throw new IllegalArgumentException("OAuth access token must not be blank");
        }
        if (refresh == null) {
            throw new IllegalArgumentException("OAuth refresh token must not be null");
        }
        if (expires <= 0L) {
            throw new IllegalArgumentException("OAuth expiry must be a positive epoch-millisecond value");
        }
        return new ManagedCredential(OAUTH, null, access, refresh, expires, metadata);
    }

    public String getType() {
        return type;
    }

    public String getKey() {
        return key;
    }

    public String getAccess() {
        return access;
    }

    public String getRefresh() {
        return refresh;
    }

    public long getExpires() {
        return expires;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public String getMetadata(String name) {
        return metadata.get(name);
    }

    public boolean isApiKey() {
        return API_KEY.equals(type);
    }

    public boolean isOAuth() {
        return OAUTH.equals(type);
    }

    public boolean hasRefreshToken() {
        return isOAuth() && refresh != null && !refresh.isBlank();
    }

    public boolean expiresWithin(long minimumValidityMillis, long nowMillis) {
        long minimum = Math.max(0L, minimumValidityMillis);
        long threshold = minimum >= Long.MAX_VALUE - nowMillis
                ? Long.MAX_VALUE
                : nowMillis + minimum;
        return isOAuth() && threshold >= expires;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ManagedCredential that)) {
            return false;
        }
        return expires == that.expires
                && Objects.equals(type, that.type)
                && Objects.equals(key, that.key)
                && Objects.equals(access, that.access)
                && Objects.equals(refresh, that.refresh)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, key, access, refresh, expires, metadata);
    }

    @Override
    public String toString() {
        return "ManagedCredential{type=" + type + ", secret=<redacted>}";
    }
}
