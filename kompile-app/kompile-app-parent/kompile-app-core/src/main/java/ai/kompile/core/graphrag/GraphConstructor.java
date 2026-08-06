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

package ai.kompile.core.graphrag;

import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.core.graphrag.model.Graph;
import java.io.IOException;
import java.util.List;

/**
 * Defines the contract for constructing a knowledge graph from textual data.
 * This component is responsible for the 'G' in RAG, turning unstructured documents into a structured graph.
 */
public interface GraphConstructor {

    /**
     * Configuration for entity extraction model.
     */
    record ExtractionModelConfig(
            String provider,      // e.g., "default", "openai", "anthropic", "ollama"
            String modelName,     // e.g., "gpt-4o", "claude-3-5-sonnet", null for provider default
            Double temperature,   // 0.0 to 2.0
            Integer maxTokens,    // max response tokens
            String customPrompt   // optional custom extraction prompt
    ) {
        public static ExtractionModelConfig defaults() {
            return new ExtractionModelConfig("default", null, 0.0, 4096, null);
        }
    }

    /**
     * Bounded task state for extracting one scheduled shard.
     *
     * <p>This is context, not evidence. It tells a small model why this exact chunk was selected,
     * which identities already exist, and which corpus/graph revision its answer belongs to. The
     * source text remains the only authority for newly emitted claims.</p>
     */
    record ConceptHint(
            String term,
            String category,
            String provenance,
            String observedContext) {

        public ConceptHint {
            term = term == null ? null : term.strip();
            category = category == null ? null : category.strip();
            provenance = provenance == null ? null : provenance.strip();
            observedContext = observedContext == null ? null : observedContext.strip();
        }
    }

    /**
     * One engine-owned source boundary inside a chunk. Offsets are zero-based, end-exclusive and
     * relative to the exact chunk text supplied to extraction. The model never supplies these.
     */
    record SourceSpan(int start, int end, String kind) {

