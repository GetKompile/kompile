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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.app.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * DTOs for the Chunk Manager API.
 * These records provide type-safe representations for chunk management operations
 * including browsing, deduplication, and export.
 */
public class ChunkManagerDtos {

    /**
     * Detailed information about a single chunk.
     * Used for individual chunk detail views.
     * Now includes location info showing which indexes contain the chunk.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkDetail(
            String id,
            String content,
            int contentLength,
            Map<String, Object> metadata,
            String sourceId,
            String sourceFilename,
            Integer chunkIndex,
            Integer totalChunks,
            Integer pageNumber,
            String indexedAt,
            boolean inKeywordIndex,
            boolean inVectorStore
    ) {
        /**
         * Creates a ChunkDetail from a vector store document map.
         */
        public static ChunkDetail fromVectorDocument(Map<String, Object> doc) {
            return fromDocument(doc, false, true);
        }

        /**
         * Creates a ChunkDetail from a keyword index document map.
         */
        public static ChunkDetail fromKeywordDocument(Map<String, Object> doc) {
            return fromDocument(doc, true, false);
        }

        /**
         * Creates a ChunkDetail with specified location flags.
         */
        public static ChunkDetail fromDocument(Map<String, Object> doc, boolean inKeyword, boolean inVector) {
            String id = (String) doc.get("id");
            String content = (String) doc.get("content");
            int contentLength = content != null ? content.length() : 0;

            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) doc.getOrDefault("metadata", Map.of());

            String sourceId = getStringFromMetadata(metadata, "source_id");
            String sourceFilename = getStringFromMetadata(metadata, "source_filename");
            Integer chunkIndex = getIntegerFromMetadata(metadata, "chunk_index");
            Integer totalChunks = getIntegerFromMetadata(metadata, "total_chunks");
            Integer pageNumber = getIntegerFromMetadata(metadata, "page_number");
            String indexedAt = getStringFromMetadata(metadata, "indexed_at");

