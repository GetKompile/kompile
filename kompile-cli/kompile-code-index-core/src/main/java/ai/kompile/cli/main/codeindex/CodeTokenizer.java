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

package ai.kompile.cli.main.codeindex;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code-aware tokenizer that splits identifiers into meaningful search tokens.
 * Handles camelCase, PascalCase, snake_case, kebab-case, dot notation,
 * and file paths. Produces unique, lowercased tokens with stop-word removal.
 *
 * <p>Inspired by sigmap's zero-dependency tokenizer approach — splits code
 * identifiers into meaningful subwords for better retrieval matching.</p>
 */
public class CodeTokenizer {

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "in", "of", "to", "for", "and", "or", "is", "are",
            "that", "this", "it", "with", "from", "by", "be", "as", "on", "at",
            "do", "not", "use", "get", "set", "up", "if", "no", "so", "we",
            "can", "has", "have", "had", "was", "were", "been", "will", "would",
            "should", "could", "may", "might", "shall", "must", "need",
            "all", "each", "every", "both", "few", "more", "most", "other",
            "some", "such", "than", "too", "very", "just", "but", "about",
            "new", "null", "void", "true", "false", "return", "import",
            "public", "private", "protected", "static", "final", "abstract",
            "class", "interface", "extends", "implements", "override",
            "var", "val", "let", "const", "def", "fn", "func", "function",
            "package", "module", "export", "default", "async", "await",
            "try", "catch", "throw", "throws", "finally",
            "string", "int", "long", "boolean", "double", "float", "byte", "char",
            "object", "any", "self", "super"
    );

    // Pattern to split camelCase: insert space before uppercase letter preceded by lowercase
    private static final Pattern CAMEL_SPLIT = Pattern.compile("([a-z])([A-Z])");
    // Pattern to split PascalCase acronyms: "XMLParser" → "XML Parser"
    private static final Pattern PASCAL_ACRONYM_SPLIT = Pattern.compile("([A-Z]+)([A-Z][a-z])");
    // Pattern to strip file extensions
    private static final Pattern FILE_EXT = Pattern.compile("\\.\\w{1,6}(?=\\s|/|$)");
    // Pattern for non-word non-whitespace characters
    private static final Pattern NON_WORD = Pattern.compile("[^\\w\\s]");
    // Delimiters: underscore, hyphen, dot, slash
    private static final Pattern DELIMITERS = Pattern.compile("[_\\-./\\\\]");

    /**
     * Tokenize text into unique, lowercased code-aware tokens.
     *
     * @param text           the text to tokenize (identifier, query, path, etc.)
     * @param removeStopWords whether to filter out stop words (default: true)
     * @param minLength      minimum token length to include (default: 2)
     * @return list of unique tokens in order of first appearance
     */
    public static List<String> tokenize(String text, boolean removeStopWords, int minLength) {
        if (text == null || text.isEmpty()) return List.of();

        // Step 1: Strip file extensions
        String processed = FILE_EXT.matcher(text).replaceAll(" ");

        // Step 2: Split camelCase and PascalCase
        processed = CAMEL_SPLIT.matcher(processed).replaceAll("$1 $2");
        processed = PASCAL_ACRONYM_SPLIT.matcher(processed).replaceAll("$1 $2");

        // Step 3: Replace delimiters with spaces
        processed = DELIMITERS.matcher(processed).replaceAll(" ");

        // Step 4: Remove non-word characters
        processed = NON_WORD.matcher(processed).replaceAll(" ");

        // Step 5: Lowercase, split on whitespace, filter by length, deduplicate
        String[] parts = processed.toLowerCase().split("\\s+");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String part : parts) {
            if (part.length() >= minLength) {
                if (!removeStopWords || !STOP_WORDS.contains(part)) {
                    unique.add(part);
                }
            }
        }

        return new ArrayList<>(unique);
    }

    /**
     * Tokenize with default settings (remove stop words, min length 2).
     */
    public static List<String> tokenize(String text) {
        return tokenize(text, true, 2);
    }

    /**
     * Tokenize preserving stop words (useful for path matching).
     */
    public static List<String> tokenizeKeepStopWords(String text) {
        return tokenize(text, false, 2);
    }

    /**
     * Extract symbol tokens from a code entity name.
     * Splits compound names and returns the meaningful parts.
     */
    public static List<String> tokenizeSymbol(String symbolName) {
        if (symbolName == null || symbolName.isEmpty()) return List.of();

        // For symbols, don't remove code-specific stop words like "get", "set"
        String processed = CAMEL_SPLIT.matcher(symbolName).replaceAll("$1 $2");
        processed = PASCAL_ACRONYM_SPLIT.matcher(processed).replaceAll("$1 $2");
        processed = DELIMITERS.matcher(processed).replaceAll(" ");
        processed = NON_WORD.matcher(processed).replaceAll(" ");

        String[] parts = processed.toLowerCase().split("\\s+");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String part : parts) {
            if (part.length() >= 2) {
                unique.add(part);
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * Tokenize a file path, extracting meaningful directory and file name tokens.
     */
    public static List<String> tokenizePath(String filePath) {
        if (filePath == null || filePath.isEmpty()) return List.of();

        // Remove extension, split on path separators and delimiters
        String noExt = filePath.replaceAll("\\.[^./\\\\]+$", "");
        return tokenize(noExt, true, 2);
    }

    /**
     * Estimate token count for a string (rough: chars / 4).
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (text.length() + 3) / 4;
    }

    /**
     * Estimate token count for a collection of signature strings.
     */
    public static int estimateTokens(Collection<String> signatures) {
        int totalChars = 0;
        for (String sig : signatures) {
            totalChars += sig.length();
        }
        return (totalChars + 3) / 4;
    }
}
