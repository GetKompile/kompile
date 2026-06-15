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

package ai.kompile.core.graphrag.query;

import ai.kompile.core.graphrag.model.Community;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Relationship;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Represents the output of a query from the Graph RAG system.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagResult {
    /**
     * The generated, synthesized answer to the user's query.
     */
    private String answer;

    /**
     * The formatted context string, derived from the graph, that was used to generate the answer.
     * This can be used for transparency and debugging.
     */
    private String formattedContext;

    /**
     * Entities found during graph traversal.
     */
    private List<Entity> entities;

    /**
     * Relationships found during graph traversal.
     */
    private List<Relationship> relationships;

    /**
     * Communities identified in the result graph.
     */
    private List<Community> communities;

    /**
     * Source text chunks that contributed to the answer.
     */
    private List<String> sourceChunks;

    /**
     * The search type that was used to produce this result.
     */
    private SearchType searchType;

    /**
     * Number of graph traversal hops actually performed (0 for pure vector search).
     */
    private int hopsPerformed;

    /**
     * Total number of graph nodes visited during traversal.
     */
    private int nodesVisited;

    /**
     * Traversal paths keyed by seed entity ID.
     * Each value is a list of entity IDs representing the path taken from that seed.
     */
    private Map<String, List<String>> traversalPaths;

    /**
     * Score breakdown for each entity ID encountered.
     * Each value is a map of score component names to values
     * (e.g., "vectorScore", "graphScore", "hopDistance", "combined").
     */
    private Map<String, Map<String, Double>> scoreBreakdown;
}