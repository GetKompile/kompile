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

package ai.kompile.core.graphrag.callbacks;

import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;

/**
 * A set of callback hooks that can be used to monitor and interact with the Graph RAG process.
 */
public interface GraphRagCallbacks {

    /**
     * Called when the graph construction process starts.
     */
    void onGraphConstructionStart();

    /**
     * Called when the graph construction process ends.
     *
     * @param graph The constructed graph.
     */
    void onGraphConstructionEnd(Graph graph);

    /**
     * Called when a query process starts.
     *
     * @param query The query being executed.
     */
    void onQueryStart(GraphRagQuery query);

    /**
     * Called when a query process ends.
     *
     * @param result The result of the query.
     */
    void onQueryEnd(GraphRagResult result);

    /**
     * Called when an error occurs during the Graph RAG process.
     *
     * @param e The exception that was thrown.
     */
    void onError(Exception e);
}