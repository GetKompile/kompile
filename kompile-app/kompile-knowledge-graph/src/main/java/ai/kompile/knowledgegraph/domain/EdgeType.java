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
package ai.kompile.knowledgegraph.domain;

/**
 * Types of edges/relationships in the knowledge graph.
 */
public enum EdgeType {
    /**
     * Parent-child relationship (SOURCE -> DOCUMENT -> SNIPPET)
     */
    HIERARCHICAL,

    /**
     * Computed from vector embedding similarity
     */
    EMBEDDING_SIMILARITY,

    /**
     * Documents mentioning the same entities/terms
     */
    SHARED_ENTITY,

    /**
     * Manual links created by users
     */
    USER_DEFINED,

    /**
     * Document cites/references another
     */
    CITATION,

    /**
     * Time-based relationship between documents
     */
    TEMPORAL,

    /**
     * Related content across different sources
     */
    CROSS_SOURCE
}
