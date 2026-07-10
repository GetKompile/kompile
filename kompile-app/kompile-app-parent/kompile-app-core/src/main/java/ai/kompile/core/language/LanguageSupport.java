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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Shared language-capability contract for pipeline components.
 */
public interface LanguageSupport {

    String ANY_LANGUAGE = "*";
    String UNDETERMINED_LANGUAGE = "und";

    /**
     * ISO-639 language codes supported by this component. {@code *} means language-agnostic
     * or multilingual support.
     */
    default List<String> getSupportedLanguages() {
        return List.of(ANY_LANGUAGE);
    }

    /**
     * Returns whether this component can process the supplied language code.
     */
    default boolean supportsLanguage(String languageCode) {
        return supportsLanguage(getSupportedLanguages(), languageCode);
    }

    static boolean supportsLanguage(Collection<String> supportedLanguages, String languageCode) {
        String normalized = normalizeLanguageCode(languageCode);
        if (normalized == null || UNDETERMINED_LANGUAGE.equals(normalized)) {
            return true;
        }
        if (supportedLanguages == null || supportedLanguages.isEmpty()) {
            return true;
        }
        for (String supported : supportedLanguages) {
            String candidate = normalizeLanguageCode(supported);
            if (candidate == null || isUniversal(candidate)) {
                return true;
            }
            if (candidate.equals(normalized)) {
                return true;
            }
            int dash = normalized.indexOf('-');
            if (dash > 0 && candidate.equals(normalized.substring(0, dash))) {
                return true;
            }
        }
        return false;
    }

    static boolean isUniversal(String languageCode) {
        String normalized = normalizeLanguageCode(languageCode);
        return normalized == null
                || ANY_LANGUAGE.equals(normalized)
                || "multi".equals(normalized)
                || "multilingual".equals(normalized);
    }

    static boolean isUniversal(Collection<String> languageCodes) {
        if (languageCodes == null || languageCodes.isEmpty()) {
            return true;
        }
        for (String languageCode : languageCodes) {
            if (isUniversal(languageCode)) {
                return true;
            }
        }
        return false;
    }

    static String normalizeLanguageCode(String languageCode) {
        if (languageCode == null) {
            return null;
        }
        String normalized = languageCode.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return normalized.isEmpty() ? null : normalized;
    }

    static List<String> normalizeLanguageList(Collection<String> languageCodes) {
        if (languageCodes == null || languageCodes.isEmpty()) {
            return List.of(ANY_LANGUAGE);
        }
        List<String> normalized = new ArrayList<>(languageCodes.size());
        for (String languageCode : languageCodes) {
            String language = normalizeLanguageCode(languageCode);
            if (language != null && !normalized.contains(language)) {
                normalized.add(language);
            }
        }
        return normalized.isEmpty() ? List.of(ANY_LANGUAGE) : List.copyOf(normalized);
    }
}
