/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Loads provider-owned, documented thinking fallbacks from classpath resources.
 *
 * <p>Live provider metadata is resolved before this configuration is consulted.
 * Every resource must identify itself as {@code DOCUMENTED_FALLBACK}, name the
 * classpath resource, and point to an upstream authoritative source. Invalid or
 * missing resources fail closed and therefore never invent a thinking selector.</p>
 */
final class ProviderThinkingConfig {
    static final String RESOURCE_ROOT = "/ai/kompile/cli/main/chat/providers/";
    static final String DOCUMENTED_FALLBACK = "DOCUMENTED_FALLBACK";

    private static final ObjectMapper MAPPER =
            ai.kompile.cli.common.util.JsonUtils.standardMapper();
    private static final Map<String, Optional<Config>> CACHE = new ConcurrentHashMap<>();

    private ProviderThinkingConfig() {
    }

    static ThinkingCapabilityProvider forProvider(String providerId) {
        String normalized = normalizeProvider(providerId);
        return model -> load(normalized)
                .map(config -> config.resolve(model))
                .orElseGet(ThinkingCapabilityProvider.ThinkingCapabilities::none);
    }

    static Optional<Config> load(String providerId) {
        String normalized = normalizeProvider(providerId);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return CACHE.computeIfAbsent(normalized, ProviderThinkingConfig::read);
    }

    static void clearCacheForTests() {
        CACHE.clear();
    }

