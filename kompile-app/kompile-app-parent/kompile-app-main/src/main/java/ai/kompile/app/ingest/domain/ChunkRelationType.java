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

package ai.kompile.app.ingest.domain;

/**
 * Types of relationships between document chunks/passages.
 * Used for semantic linking and navigation between related content.
 */
public enum ChunkRelationType {

    /**
     * Chunks are sequentially adjacent in the original document
     */
    SEQUENTIAL,

    /**
     * Chunk references another chunk
     */
    REFERENCES,

    /**
     * Chunk is referenced by another chunk
     */
    REFERENCED_BY,

    /**
     * Chunks share similar content (based on embeddings)
     */
    SIMILAR_TO,

    /**
     * Chunk provides more detail or elaboration of another
     */
    ELABORATES,

    /**
     * Chunk summarizes another chunk
     */
    SUMMARIZES,

    /**
     * Chunk provides an example for another chunk's concept
     */
    EXEMPLIFIES,

    /**
     * Chunk contradicts or presents opposing view
     */
    CONTRADICTS,

    /**
     * Chunk supports or confirms another
     */
    SUPPORTS,

    /**
     * Chunk is a prerequisite for understanding another
     */
    PREREQUISITE,

    /**
     * Chunk follows from or builds on another
     */
    FOLLOWS_FROM,

    /**
     * Chunks share common entities
     */
    SHARED_ENTITY,

    /**
     * Chunk is related by topic/theme
     */
    TOPICALLY_RELATED,

    /**
     * Chunk is a parent section containing another
     */
    CONTAINS,

    /**
     * Chunk is contained within another
     */
    CONTAINED_IN,

    /**
     * User-defined relationship
     */
    CUSTOM
}
