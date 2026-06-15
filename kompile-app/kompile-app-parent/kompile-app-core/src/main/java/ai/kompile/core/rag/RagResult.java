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

import ai.kompile.core.retrievers.RetrievedDoc;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents the output of a query from the RAG system.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagResult {
    /**
     * The generated, synthesized answer to the user's query.
     */
    private String answer;

    /**
     * The formatted context string that was used to generate the answer.
     * This can be used for transparency and debugging.
     */
    private String formattedContext;

    /**
     * The list of documents retrieved to generate the answer.
     */
    private List<RetrievedDoc> retrievedDocs;
}