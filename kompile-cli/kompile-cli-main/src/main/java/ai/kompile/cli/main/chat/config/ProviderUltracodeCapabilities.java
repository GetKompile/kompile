/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Documented ultracode support, separate from thinking/effort. Ultracode is a
 * Claude Code setting rather than a model effort level: it is requested with
 * the documented {@link #effort()} wire value, runs at {@link #requiresEffort()}
 * and additionally lets Claude plan dynamic workflows. A model qualifies when
 * its live effort options include {@link #requiresEffort()}; Claude Code still
 * owns workflow availability and organization effort caps.
 */
public record ProviderUltracodeCapabilities(
        String effort, String requiresEffort, String sourceUrl, String notice) {
    private static final Map<String, ProviderUltracodeCapabilities> CACHE = new ConcurrentHashMap<>();

    public boolean declared() {
        return !effort.isBlank();
    }

    /** True when the model's effort options (live discovery first) carry the required level. */
    public boolean supportsEffortOptions(List<String> effortOptions) {
        return declared() && effortOptions != null && effortOptions.stream()
                .anyMatch(option -> requiresEffort.equalsIgnoreCase(option));
    }

    public static ProviderUltracodeCapabilities none() {
        return new ProviderUltracodeCapabilities("", "", "", "");
    }

    public static ProviderUltracodeCapabilities forProvider(String providerId) {
        String provider = providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
        // Only the Claude Code transport has an implemented ultracode wire contract.
        return switch (provider) {
            case "anthropic" -> CACHE.computeIfAbsent(provider, ProviderUltracodeCapabilities::load);
            default -> none();
        };
    }

    private static ProviderUltracodeCapabilities load(String provider) {
        String path = ProviderThinkingConfig.RESOURCE_ROOT + provider + ".json";
        try (InputStream input = ProviderUltracodeCapabilities.class.getResourceAsStream(path)) {
            if (input == null) return none();
            JsonNode root = JsonUtils.standardMapper().readTree(input);
            if (root.path("schemaVersion").asInt() != 1
                    || !provider.equals(root.path("provider").asText())) return none();
            JsonNode ultracode = root.path("ultracode");
            if (!ultracode.isObject()
                    || !ProviderThinkingConfig.DOCUMENTED_FALLBACK.equals(
                            ultracode.path("metadataSource").asText())) return none();
            JsonNode source = ultracode.path("source");
            String url = source.path("url").asText("");
            String notice = source.path("notice").asText("");
            if (!url.startsWith("https://") || notice.isBlank()) return none();
            LocalDate.parse(source.path("verifiedAt").asText(""));
            String effort = ultracode.path("effort").asText("").trim();
            String requiresEffort = ultracode.path("requiresEffort").asText("").trim();
            if (effort.isBlank() || requiresEffort.isBlank()) return none();
            return new ProviderUltracodeCapabilities(effort, requiresEffort, url, notice);
        } catch (Exception invalidResource) {
            // Missing or malformed documentation must not enable a token-heavy mode.
            return none();
        }
    }
}
