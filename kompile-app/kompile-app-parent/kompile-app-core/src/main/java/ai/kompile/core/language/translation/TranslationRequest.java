/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.core.language.translation;

import ai.kompile.core.language.LanguageSupport;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable input and semantic options for one natural-language translation task.
 *
 * <p>The request deliberately contains no backend or client object. A caller selects a
 * provider/model and supplies the text-generation callback to {@link TranslationService}.</p>
 */
@JsonDeserialize(builder = TranslationRequest.Builder.class)
public final class TranslationRequest {

    /** Fingerprint schema; changing it intentionally invalidates future checkpoints. */
    public static final String FINGERPRINT_VERSION = "translation-request-v2";
    public static final int DEFAULT_MAX_CHARS_PER_REQUEST = 8_000;
    public static final int DEFAULT_MAX_RESPONSE_CHARS = 16_000;
    public static final int MAX_CONFIGURED_BOUND = 1_000_000;
    public static final int MAX_SOURCE_CHARS = 20_000_000;
    public static final int MAX_OUTPUT_CHARS = 20_000_000;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = MAX_OUTPUT_CHARS;

    @JsonProperty("text")
    private final String text;
    @JsonProperty("sourceId")
    private final String sourceId;
    @JsonProperty("sourceLanguage")
    private final String sourceLanguage;
    @JsonProperty("targetLanguage")
    private final String targetLanguage;
    @JsonProperty("provider")
    private final String provider;
    @JsonProperty("modelId")
    private final String modelId;
    @JsonProperty("maxCharsPerRequest")
    private final int maxCharsPerRequest;
    @JsonProperty("maxResponseChars")
    private final int maxResponseChars;
    @JsonProperty("maxOutputChars")
    private final int maxOutputChars;
    @JsonProperty("preserveTerms")
    private final List<String> preserveTerms;
    @JsonProperty("domainHint")
    private final String domainHint;
    @JsonProperty("customInstructions")
    private final String customInstructions;
    @JsonProperty("failurePolicy")
    private final FailurePolicy failurePolicy;

    /**
     * Creates a validated request.
     *
     * @param text source text; null is treated as empty text and is skipped
     * @param sourceId optional caller-owned source identity
     * @param sourceLanguage explicit locale tag, or null/und/auto for unknown source
     * @param targetLanguage required concrete locale tag
     * @param provider optional exact provider identity
     * @param modelId optional exact model identity
     * @param maxCharsPerRequest positive segmentation bound
     * @param maxResponseChars positive per-segment response bound
     * @param preserveTerms terms to keep in the source language
     * @param domainHint optional domain guidance
     * @param customInstructions optional additional translation guidance
     * @param failurePolicy whether failed segments abort output or retain their originals
     */
    public TranslationRequest(String text, String sourceId, String sourceLanguage,
                              String targetLanguage, String provider, String modelId,
                              int maxCharsPerRequest, int maxResponseChars,
                              List<String> preserveTerms, String domainHint,
                              String customInstructions, FailurePolicy failurePolicy) {
        this(text, sourceId, sourceLanguage, targetLanguage, provider, modelId,
                maxCharsPerRequest, maxResponseChars, preserveTerms, domainHint,
                customInstructions, failurePolicy, DEFAULT_MAX_OUTPUT_CHARS);
    }

