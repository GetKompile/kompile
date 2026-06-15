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
package ai.kompile.core.graphbuilder;

import ai.kompile.core.retrievers.RetrievedDoc;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Interface for knowledge graph builders.
 *
 * <p>Implementations can extract entities and relationships from text using
 * various approaches:
 * <ul>
 *   <li><b>manual</b> - User creates triples directly via UI</li>
 *   <li><b>llm</b> - LLM-based entity/relationship extraction</li>
 *   <li><b>pattern</b> - Regex/NLP pattern-based extraction</li>
 *   <li><b>hybrid</b> - Combination of techniques</li>
 * </ul>
 */
public interface KnowledgeGraphBuilder {

    /**
     * Get the unique identifier for this builder.
     */
    String getId();

    /**
     * Get the human-readable display name.
     */
    String getDisplayName();

    /**
     * Get the builder type.
     */
    GraphBuilderType getType();

    /**
     * Configure this builder with the given configuration.
     *
     * @param config the configuration to apply
     */
    void configure(BuilderConfig config);

    /**
     * Get the current configuration.
     *
     * @return the current configuration
     */
    default BuilderConfig getConfig() {
        return BuilderConfig.defaults();
    }

    /**
     * Build triples from a list of chunks.
     *
     * @param chunks the document chunks to process
     * @param context the build context (job ID, fact sheet, etc.)
     * @param progressCallback callback for progress updates
     * @return list of proposed triples
     */
    List<ProposedTriple> buildFromChunks(
            List<RetrievedDoc> chunks,
            GraphBuildContext context,
            Consumer<BuildProgress> progressCallback
    );

    /**
     * Get extraction logs for a job (for LLM builders with full transparency).
     *
     * @param jobId the job ID
     * @return extraction log entries, or empty if not supported
     */
    default Optional<List<ExtractionLogEntry>> getExtractionLog(String jobId) {
        return Optional.empty();
    }

    /**
     * Whether this builder supports full extraction logs.
     */
    default boolean supportsExtractionLog() {
        return false;
    }

    /**
     * Whether this builder supports running concurrently during document indexing.
     */
    default boolean supportsConcurrentIndexing() {
        return false;
    }

    /**
     * Get builder info for API responses.
     */
    default GraphBuilderInfo getInfo() {
        return new GraphBuilderInfo(
                getId(),
                getDisplayName(),
                getDescription(),
                getType(),
                supportsExtractionLog()
        );
    }

    /**
     * Get a description of this builder's capabilities.
     */
    default String getDescription() {
        return "Knowledge graph builder";
    }
}
