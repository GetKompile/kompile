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

package ai.kompile.app.core.extraction;

import ai.kompile.core.retrievers.RetrievedDoc;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Unified interface for content extraction operations.
 *
 * <p>This interface provides a common abstraction for different types of content extraction:</p>
 * <ul>
 *   <li><b>Chunking</b> - Breaking documents into smaller pieces for embedding</li>
 *   <li><b>Entity Extraction</b> - Identifying named entities (people, places, organizations)</li>
 *   <li><b>Relationship Extraction</b> - Finding relationships between entities</li>
 *   <li><b>Table Extraction</b> - Extracting structured tables from documents</li>
 *   <li><b>Concept Extraction</b> - Identifying key concepts and themes</li>
 *   <li><b>Code Block Extraction</b> - Finding and categorizing code snippets</li>
 *   <li><b>Citation Extraction</b> - Extracting references and citations</li>
 * </ul>
 *
 * <p>All extractors can run concurrently in separate worker threads, allowing
 * parallel processing of different extraction types on the same document set.</p>
 */
public interface ContentExtractor {

    /**
     * The type of content this extractor produces.
     */
    enum ExtractorType {
        /** Produces document chunks for embedding */
        CHUNKING,
        /** Extracts named entities */
        ENTITY,
        /** Extracts relationships between entities */
        RELATIONSHIP,
        /** Extracts structured tables */
        TABLE,
        /** Extracts key concepts and themes */
        CONCEPT,
        /** Extracts code blocks */
        CODE,
        /** Extracts citations and references */
        CITATION,
        /** Extracts fact statements */
        FACT,
        /** Extracts summary information */
        SUMMARY,
        /** Custom user-defined extractor */
        CUSTOM
    }

    /**
     * Returns the unique name of this extractor.
     * Used for identification and configuration.
     */
    String getName();

    /**
     * Returns the type of content this extractor produces.
     */
    ExtractorType getType();

    /**
     * Returns the priority of this extractor (higher = runs first).
     * Default is 0. Chunking typically has the highest priority.
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Returns true if this extractor is enabled.
     * Can be disabled via configuration.
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Extracts content from a single document.
     *
     * @param document The document to extract from
     * @param options  Extraction options (extractor-specific)
     * @return The extraction result
     * @throws Exception if extraction fails
     */
    ExtractionResult extract(RetrievedDoc document, Map<String, Object> options) throws Exception;

    /**
     * Extracts content from a single document with progress reporting.
     *
     * @param document         The document to extract from
     * @param options          Extraction options
     * @param progressCallback Callback for progress updates
     * @return The extraction result
     * @throws Exception if extraction fails
     */
    default ExtractionResult extract(
            RetrievedDoc document,
            Map<String, Object> options,
            Consumer<ExtractionProgress> progressCallback) throws Exception {
        return extract(document, options);
    }

    /**
     * Extracts content from multiple documents (batch processing).
     *
     * @param documents The documents to extract from
     * @param options   Extraction options
     * @return List of extraction results (one per document)
     * @throws Exception if extraction fails
     */
    default List<ExtractionResult> extractBatch(
            List<RetrievedDoc> documents,
            Map<String, Object> options) throws Exception {
        return documents.stream()
                .map(doc -> {
                    try {
                        return extract(doc, options);
                    } catch (Exception e) {
                        return ExtractionResult.failed(doc.getId(), e.getMessage());
                    }
                })
                .toList();
    }

    /**
     * Configures the extractor with the given options.
     *
     * @param options Configuration options
     */
    void configure(Map<String, Object> options);

    /**
     * Returns the supported languages for this extractor.
     * Use "*" for universal language support.
     */
    default List<String> getSupportedLanguages() {
        return List.of("*");
    }

    /**
     * Returns true if this extractor can be interrupted.
     */
    default boolean isInterruptible() {
        return true;
    }

    /**
     * Resets the extractor state for a new extraction run.
     */
    default void reset() {
        // Default no-op
    }

    /**
     * Progress information for extraction operations.
     */
    record ExtractionProgress(
            String extractorName,
            ExtractorType extractorType,
            String phase,
            int percentComplete,
            int itemsProcessed,
            int totalItems,
            String message
    ) {
        public static ExtractionProgress starting(String name, ExtractorType type, int total) {
            return new ExtractionProgress(name, type, "starting", 0, 0, total, "Starting extraction");
        }

        public static ExtractionProgress inProgress(String name, ExtractorType type, int processed, int total) {
            int percent = total > 0 ? (processed * 100) / total : 0;
            return new ExtractionProgress(name, type, "processing", percent, processed, total,
                    String.format("Processed %d/%d", processed, total));
        }

        public static ExtractionProgress complete(String name, ExtractorType type, int total) {
            return new ExtractionProgress(name, type, "complete", 100, total, total, "Extraction complete");
        }
    }
}
