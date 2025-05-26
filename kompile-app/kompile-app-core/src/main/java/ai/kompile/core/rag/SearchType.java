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

package ai.kompile.core.rag;

/**
 * Enumerates the different search strategies that can be used to query the RAG system.
 */
public enum SearchType {
    /**
     * Local search focuses on a specific part of the knowledge base, typically around documents directly related to the query.
     * It is faster and more focused.
     */
    LOCAL,

    /**
     * Global search considers the entire knowledge base to find broader, more contextual answers.
     * It is more comprehensive but may be slower.
     */
    GLOBAL
}