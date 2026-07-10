/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.language;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.source.SourceMetadataConstants;

import java.util.Map;
import java.util.OptionalDouble;

/**
 * Canonical natural-language metadata keys and alias synchronization helpers.
 */
public final class LanguageMetadata {

    private LanguageMetadata() {
    }

    public static final String LANGUAGE = GraphConstants.META_LANGUAGE;
    public static final String LANGUAGE_CONFIDENCE = GraphConstants.META_LANGUAGE_CONFIDENCE;
    public static final String LANGUAGE_SOURCE = GraphConstants.META_LANGUAGE_SOURCE;
    public static final String LANGUAGE_REASON = GraphConstants.META_LANGUAGE_REASON;

    public static final String SOURCE_LANGUAGE_CONFIDENCE = SourceMetadataConstants.LANGUAGE_CONFIDENCE;
    public static final String SOURCE_LANGUAGE_METHOD = SourceMetadataConstants.LANGUAGE_DETECTION_METHOD;

    public static final String DETECTED_LANGUAGE = "detected_language";
    public static final String DETECTED_LANGUAGE_CONFIDENCE = "detected_language_confidence";

    public static final String SOURCE_DETECTED = "detected";
    public static final String SOURCE_HEADER = "header";
    public static final String SOURCE_CONFIG = "config";
    public static final String SOURCE_TRANSLATION = "translation";
    public static final String SOURCE_UNDETERMINED = "undetermined";

    public static String canonicalLanguage(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        String language = stringValue(metadata.get(LANGUAGE));
        if (language == null) {
            language = stringValue(metadata.get(SourceMetadataConstants.LANGUAGE));
        }
        if (language == null) {
            language = stringValue(metadata.get(DETECTED_LANGUAGE));
        }
        return LanguageSupport.normalizeLanguageCode(language);
    }

    public static OptionalDouble languageConfidence(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return OptionalDouble.empty();
        }
        OptionalDouble confidence = numericValue(metadata.get(LANGUAGE_CONFIDENCE));
        if (confidence.isPresent()) {
            return confidence;
        }
        confidence = numericValue(metadata.get(SOURCE_LANGUAGE_CONFIDENCE));
        if (confidence.isPresent()) {
            return confidence;
        }
        return numericValue(metadata.get(DETECTED_LANGUAGE_CONFIDENCE));
    }

    public static void putDetectedLanguage(Map<String, Object> metadata, String language, double confidence) {
        putLanguage(metadata, language, confidence, SOURCE_DETECTED, null);
    }

    public static void putConfiguredLanguage(Map<String, Object> metadata, String language) {
        putLanguage(metadata, language, 1.0, SOURCE_CONFIG, null);
    }

    public static void putTranslatedLanguage(Map<String, Object> metadata, String targetLanguage,
                                             String sourceLanguage) {
        putLanguage(metadata, targetLanguage, 1.0, SOURCE_TRANSLATION,
                sourceLanguage == null ? null : "translated_from=" + sourceLanguage);
    }

    public static void putLanguage(Map<String, Object> metadata, String language, Double confidence,
                                   String source, String reason) {
        if (metadata == null) {
            return;
        }
        String normalized = LanguageSupport.normalizeLanguageCode(language);
        if (normalized == null) {
            normalized = LanguageSupport.UNDETERMINED_LANGUAGE;
        }

        metadata.put(LANGUAGE, normalized);
        metadata.put(SourceMetadataConstants.LANGUAGE, normalized);
        metadata.put(DETECTED_LANGUAGE, normalized);

        if (confidence != null) {
            double bounded = Math.max(0.0, Math.min(1.0, confidence));
            metadata.put(LANGUAGE_CONFIDENCE, bounded);
            metadata.put(SOURCE_LANGUAGE_CONFIDENCE, bounded);
            metadata.put(DETECTED_LANGUAGE_CONFIDENCE, bounded);
        }
        if (source != null && !source.isBlank()) {
            metadata.put(LANGUAGE_SOURCE, source);
            metadata.put(SOURCE_LANGUAGE_METHOD, source);
        }
        if (reason != null && !reason.isBlank()) {
            metadata.put(LANGUAGE_REASON, reason);
        }
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static OptionalDouble numericValue(Object value) {
        if (value instanceof Number number) {
            return OptionalDouble.of(number.doubleValue());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return OptionalDouble.of(Double.parseDouble(s.trim()));
            } catch (NumberFormatException ignored) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }
}
