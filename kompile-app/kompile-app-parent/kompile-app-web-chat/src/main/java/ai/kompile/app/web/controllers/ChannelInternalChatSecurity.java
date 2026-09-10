/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.channel.api.ChannelControlHeaders;
import ai.kompile.channel.api.ChannelInternalAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Verifies nonce-bound HMAC calls from the admin-owned channel runtime. */
@Component
final class ChannelInternalChatSecurity {

    private static final long CLOCK_SKEW_SECONDS = 60L;

    private final String configuredToken;
    private final Path tokenPath;
    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();

    ChannelInternalChatSecurity(
            @Value("${KOMPILE_CHANNEL_ADMIN_TOKEN:}") String configuredToken,
            @Value("${kompile.data.dir:${user.home}/.kompile}") String dataDir) {
        this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
        this.tokenPath = Path.of(dataDir, "config", ChannelControlHeaders.TOKEN_FILE_NAME);
    }

    void verify(HttpServletRequest request, byte[] body) {
        long now = Instant.now().getEpochSecond();
        long timestamp;
        try {
            timestamp = Long.parseLong(request.getHeader(
                    ChannelInternalAuthentication.TIMESTAMP_HEADER));
        } catch (RuntimeException missingTimestamp) {
            throw unauthorized();
        }
        if (Math.abs(now - timestamp) > CLOCK_SKEW_SECONDS) {
            throw unauthorized();
        }
        String nonce = request.getHeader(ChannelInternalAuthentication.NONCE_HEADER);
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{16,128}")) {
            throw unauthorized();
        }
        usedNonces.entrySet().removeIf(entry -> now - entry.getValue() > CLOCK_SKEW_SECONDS);
        if (usedNonces.putIfAbsent(nonce, now) != null) {
            throw unauthorized();
        }
        String token = token();
        boolean valid = ChannelInternalAuthentication.verify(
                token,
                timestamp,
                nonce,
                body,
                request.getHeader(ChannelInternalAuthentication.SIGNATURE_HEADER));
        if (!valid) {
            usedNonces.remove(nonce);
            throw unauthorized();
        }
    }

    private String token() {
        try {
            String value = configuredToken.isBlank()
                    ? Files.readString(tokenPath, StandardCharsets.UTF_8).trim()
                    : configuredToken;
            if (value.getBytes(StandardCharsets.UTF_8).length
                    < ChannelControlHeaders.MINIMUM_TOKEN_BYTES) {
                throw unauthorized();
            }
            return value;
        } catch (java.io.IOException unavailable) {
            throw unauthorized();
        }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid internal channel chat authentication");
    }
}
