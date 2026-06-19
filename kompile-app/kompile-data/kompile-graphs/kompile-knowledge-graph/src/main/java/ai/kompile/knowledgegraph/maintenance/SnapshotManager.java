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
package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.core.graphrag.maintenance.model.GraphSnapshot;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages lightweight graph snapshots by storing metadata (node/edge ID lists and counts)
 * to disk under ~/.kompile/graph-snapshots/{factSheetId}/.
 */
@Slf4j
@Component
public class SnapshotManager {

    private static final String SNAPSHOT_DIR =
            System.getProperty("user.home") + "/.kompile/graph-snapshots";

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final ObjectMapper objectMapper;

    public SnapshotManager(GraphNodeRepository nodeRepository,
                           GraphEdgeRepository edgeRepository,
                           ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a snapshot by collecting active node/edge IDs and counts for the given fact sheet,
     * then persisting a lightweight JSON manifest to disk.
     *
     * @param factSheetId the fact sheet whose graph is being snapshotted
     * @param reason      human-readable reason for the snapshot
     * @return a {@link GraphSnapshot} record describing the persisted snapshot
     */
    public GraphSnapshot createSnapshot(Long factSheetId, String reason) {
        String snapshotId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        // Collect IDs
        List<String> nodeIds = nodeRepository.findActiveEntities(factSheetId)
                .stream()
                .map(n -> n.getNodeId())
                .toList();
        long activeEdgeCount = edgeRepository.countActiveEdges(factSheetId);
        long activeNodeCount = nodeRepository.countActiveNodes(factSheetId);

        // Build lightweight manifest
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("snapshotId", snapshotId);
        manifest.put("factSheetId", factSheetId);
        manifest.put("createdAt", now.toString());
        manifest.put("reason", reason);
        manifest.put("entityCount", (int) activeNodeCount);
        manifest.put("relationshipCount", (int) activeEdgeCount);
        manifest.put("communityCount", 0);
        manifest.put("exportFormat", "json-manifest");
        manifest.put("nodeIds", nodeIds);

        // Ensure directory exists
        Path dir = Path.of(SNAPSHOT_DIR, String.valueOf(factSheetId));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Failed to create snapshot directory {}: {}", dir, e.getMessage(), e);
            throw new RuntimeException("Cannot create snapshot directory: " + dir, e);
        }

        Path snapshotFile = dir.resolve(snapshotId + ".json");
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(snapshotFile.toFile(), manifest);
        } catch (IOException e) {
            log.error("Failed to write snapshot file {}: {}", snapshotFile, e.getMessage(), e);
            throw new RuntimeException("Cannot write snapshot: " + snapshotFile, e);
        }

        log.info("Created snapshot {} for factSheet={}, nodes={}, edges={}, reason='{}'",
                snapshotId, factSheetId, activeNodeCount, activeEdgeCount, reason);

        return new GraphSnapshot(
                snapshotId,
                factSheetId,
                now,
                reason,
                (int) activeNodeCount,
                (int) activeEdgeCount,
                0,
                "json-manifest",
                snapshotFile.toString()
        );
    }

    /**
     * List all snapshots for the given fact sheet by reading the manifest files from disk.
     *
     * @param factSheetId the fact sheet ID
     * @return list of {@link GraphSnapshot} records, newest first
     */
    public List<GraphSnapshot> listSnapshots(Long factSheetId) {
        Path dir = Path.of(SNAPSHOT_DIR, String.valueOf(factSheetId));
        if (!Files.exists(dir)) {
            log.debug("No snapshot directory found for factSheet={}", factSheetId);
            return List.of();
        }

        File[] files = dir.toFile().listFiles(f -> f.getName().endsWith(".json"));
        if (files == null || files.length == 0) {
            return List.of();
        }

        List<GraphSnapshot> snapshots = new ArrayList<>();
        for (File file : files) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> manifest = objectMapper.readValue(file, Map.class);
                String snapshotId = (String) manifest.getOrDefault("snapshotId", file.getName().replace(".json", ""));
                Instant createdAt = Instant.parse((String) manifest.getOrDefault("createdAt", Instant.EPOCH.toString()));
                String reason = (String) manifest.getOrDefault("reason", "");
                int entityCount = ((Number) manifest.getOrDefault("entityCount", 0)).intValue();
                int relCount = ((Number) manifest.getOrDefault("relationshipCount", 0)).intValue();
                int communityCount = ((Number) manifest.getOrDefault("communityCount", 0)).intValue();
                String format = (String) manifest.getOrDefault("exportFormat", "json-manifest");

                snapshots.add(new GraphSnapshot(
                        snapshotId,
                        factSheetId,
                        createdAt,
                        reason,
                        entityCount,
                        relCount,
                        communityCount,
                        format,
                        file.getAbsolutePath()
                ));
            } catch (IOException e) {
                log.warn("Failed to parse snapshot file {}: {}", file.getName(), e.getMessage());
            }
        }

        // Sort newest first
        snapshots.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        log.debug("Listed {} snapshots for factSheet={}", snapshots.size(), factSheetId);
        return snapshots;
    }
}
