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

package ai.kompile.core.graphrag;

import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;

/**
 * The main entry point for performing Retrieval-Augmented Generation (RAG) queries against a knowledge graph.
 * This service orchestrates the process of understanding a query, searching the graph,
 * synthesizing information, and generating a human-readable answer.
 */
public interface GraphRagService {

    /**
     * Answers a user's query by leveraging the knowledge graph.
     * The process typically involves:
     * <ol>
     * <li>Performing a search over the graph based on the query (e.g., LOCAL or GLOBAL).</li>
     * <li>Extracting a relevant sub-graph and/or context.</li>
     * <li>Synthesizing the extracted information using a Language Model to generate a final answer.</li>
     * </ol>
     *
     * @param query An object containing the user's query and other search parameters.
     * @return A {@link GraphRagResult} containing the generated answer and the context used.
     */
    GraphRagResult answerQuery(GraphRagQuery query);
}
