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

package ai.kompile.app.tools;

import ai.kompile.app.services.ChunkDeduplicationService;
import ai.kompile.core.embeddings.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ChunkManagementTool {

    private static final Logger logger = LoggerFactory.getLogger(ChunkManagementTool.class);

    private final VectorStore vectorStore;
    private final ChunkDeduplicationService deduplicationService;

    @Autowired
    public ChunkManagementTool(
            @Autowired(required = false) VectorStore vectorStore,
            @Autowired(required = false) ChunkDeduplicationService deduplicationService) {
        this.vectorStore = vectorStore;
        this.deduplicationService = deduplicationService;
        logger.info("ChunkManagementTool initialized");
    }

    public record AnalyzeDuplicatesInput(String strategy) {}
    public record DeduplicateChunksInput(String strategy, String keepPolicy, Boolean dryRun) {}
    public record DeleteChunkInput(String chunkId) {}
    public record DeleteChunksBySourceInput(String sourceId) {}

    @Tool(name = "analyze_duplicate_chunks",
            description = "Analyzes chunks for duplicates using a strategy: 'content_hash' (exact duplicates) or 'source_and_index' (same source + index). " +
                    "Returns duplicate groups and counts without making changes.")
    public Map<String, Object> analyzeDuplicates(AnalyzeDuplicatesInput input) {
        try {
            if (deduplicationService == null) return Map.of("status", "error", "error", "ChunkDeduplicationService not available");
            String strategy = input.strategy() != null ? input.strategy() : "content_hash";
            var analysis = deduplicationService.analyzeDuplicates(strategy);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("analysis", analysis);
            return result;
        } catch (Exception e) {
            logger.error("Error analyzing duplicates: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "deduplicate_chunks",
            description = "Removes duplicate chunks. Strategy: 'content_hash' or 'source_and_index'. " +
                    "keepPolicy: 'first' or 'latest'. Set dryRun=true to preview without deleting.")
    public Map<String, Object> deduplicateChunks(DeduplicateChunksInput input) {
        try {
            if (deduplicationService == null) return Map.of("status", "error", "error", "ChunkDeduplicationService not available");
            String strategy = input.strategy() != null ? input.strategy() : "content_hash";
            String keep = input.keepPolicy() != null ? input.keepPolicy() : "first";
            boolean dryRun = input.dryRun() != null ? input.dryRun() : false;
            var dedup = deduplicationService.deduplicate(strategy, keep, dryRun);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("dryRun", dryRun);
            result.put("result", dedup);
            return result;
        } catch (Exception e) {
            logger.error("Error deduplicating chunks: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_chunk",
            description = "Deletes a specific chunk by its ID from the vector store.")
    public Map<String, Object> deleteChunk(DeleteChunkInput input) {
        try {
            if (vectorStore == null) return Map.of("status", "error", "error", "VectorStore not available");
            if (input.chunkId() == null) return Map.of("status", "error", "error", "chunkId is required");
            vectorStore.delete(List.of(input.chunkId()));
            return Map.of("status", "success", "message", "Chunk deleted", "chunkId", input.chunkId());
        } catch (Exception e) {
            logger.error("Error deleting chunk: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_chunks_by_source",
            description = "Deletes all chunks associated with a specific source document ID.")
    public Map<String, Object> deleteChunksBySource(DeleteChunksBySourceInput input) {
        try {
            if (vectorStore == null) return Map.of("status", "error", "error", "VectorStore not available");
            if (input.sourceId() == null) return Map.of("status", "error", "error", "sourceId is required");
            List<String> docIds = vectorStore.getDocumentIdsBySourceId(input.sourceId());
            if (docIds.isEmpty()) return Map.of("status", "success", "message", "No chunks found for source", "sourceId", input.sourceId());
            vectorStore.delete(docIds);
            return Map.of("status", "success", "message", "Chunks deleted for source", "sourceId", input.sourceId(), "deletedCount", docIds.size());
        } catch (Exception e) {
            logger.error("Error deleting chunks by source: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