    private TranslationRequest(String text, String sourceId, String sourceLanguage,
                               String targetLanguage, String provider, String modelId,
                               int maxCharsPerRequest, int maxResponseChars,
                               List<String> preserveTerms, String domainHint,
                               String customInstructions, FailurePolicy failurePolicy,
                               int maxOutputChars) {
        this.text = text == null ? "" : text;
        if (this.text.length() > MAX_SOURCE_CHARS) {
            throw new IllegalArgumentException("text must not exceed " + MAX_SOURCE_CHARS
                    + " UTF-16 code units");
        }
        this.sourceId = optionalText(sourceId);
        this.sourceLanguage = normalizeSourceLanguage(sourceLanguage);
        this.targetLanguage = normalizeTargetLanguage(targetLanguage);
        this.provider = optionalText(provider);
        this.modelId = optionalText(modelId);
        if (maxCharsPerRequest <= 0 || maxCharsPerRequest > MAX_CONFIGURED_BOUND) {
            throw new IllegalArgumentException("maxCharsPerRequest must be between 1 and "
                    + MAX_CONFIGURED_BOUND);
        }
        if (maxResponseChars <= 0 || maxResponseChars > MAX_CONFIGURED_BOUND) {
            throw new IllegalArgumentException("maxResponseChars must be between 1 and "
                    + MAX_CONFIGURED_BOUND);
        }
        if (maxOutputChars <= 0 || maxOutputChars > MAX_OUTPUT_CHARS) {
            throw new IllegalArgumentException("maxOutputChars must be between 1 and "
                    + MAX_OUTPUT_CHARS);
        }
        this.maxCharsPerRequest = maxCharsPerRequest;
        this.maxResponseChars = maxResponseChars;
        this.maxOutputChars = maxOutputChars;
        this.preserveTerms = normalizeTerms(preserveTerms);
        this.domainHint = optionalText(domainHint);
        this.customInstructions = optionalText(customInstructions);
        this.failurePolicy = failurePolicy == null ? FailurePolicy.FAIL : failurePolicy;
    }

    /** Starts an immutable request builder with safe segmentation defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Starts a builder pre-populated with this request's immutable values. */
    public Builder toBuilder() {
        return new Builder()
                .text(text)
                .sourceId(sourceId)
                .sourceLanguage(sourceLanguage)
                .targetLanguage(targetLanguage)
                .provider(provider)
                .modelId(modelId)
                .maxCharsPerRequest(maxCharsPerRequest)
                .maxResponseChars(maxResponseChars)
                .maxOutputChars(maxOutputChars)
                .preserveTerms(preserveTerms)
                .domainHint(domainHint)
                .customInstructions(customInstructions)
                .failurePolicy(failurePolicy);
    }

    public String text() {
        return text;
    }

    public String sourceId() {
        return sourceId;
    }

    /** Returns a concrete source locale or {@code und} when the source is not known. */
    public String sourceLanguage() {
        return sourceLanguage;
    }

    public String targetLanguage() {
        return targetLanguage;
    }

    public String provider() {
        return provider;
    }

    public String modelId() {
        return modelId;
    }

    public int maxCharsPerRequest() {
        return maxCharsPerRequest;
    }

    public int maxResponseChars() {
        return maxResponseChars;
    }

    public int maxOutputChars() {
        return maxOutputChars;
    }

    public List<String> preserveTerms() {
        return preserveTerms;
    }

    public String domainHint() {
        return domainHint;
    }

    public String customInstructions() {
        return customInstructions;
    }

    public FailurePolicy failurePolicy() {
        return failurePolicy;
    }

