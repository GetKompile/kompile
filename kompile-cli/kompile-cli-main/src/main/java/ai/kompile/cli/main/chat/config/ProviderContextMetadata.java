/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Vendor-owned JSON pointers, not guesses based on model names or another vendor's catalog. */
final class ProviderContextMetadata {
    private static final String RESOURCE = "ai/kompile/cli/main/chat/context-metadata.json";
    private static final ObjectMapper MAPPER = ai.kompile.cli.common.util.JsonUtils.standardMapper();
    private static final JsonNode DEFAULTS = loadDefaults();

    private ProviderContextMetadata() { }

    /** Zero means the vendor did not publish that limit, never a fabricated output/window ratio. */
    static ModelContextResolver.ModelLimits limits(String provider, JsonNode model) {
        String id = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if ("codex".equals(id)) id = "openai-codex";
        JsonNode descriptor = configuration().path("providers").path(id);
        return new ModelContextResolver.ModelLimits(
                firstPositive(model, descriptor.path("context")),
                firstPositive(model, descriptor.path("output")));
    }

    private static int firstPositive(JsonNode model, JsonNode pointers) {
        if (model == null || !pointers.isArray()) return 0;
        for (JsonNode pointer : pointers) {
            if (!pointer.isTextual() || !pointer.asText().startsWith("/")) continue;
            JsonNode value = model.at(pointer.asText());
            if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0) {
                return value.intValue();
            }
        }
        return 0;
    }

    /** Optional deployment-specific vendor schema; missing providers retain the shipped contract. */
    private static JsonNode configuration() {
        String path = System.getProperty("kompile.chat.contextMetadataConfig");
        if (path != null && !path.isBlank()) {
            try {
                JsonNode override = MAPPER.readTree(Files.readString(Path.of(path)));
                if (override.path("schemaVersion").asInt() == 1 && override.path("providers").isObject()) {
                    var merged = DEFAULTS.deepCopy();
                    override.path("providers").fields().forEachRemaining(entry ->
                            ((com.fasterxml.jackson.databind.node.ObjectNode) merged.path("providers"))
                                    .set(entry.getKey(), entry.getValue()));
                    return merged;
                }
            } catch (Exception ignored) {
                // Malformed optional metadata must not prevent chatting.
            }
        }
        return DEFAULTS;
    }

    private static JsonNode loadDefaults() {
        try (InputStream input = ProviderContextMetadata.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            JsonNode root = input == null ? null : MAPPER.readTree(input);
            if (root != null && root.path("schemaVersion").asInt() == 1 && root.path("providers").isObject()) {
                return root;
            }
        } catch (Exception ignored) { }
        var empty = MAPPER.createObjectNode();
        empty.putObject("providers");
        return empty;
    }
}
