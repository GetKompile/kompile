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

package ai.kompile.app.core.chunking;

import ai.kompile.core.retrievers.RetrievedDoc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Utility class for filtering chunks to ensure they contain complete sentences
 * and collecting "garbage" content (numbers, short fragments, non-sentences)
 * into a separate chunk.
 *
 * <p>Garbage is defined as:</p>
 * <ul>
 *   <li>Single numbers (including decimals, negative numbers)</li>
 *   <li>Short fragments (less than 20 characters) without sentence punctuation</li>
 *   <li>Any text that does not end with sentence punctuation (. ! ?)</li>
 * </ul>
 */
public class SentenceFilter {

    /**
     * Minimum length for a fragment to be considered valid content
     * if it doesn't have sentence punctuation.
     */
    public static final int MIN_FRAGMENT_LENGTH = 20;

    /**
     * Pattern to detect sentence-ending punctuation (. ! ?) at the end of text.
     * Allows optional trailing whitespace.
     */
    private static final Pattern SENTENCE_END = Pattern.compile(".*[.!?][\"'\\)\\]]*\\s*$", Pattern.DOTALL);

    /**
     * Pattern to match numbers only (including decimals, negative numbers,
     * numbers with commas as decimal separators).
     */
    private static final Pattern NUMBER_ONLY = Pattern.compile("^\\s*-?\\d+([.,]\\d+)?\\s*$");

