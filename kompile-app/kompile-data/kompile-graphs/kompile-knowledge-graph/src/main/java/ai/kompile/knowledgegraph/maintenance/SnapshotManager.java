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
import ai.kompile.knowledgegraph.grounding.GroundingResetPort;
import ai.kompile.knowledgegraph.io.GraphEmbeddingSidecar;
import ai.kompile.knowledgegraph.io.GraphIOService;
import ai.kompile.knowledgegraph.io.model.ImportResult;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
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

    /**
     * Base directory for snapshots. When the app runs inside a project
     * ({@code kompile.data.dir} set), snapshots live under the versioned project
     * tree ({@code <dataDir>/data/graph/snapshots}) so they travel with a git
     * clone; otherwise they fall back to {@code ~/.kompile/graph-snapshots}.
     */
    @Value("${kompile.data.dir:}")
    private String dataDir;

    private final ObjectMapper objectMapper;
    private final GraphIOService graphIOService;
    private final KnowledgeGraphService knowledgeGraphService;

    public SnapshotManager(ObjectMapper objectMapper,
                           GraphIOService graphIOService,
                           KnowledgeGraphService knowledgeGraphService) {
        this.objectMapper = objectMapper;
        this.graphIOService = graphIOService;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /**
     * [M-9] The binary embedding sidecar. Optional — when present, snapshots persist KG embeddings
     * alongside the structure dump so a restore reattaches them instead of forcing recomputation.
     */
    @Autowired(required = false)
    private GraphEmbeddingSidecar embeddingSidecar;

    /**
     * Optional — wired when {@code kompile-graph-change-tracking} is on the classpath.
     * After a snapshot restore, the in-memory KB state is stale (the underlying graph changed
     * completely); this port evicts the cached state and schedules a fresh re-ground.
     */
    @Autowired(required = false)
    private GroundingResetPort groundingResetPort;

    private Path snapshotBaseDir() {
        if (dataDir != null && !dataDir.isBlank()) {
            return Path.of(dataDir, "data", "graph", "snapshots");
        }
        return Path.of(System.getProperty("user.home"), ".kompile", "graph-snapshots");
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

        // Collect IDs from the @Primary live store (matrix/vector path) via the service layer.
        // PREVIOUSLY used nodeRepository.findActiveEntities / edgeRepository.countActiveEdges — those
        // are JPA-only and are always empty on the live matrix path (H-6 fix).
        List<String> nodeIds = knowledgeGraphService.getNodesInFactSheet(factSheetId)
                .stream()
                .filter(n -> !Boolean.TRUE.equals(n.getStale()))
                .map(ai.kompile.knowledgegraph.domain.GraphNode::getNodeId)
                .toList();
        long activeNodeCount = nodeIds.size();
        long activeEdgeCount = knowledgeGraphService.getEdgesInFactSheet(factSheetId)
                .stream()
                .filter(e -> !Boolean.TRUE.equals(e.getStale()))
                .count();

        // Build lightweight manifest
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("snapshotId", snapshotId);
        manifest.put("factSheetId", factSheetId);
        manifest.put("createdAt", now.toString());
        manifest.put("reason", reason);
        manifest.put("entityCount", (int) activeNodeCount);
        manifest.put("relationshipCount", (int) activeEdgeCount);
        manifest.put("communityCount", 0);
        manifest.put("exportFormat", "json-full");
        manifest.put("nodeIds", nodeIds);

        // Ensure directory exists
        Path dir = snapshotBaseDir().resolve(String.valueOf(factSheetId));
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

        // Full, restorable graph dump alongside the manifest (reuses the portability exporter, so
        // restore is a lossless re-import). Kept in a separate file so listing stays lightweight.
        Path graphFile = dir.resolve(snapshotId + ".graph.json");
        try (OutputStream os = Files.newOutputStream(graphFile)) {
            // [L-7] Stream the dump straight to disk so snapshotting a large fact sheet does not build
            // the whole PortableGraph + serialized byte[] in memory.
            graphIOService.exportGraphStreaming("json", factSheetId, os);
        } catch (Exception e) {
            log.error("Failed to write snapshot graph dump {}: {}", graphFile, e.getMessage(), e);
            throw new RuntimeException("Cannot write snapshot graph dump: " + graphFile, e);
        }

        // [M-9] Persist KG embeddings (binary sidecar) next to the structure dump so a restore
        // reattaches them rather than silently dropping them — the structure JSON is embedding-free.
        // Best-effort: a fact sheet with no embeddings simply produces no sidecar file.
        if (embeddingSidecar != null) {
            try {
                byte[] emb = embeddingSidecar.export(factSheetId);
                if (emb != null && emb.length > 0) {
                    Files.write(dir.resolve(snapshotId + ".embeddings.bin"), emb);
                }
            } catch (Exception e) {
                log.warn("Failed to write snapshot embeddings for {}: {}", snapshotId, e.getMessage());
            }
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
                "json-full",
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
        Path dir = snapshotBaseDir().resolve(String.valueOf(factSheetId));
        if (!Files.exists(dir)) {
            log.debug("No snapshot directory found for factSheet={}", factSheetId);
            return List.of();
        }

        File[] files = dir.toFile().listFiles(
                f -> f.getName().endsWith(".json") && !f.getName().endsWith(".graph.json"));
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
                String format = (String) manifest.getOrDefault("exportFormat", "json-full");

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

    /**
     * Restore a fact sheet's graph to a prior snapshot: clear the current fact-sheet graph and
     * re-import the snapshot's full dump (the Phase-1 portability importer). Structure + metadata
     * + provenance are restored; KG embeddings are reattached from the snapshot's binary sidecar
     * when present ([M-9], so they no longer need recomputing). Anything created after the snapshot
     * is discarded — that's the rollback.
     *
     * @return the restored snapshot's metadata, or {@code null} if it couldn't be resolved
     */
    public GraphSnapshot restoreSnapshot(String snapshotId) {
        Path base = snapshotBaseDir();
        Path graphFile = null;
        Long factSheetId = null;
        if (Files.isDirectory(base)) {
            try (var subdirs = Files.list(base)) {
                for (Path sub : subdirs.filter(Files::isDirectory).toList()) {
                    Path candidate = sub.resolve(snapshotId + ".graph.json");
                    if (Files.isRegularFile(candidate)) {
                        graphFile = candidate;
                        try {
                            factSheetId = Long.valueOf(sub.getFileName().toString());
                        } catch (NumberFormatException ignored) {
                            // Non-numeric snapshot subdirectory; leave factSheetId null.
                        }
                        break;
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Cannot scan snapshot directory: " + base, e);
            }
        }
        if (graphFile == null) {
            throw new IllegalArgumentException("No restorable snapshot dump for id=" + snapshotId);
        }

        byte[] dump;
        try {
            dump = Files.readAllBytes(graphFile);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read snapshot dump: " + graphFile, e);
        }

        // Revert: drop the current graph for this fact sheet, then re-import the snapshot dump.
        if (factSheetId != null) {
            knowledgeGraphService.deleteByFactSheetId(factSheetId);
        }
        try {
            ImportResult result = graphIOService.importGraph("json", dump, null);
            log.info("Restored snapshot {} (factSheet={}): {} nodes, {} edges restored",
                    snapshotId, factSheetId, result.nodesCreated(), result.edgesCreated());
        } catch (Exception e) {
            throw new RuntimeException("Failed to restore snapshot " + snapshotId, e);
        }

        // [M-9] Reattach KG embeddings from the snapshot's sidecar so the restore preserves them
        // instead of forcing recomputation. Best-effort: absent file / no live store → skipped.
        if (embeddingSidecar != null && factSheetId != null) {
            Path embFile = graphFile.resolveSibling(snapshotId + ".embeddings.bin");
            if (Files.isRegularFile(embFile)) {
                try {
                    int applied = embeddingSidecar.importInto(factSheetId, Files.readAllBytes(embFile));
                    if (applied > 0) {
                        log.info("Reattached {} KG embeddings for restored snapshot {}", applied, snapshotId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to reattach snapshot embeddings for {}: {}", snapshotId, e.getMessage());
                }
            }
        }

        // Invalidate the in-memory KB grounding state (facts/inferences are now stale because
        // the entire graph was replaced) and schedule a fresh re-ground asynchronously.
        // This is done AFTER embedding reattachment so the cascade sees the full restored graph.
        if (factSheetId != null && groundingResetPort != null) {
            groundingResetPort.invalidateAndReground(factSheetId, "restore:" + snapshotId);
        }

        final String wantedId = snapshotId;
        return factSheetId == null ? null : listSnapshots(factSheetId).stream()
                .filter(s -> wantedId.equals(s.snapshotId()))
                .findFirst()
                .orElse(null);
    }
}
