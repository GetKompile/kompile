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
package ai.kompile.core.graphrag.agent;

import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.retrievers.RetrievedDoc;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An agent that extracts entities and relations from document chunks and produces
 * a {@link Graph}. Multiple agents can be composed via {@link MultiAgentGraphBuilder}
 * to combine structural, pattern-based, and LLM-based extraction on the same documents.
 *
 * <p>Implementations must be stateless per-call: each invocation of {@link #extract}
 * should be independent. Configuration (e.g., model name, entity types) is passed
 * through the {@code config} parameter.
 *
 * <p>Unlike {@link ai.kompile.core.graphbuilder.KnowledgeGraphBuilder}, this interface
 * is intentionally simple: no job management, no proposal workflow, no persistence.
 * It represents a single extraction capability, not a full pipeline.
 */
public interface RelationExtractionAgent {

    /**
     * Unique identifier for this agent (e.g., "llm-openai", "pattern-ner", "excel-structural").
     */
    String getId();

    /**
     * Human-readable description of what this agent does.
     */
    String getDescription();

    /**
     * Content types this agent can handle. Return empty set for "any content type".
     * Values should match the {@code content_type} metadata field on documents
     * (e.g., "text", "table", "formula_graph").
     */
    Set<String> supportedContentTypes();

    /**
     * Extract entities and relationships from document chunks.
     *
     * @param chunks the document chunks to process
     * @param config extraction configuration (entity types, model, etc.)
     * @return extraction result containing a graph and agent metrics
     */
    ExtractionResult extract(List<RetrievedDoc> chunks, ExtractionConfig config);

    /**
     * Configuration passed to an agent for a single extraction run.
     *
     * @param entityTypes entity types to look for (e.g., "PERSON", "ORGANIZATION")
     * @param relationshipTypes relationship types to extract
     * @param minConfidence minimum confidence threshold (0.0-1.0)
     * @param options additional agent-specific options
     */
    record ExtractionConfig(
            List<String> entityTypes,
            List<String> relationshipTypes,
            double minConfidence,
            Map<String, Object> options
    ) {
        public static ExtractionConfig defaults() {
            return new ExtractionConfig(
                    List.of("PERSON", "ORGANIZATION", "LOCATION", "CONCEPT", "EVENT",
                            "PROCESS", "PROCEDURE"),
                    List.of(),
                    0.5,
                    Map.of()
            );
        }
    }

    /**
     * Result of a single agent's extraction run.
     *
     * @param graph the extracted entities and relationships
     * @param metrics performance and quality metrics
     */
    record ExtractionResult(
            Graph graph,
            AgentMetrics metrics
    ) {}

    /**
     * Metrics from an agent extraction run.
     *
     * @param agentId which agent produced these metrics
     * @param extractionTimeMs wall-clock time for extraction
     * @param entitiesExtracted number of entities found
     * @param relationsExtracted number of relations found
     * @param chunksProcessed number of chunks processed
     * @param modelUsed model identifier if applicable (null for non-LLM agents)
     * @param metadata additional agent-specific metadata
     */
    record AgentMetrics(
            String agentId,
            long extractionTimeMs,
            int entitiesExtracted,
            int relationsExtracted,
            int chunksProcessed,
            String modelUsed,
            Map<String, Object> metadata
    ) {}
}
