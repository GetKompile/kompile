/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipeline.serving.definition;

import ai.kompile.core.language.translation.TranslationRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Non-secret, text-only translation options shared by authoring and host execution. */
public final class TranslationPipelineOptions {
    static final Set<String> PROCESSOR_FIELDS = Set.of(
            "type", "operation", "provider", "modelId", "modelSource", "timeoutMinutes", "translation");
    private static final Set<String> TRANSLATION_FIELDS = Set.of(
            "sourceId", "sourceLanguage", "targetLanguage", "maxCharsPerRequest", "maxResponseChars",
            "maxOutputChars", "preserveTerms", "domainHint", "customInstructions", "failurePolicy");

    private TranslationPipelineOptions() {}

    public static boolean isTranslation(Map<String, Object> processor) {
        return processor != null && "translation".equals(processor.get("operation"));
    }

    /**
     * Validates options without I/O or model resolution. The adapter supplies effective requested
     * provider/model identities; these are not evidence of a model observed in a response.
     * A non-null runtime sourceId overrides the optional configured sourceId.
     */
    public static TranslationRequest toRequest(Map<String, Object> processor, String text,
                                              String sourceId, String provider, String modelId) {
        if (processor == null || !"CHAT_MODEL".equals(processor.get("type")) || !isTranslation(processor)) {
            throw invalid("requires type=CHAT_MODEL and operation=translation");
        }
        fields(processor, PROCESSOR_FIELDS);
        string(processor, "provider", false);
        string(processor, "modelId", false);
        if (processor.containsKey("modelSource") && !"chat".equals(processor.get("modelSource"))) {
            throw invalid("modelSource must be chat");
        }
        int timeout = integer(processor, "timeoutMinutes", 1);
        if (timeout < 1 || timeout > 1_440) throw invalid("timeoutMinutes must be between 1 and 1440");
        if (!(processor.get("translation") instanceof Map<?, ?> options)) {
            throw invalid("translation must be an object");
        }
        fields(options, TRANSLATION_FIELDS);
        String configuredSourceId = string(options, "sourceId", false);
        TranslationRequest.Builder builder = TranslationRequest.builder()
                .text(text)
                .sourceId(sourceId == null ? configuredSourceId : sourceId)
                .sourceLanguage(string(options, "sourceLanguage", false))
                .targetLanguage(string(options, "targetLanguage", true))
                .provider(provider)
                .modelId(modelId)
                .maxCharsPerRequest(integer(options, "maxCharsPerRequest", TranslationRequest.DEFAULT_MAX_CHARS_PER_REQUEST))
                .maxResponseChars(integer(options, "maxResponseChars", TranslationRequest.DEFAULT_MAX_RESPONSE_CHARS))
                .maxOutputChars(integer(options, "maxOutputChars", TranslationRequest.DEFAULT_MAX_OUTPUT_CHARS))
                .domainHint(string(options, "domainHint", false))
                .customInstructions(string(options, "customInstructions", false));
        if (options.containsKey("preserveTerms")) {
            if (!(options.get("preserveTerms") instanceof List<?> values)) throw invalid("preserveTerms must be an array of strings");
            List<String> terms = new ArrayList<>(values.size());
            for (Object value : values) {
                if (!(value instanceof String term) || term.isBlank()) throw invalid("preserveTerms must contain non-empty strings");
                terms.add(term);
            }
            builder.preserveTerms(terms);
        }
        if (options.containsKey("failurePolicy")) {
            String policy = string(options, "failurePolicy", true);
            try {
                builder.failurePolicy(TranslationRequest.FailurePolicy.valueOf(policy));
            } catch (IllegalArgumentException invalidPolicy) {
                throw invalid("failurePolicy must be FAIL or KEEP_ORIGINAL");
            }
        }
        // Locale normalization, segmentation and total bounds remain owned by the core contract.
        return builder.build();
    }

    private static String string(Map<?, ?> options, String key, boolean required) {
        Object value = options.get(key);
        if (value == null && !required) return null;
        if (!(value instanceof String text) || text.isBlank()) throw invalid(key + " must be a non-empty string");
        return text;
    }

    private static int integer(Map<?, ?> options, String key, int defaultValue) {
        if (!options.containsKey(key)) return defaultValue;
        if (!(options.get(key) instanceof Number value)) throw invalid(key + " must be an integer");
        try {
            return new BigDecimal(value.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException notInteger) {
            throw invalid(key + " must be an integer within the supported bounds");
        }
    }

    private static void fields(Map<?, ?> options, Set<String> allowed) {
        for (Object key : options.keySet()) {
            if (!(key instanceof String field) || !allowed.contains(field)) throw invalid("Unsupported field: " + key);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("CHAT_MODEL translation: " + message);
    }
}
