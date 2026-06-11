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
     * Configure the extraction model settings.
     *
     * @param config the extraction model configuration
     */
    default void configure(ExtractionModelConfig config) {
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
     * Listener for per-document extraction progress during graph construction.
     */
    @FunctionalInterface
    interface ProgressListener {
        void onProgress(DocumentExtractionProgress progress);
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