    /**
     * Stable SHA-256 identity for source text and every semantic translation option.
     * No generated output or callback identity is included.
     */
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            append(digest, "version", FINGERPRINT_VERSION);
            append(digest, "text", text);
            append(digest, "sourceId", sourceId);
            append(digest, "sourceLanguage", sourceLanguage);
            append(digest, "targetLanguage", targetLanguage);
            append(digest, "provider", provider);
            append(digest, "modelId", modelId);
            append(digest, "maxCharsPerRequest", Integer.toString(maxCharsPerRequest));
            append(digest, "maxResponseChars", Integer.toString(maxResponseChars));
            append(digest, "maxOutputChars", Integer.toString(maxOutputChars));
            append(digest, "failurePolicy", failurePolicy.name());
            append(digest, "preserveTerms.count", Integer.toString(preserveTerms.size()));
            for (int i = 0; i < preserveTerms.size(); i++) {
                append(digest, "preserveTerms." + i, preserveTerms.get(i));
            }
            append(digest, "domainHint", domainHint);
            append(digest, "customInstructions", customInstructions);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required by the Java runtime", e);
        }
    }

    private static String normalizeSourceLanguage(String language) {
        String normalized = LanguageSupport.normalizeLanguageCode(language);
        if (normalized == null || LanguageSupport.UNDETERMINED_LANGUAGE.equals(normalized)
                || "auto".equals(normalized) || LanguageSupport.isUniversal(normalized)) {
            return LanguageSupport.UNDETERMINED_LANGUAGE;
        }
        if (!isConcreteLanguage(normalized)) {
            throw new IllegalArgumentException("sourceLanguage must be a locale tag or und/auto");
        }
        return normalized;
    }

    private static String normalizeTargetLanguage(String language) {
        String normalized = LanguageSupport.normalizeLanguageCode(language);
        if (normalized == null || LanguageSupport.UNDETERMINED_LANGUAGE.equals(normalized)
                || "auto".equals(normalized) || LanguageSupport.isUniversal(normalized)
                || !isConcreteLanguage(normalized)) {
            throw new IllegalArgumentException(
                    "targetLanguage must be a concrete locale tag; und, auto, and wildcards are not valid");
        }
        return normalized;
    }

    private static boolean isConcreteLanguage(String language) {
        return language.matches("[a-z]{2,3}(?:-[a-z0-9]{1,8})*");
    }

    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> normalizeTerms(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(terms.size());
        for (String term : terms) {
            String value = optionalText(term);
            if (value != null && !normalized.contains(value)) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private static void append(MessageDigest digest, String key, String value) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        appendLength(digest, keyBytes.length);
        digest.update(keyBytes);
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        appendLength(digest, valueBytes.length);
        digest.update(valueBytes);
    }

    private static void appendLength(MessageDigest digest, int length) {
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    /** Output behavior after a model segment fails. */
    public enum FailurePolicy {
        /** Fail the whole task and do not expose partially translated output. */
        FAIL,
        /** Retain only failed segment originals; the result remains PARTIAL or FAILED. */
        KEEP_ORIGINAL
    }

    /** Builder for the exact immutable request contract. */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private String text;
        private String sourceId;
        private String sourceLanguage;
        private String targetLanguage;
        private String provider;
        private String modelId;
        private int maxCharsPerRequest = DEFAULT_MAX_CHARS_PER_REQUEST;
        private int maxResponseChars = DEFAULT_MAX_RESPONSE_CHARS;
        private int maxOutputChars = DEFAULT_MAX_OUTPUT_CHARS;
        private List<String> preserveTerms = List.of();
        private String domainHint;
        private String customInstructions;
        private FailurePolicy failurePolicy = FailurePolicy.FAIL;

        private Builder() {
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        public Builder sourceLanguage(String sourceLanguage) {
            this.sourceLanguage = sourceLanguage;
            return this;
        }

        public Builder targetLanguage(String targetLanguage) {
            this.targetLanguage = targetLanguage;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder maxCharsPerRequest(int maxCharsPerRequest) {
            this.maxCharsPerRequest = maxCharsPerRequest;
            return this;
        }

        public Builder maxResponseChars(int maxResponseChars) {
            this.maxResponseChars = maxResponseChars;
            return this;
        }

        public Builder maxOutputChars(int maxOutputChars) {
            this.maxOutputChars = maxOutputChars;
            return this;
        }

        public Builder preserveTerms(List<String> preserveTerms) {
            this.preserveTerms = preserveTerms;
            return this;
        }

        public Builder domainHint(String domainHint) {
            this.domainHint = domainHint;
            return this;
        }

        public Builder customInstructions(String customInstructions) {
            this.customInstructions = customInstructions;
            return this;
        }

        public Builder failurePolicy(FailurePolicy failurePolicy) {
            this.failurePolicy = failurePolicy;
            return this;
        }

        public TranslationRequest build() {
            return new TranslationRequest(text, sourceId, sourceLanguage, targetLanguage,
                    provider, modelId, maxCharsPerRequest, maxResponseChars, preserveTerms,
                    domainHint, customInstructions, failurePolicy, maxOutputChars);
        }
    }
}
