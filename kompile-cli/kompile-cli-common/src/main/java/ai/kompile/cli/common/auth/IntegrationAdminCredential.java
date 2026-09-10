/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.auth;

import ai.kompile.channel.api.ChannelControlHeaders;
import ai.kompile.cli.common.KompileHome;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Resolves the shared integration administration credential without exposing it in argv. */
public final class IntegrationAdminCredential {

    private IntegrationAdminCredential() {
    }

    /** Remote endpoints accept only an explicitly supplied environment credential. */
    public static String loadFor(URI endpoint) throws IOException {
        String configured = System.getenv(ChannelControlHeaders.TOKEN_ENVIRONMENT);
        if (configured != null && !configured.isBlank()) return validate(configured.trim());
        if (endpoint == null || !isLoopback(endpoint.getHost())) return null;

        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(KompileHome.resolvedProjectDirectory().toPath()
                .resolve("config").resolve(ChannelControlHeaders.TOKEN_FILE_NAME));
        candidates.add(KompileHome.resolvedHomeDirectory().toPath()
                .resolve("config").resolve(ChannelControlHeaders.TOKEN_FILE_NAME));
        candidates.add(KompileHome.homeDirectory().toPath()
                .resolve("config").resolve(ChannelControlHeaders.TOKEN_FILE_NAME));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return validate(Files.readString(candidate, StandardCharsets.UTF_8).trim());
            }
        }
        return null;
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized) || "0:0:0:0:0:0:0:1".equals(normalized);
    }

    private static String validate(String token) {
        if (token.getBytes(StandardCharsets.UTF_8).length
                < ChannelControlHeaders.MINIMUM_TOKEN_BYTES) {
            throw new IllegalStateException(
                    "Integration administration token must contain at least "
                            + ChannelControlHeaders.MINIMUM_TOKEN_BYTES + " bytes");
        }
        return token;
    }
}