        public SourceSpan {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("invalid source span: " + start + ".." + end);
            }
            kind = kind == null || kind.isBlank() ? "SOURCE_EVENT" : kind.strip();
        }
    }

    record ExtractionTaskContext(
            String taskId,
            String partitionId,
            String corpusSnapshotId,
            List<String> subjects,
            String chunkId,
            String discoveryChannel,
            String evidenceReason,
            Double evidenceConfidence,
            String graphRevision,
            String graphContext,
            List<ConceptHint> conceptHints,
            List<SourceSpan> sourceSpans) {

        public ExtractionTaskContext {
            subjects = subjects == null ? List.of() : List.copyOf(subjects);
            conceptHints = conceptHints == null ? List.of() : List.copyOf(conceptHints);
            sourceSpans = sourceSpans == null ? List.of() : sourceSpans.stream()
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        /** Source-compatible constructor for task producers predating source span plans. */
        public ExtractionTaskContext(
                String taskId,
                String partitionId,
                String corpusSnapshotId,
                List<String> subjects,
                String chunkId,
                String discoveryChannel,
                String evidenceReason,
                Double evidenceConfidence,
                String graphRevision,
                String graphContext,
                List<ConceptHint> conceptHints) {
            this(taskId, partitionId, corpusSnapshotId, subjects, chunkId, discoveryChannel,
                    evidenceReason, evidenceConfidence, graphRevision, graphContext, conceptHints,
                    List.of());
        }

        /** Source-compatible constructor for task producers that do not yet supply concept hints. */
        public ExtractionTaskContext(
                String taskId,
                String partitionId,
                String corpusSnapshotId,
                List<String> subjects,
                String chunkId,
                String discoveryChannel,
                String evidenceReason,
                Double evidenceConfidence,
                String graphRevision,
                String graphContext) {
            this(taskId, partitionId, corpusSnapshotId, subjects, chunkId, discoveryChannel,
                    evidenceReason, evidenceConfidence, graphRevision, graphContext, List.of(),
                    List.of());
        }

        public ExtractionTaskContext withGraphState(String revision, String context) {
            return new ExtractionTaskContext(taskId, partitionId, corpusSnapshotId, subjects,
                    chunkId, discoveryChannel, evidenceReason, evidenceConfidence, revision,
                    context, conceptHints, sourceSpans);
        }

        public ExtractionTaskContext withConceptHints(List<ConceptHint> hints) {
            return new ExtractionTaskContext(taskId, partitionId, corpusSnapshotId, subjects,
                    chunkId, discoveryChannel, evidenceReason, evidenceConfidence, graphRevision,
                    graphContext, hints, sourceSpans);
        }

        public ExtractionTaskContext withSourceSpans(List<SourceSpan> spans) {
            return new ExtractionTaskContext(taskId, partitionId, corpusSnapshotId, subjects,
                    chunkId, discoveryChannel, evidenceReason, evidenceConfidence, graphRevision,
                    graphContext, conceptHints, spans);
        }

        /** Rendering placed before the source text in a constructor prompt. */
        public String promptBlock() {
            StringBuilder prompt = new StringBuilder("\nTASK CONTEXT (routing and identity hints; NOT source evidence):\n");
            append(prompt, "task", taskId);
            append(prompt, "partition", partitionId);
            append(prompt, "corpusSnapshot", corpusSnapshotId);
            if (!subjects.isEmpty()) {
                append(prompt, "subjects", String.join(" | ", subjects));
            }
            append(prompt, "chunk", chunkId);
            append(prompt, "discoveryChannel", discoveryChannel);
            append(prompt, "evidenceReason", evidenceReason);
            if (evidenceConfidence != null) {
                append(prompt, "evidenceConfidence", String.valueOf(evidenceConfidence));
            }
            append(prompt, "graphRevision", graphRevision);
            if (graphContext != null && !graphContext.isBlank()) {
                prompt.append("EXISTING GRAPH CONTEXT (reuse identities when appropriate):\n")
                        .append(graphContext.strip()).append('\n');
            }
            if (!conceptHints.isEmpty()) {
                prompt.append("CONCEPT COVERAGE HINTS (routing aids, NOT asserted facts):\n");
                for (ConceptHint hint : conceptHints.stream().limit(16).toList()) {
                    if (hint == null || hint.term() == null || hint.term().isBlank()) {
                        continue;
                    }
                    prompt.append("- term=").append(hint.term());
                    appendInline(prompt, "category", hint.category());
                    appendInline(prompt, "provenance", hint.provenance());
                    appendInline(prompt, "observedContext", hint.observedContext());
                    prompt.append('\n');
                }
                prompt.append("Use each hint only to check whether SOURCE TEXT contains an explicit assertion involving it. A hint is never evidence by itself.\n");
            }
            prompt.append("AUTHORITY RULE: Emit a new entity, relationship, date, process step, or claim only when the SOURCE TEXT below supports it. Existing graph context may guide identity reuse but is never proof of a new claim.\n");
            return prompt.toString();
        }

        private static void append(StringBuilder target, String name, String value) {
            if (value != null && !value.isBlank()) {
                target.append("- ").append(name).append(": ").append(value.strip()).append('\n');
            }
        }

        private static void appendInline(StringBuilder target, String name, String value) {
            if (value != null && !value.isBlank()) {
                String bounded = value.strip();
                if (bounded.length() > 240) {
                    bounded = bounded.substring(0, 240) + "…";
                }
                target.append(" | ").append(name).append('=').append(bounded.replace('\n', ' '));
            }
        }
    }

    /**
     * Configure the extraction model settings.
     *
     * @param config the extraction model configuration
     */
    default void configure(ExtractionModelConfig config) {
        // Default implementation does nothing - implementations can override
    }

    /**
     * Configure project-specific semantic validation and aligned prompt rules.
     * Implementations that perform their own LLM extraction should override this.
     */
    default void configureValidation(GraphExtractionValidationPolicy policy) {
        // Default implementation does nothing - implementations can override
    }

    /**
     * Constructs a comprehensive knowledge graph from all documents within a specified collection in the index.
     * This is typically a long-running, offline process.
     *
     * @param collectionName The name of the document collection in the IndexerService to process.
     * @return The fully constructed {@link Graph}.
     * @throws IOException if there is an error reading documents from the underlying index.
     */
    Graph constructGraph(String collectionName) throws IOException;

    /**
     * Constructs a smaller, targeted sub-graph from a specific list of retrieved documents.
     * This is useful for real-time, query-focused graph construction.
     *
     * @param docs The list of {@link RetrievedDoc} objects to build the graph from.
     * @return A {@link Graph} representing the knowledge extracted from the provided documents.
     */
    Graph constructGraphFromDocs(List<RetrievedDoc> docs, GraphSchema graphSchema, SchemaEnforcementMode enforcementMode);

    /**
     * Constructs a graph from a list of retrieved documents with additional options.
     *
     * @param docs The list of documents to process
     * @param graphSchema The schema to enforce
     * @param enforcementMode The schema enforcement mode
     * @param skipEmbedding Whether to skip embedding generation
     * @param skipMatrixGraph Whether to skip matrix graph persistence
     * @param progressListener Optional listener for per-document extraction progress
     * @return A Graph representing the extracted knowledge
     */
    default Graph constructGraphFromDocs(List<RetrievedDoc> docs, GraphSchema graphSchema,
                                         SchemaEnforcementMode enforcementMode,
                                         boolean skipEmbedding, boolean skipMatrixGraph,
                                         ProgressListener progressListener) {
        return constructGraphFromDocs(docs, graphSchema, enforcementMode);
    }

    /**
     * Context-aware form used by partition extraction. Implementations that do not yet consume
     * task context retain their existing behavior through this default.
     */
    default Graph constructGraphFromDocs(List<RetrievedDoc> docs, GraphSchema graphSchema,
                                         SchemaEnforcementMode enforcementMode,
                                         boolean skipEmbedding, boolean skipMatrixGraph,
                                         ProgressListener progressListener,
                                         ExtractionTaskContext taskContext) {
        return constructGraphFromDocs(docs, graphSchema, enforcementMode, skipEmbedding,
                skipMatrixGraph, progressListener);
    }

    /**
     * Listener for per-document extraction progress during graph construction.
     */
    @FunctionalInterface
    interface ProgressListener {
        void onProgress(DocumentExtractionProgress progress);
    }

    /**
     * Listener for retry and parse-failure events during graph extraction.
     *
     * <p>Implementations can use this to update job counters ({@link ai.kompile.core.crawl.graph.UnifiedCrawlJob})
     * or collect metrics.</p>
     */
    @FunctionalInterface
    interface RetryStatsListener {
        /**
         * Called when a retry or parse-failure event occurs during graph extraction.
         *
         * @param retryAttempt the retry attempt number (1-based), or 0 for a final parse failure
         * @param parseFailure true if all retries were exhausted (final failure), false if a retry
         */
        void onRetryEvent(int retryAttempt, boolean parseFailure);
    }

    /**
     * Status of a single document extraction within a batch.
     */
    enum DocumentExtractionStatus {
        STARTED, COMPLETED, FAILED, TIMED_OUT
    }

    /**
     * Progress report for a single document extraction.
     */
    record DocumentExtractionProgress(
            String documentId,
            int documentIndex,
            int totalDocuments,
            DocumentExtractionStatus status,
            String errorMessage,
            int entities,
            int relationships,
            int textLength,
            long elapsedMs
    ) {}
}