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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Interface for text chunking strategies.
 * Implementations of this interface will provide different ways to split a RetrievedDoc into smaller chunks.
 * 
 * <p>
 * Text chunking is a critical component in RAG (Retrieval-Augmented Generation) systems,
 * as it determines how large documents are broken down into manageable pieces for
 * vector storage and retrieval.
 * </p>
 * 
 * <p>
 * Example usage:
 * <pre>{@code
 * TextChunker chunker = new RecursiveCharacterTextChunker();
 * RetrievedDoc document = new RetrievedDoc("Long text content...", metadata);
 * Map<String, Object> options = Map.of(
 *     "chunkSize", 500,
 *     "overlap", 50,
 *     "language", "en"
 * );
 * List<RetrievedDoc> chunks = chunker.chunk(document, options);
 * }</pre>
 */
public interface TextChunker {

    /**
     * Chunks the given document into a list of smaller documents.
     * 
     * <p>
     * The chunking process should preserve metadata from the original document
     * and may add additional metadata to track chunk relationships.
     * </p>
     *
     * @param document The document to be chunked. Must contain text content.
     * @param options  A map of options to configure the chunking process. Common options include:
     *                 <ul>
     *                 <li><code>chunkSize</code> (Integer): Maximum size of each chunk in characters</li>
     *                 <li><code>overlap</code> (Integer): Number of characters to overlap between chunks</li>
     *                 <li><code>language</code> (String): Language code for language-specific chunking</li>
     *                 <li><code>separators</code> (List&lt;String&gt;): Custom separators for splitting</li>
     *                 <li><code>preserveParagraphs</code> (Boolean): Whether to avoid splitting paragraphs</li>
     *                 </ul>
     * @return A list of chunked documents. Each chunk maintains the original document's metadata
     *         with additional chunk-specific metadata.
     * @throws IllegalArgumentException if the document is null, doesn't contain text content,
     *                                  or if required options are missing or invalid
     */
    List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options);

    /**
     * Returns the name of the chunking strategy.
     * This can be used to identify and select a specific chunker.
     * 
     * @return The name of the chunker (e.g., "recursive-character", "sentence-based", "paragraph-based").
     */
    String getName();

    /**
     * Returns a list of language codes (e.g., "en", "es", "ja") supported by this chunker.
     * An asterisk (*) can be used to indicate that the chunker is language-agnostic.
     * 
     * @return A list of supported language codes. Never null.
     */
    List<String> getSupportedLanguages();

    /**
     * Returns the default options for this chunker.
     * These options will be used when no options are provided to the chunk method.
     * 
     * @return A map of default options. Never null.
     */
    default Map<String, Object> getDefaultOptions() {
        return Map.of(
            "chunkSize", 1000,
            "overlap", 200,
            "preserveParagraphs", true
        );
    }

    /**
     * Validates that the given document can be chunked by this implementation.
     * 
     * @param document The document to validate
     * @throws IllegalArgumentException if the document cannot be chunked
     */
    default void validateDocument(RetrievedDoc document) {
        if (document == null) {
            throw new IllegalArgumentException("Document cannot be null");
        }
        if (!document.isText()) {
            throw new IllegalArgumentException("Document must contain text content for chunking");
        }
        String text = document.getText();
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Document text content cannot be null or empty");
        }
    }

    /**
     * Validates and merges the provided options with default options.
     *
     * @param options The options to validate and merge
     * @return A merged map of options with defaults applied
     */
    default Map<String, Object> prepareOptions(Map<String, Object> options) {
        Map<String, Object> mergedOptions = new java.util.HashMap<>(getDefaultOptions());
        if (options != null) {
            mergedOptions.putAll(options);
        }
        return mergedOptions;
    }

    /**
     * Chunks the given document with progress reporting.
     *
     * @param document The document to be chunked
     * @param options  Options to configure the chunking process
     * @param progressCallback A callback that receives progress updates during chunking.
     *                         The callback receives a ChunkingProgress object with current status.
     * @return A list of chunked documents
     */
    default List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options,
                                      Consumer<ChunkingProgress> progressCallback) {
        // Default implementation ignores progress callback
        return chunk(document, options);
    }

    /**
     * Progress information during chunking.
     */
    record ChunkingProgress(
            /** Current phase of chunking (e.g., "analyzing", "splitting", "processing") */
            String phase,
            /** Progress percentage (0-100) */
            int progressPercent,
            /** Number of chunks created so far */
            int chunksCreated,
            /** Total characters processed */
            int charsProcessed,
            /** Total characters to process */
            int totalChars,
            /** Descriptive message about current progress */
            String message
    ) {
        public static ChunkingProgress of(String phase, int percent, int chunks, int processed, int total, String message) {
            return new ChunkingProgress(phase, percent, chunks, processed, total, message);
        }
    }
}
