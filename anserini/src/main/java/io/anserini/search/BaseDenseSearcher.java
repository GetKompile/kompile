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

/*
 * Anserini: A Lucene toolkit for reproducible information retrieval research
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

package io.anserini.search;

import org.jetbrains.annotations.Nullable;
import java.io.IOException;

/**
 * Base class for dense searchers in Anserini.
 * It expects queries to be either String (to be encoded) or float[] (pre-encoded vector).
 * The generic type Q from BaseSearcher is specialized to Object here to allow runtime type checking.
 *
 * @param <K> type of query id (typically String or Integer)
 */
public abstract class BaseDenseSearcher<K extends Comparable<K>> extends BaseSearcher<K, Object> {

    public BaseDenseSearcher(BaseSearchArgs args) {
        super(args);
    }

    /**
     * Dispatches to the appropriate search method based on the runtime type of the query object.
     * This method implements the abstract search method from {@link BaseSearcher}.
     *
     * @param queryId the unique identifier for the query. Can be null.
     * @param query   the query content, expected to be either String or float[].
     * @param k       the number of hits to return.
     * @return an array of {@link ScoredDoc} representing the search results.
     * @throws IOException if an I/O error occurs during searching.
     * @throws IllegalArgumentException if the query type is not String or float[].
     */
    @Override
    public ScoredDoc[] search(@Nullable K queryId, Object query, int k) throws IOException {
        if (query instanceof float[]) {
            return searchVector(queryId, (float[]) query, k);
        } else if (query instanceof String) {
            return searchString(queryId, (String) query, k);
        } else if (query == null) {
            throw new IllegalArgumentException("Query object cannot be null.");
        }
        else {
            throw new IllegalArgumentException("Unsupported query type for BaseDenseSearcher: " + query.getClass().getName() +
                    ". Expected String or float[].");
        }
    }

    /**
     * Searches the collection with a pre-encoded query vector.
     * To be implemented by concrete subclasses.
     *
     * @param queryId     query id, can be null.
     * @param queryVector query vector.
     * @param k           number of hits.
     * @return array of search results.
     * @throws IOException if error encountered during search.
     */
    protected abstract ScoredDoc[] searchVector(@Nullable K queryId, float[] queryVector, int k) throws IOException;

    /**
     * Searches the collection with a string query that will be encoded by the underlying encoder.
     * To be implemented by concrete subclasses.
     *
     * @param queryId     query id, can be null.
     * @param queryString query string.
     * @param k           number of hits.
     * @return array of search results.
     * @throws IOException if error encountered during search.
     */
    protected abstract ScoredDoc[] searchString(@Nullable K queryId, String queryString, int k) throws IOException;
}