            return new ChunkDetail(
                    id, content, contentLength, metadata,
                    sourceId, sourceFilename, chunkIndex, totalChunks,
                    pageNumber, indexedAt, inKeyword, inVector
            );
        }

        /**
         * Creates a new ChunkDetail with updated location flags.
         */
        public ChunkDetail withLocations(boolean inKeyword, boolean inVector) {
            return new ChunkDetail(id, content, contentLength, metadata, sourceId, sourceFilename,
                    chunkIndex, totalChunks, pageNumber, indexedAt, inKeyword, inVector);
        }

        private static String getStringFromMetadata(Map<String, Object> metadata, String key) {
            Object value = metadata.get(key);
            return value != null ? value.toString() : null;
        }

        private static Integer getIntegerFromMetadata(Map<String, Object> metadata, String key) {
            Object value = metadata.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            } else if (value instanceof String) {
                try {
                    return Integer.parseInt((String) value);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }
    }

    /**
     * Summary information for chunk list views.
     * Contains only essential info to reduce payload size.
     * Now includes location info showing which indexes contain the chunk.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkSummary(
            String id,
            String preview,
            int contentLength,
            String sourceId,
            String sourceFilename,
            Integer chunkIndex,
            boolean inKeywordIndex,
            boolean inVectorStore
    ) {
        /**
         * Creates a ChunkSummary from a vector store document map.
         * Marks the chunk as being in the vector store.
         */
        public static ChunkSummary fromVectorDocument(Map<String, Object> doc) {
            return fromDocument(doc, false, true);
        }

        /**
         * Creates a ChunkSummary from a keyword index document map.
         * Marks the chunk as being in the keyword index.
         */
        public static ChunkSummary fromKeywordDocument(Map<String, Object> doc) {
            return fromDocument(doc, true, false);
        }

        /**
         * Creates a ChunkSummary with specified location flags.
         */
        public static ChunkSummary fromDocument(Map<String, Object> doc, boolean inKeyword, boolean inVector) {
            String id = (String) doc.get("id");
            String preview = (String) doc.get("preview");
            String content = (String) doc.get("content");
            int contentLength = content != null ? content.length() : 0;

            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) doc.getOrDefault("metadata", Map.of());

            String sourceId = metadata.get("source_id") != null ? metadata.get("source_id").toString() : null;
            String sourceFilename = metadata.get("source_filename") != null ? metadata.get("source_filename").toString() : null;
            Integer chunkIndex = null;
            Object chunkIndexObj = metadata.get("chunk_index");
            if (chunkIndexObj instanceof Number) {
                chunkIndex = ((Number) chunkIndexObj).intValue();
            }

            return new ChunkSummary(id, preview, contentLength, sourceId, sourceFilename, chunkIndex, inKeyword, inVector);
        }

        /**
         * Creates a new ChunkSummary with updated location flags.
         */
        public ChunkSummary withLocations(boolean inKeyword, boolean inVector) {
            return new ChunkSummary(id, preview, contentLength, sourceId, sourceFilename, chunkIndex, inKeyword, inVector);
        }
    }

    /**
     * Statistics about index counts.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndexStats(
            long keywordIndexCount,
            long vectorStoreCount
    ) {}

    /**
     * Response for paginated chunk listings.
     * Now includes index statistics.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkListResponse(
            List<ChunkSummary> chunks,
            int offset,
            int limit,
            long totalCount,
            int pageCount,
            IndexStats indexStats
    ) {
        /**
         * Creates a response without stats (backwards compatible).
         */
        public ChunkListResponse(List<ChunkSummary> chunks, int offset, int limit, long totalCount, int pageCount) {
            this(chunks, offset, limit, totalCount, pageCount, null);
        }
    }

    /**
     * A group of duplicate chunks identified by deduplication.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DuplicateGroup(
            String groupKey,
            String strategy,
            List<ChunkSummary> duplicates,
            String keepId
    ) {}

    /**
     * Response containing duplicate analysis results.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DuplicateAnalysisResponse(
            String strategy,
            int totalDuplicateGroups,
            int totalDuplicateChunks,
            int chunksToRemove,
            List<DuplicateGroup> groups
    ) {}

    /**
     * Result of a deduplication operation.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeduplicationResult(
            String strategy,
            int duplicateGroupsFound,
            int chunksRemoved,
            int chunksKept,
            boolean success,
            String message
    ) {}

    /**
     * Request for deduplication operations.
     */
    public record DeduplicationRequest(
            String strategy,
            String keepPolicy,
            boolean dryRun
    ) {
        public DeduplicationRequest {
            if (strategy == null) {
                strategy = "content_hash";
            }
            if (keepPolicy == null) {
                keepPolicy = "first";
            }
        }
    }

    /**
     * Request for exporting chunks to markdown.
     */
    public record ExportRequest(
            List<String> chunkIds,
            String sourceId,
            boolean includeMetadata,
            String format
    ) {
        public ExportRequest {
            if (format == null) {
                format = "markdown";
            }
        }
    }

    /**
     * Response for chunk export operations.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExportResponse(
            String format,
            String content,
            int chunkCount,
            String filename
    ) {}

    /**
     * Request for deleting chunks by source.
     */
    public record DeleteBySourceRequest(
            String sourceId
    ) {}

    /**
     * Request for clearing all chunks (requires confirmation token).
     */
    public record ClearAllRequest(
            String confirmationToken
    ) {}

    /**
     * Generic operation response.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OperationResponse(
            boolean success,
            String message,
            int affectedCount
    ) {}

    /**
     * Information about a source document.
     * Now includes counts from both indexes.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceInfo(
            String sourceId,
            String filename,
            int chunkCount,
            int keywordChunkCount,
            int vectorChunkCount
    ) {
        /**
         * Creates a SourceInfo with combined count (backwards compatible).
         */
        public SourceInfo(String sourceId, String filename, int chunkCount) {
            this(sourceId, filename, chunkCount, 0, chunkCount);
        }
    }

    /**
     * Response listing all source documents.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceListResponse(
            List<SourceInfo> sources,
            int totalSources
    ) {}

    // ═══════════════════════════════════════════════════════════════════════════
    // CHUNK EDITING DTOs
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Request for updating a chunk's content and metadata.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkUpdateRequest(
            String content,
            String semanticType,
            String sourceTitle,
            String sourceAuthor,
            String sourceDate,
            String sourceUrl,
            List<ChunkEntityDto> entities,
            Map<String, Object> metadata
    ) {}

    /**
     * Entity extracted from a chunk.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkEntityDto(
            String name,
            String type,
            Double confidence,
            Integer startOffset,
            Integer endOffset
    ) {}

    /**
     * Detailed chunk information for editing.
     * Extends ChunkDetail with additional editable fields.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkEditDetail(
            String id,
            String content,
            int contentLength,
            Map<String, Object> metadata,
            String sourceId,
            String sourceFilename,
            Integer chunkIndex,
            Integer totalChunks,
            Integer pageNumber,
            String indexedAt,
            boolean inKeywordIndex,
            boolean inVectorStore,
            // Additional editable fields
            String semanticType,
            String sourceTitle,
            String sourceAuthor,
            String sourceDate,
            String sourceUrl,
            String sourcePath,
            List<ChunkEntityDto> entities,
            Integer entityCount,
            Integer relationCount
    ) {
        /**
         * Creates a ChunkEditDetail from a document map with full metadata.
         */
        public static ChunkEditDetail fromDocument(Map<String, Object> doc, boolean inKeyword, boolean inVector) {
            String id = (String) doc.get("id");
            String content = (String) doc.get("content");
            int contentLength = content != null ? content.length() : 0;

            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) doc.getOrDefault("metadata", Map.of());

            String sourceId = getStringFromMetadata(metadata, "source_id");
            String sourceFilename = getStringFromMetadata(metadata, "source_filename");
            Integer chunkIndex = getIntegerFromMetadata(metadata, "chunk_index");
            Integer totalChunks = getIntegerFromMetadata(metadata, "total_chunks");
            Integer pageNumber = getIntegerFromMetadata(metadata, "page_number");
            String indexedAt = getStringFromMetadata(metadata, "indexed_at");

            // Additional editable fields
            String semanticType = getStringFromMetadata(metadata, "semantic_type");
            String sourceTitle = getStringFromMetadata(metadata, "source_title");
            String sourceAuthor = getStringFromMetadata(metadata, "source_author");
            String sourceDate = getStringFromMetadata(metadata, "source_date");
            String sourceUrl = getStringFromMetadata(metadata, "source_url");
            String sourcePath = getStringFromMetadata(metadata, "source_path");
            Integer entityCount = getIntegerFromMetadata(metadata, "entity_count");
            Integer relationCount = getIntegerFromMetadata(metadata, "relation_count");

            // Parse entities from JSON if present
            List<ChunkEntityDto> entities = List.of();
            String entitiesJson = getStringFromMetadata(metadata, "entities_json");
            if (entitiesJson != null && !entitiesJson.isEmpty()) {
                // Parse entities - in production would use Jackson
                entities = List.of(); // Placeholder - actual parsing in controller
            }

            return new ChunkEditDetail(
                    id, content, contentLength, metadata,
                    sourceId, sourceFilename, chunkIndex, totalChunks,
                    pageNumber, indexedAt, inKeyword, inVector,
                    semanticType, sourceTitle, sourceAuthor, sourceDate,
                    sourceUrl, sourcePath, entities, entityCount, relationCount
            );
        }

        private static String getStringFromMetadata(Map<String, Object> metadata, String key) {
            Object value = metadata.get(key);
            return value != null ? value.toString() : null;
        }

        private static Integer getIntegerFromMetadata(Map<String, Object> metadata, String key) {
            Object value = metadata.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            } else if (value instanceof String) {
                try {
                    return Integer.parseInt((String) value);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }
    }

    /**
     * Response for chunk update operations.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkUpdateResponse(
            boolean success,
            String message,
            ChunkEditDetail updatedChunk
    ) {}

    /**
     * Available semantic types for chunks.
     */
    public static final List<String> SEMANTIC_TYPES = List.of(
            "TEXT", "DEFINITION", "EXPLANATION", "PROCEDURE", "EXAMPLE",
            "SUMMARY", "FACT", "OPINION", "QUESTION", "ANSWER",
            "CODE", "TABLE", "LIST", "HEADER", "QUOTE",
            "REFERENCE", "METADATA", "WARNING", "NOTE", "CONCLUSION", "UNKNOWN"
    );

    /**
     * Available entity types for chunks.
     */
    public static final List<String> ENTITY_TYPES = List.of(
            "PERSON", "ORGANIZATION", "LOCATION", "DATE", "TIME",
            "MONEY", "PERCENT", "PRODUCT", "EVENT", "WORK_OF_ART",
            "LAW", "LANGUAGE", "TECHNOLOGY", "CONCEPT", "QUANTITY",
            "URL", "EMAIL", "CODE", "API", "FUNCTION", "CLASS", "CUSTOM", "UNKNOWN"
    );
}
