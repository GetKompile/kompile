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
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.grounding.GroundingResetPort;
import ai.kompile.knowledgegraph.io.GraphEmbeddingSidecar;
import ai.kompile.knowledgegraph.io.GraphIOService;
import ai.kompile.knowledgegraph.io.model.ImportResult;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maintains restorable graph snapshots as native {@code .kgraph} archives.
 *
 * <p>New snapshots are one property-complete UnifiedGraph archive. Legacy JSON manifest, graph
 * dump, and embedding-sidecar triplets remain discoverable and restorable; a successful legacy
 * restore writes a native archive alongside the original files without deleting them.</p>
 */
@Slf4j
@Component
public class SnapshotManager {

    private static final String EXTENSION = ".kgraph";
    private static final String SNAPSHOT_REASON = "kompile.snapshot.reason";
    private static final String SNAPSHOT_CREATED_AT = "kompile.snapshot.createdAt";
    private static final String LEGACY_MANIFEST_SUFFIX = ".json";
    private static final String LEGACY_GRAPH_SUFFIX = ".graph.json";
    private static final String LEGACY_EMBEDDING_SUFFIX = ".embeddings.bin";

    @Value("${kompile.data.dir:}")
    private String dataDir;

    private final UnifiedGraphBridge bridge;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired(required = false)
    private GraphIOService graphIOService;

    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private GraphEmbeddingSidecar embeddingSidecar;

    @Autowired(required = false)
    private GroundingResetPort groundingResetPort;

    public SnapshotManager(UnifiedGraphBridge bridge) {
        this.bridge = bridge;
    }

    public GraphSnapshot createSnapshot(Long factSheetId, String reason) {
        if (factSheetId == null) {
            throw new IllegalArgumentException("factSheetId is required");
        }
        String snapshotId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Path directory = sheetDirectory(factSheetId);
        Path file = directory.resolve(snapshotId + EXTENSION);
        try {
            Files.createDirectories(directory);
            UnifiedGraph graph = bridge.export(factSheetId)
                    .meta(SNAPSHOT_REASON, reason == null ? "" : reason)
                    .meta(SNAPSHOT_CREATED_AT, now.toString());
            graph.save(file);
            return snapshotMetadata(file, factSheetId, graph, now);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write graph snapshot: " + file, e);
        }
    }

