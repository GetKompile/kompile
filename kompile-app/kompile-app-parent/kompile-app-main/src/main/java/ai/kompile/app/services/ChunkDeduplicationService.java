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

package ai.kompile.app.services;

import ai.kompile.app.web.dto.ChunkManagerDtos.*;
import ai.kompile.core.embeddings.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for detecting and removing duplicate chunks from the vector store.
 * <p>
 * Supports two deduplication strategies:
 * <ul>
 *   <li><b>content_hash</b>: Identifies exact duplicates using SHA-256 hash of content</li>
 *   <li><b>source_and_index</b>: Identifies re-indexed chunks from the same source</li>
 * </ul>
 */
@Slf4j
@Service("appChunkDeduplicationService")
public class ChunkDeduplicationService {

    public static final String STRATEGY_CONTENT_HASH = "content_hash";
    public static final String STRATEGY_SOURCE_AND_INDEX = "source_and_index";

    public static final String KEEP_FIRST = "first";
    public static final String KEEP_LATEST = "latest";

    private final VectorStore vectorStore;

    @Autowired
    public ChunkDeduplicationService(@Autowired(required = false) VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Analyzes the vector store for duplicates without removing them.
     *
     * @param strategy The deduplication strategy to use
     * @return Analysis results with duplicate groups
     */
    public DuplicateAnalysisResponse analyzeDuplicates(String strategy) {
        if (vectorStore == null) {
            log.warn("VectorStore not available for deduplication analysis");
            return new DuplicateAnalysisResponse(strategy, 0, 0, 0, List.of());
        }

        List<Map<String, Object>> allDocs = vectorStore.getAllVectorDocuments();
        log.info("Analyzing {} documents for duplicates using strategy: {}", allDocs.size(), strategy);

        List<DuplicateGroup> groups;
        if (STRATEGY_SOURCE_AND_INDEX.equals(strategy)) {
            groups = findDuplicatesBySourceAndIndex(allDocs);
        } else {
            groups = findDuplicatesByContentHash(allDocs);
        }

        int totalDuplicateChunks = groups.stream()
                .mapToInt(g -> g.duplicates().size())
                .sum();
        int chunksToRemove = groups.stream()
                .mapToInt(g -> g.duplicates().size() - 1) // Keep one from each group
                .sum();

        log.info("Found {} duplicate groups with {} total duplicates ({} to remove)",
                groups.size(), totalDuplicateChunks, chunksToRemove);

        return new DuplicateAnalysisResponse(
                strategy,
                groups.size(),
                totalDuplicateChunks,
                chunksToRemove,
                groups
        );
    }

    /**
     * Removes duplicate chunks from the vector store.
     *
     * @param strategy   The deduplication strategy to use
     * @param keepPolicy Which duplicate to keep ("first" or "latest")
     * @param dryRun     If true, only analyze without removing
     * @return Result of the deduplication operation
     */
    public DeduplicationResult deduplicate(String strategy, String keepPolicy, boolean dryRun) {
        if (vectorStore == null) {
            log.warn("VectorStore not available for deduplication");
            return new DeduplicationResult(strategy, 0, 0, 0, false,
                    "VectorStore not available");
        }

        List<Map<String, Object>> allDocs = vectorStore.getAllVectorDocuments();
        log.info("Running deduplication on {} documents (strategy: {}, keep: {}, dryRun: {})",
                allDocs.size(), strategy, keepPolicy, dryRun);

        List<DuplicateGroup> groups;
        if (STRATEGY_SOURCE_AND_INDEX.equals(strategy)) {
            groups = findDuplicatesBySourceAndIndex(allDocs);
        } else {
            groups = findDuplicatesByContentHash(allDocs);
        }

        if (groups.isEmpty()) {
            return new DeduplicationResult(strategy, 0, 0, allDocs.size(), true,
                    "No duplicates found");
        }

        // Determine which chunks to remove based on keep policy
        List<String> idsToRemove = new ArrayList<>();
        int chunksKept = 0;

        for (DuplicateGroup group : groups) {
            List<ChunkSummary> duplicates = group.duplicates();
            if (duplicates.size() <= 1) {
                continue;
            }

            // Sort duplicates for consistent selection
            List<ChunkSummary> sorted = new ArrayList<>(duplicates);
            if (KEEP_LATEST.equals(keepPolicy)) {
                // Keep the last one (most recently indexed)
                Collections.reverse(sorted);
            }

            // Keep first, remove rest
            chunksKept++;
            for (int i = 1; i < sorted.size(); i++) {
                idsToRemove.add(sorted.get(i).id());
            }
        }

        if (dryRun) {
            log.info("Dry run: would remove {} duplicates, keeping {} chunks",
                    idsToRemove.size(), chunksKept);
            return new DeduplicationResult(
                    strategy,
                    groups.size(),
                    0, // No actual removal in dry run
                    allDocs.size(),
                    true,
                    String.format("Dry run: would remove %d duplicates from %d groups",
                            idsToRemove.size(), groups.size())
            );
        }

        // Actually remove duplicates
        boolean success = vectorStore.delete(idsToRemove);
        int removedCount = success ? idsToRemove.size() : 0;

        log.info("Deduplication complete: removed {} chunks, {} groups processed",
                removedCount, groups.size());

        return new DeduplicationResult(
                strategy,
                groups.size(),
                removedCount,
                allDocs.size() - removedCount,
                success,
                success ? String.format("Removed %d duplicate chunks from %d groups",
                        removedCount, groups.size())
                        : "Failed to remove some duplicates"
        );
    }

    /**
     * Find duplicates by computing SHA-256 hash of content.
     * Chunks with identical content will be grouped together.
     */
    private List<DuplicateGroup> findDuplicatesByContentHash(List<Map<String, Object>> allDocs) {
        Map<String, List<ChunkSummary>> hashGroups = new HashMap<>();

        for (Map<String, Object> doc : allDocs) {
            String content = (String) doc.get("content");
            if (content == null || content.isEmpty()) {
                continue;
            }

            String hash = computeSha256(content);
            ChunkSummary summary = ChunkSummary.fromVectorDocument(doc);

            hashGroups.computeIfAbsent(hash, k -> new ArrayList<>()).add(summary);
        }

        // Filter to only groups with duplicates (size > 1)
        return hashGroups.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> new DuplicateGroup(
                        e.getKey().substring(0, Math.min(16, e.getKey().length())), // Truncate hash for display
                        STRATEGY_CONTENT_HASH,
                        e.getValue(),
                        e.getValue().get(0).id() // Default keep first
                ))
                .collect(Collectors.toList());
    }