    private static Optional<Config> read(String providerId) {
        if (!providerId.matches("[a-z0-9][a-z0-9-]*")) {
            return Optional.empty();
        }
        String resourcePath = RESOURCE_ROOT + providerId + ".json";
        try (InputStream input = ProviderThinkingConfig.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                return Optional.empty();
            }
            JsonNode root = MAPPER.readTree(input);
            return Optional.of(parse(root, providerId, resourcePath));
        } catch (IOException | IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private static Config parse(JsonNode root, String expectedProvider, String resourcePath) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Provider thinking resource must be a JSON object");
        }
        int schemaVersion = root.path("schemaVersion").asInt(-1);
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported provider thinking schema");
        }
        String provider = text(root, "provider");
        if (!expectedProvider.equals(normalizeProvider(provider))) {
            throw new IllegalArgumentException("Provider id does not match resource name");
        }
        String metadataSource = text(root, "metadataSource");
        if (!DOCUMENTED_FALLBACK.equals(metadataSource)) {
            throw new IllegalArgumentException(
                    "Provider resource must clearly declare DOCUMENTED_FALLBACK");
        }

        Source source = parseSource(root.path("source"), null);
        JsonNode profilesNode = root.path("profiles");
        if (!profilesNode.isArray()) {
            throw new IllegalArgumentException("profiles must be an array");
        }
        List<Profile> profiles = new ArrayList<>();
        for (JsonNode profileNode : profilesNode) {
            profiles.add(parseProfile(profileNode, source));
        }
        return new Config(
                provider,
                metadataSource,
                "classpath:" + resourcePath,
                source,
                List.copyOf(profiles));
    }

    private static Profile parseProfile(JsonNode node, Source inheritedSource) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Profile must be an object");
        }
        JsonNode patternsNode = node.path("modelPatterns");
        if (!patternsNode.isArray() || patternsNode.isEmpty()) {
            throw new IllegalArgumentException("Profile requires modelPatterns");
        }
        List<Pattern> patterns = new ArrayList<>();
        for (JsonNode patternNode : patternsNode) {
            String expression = patternNode.asText("").trim();
            if (expression.isBlank()) {
                throw new IllegalArgumentException("Model pattern cannot be blank");
            }
            try {
                patterns.add(Pattern.compile(expression, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException error) {
                throw new IllegalArgumentException("Invalid model pattern: " + expression, error);
            }
        }

        JsonNode optionsNode = node.path("options");
        if (!optionsNode.isArray() || optionsNode.isEmpty()) {
            throw new IllegalArgumentException("Profile requires thinking options");
        }
        List<ThinkingCapabilityProvider.Option> options = new ArrayList<>();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode optionNode : optionsNode) {
            String value = text(optionNode, "value");
            if (value.isBlank() || !values.add(value)) {
                throw new IllegalArgumentException("Thinking option values must be non-blank and unique");
            }
            options.add(new ThinkingCapabilityProvider.Option(
                    value,
                    textOr(optionNode, "label", value),
                    textOr(optionNode, "description", "")));
        }

        String defaultValue = textOr(node, "defaultValue", "");
        if (!defaultValue.isBlank() && !values.contains(defaultValue)) {
            throw new IllegalArgumentException("defaultValue must name a configured option");
        }
        Source source = node.has("source")
                ? parseSource(node.path("source"), inheritedSource)
                : inheritedSource;
        return new Profile(
                List.copyOf(patterns),
                List.copyOf(options),
                defaultValue,
                node.path("mandatory").asBoolean(false),
                source);
    }

    private static Source parseSource(JsonNode node, Source inherited) {
        String label = textOr(node, "label", inherited == null ? "" : inherited.label());
        String url = textOr(node, "url", inherited == null ? "" : inherited.url());
        String verifiedAt = textOr(
                node, "verifiedAt", inherited == null ? "" : inherited.verifiedAt());
        String notice = textOr(node, "notice", inherited == null ? "" : inherited.notice());
        if (label.isBlank() || url.isBlank() || verifiedAt.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider source requires label, url, and verifiedAt");
        }
        if (!notice.toUpperCase(Locale.ROOT).contains(DOCUMENTED_FALLBACK)) {
            throw new IllegalArgumentException(
                    "Provider source notice must clearly say DOCUMENTED_FALLBACK");
        }
        try {
            LocalDate.parse(verifiedAt);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("verifiedAt must be ISO-8601", error);
        }
        return new Source(label, url, verifiedAt, notice);
    }

    private static String text(JsonNode node, String field) {
        return textOr(node, field, "");
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback == null ? "" : fallback;
        }
        String value = node.path(field).asText(null);
        return value == null ? (fallback == null ? "" : fallback) : value.trim();
    }

    private static String normalizeProvider(String providerId) {
        return providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
    }

    record Config(
            String provider,
            String metadataSource,
            String resourcePath,
            Source source,
            List<Profile> profiles) {
        Config {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
        }

        ThinkingCapabilityProvider.ThinkingCapabilities resolve(
                LiveModelDiscovery.Model model) {
            if (model == null || model.id().isBlank()) {
                return ThinkingCapabilityProvider.ThinkingCapabilities.none();
            }
            return profiles.stream()
                    .filter(profile -> profile.matches(model.id()))
                    .findFirst()
                    .map(profile -> new ThinkingCapabilityProvider.ThinkingCapabilities(
                            profile.options(),
                            profile.defaultValue(),
                            ThinkingCapabilityProvider.Source.DOCUMENTED_FALLBACK,
                            resourcePath,
                            profile.source().url(),
                            profile.source().label(),
                            profile.source().verifiedAt(),
                            profile.mandatory()))
                    .orElseGet(ThinkingCapabilityProvider.ThinkingCapabilities::none);
        }
    }

    record Source(String label, String url, String verifiedAt, String notice) {
    }

    record Profile(
            List<Pattern> modelPatterns,
            List<ThinkingCapabilityProvider.Option> options,
            String defaultValue,
            boolean mandatory,
            Source source) {
        Profile {
            modelPatterns = modelPatterns == null ? List.of() : List.copyOf(modelPatterns);
            options = options == null ? List.of() : List.copyOf(options);
            defaultValue = defaultValue == null ? "" : defaultValue;
        }

        boolean matches(String modelId) {
            return modelId != null
                    && modelPatterns.stream()
                    .anyMatch(pattern -> pattern.matcher(modelId.trim()).matches());
        }
    }
}
