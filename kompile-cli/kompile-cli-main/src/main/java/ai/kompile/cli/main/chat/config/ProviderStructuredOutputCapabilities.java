/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Documented schema-bearing structured-output ({@code response_format} with a
 * {@code json_schema}) support per provider, separate from model-specific
 * behavior. Only transports with an implemented wire contract in
 * {@link DirectLlmClient#applyChatCompletionsJsonOutput} may appear in
 * {@link #forProvider}; the provider resource is the documentation gate.
 *
 * <p>Providers whose documented API offers only {@code json_object} (schema in
 * the prompt, response validated client-side) are deliberately excluded: those
 * callers already have the dispatcher's validated text route. A provider in the
 * switch without a valid resource simply stays disabled.</p>
 *
 * <p>Model-id allowlists are deliberately not used: structured output is a
 * transport-level capability on OpenAI-compatible surfaces, and a static model
 * list would silently deny future models. Model-level support remains UNKNOWN
 * until a live call.</p>
 */
public record ProviderStructuredOutputCapabilities(
        boolean jsonSchema, String sourceUrl, String notice) {
    private static final Map<String, ProviderStructuredOutputCapabilities> CACHE = new ConcurrentHashMap<>();

    public boolean supportsJsonSchema() {
        return jsonSchema;
    }

    public static ProviderStructuredOutputCapabilities none() {
        return new ProviderStructuredOutputCapabilities(false, "", "");
    }

    /** Only chat-completions transports with an implemented json_schema wire contract may opt in.
     *  The Responses protocol (openai-codex) carries schemas via text.format and is protocol-gated
     *  before this descriptor is consulted. */
    public static ProviderStructuredOutputCapabilities forProvider(String providerId) {
        String provider = providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "openai", "zai", "groq", "xai", "ollama", "openrouter" ->
                    CACHE.computeIfAbsent(provider, ProviderStructuredOutputCapabilities::load);
            default -> none();
        };
    }

    private static ProviderStructuredOutputCapabilities load(String provider) {
        String path = ProviderThinkingConfig.RESOURCE_ROOT + provider + ".json";
        try (InputStream input = ProviderStructuredOutputCapabilities.class.getResourceAsStream(path)) {
            if (input == null) return none();
            JsonNode root = ai.kompile.cli.common.util.JsonUtils.standardMapper().readTree(input);
            if (root.path("schemaVersion").asInt() != 1
                    || !provider.equals(root.path("provider").asText())) return none();
            JsonNode structured = root.path("structuredOutput");
            if (!structured.isObject()
                    || !ProviderThinkingConfig.DOCUMENTED_FALLBACK.equals(
                            structured.path("metadataSource").asText())) return none();
            JsonNode source = structured.path("source");
            String url = source.path("url").asText("");
            String notice = source.path("notice").asText("");
            if (!url.startsWith("https://") || notice.isBlank()) return none();
            LocalDate.parse(source.path("verifiedAt").asText(""));
            if (!structured.path("jsonSchema").asBoolean(false)) return none();
            return new ProviderStructuredOutputCapabilities(true, url, notice);
        } catch (Exception invalidResource) {
            // Missing or malformed documentation must not enable the capability.
            return none();
        }
    }
}
