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

/**
 * The main entry point for performing Retrieval-Augmented Generation (RAG) queries.
 */
public interface RagService {

    /**
     * Answers a user's query by leveraging the RAG system.
     *
     * @param query An object containing the user's query and other search parameters.
     * @return A {@link RagResult} containing the generated answer and the context used.
     */
    RagResult answerQuery(RagQuery query);
}