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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.graphrag.passes;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Derives bounded identity-retrieval probes from aliases explicitly attached to a mention in the
 * current source window.
 *
 * <p>This is deliberately narrower than fuzzy alias generation. Only a parenthetical group that
 * immediately follows the exact mention is considered, so transliterations, abbreviations and
 * stable identifiers can improve graph candidate recall without inventing cross-shard evidence.
 * The returned values remain retrieval controls: candidate-specific type, identifier and graph
 * purity constraints still decide whether reuse is legal.</p>
 */
final class SourceGroundedIdentityVariants {

    static final int MAX_VARIANTS = 8;
    static final int MAX_GROUP_CHARS = 240;
    static final int MAX_VARIANT_CHARS = 96;

    private SourceGroundedIdentityVariants() {
    }

    static List<String> forMention(PassContext context, String mention) {
        if (context == null || mention == null || mention.isBlank()) {
            return List.of();
        }
        String source = context.sourceText();
        if (source == null || source.isBlank()) {
            return List.of();
        }

        String originalKey = identityKey(mention);
        Map<String, String> variants = new LinkedHashMap<>();
        int searchFrom = 0;
        while (searchFrom < source.length() && variants.size() < MAX_VARIANTS) {
            int mentionStart = source.indexOf(mention, searchFrom);
            if (mentionStart < 0) {
                break;
            }
            int cursor = mentionStart + mention.length();
            searchFrom = Math.max(cursor, mentionStart + 1);
            while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= source.length()) {
                continue;
            }
            char opener = source.charAt(cursor);
            char closer = opener == '(' ? ')' : opener == '（' ? '）' : 0;
            if (closer == 0) {
                continue;
            }
            int close = source.indexOf(closer, cursor + 1);
            if (close < 0 || close - cursor - 1 > MAX_GROUP_CHARS) {
                continue;
            }
            String group = source.substring(cursor + 1, close);
            for (String part : group.split("[,，、;/；|]")) {
                String variant = clean(part);
                String key = identityKey(variant);
                if (variant == null || key.isBlank() || key.equals(originalKey)) {
                    continue;
                }
                variants.putIfAbsent(key, variant);
                if (variants.size() >= MAX_VARIANTS) {
                    break;
                }
            }
        }
        return List.copyOf(new ArrayList<>(variants.values()));
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        int start = 0;
        int end = normalized.length();
        while (start < end && isDecorator(normalized.charAt(start))) {
            start++;
        }
        while (end > start && isDecorator(normalized.charAt(end - 1))) {
            end--;
        }
        String cleaned = normalized.substring(start, end).strip();
        if (cleaned.isBlank() || cleaned.length() > MAX_VARIANT_CHARS
                || cleaned.codePoints().noneMatch(Character::isLetterOrDigit)) {
            return null;
        }
        return cleaned;
    }

    private static boolean isDecorator(char value) {
        return Character.isWhitespace(value)
                || value == '\'' || value == '"'
                || value == '‘' || value == '’'
                || value == '“' || value == '”'
                || value == '[' || value == ']'
                || value == '{' || value == '}';
    }

    private static String identityKey(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }
}
