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
package ai.kompile.core.indexers;

import java.util.List;

/**
 * Resolves graph node identifiers to vector/keyword index document IDs.
 * <p>
 * During crawl indexing, SNIPPET graph nodes receive a synthetic external ID
 * (e.g. "chunk:jobId:path:index"), while the vector store and keyword index
 * store documents under a different Spring AI auto-generated UUID. This interface
 * bridges that gap so that services operating on graph nodes can clean up
 * corresponding index entries.
 */
public interface CrossIndexIdResolver {

    /**
     * Given a graph node's external ID (the snippet ID stored as GraphNode.externalId),
     * returns the corresponding index document IDs used in the vector store and keyword index.
     *
     * @param graphNodeExternalId the external ID of a SNIPPET graph node
     * @return the index document IDs (chunk IDs) for deletion, or empty list if not found
     */
    List<String> resolveIndexDocumentIds(String graphNodeExternalId);
}
