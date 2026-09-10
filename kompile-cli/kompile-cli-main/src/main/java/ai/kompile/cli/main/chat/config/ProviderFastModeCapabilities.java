/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Documented fast-mode eligibility, separate from thinking/effort. Model names
 * and dated upstream sources live in the provider's resource, not in the UI.
 * Eligibility is not an account entitlement check: the provider still owns
 * billing, organization policy, capacity and regional availability.
 */
public record ProviderFastModeCapabilities(
        List<Pattern> modelPatterns, String sourceUrl, String notice) {
    private static final Map<String, ProviderFastModeCapabilities> CACHE = new ConcurrentHashMap<>();

    public ProviderFastModeCapabilities {
        modelPatterns = List.copyOf(modelPatterns);
    }

    public boolean supports(String model) {
        return model != null && modelPatterns.stream()
                .anyMatch(pattern -> pattern.matcher(model.trim()).matches());
    }

    public static ProviderFastModeCapabilities none() {
        return new ProviderFastModeCapabilities(List.of(), "", "");
    }

    public static ProviderFastModeCapabilities forProvider(String providerId) {
        String provider = providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
        // Only transports with implemented fast-mode wire contracts may opt in.
        return switch (provider) {
            case "openai", "openai-codex", "anthropic" -> CACHE.computeIfAbsent(
                    provider, ProviderFastModeCapabilities::load);
            default -> none();
        };
    }

    private static ProviderFastModeCapabilities load(String provider) {
        String path = ProviderThinkingConfig.RESOURCE_ROOT + provider + ".json";
        try (InputStream input = ProviderFastModeCapabilities.class.getResourceAsStream(path)) {
            if (input == null) return none();
            JsonNode root = ai.kompile.cli.common.util.JsonUtils.standardMapper().readTree(input);
            if (root.path("schemaVersion").asInt() != 1
                    || !provider.equals(root.path("provider").asText())) return none();
            JsonNode fast = root.path("fastMode");
            if (!ProviderThinkingConfig.DOCUMENTED_FALLBACK.equals(
                    fast.path("metadataSource").asText())) return none();
            JsonNode source = fast.path("source");
            String url = source.path("url").asText("");
            String notice = source.path("notice").asText("");
            if (!url.startsWith("https://") || notice.isBlank()) return none();
            LocalDate.parse(source.path("verifiedAt").asText(""));
            JsonNode patterns = fast.path("modelPatterns");
            if (!patterns.isArray() || patterns.isEmpty()) return none();
            List<Pattern> compiled = new ArrayList<>();
            for (JsonNode pattern : patterns) {
                if (!pattern.isTextual() || pattern.asText().isBlank()) return none();
                compiled.add(Pattern.compile(pattern.asText(), Pattern.CASE_INSENSITIVE));
            }
            return new ProviderFastModeCapabilities(compiled, url, notice);
        } catch (Exception invalidResource) {
            // Missing or malformed documentation must not enable a paid feature.
            return none();
        }
    }
}
