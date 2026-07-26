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

package ai.kompile.core.graphrag.partition.staging;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The small string decisions staging makes, in one place so they cannot drift apart.
 *
 * <p>Extractor output is untidy — blank strings, padded names, null lists — and every staged type
 * has to normalise it the same way, because the normalisation is what decides whether two chunks
 * are talking about the same thing.</p>
 */
final class StagedText {

    private StagedText() {
    }

    /** Trimmed, or null when the value carries nothing. */
    static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** First value that carries something, trimmed; the last argument is the fallback. */
    static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String trimmed = trimmedOrNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    /** Trimmed, non-blank entries in their original order, duplicates kept out. */
    static List<String> cleaned(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(StagedText::trimmedOrNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * The form two mentions of the same thing have to agree on: trimmed, lower-cased, with runs of
     * whitespace collapsed.
     *
     * <p>Case and spacing are the differences that carry no meaning between "Acme Corp" and
     * "acme  corp"; anything beyond that is entity resolution's job, not staging's, and staging
     * deliberately stops here rather than guessing.</p>
     */
    static String normalisedKeyPart(String value) {
        String trimmed = trimmedOrNull(value);
        return trimmed == null ? "" : trimmed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
