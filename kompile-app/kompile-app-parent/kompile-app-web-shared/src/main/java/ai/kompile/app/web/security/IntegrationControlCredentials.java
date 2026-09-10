/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.security;

import ai.kompile.channel.api.BrowserSessionCredentials;
import ai.kompile.channel.api.ChannelControlHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Reads the shared integration credential used by every split application persona. */
@Component
public final class IntegrationControlCredentials {

    private final String configuredToken;
    private final Path dataDirectory;
    private final Path tokenPath;

    public IntegrationControlCredentials(
            @Value("${KOMPILE_CHANNEL_ADMIN_TOKEN:}") String configuredToken,
            @Value("${kompile.data.dir:${user.home}/.kompile}") String dataDir) {
        this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
        if (!this.configuredToken.isBlank()
                && this.configuredToken.getBytes(StandardCharsets.UTF_8).length
                < ChannelControlHeaders.MINIMUM_TOKEN_BYTES) {
            throw new IllegalStateException(
                    ChannelControlHeaders.TOKEN_ENVIRONMENT + " must contain at least "
                            + ChannelControlHeaders.MINIMUM_TOKEN_BYTES + " bytes");
        }
        this.dataDirectory = Path.of(dataDir);
        this.tokenPath = dataDirectory.resolve("config").resolve(ChannelControlHeaders.TOKEN_FILE_NAME);
    }

    public String credential() {
        if (!configuredToken.isBlank()) return configuredToken;
        try {
            String value = Files.readString(tokenPath, StandardCharsets.UTF_8).trim();
            return value.getBytes(StandardCharsets.UTF_8).length
                    >= ChannelControlHeaders.MINIMUM_TOKEN_BYTES ? value : "";
        } catch (IOException unavailable) {
            return "";
        }
    }

    public boolean matches(String supplied) {
        String expected = credential();
        return !expected.isBlank() && supplied != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    public BrowserSessionCredentials browserSessions() {
        return new BrowserSessionCredentials(credential(), dataDirectory);
    }
}
