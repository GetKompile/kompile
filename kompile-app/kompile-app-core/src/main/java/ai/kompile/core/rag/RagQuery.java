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

package ai.kompile.core.rag;

import lombok.Builder;
import lombok.Data;

/**
 * Defines the input for a query to the RAG system.
 */
@Data
@Builder
public class RagQuery {
    /**
     * The user's natural language query.
     */
    private String query;

    /**
     * Whether to use tool calling in the response generation.
     */
    private boolean useToolCalling = false;

    /**
     * The type of search to execute. Defaults to LOCAL.
     */
    @Builder.Default
    private SearchType searchType = SearchType.LOCAL;

    /**
     * The maximum number of documents to retrieve. Defaults to 10.
     */
    @Builder.Default
    private int k = 10;
}