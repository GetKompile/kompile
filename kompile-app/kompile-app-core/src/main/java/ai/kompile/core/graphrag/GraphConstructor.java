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
    Graph constructGraphFromDocs(List<RetrievedDoc> docs);
}