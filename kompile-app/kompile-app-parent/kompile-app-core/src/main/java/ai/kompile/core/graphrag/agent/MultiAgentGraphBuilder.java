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
 * Orchestrates multiple {@link RelationExtractionAgent}s on the same set of document
 * chunks, merges their results into a single {@link Graph}, and tracks per-agent
 * contributions.
 *
 * <p>The merge strategy controls how conflicting or overlapping extractions from
 * different agents are resolved. For example, two agents may extract the same entity
 * with different confidence scores or slightly different names.
 */
public interface MultiAgentGraphBuilder {

    /**
     * Run all agents on the given chunks and merge their results.
     *
     * @param chunks the document chunks to process
     * @param agents the agents to run (order may matter for FIRST_WINS strategy)
     * @param strategy how to merge results from different agents
     * @param config shared extraction configuration passed to each agent
     * @return merged result with per-agent contribution tracking
     */
    MergedGraphResult buildGraph(
            List<RetrievedDoc> chunks,
            List<RelationExtractionAgent> agents,
            GraphMergeStrategy strategy,
            RelationExtractionAgent.ExtractionConfig config
    );

    /**
     * Strategy for merging entity and relationship graphs from multiple agents.
     */
    enum GraphMergeStrategy {
        /**
         * Accept all entities and relationships from all agents. Duplicates
         * (same entity ID or same source+target+type triple) are deduplicated,
         * keeping the higher-confidence version.
         */
        UNION,

        /**
         * Only keep entities and relationship types that appear in at least
         * two agents' outputs. Confidence is averaged across agents.
         */
        INTERSECTION,

        /**
         * When entities or relationships conflict, keep the version with
         * the highest confidence score.
         */
        HIGHEST_CONFIDENCE,

        /**
         * First agent's output wins on conflicts. Later agents only contribute
         * entities and relationships not already present.
         */
        FIRST_WINS
    }

    /**
     * Result of a multi-agent extraction run.
     *
     * @param mergedGraph the final merged graph
     * @param contributions per-agent extraction details
     * @param totalEntities total unique entities in the merged graph
     * @param totalRelations total unique relationships in the merged graph
     * @param totalTimeMs wall-clock time for the entire multi-agent run
     * @param strategy the merge strategy that was applied
     */
    record MergedGraphResult(
            Graph mergedGraph,
            Map<String, AgentContribution> contributions,
            int totalEntities,
            int totalRelations,
            long totalTimeMs,
            GraphMergeStrategy strategy
    ) {}

    /**
     * Per-agent contribution to the merged graph.
     *
     * @param agentId the agent that produced this contribution
     * @param entitiesExtracted entities extracted by this agent
     * @param relationsExtracted relations extracted by this agent
     * @param entitiesRetained entities from this agent in the final merged graph
     * @param relationsRetained relations from this agent in the final merged graph
     * @param extractionTimeMs time this agent took
     * @param entityTypes set of entity types this agent found
     * @param relationTypes set of relation types this agent found
     */
    record AgentContribution(
            String agentId,
            int entitiesExtracted,
            int relationsExtracted,
            int entitiesRetained,
            int relationsRetained,
            long extractionTimeMs,
            Set<String> entityTypes,
            Set<String> relationTypes
    ) {}
}
