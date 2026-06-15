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

package ai.kompile.core.crawl.graph;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * A pluggable pre-processing step that transforms documents before chunking,
 * embedding, and graph extraction. Preprocessors run in a defined order between
 * document loading and text normalization.
 *
 * <p>Each preprocessor declares an {@link #order()} for sequencing, an
 * {@link #appliesTo(Document, PreprocessingConfig)} predicate for per-document
 * filtering, and a {@link #process(List, PreprocessingConfig)} method that
 * transforms document batches in place or returns new documents.</p>
 *
 * <h3>Ordering conventions:</h3>
 * <ul>
 *   <li>0–99: Metadata enrichment (language detection, encoding detection)</li>
 *   <li>100–199: Content normalization (encoding fix, Unicode normalization, script transliteration)</li>
 *   <li>200–299: Content transformation (translation, terminology standardization)</li>
 *   <li>300–399: Content filtering (PII redaction, boilerplate removal)</li>
 *   <li>400–499: Deduplication</li>
 * </ul>
 *
 * <p>Implementations are Spring beans discovered via {@code @Component}.
 * They are only invoked when their corresponding step is enabled in the
 * {@link PreprocessingConfig} attached to the crawl request.</p>
 */
public interface DocumentPreprocessor {

    /**
     * Unique identifier for this preprocessor, used in config and progress tracking.
     * Must match the key used in {@link PreprocessingConfig.StepConfig}.
     */
    String id();

    /**
     * Human-readable display name for UI pipeline step progress.
     */
    String displayName();

    /**
     * Execution order. Lower values run first. See class javadoc for conventions.
     */
    int order();

    /**
     * Whether this preprocessor should process the given document,
     * based on document metadata and the preprocessing configuration.
     * Called per-document before {@link #process} to allow skipping
     * documents that don't need this step.
     *
     * @param document the document to check
     * @param config   the preprocessing configuration for this crawl
     * @return true if this preprocessor should process this document
     */
    boolean appliesTo(Document document, PreprocessingConfig config);

    /**
     * Process a batch of documents. Returns the transformed list, which may
     * contain the same documents (modified in-place), new documents, or fewer
     * documents (if some were filtered/merged).
     *
     * <p>Implementations should check {@link Thread#isInterrupted()} periodically
     * for cancellation support.</p>
     *
     * @param documents the documents to process (only those passing {@link #appliesTo})
     * @param config    the preprocessing configuration for this crawl
     * @return transformed documents
     */
    List<Document> process(List<Document> documents, PreprocessingConfig config);

    /**
     * Whether this preprocessor requires LLM access (translation, PII redaction, etc.).
     * Used for capacity planning and cost estimation.
     */
    default boolean requiresLlm() {
        return false;
    }

    /**
     * Estimated cost multiplier relative to a no-op step (1.0).
     * Translation might be 10.0, encoding normalization might be 1.1.
     * Used for progress estimation and cost warnings.
     */
    default double costMultiplier() {
        return 1.0;
    }
}