    public List<GraphSnapshot> listSnapshots(Long factSheetId) {
        if (factSheetId == null) {
            throw new IllegalArgumentException("factSheetId is required");
        }
        Map<String, GraphSnapshot> snapshots = new LinkedHashMap<>();
        for (Path candidateDirectory : snapshotDirectories(factSheetId)) {
            if (!Files.isDirectory(candidateDirectory)) continue;
            try (var files = Files.list(candidateDirectory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                        .sorted(Comparator.comparing(Path::toString)).toList()) {
                    try {
                        GraphSnapshot metadata = snapshotMetadata(
                                file, factSheetId, UnifiedGraph.load(file), null);
                        snapshots.put(metadata.snapshotId(), metadata);
                    } catch (IOException e) {
                        log.warn("Skipping unreadable graph snapshot {}: {}", file, e.getMessage());
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot list graph snapshots in " + candidateDirectory, e);
            }
        }
        for (Path candidateDirectory : snapshotDirectories(factSheetId)) {
            if (!Files.isDirectory(candidateDirectory)) continue;
            try (var files = Files.list(candidateDirectory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(SnapshotManager::isLegacyManifest)
                        .sorted(Comparator.comparing(Path::toString)).toList()) {
                    try {
                        GraphSnapshot metadata = legacySnapshotMetadata(file, factSheetId);
                        snapshots.putIfAbsent(metadata.snapshotId(), metadata);
                    } catch (IOException | RuntimeException e) {
                        log.warn("Skipping unreadable legacy graph snapshot {}: {}", file, e.getMessage());
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot list legacy graph snapshots in "
                        + candidateDirectory, e);
            }
        }
        List<GraphSnapshot> ordered = new ArrayList<>(snapshots.values());
        ordered.sort(Comparator.comparing(GraphSnapshot::createdAt).reversed()
                .thenComparing(GraphSnapshot::snapshotId));
        return List.copyOf(ordered);
    }

    public GraphSnapshot restoreSnapshot(String snapshotId) {
        ResolvedSnapshot resolved = resolveSnapshot(snapshotId);
        Path snapshot = resolved.path();
        Long factSheetId = resolved.factSheetId();
        if (factSheetId == null) {
            throw new IllegalArgumentException("Snapshot directory is not a fact-sheet scope: " + snapshot);
        }
        if (resolved.legacy()) {
            return restoreLegacySnapshot(resolved);
        }
        try {
            UnifiedGraph graph = UnifiedGraph.load(snapshot);
            UnifiedGraphBridge.ImportSummary summary = bridge.importGraph(graph, factSheetId);
            if (groundingResetPort != null) {
                groundingResetPort.invalidateAndReground(factSheetId, "restore:" + snapshotId);
            }
            log.info("Restored native graph snapshot {} for factSheet={}: {} nodes, {} edges",
                    snapshotId, factSheetId, summary.nodes(), summary.edges());
            return snapshotMetadata(snapshot, factSheetId, graph, null);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read graph snapshot: " + snapshot, e);
        }
    }

    private GraphSnapshot restoreLegacySnapshot(ResolvedSnapshot resolved) {
        if (graphIOService == null || knowledgeGraphService == null) {
            throw new IllegalStateException("Legacy snapshot restore services are not available");
        }
        Path graphFile = resolved.path();
        Path manifestFile = graphFile.resolveSibling(resolved.snapshotId() + LEGACY_MANIFEST_SUFFIX);
        Long factSheetId = resolved.factSheetId();
        UnifiedGraph backup = bridge.export(factSheetId);
        try {
            byte[] dump = Files.readAllBytes(graphFile);
            objectMapper.readTree(dump);
            knowledgeGraphService.deleteByFactSheetId(factSheetId);
            ImportResult result = graphIOService.importGraph("json", dump, null, factSheetId);

            Path embeddingFile = graphFile.resolveSibling(
                    resolved.snapshotId() + LEGACY_EMBEDDING_SUFFIX);
            if (Files.isRegularFile(embeddingFile)) {
                if (embeddingSidecar == null) {
                    throw new IllegalStateException("Legacy snapshot contains embeddings but the sidecar is unavailable");
                }
                int applied = embeddingSidecar.importInto(factSheetId, Files.readAllBytes(embeddingFile));
                if (applied == 0 && Files.size(embeddingFile) > 0) {
                    throw new IllegalStateException("Legacy snapshot embeddings could not be restored");
                }
            }

            GraphSnapshot legacyMetadata = legacySnapshotMetadata(manifestFile, factSheetId);
            UnifiedGraph migrated = bridge.export(factSheetId)
                    .meta(SNAPSHOT_REASON, legacyMetadata.reason())
                    .meta(SNAPSHOT_CREATED_AT, legacyMetadata.createdAt().toString());
            Path migratedFile = graphFile.resolveSibling(resolved.snapshotId() + EXTENSION);
            migrated.save(migratedFile);
            if (groundingResetPort != null) {
                groundingResetPort.invalidateAndReground(factSheetId, "restore:" + resolved.snapshotId());
            }
            log.info("Restored legacy snapshot {} for factSheet={}: {} nodes, {} edges; wrote {}",
                    resolved.snapshotId(), factSheetId, result.nodesCreated(), result.edgesCreated(), migratedFile);
            return snapshotMetadata(migratedFile, factSheetId, migrated, legacyMetadata.createdAt());
        } catch (Exception failure) {
            try {
                bridge.importGraph(backup, factSheetId);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("Failed to restore legacy snapshot "
                    + resolved.snapshotId(), failure);
        }
    }

    private GraphSnapshot snapshotMetadata(Path file, Long factSheetId, UnifiedGraph graph, Instant createdAtHint) {
        Instant createdAt = createdAt(graph, file, createdAtHint);
        String snapshotId = stripExtension(file.getFileName().toString());
        Object reasonValue = graph.meta().get(SNAPSHOT_REASON);
        String reason = reasonValue == null ? "" : String.valueOf(reasonValue);
        return new GraphSnapshot(
                snapshotId,
                factSheetId,
                createdAt,
                reason,
                graph.entities().size(),
                graph.relations().size(),
                0,
                "kgraph",
                file.toAbsolutePath().toString());
    }

    private static Instant createdAt(UnifiedGraph graph, Path file, Instant hint) {
        Object value = graph.meta().get(SNAPSHOT_CREATED_AT);
        if (value != null) {
            try {
                return Instant.parse(String.valueOf(value));
            } catch (RuntimeException ignored) {
                // Fall through to the filesystem timestamp.
            }
        }
        if (hint != null) {
            return hint;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return attributes.lastModifiedTime().toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private ResolvedSnapshot resolveSnapshot(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()
                || snapshotId.contains("/") || snapshotId.contains("\\")
                || snapshotId.contains("..")) {
            throw new IllegalArgumentException("Invalid snapshotId: " + snapshotId);
        }
        for (Path root : snapshotRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (var directories = Files.list(root)) {
                for (Path directory : directories.filter(Files::isDirectory).toList()) {
                    Long factSheetId = factSheetId(directory);
                    if (factSheetId == null) continue;
                    Path nativeArchive = directory.resolve(snapshotId + EXTENSION);
                    if (Files.isRegularFile(nativeArchive)) {
                        return new ResolvedSnapshot(snapshotId, nativeArchive, factSheetId, false);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot scan graph snapshots in " + root, e);
            }
        }
        for (Path root : snapshotRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (var directories = Files.list(root)) {
                for (Path directory : directories.filter(Files::isDirectory).toList()) {
                    Long factSheetId = factSheetId(directory);
                    if (factSheetId == null) continue;
                    Path legacyGraph = directory.resolve(snapshotId + LEGACY_GRAPH_SUFFIX);
                    if (Files.isRegularFile(legacyGraph)) {
                        return new ResolvedSnapshot(snapshotId, legacyGraph, factSheetId, true);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot scan legacy graph snapshots in " + root, e);
            }
        }
        throw new IllegalArgumentException("No graph snapshot exists for id=" + snapshotId);
    }

    @SuppressWarnings("unchecked")
    private GraphSnapshot legacySnapshotMetadata(Path manifestFile, Long factSheetId) throws IOException {
        Map<String, Object> manifest = objectMapper.readValue(manifestFile.toFile(), Map.class);
        String fileName = manifestFile.getFileName().toString();
        String snapshotId = String.valueOf(manifest.getOrDefault(
                "snapshotId", fileName.substring(0, fileName.length() - LEGACY_MANIFEST_SUFFIX.length())));
        Instant createdAt = Instant.parse(String.valueOf(
                manifest.getOrDefault("createdAt", Instant.EPOCH.toString())));
        return new GraphSnapshot(
                snapshotId,
                factSheetId,
                createdAt,
                String.valueOf(manifest.getOrDefault("reason", "")),
                ((Number) manifest.getOrDefault("entityCount", 0)).intValue(),
                ((Number) manifest.getOrDefault("relationshipCount", 0)).intValue(),
                ((Number) manifest.getOrDefault("communityCount", 0)).intValue(),
                String.valueOf(manifest.getOrDefault("exportFormat", "json-full")),
                manifestFile.toAbsolutePath().toString());
    }

    private Path snapshotBaseDir() {
        if (dataDir != null && !dataDir.isBlank()) {
            return Path.of(dataDir, "graph-snapshots");
        }
        return Path.of(System.getProperty("user.home"), ".kompile", "graph-snapshots");
    }

    private Path sheetDirectory(Long factSheetId) {
        return snapshotBaseDir().resolve("factsheet-" + factSheetId);
    }

    private List<Path> snapshotRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(snapshotBaseDir());
        if (dataDir != null && !dataDir.isBlank()) {
            Path legacy = Path.of(dataDir, "data", "graph", "snapshots");
            if (!legacy.equals(snapshotBaseDir())) roots.add(legacy);
        }
        return List.copyOf(roots);
    }

    private List<Path> snapshotDirectories(Long factSheetId) {
        List<Path> directories = new ArrayList<>();
        for (Path root : snapshotRoots()) {
            directories.add(root.resolve("factsheet-" + factSheetId));
            directories.add(root.resolve(String.valueOf(factSheetId)));
        }
        return directories.stream().distinct().toList();
    }

    private static Long factSheetId(Path directory) {
        String name = directory.getFileName().toString();
        String prefix = "factsheet-";
        try {
            return Long.valueOf(name.startsWith(prefix) ? name.substring(prefix.length()) : name);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripExtension(String fileName) {
        return fileName.endsWith(EXTENSION)
                ? fileName.substring(0, fileName.length() - EXTENSION.length())
                : fileName;
    }

    private static boolean isLegacyManifest(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(LEGACY_MANIFEST_SUFFIX)
                && !name.endsWith(LEGACY_GRAPH_SUFFIX);
    }

    private record ResolvedSnapshot(
            String snapshotId, Path path, Long factSheetId, boolean legacy) { }
}