    /**
     * Pattern to match page numbers like "Page 1", "p. 42", "- 15 -", etc.
     */
    private static final Pattern PAGE_NUMBER = Pattern.compile(
        "^\\s*(page|p\\.?|pg\\.?)\\s*\\d+\\s*$|^\\s*-\\s*\\d+\\s*-\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Result of filtering chunks.
     *
     * @param validChunks Complete sentences suitable for embedding
     * @param garbageFragments Accumulated garbage content
     */
    public record FilterResult(
        List<String> validChunks,
        List<String> garbageFragments
    ) {
        public boolean hasGarbage() {
            return garbageFragments != null && !garbageFragments.isEmpty();
        }
    }

    /**
     * Filters a list of raw chunks into valid sentences and garbage.
     *
     * @param rawChunks The raw chunks to filter
     * @return FilterResult containing valid chunks and garbage fragments
     */
    public static FilterResult filterChunks(List<String> rawChunks) {
        List<String> validChunks = new ArrayList<>();
        List<String> garbageFragments = new ArrayList<>();

        if (rawChunks == null) {
            return new FilterResult(validChunks, garbageFragments);
        }

        for (String chunk : rawChunks) {
            if (chunk == null) {
                continue;
            }
            String trimmed = chunk.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (isGarbage(trimmed)) {
                garbageFragments.add(trimmed);
            } else {
                validChunks.add(trimmed);
            }
        }

        return new FilterResult(validChunks, garbageFragments);
    }

    /**
     * Filters RetrievedDoc chunks and returns valid chunks plus a garbage chunk if needed.
     *
     * @param chunks The chunks to filter
     * @param originalDoc The original document (for metadata)
     * @param chunkerName The name of the chunker for metadata
     * @param includeGarbageChunk Whether to include the garbage chunk in results
     * @return Filtered list of chunks with garbage chunk at the end if applicable
     */
    public static List<RetrievedDoc> filterAndCollectGarbage(
            List<RetrievedDoc> chunks,
            RetrievedDoc originalDoc,
            String chunkerName,
            boolean includeGarbageChunk) {

        List<RetrievedDoc> validChunks = new ArrayList<>();
        List<String> garbageFragments = new ArrayList<>();

        for (RetrievedDoc chunk : chunks) {
            String text = chunk.getText();
            if (text == null || text.trim().isEmpty()) {
                continue;
            }

            if (isGarbage(text.trim())) {
                garbageFragments.add(text.trim());
            } else {
                validChunks.add(chunk);
            }
        }

        // Re-index valid chunks
        List<RetrievedDoc> result = new ArrayList<>();
        int totalChunks = validChunks.size() + (includeGarbageChunk && !garbageFragments.isEmpty() ? 1 : 0);

        for (int i = 0; i < validChunks.size(); i++) {
            RetrievedDoc chunk = validChunks.get(i);
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("chunk.index", i);
            metadata.put("chunk.total", totalChunks);
            metadata.put("chunk.isGarbage", false);
            metadata.put("chunk.contentType", "sentence");

            result.add(RetrievedDoc.builder()
                .id(originalDoc.getId() + "-chunk-" + i)
                .text(chunk.getText())
                .metadata(metadata)
                .score(originalDoc.getScore())
                .build());
        }

        // Add garbage chunk at the end if enabled and there's garbage
        if (includeGarbageChunk && !garbageFragments.isEmpty()) {
            result.add(createGarbageChunk(originalDoc, garbageFragments, chunkerName, result.size(), totalChunks));
        }

        return result;
    }

    /**
     * Determines if a piece of text is "garbage" (not a complete sentence).
     *
     * <p>Garbage includes:</p>
     * <ul>
     *   <li>Empty or whitespace-only text</li>
     *   <li>Numbers only (e.g., "42", "3.14", "-100")</li>
     *   <li>Page numbers (e.g., "Page 1", "p. 42", "- 15 -")</li>
     *   <li>Short fragments (less than MIN_FRAGMENT_LENGTH) without sentence punctuation</li>
     *   <li>Text that doesn't end with sentence punctuation (. ! ?)</li>
     * </ul>
     *
     * @param text The text to check
     * @return true if the text is garbage, false if it's a valid sentence
     */
    public static boolean isGarbage(String text) {
        if (text == null) {
            return true;
        }

        String trimmed = text.trim();

        // Empty or whitespace only
        if (trimmed.isEmpty()) {
            return true;
        }

        // Number only (including decimals)
        if (NUMBER_ONLY.matcher(trimmed).matches()) {
            return true;
        }

        // Page number patterns
        if (PAGE_NUMBER.matcher(trimmed).matches()) {
            return true;
        }

        // Short fragment without sentence punctuation
        if (trimmed.length() < MIN_FRAGMENT_LENGTH) {
            return !SENTENCE_END.matcher(trimmed).matches();
        }

        // Does not end with sentence punctuation
        if (!SENTENCE_END.matcher(trimmed).matches()) {
            return true;
        }

        return false;
    }

    /**
     * Determines if text is a complete sentence.
     *
     * @param text The text to check
     * @return true if the text ends with sentence punctuation
     */
    public static boolean isCompleteSentence(String text) {
        return !isGarbage(text);
    }

    /**
     * Creates a garbage chunk with proper metadata.
     *
     * @param originalDoc The original document
     * @param garbageFragments The collected garbage fragments
     * @param chunkerName The name of the chunker
     * @param index The chunk index
     * @param totalChunks Total number of chunks
     * @return A RetrievedDoc containing the garbage content
     */
    public static RetrievedDoc createGarbageChunk(
            RetrievedDoc originalDoc,
            List<String> garbageFragments,
            String chunkerName,
            int index,
            int totalChunks) {

        String garbageText = String.join("\n", garbageFragments);

        Map<String, Object> metadata = new HashMap<>(originalDoc.getMetadata());
        metadata.put("chunk.strategy", chunkerName);
        metadata.put("chunk.index", index);
        metadata.put("chunk.total", totalChunks);
        metadata.put("chunk.originalId", originalDoc.getId());
        metadata.put("chunk.size", garbageText.length());
        metadata.put("chunk.isGarbage", true);
        metadata.put("chunk.contentType", "garbage");
        metadata.put("chunk.fragmentCount", garbageFragments.size());

        return RetrievedDoc.builder()
            .id(originalDoc.getId() + "-garbage")
            .text(garbageText)
            .metadata(metadata)
            .score(originalDoc.getScore())
            .build();
    }

    /**
     * Utility method to get the number of sentences in a text.
     *
     * @param text The text to analyze
     * @return Approximate number of sentences
     */
    public static int countSentences(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        // Simple count of sentence-ending punctuation
        int count = 0;
        for (char c : text.toCharArray()) {
            if (c == '.' || c == '!' || c == '?') {
                count++;
            }
        }
        return Math.max(count, 1); // At least 1 if there's text
    }
}
