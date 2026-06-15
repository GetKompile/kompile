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

import ai.kompile.core.graphrag.model.Graph;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GraphRagQuery {
    /**
     * The user's natural language query.
     */
    private String query;

    /**
     * The graph to perform the query against.
     * If not provided, the service may use a default or pre-loaded graph.
     */
    private Graph graph;

    /**
     * The type of search to execute.
     */
    private SearchType searchType;

    /**
     * The maximum number of results to retrieve or consider.
     */
    private int k;

    /**
     * The unique identifier for the conversation session, allowing for stateful interactions.
     */
    @Builder.Default
    private String conversationId = "default"; // Defaults to a single shared conversation

    /**
     * Number of hops to traverse from seed nodes during graph exploration.
     */
    @Builder.Default
    private int hopDepth = 2;

    /**
     * Maximum number of nodes to visit during graph traversal.
     */
    @Builder.Default
    private int maxTraversalNodes = 50;

    /**
     * Weight for vector similarity scores in hybrid search (0.0 to 1.0).
     * Graph structure weight is 1.0 - vectorWeight.
     */
    @Builder.Default
    private double vectorWeight = 0.5;

    /**
     * Fact sheet ID to scope the search to a specific knowledge base partition.
     */
    private Long factSheetId;

    /**
     * Whether to include community information in the search results.
     */
    @Builder.Default
    private boolean includeCommunities = true;
}