    /**
     * Find duplicates by source_id + chunk_index.
     * Multiple chunks with the same source and index indicate re-indexed files.
     */
    private List<DuplicateGroup> findDuplicatesBySourceAndIndex(List<Map<String, Object>> allDocs) {
        Map<String, List<ChunkSummary>> sourceIndexGroups = new HashMap<>();

        for (Map<String, Object> doc : allDocs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) doc.getOrDefault("metadata", Map.of());

            Object sourceIdObj = metadata.get("source_id");
            Object chunkIndexObj = metadata.get("chunk_index");

            if (sourceIdObj == null) {
                continue;
            }

            String sourceId = sourceIdObj.toString();
            String chunkIndex = chunkIndexObj != null ? chunkIndexObj.toString() : "0";
            String key = sourceId + "::" + chunkIndex;

            ChunkSummary summary = ChunkSummary.fromVectorDocument(doc);
            sourceIndexGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(summary);
        }

        // Filter to only groups with duplicates (size > 1)
        return sourceIndexGroups.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> {
                    String[] parts = e.getKey().split("::", 2);
                    String displayKey = parts.length > 1 ?
                            truncateSource(parts[0]) + " [chunk " + parts[1] + "]" :
                            truncateSource(e.getKey());
                    return new DuplicateGroup(
                            displayKey,
                            STRATEGY_SOURCE_AND_INDEX,
                            e.getValue(),
                            e.getValue().get(e.getValue().size() - 1).id() // Default keep latest
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * Compute SHA-256 hash of content.
     */
    private String computeSha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 algorithm not available", e);
            // Fallback to simple hash
            return String.valueOf(content.hashCode());
        }
    }

    /**
     * Truncate source ID for display.
     */
    private String truncateSource(String source) {
        if (source == null) {
            return "";
        }
        if (source.length() <= 50) {
            return source;
        }
        // Try to get filename from path
        int lastSlash = source.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < source.length() - 1) {
            return "..." + source.substring(lastSlash);
        }
        return source.substring(0, 47) + "...";
    }
}
