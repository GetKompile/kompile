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

package ai.kompile.core.graphrag.query;

import ai.kompile.core.graphrag.model.Graph;
import lombok.Builder;
import lombok.Data;

/**
 * Defines the input for a query to the Graph RAG system.
 */
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
}