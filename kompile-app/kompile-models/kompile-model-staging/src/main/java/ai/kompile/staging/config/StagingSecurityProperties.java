/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.staging.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Security settings for the model-staging HTTP surface.
 *
 * <p>The service is loopback-only by default. Binding to any other address is
 * treated as LAN mode even when {@code lan-enabled} was omitted, and startup
 * then fails unless a strong API token and at least one exact browser origin
 * have been configured.</p>
 */
@Component
@ConfigurationProperties(prefix = "kompile.staging.security")
public final class StagingSecurityProperties implements InitializingBean {

    public static final int MINIMUM_TOKEN_BYTES = 32;

    private final String bindAddress;
    private boolean lanEnabled;
    private String apiToken = "";
    private List<String> allowedOrigins = new ArrayList<>();
    private Set<String> normalizedAllowedOrigins = Set.of();

    public StagingSecurityProperties(
            @Value("${server.address:127.0.0.1}") String bindAddress) {
        this.bindAddress = bindAddress == null ? "" : bindAddress.trim();
    }

    @Override
    public void afterPropertiesSet() {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String origin : allowedOrigins) {
            if (origin == null || origin.isBlank()) {
                continue;
            }
            normalized.add(normalizeOrigin(origin));
        }
        normalizedAllowedOrigins = Collections.unmodifiableSet(normalized);

        if (isLanMode()) {
            if (apiToken == null
                    || apiToken.getBytes(StandardCharsets.UTF_8).length < MINIMUM_TOKEN_BYTES) {
                throw new IllegalStateException(
                        "LAN model staging requires KOMPILE_STAGING_API_TOKEN "
                                + "with at least " + MINIMUM_TOKEN_BYTES + " bytes");
            }
            if (normalizedAllowedOrigins.isEmpty()) {
                throw new IllegalStateException(
                        "LAN model staging requires one or more exact "
                                + "KOMPILE_STAGING_ALLOWED_ORIGINS");
            }
        }
    }

    public boolean isLanMode() {
        return lanEnabled || !isLoopbackHost(bindAddress);
    }

    public boolean isLanEnabled() {
        return lanEnabled;
    }

    public void setLanEnabled(boolean lanEnabled) {
        this.lanEnabled = lanEnabled;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken == null ? "" : apiToken;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null
                ? new ArrayList<>()
                : new ArrayList<>(allowedOrigins);
    }

    public Set<String> getNormalizedAllowedOrigins() {
        return normalizedAllowedOrigins;
    }

    public boolean isAllowedOrigin(String origin) {
        try {
            return normalizedAllowedOrigins.contains(normalizeOrigin(origin));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public boolean matchesApiToken(String candidate) {
        byte[] expected = apiToken == null
                ? new byte[0]
                : apiToken.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = candidate == null
                ? new byte[0]
                : candidate.getBytes(StandardCharsets.UTF_8);
        return expected.length >= MINIMUM_TOKEN_BYTES
                && MessageDigest.isEqual(expected, supplied);
    }

    public static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }

    public static String normalizeOrigin(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
            throw new IllegalArgumentException("Origin must be an absolute HTTP(S) origin");
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null
                    ? ""
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalArgumentException("Origin scheme must be http or https");
            }
            if (uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new IllegalArgumentException(
                        "Origin must contain only scheme, host, and optional port");
            }
            int port = uri.getPort();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.indexOf(':') >= 0) {
                host = "[" + host + "]";
            }
            String normalized = scheme + "://" + host;
            if (port >= 0 && !(("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443))) {
                normalized += ":" + port;
            }
            return normalized;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid exact origin", e);
        }
    }
}
