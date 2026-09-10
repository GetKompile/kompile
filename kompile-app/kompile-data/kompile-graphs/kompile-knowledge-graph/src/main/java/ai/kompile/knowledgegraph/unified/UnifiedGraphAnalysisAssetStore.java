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
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-fact-sheet cache of analysis {@link UnifiedGraph} snapshots with an optional
 * write-through {@code .kgraph} sidecar.
 *
 * <p>Analysis passes (reasoning exports, learned layers, candidate materialization) produce
 * UnifiedGraph snapshots that are expensive to rebuild. This store keeps the latest snapshot per
 * fact sheet in memory and, when {@code kompile.unifiedgraph.assets.dir} names a directory,
 * writes each snapshot through to {@code <dir>/factsheet-<id>.kgraph} so it survives restarts.
 * All sidecar IO is best-effort: failures are logged and never propagate.</p>
 *
 * <p>{@link #removeForFactSheet(Long)} evicts the cache entry and deletes the sidecar — invoked
 * from fact-sheet deletion cleanup so snapshots cannot outlive their sheet.</p>
 */
@Service
public class UnifiedGraphAnalysisAssetStore {

    private static final Logger log = LoggerFactory.getLogger(UnifiedGraphAnalysisAssetStore.class);

    /** System property naming the sidecar directory; unset = in-memory only. */
    public static final String SIDECAR_DIR_PROPERTY = "kompile.unifiedgraph.assets.dir";

    private final ConcurrentHashMap<Long, UnifiedGraph> snapshots = new ConcurrentHashMap<>();

    /** Stable, transport-friendly summary used by graph tools and REST responses. */
    public record AssetSummary(
            String graphId,
            int vectorLayers,
            int entityOpinions,
            int relationOpinions,
            int weightMaps,
            int artifacts,
            boolean present) {
    }

    /** Exact prior state used by managed import compensation. */
    public record AssetState(boolean present, UnifiedGraph graph) {
        public static AssetState absent() {
            return new AssetState(false, null);
        }
    }

    /** Store (replace) the snapshot for a fact sheet; best-effort sidecar write-through. */
    public void put(long factSheetId, UnifiedGraph graph) {
        if (graph == null) {
            return;
        }
        snapshots.put(factSheetId, graph);
        Path sidecar = sidecarPath(factSheetId);
        if (sidecar == null) {
            return;
        }
        try {
            Files.createDirectories(sidecar.getParent());
            graph.save(sidecar);
        } catch (Exception e) {
            log.warn("Analysis-asset sidecar write failed for factSheet={}: {}",
                    factSheetId, e.getMessage());
        }
    }

    /** The cached snapshot for a fact sheet, if any. */
    public Optional<UnifiedGraph> get(long factSheetId) {
        UnifiedGraph cached = snapshots.get(factSheetId);
        if (cached != null) {
            return Optional.of(cached);
        }

        Path sidecar = sidecarPath(factSheetId);
        if (sidecar == null || !Files.isRegularFile(sidecar)) {
            return Optional.empty();
        }
        try {
            UnifiedGraph loaded = UnifiedGraph.load(sidecar);
            UnifiedGraph prior = snapshots.putIfAbsent(factSheetId, loaded);
            return Optional.of(prior != null ? prior : loaded);
        } catch (Exception e) {
            log.warn("Analysis-asset sidecar read failed for factSheet={}: {}",
                    factSheetId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Read the current snapshot without populating the in-memory cache. Used by import rollback
     * preparation so capturing a compensating snapshot does not change live cache state.
     */
    public Optional<UnifiedGraph> snapshot(long factSheetId) {
        UnifiedGraph cached = snapshots.get(factSheetId);
        if (cached != null) return Optional.of(cached);
        Path sidecar = sidecarPath(factSheetId);
        if (sidecar == null || !Files.isRegularFile(sidecar)) return Optional.empty();
        try {
            return Optional.of(UnifiedGraph.load(sidecar));
        } catch (Exception e) {
            log.warn("Analysis-asset snapshot read failed for factSheet={}: {}",
                    factSheetId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Capture exact state without swallowing corrupt-sidecar failures. */
    public AssetState captureStrict(long factSheetId) throws java.io.IOException {
        UnifiedGraph cached = snapshots.get(factSheetId);
        if (cached != null) return new AssetState(true, copy(cached));
        Path sidecar = sidecarPath(factSheetId);
        if (sidecar == null || !Files.exists(sidecar)) return AssetState.absent();
        if (!Files.isRegularFile(sidecar)) {
            throw new java.io.IOException("Analysis asset is not a regular file: " + sidecar);
        }
        return new AssetState(true, UnifiedGraph.load(sidecar));
    }

    /** Atomically replace the persistent sidecar before publishing the in-memory snapshot. */
    public void replaceStrict(long factSheetId, UnifiedGraph graph) throws java.io.IOException {
        if (graph == null) throw new IllegalArgumentException("graph is required");
        Path sidecar = sidecarPath(factSheetId);
        if (sidecar != null) {
            Files.createDirectories(sidecar.getParent());
            Path temporary = Files.createTempFile(sidecar.getParent(), sidecar.getFileName().toString(), ".tmp");
            try {
                graph.save(temporary);
                try {
                    Files.move(temporary, sidecar,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, sidecar, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        snapshots.put(factSheetId, graph);
    }

    /** Restore exact prior presence or absence. */
    public void restoreStrict(long factSheetId, AssetState state) throws java.io.IOException {
        if (state != null && state.present()) {
            replaceStrict(factSheetId, state.graph());
            return;
        }
        Path sidecar = sidecarPath(factSheetId);
        if (sidecar != null) Files.deleteIfExists(sidecar);
        snapshots.remove(factSheetId);
    }

    /** Summarize the currently cached or persisted analysis asset for a fact sheet. */
    public AssetSummary summary(long factSheetId) {
        Optional<UnifiedGraph> snapshot = get(factSheetId);
        if (snapshot.isEmpty()) {
            return new AssetSummary("factsheet_" + factSheetId, 0, 0, 0, 0, 0, false);
        }
        UnifiedGraph graph = snapshot.get();
        String graphId = graph.graphId() != null ? graph.graphId() : "factsheet_" + factSheetId;
        return new AssetSummary(
                graphId,
                graph.vectorLayers().size(),
                graph.entityOpinions().size(),
                graph.relationOpinions().size(),
                graph.weightMaps().size(),
                graph.artifacts().size(),
                true);
    }

    /** Evict the snapshot and delete its sidecar (fact-sheet deletion cleanup). */
    public void removeForFactSheet(Long factSheetId) {
        if (factSheetId == null) {
            return;
        }
        snapshots.remove(factSheetId);
        Path sidecar = sidecarPath(factSheetId);
        if (sidecar == null) {
            return;
        }
        try {
            Files.deleteIfExists(sidecar);
        } catch (Exception e) {
            log.warn("Analysis-asset sidecar delete failed for factSheet={}: {}",
                    factSheetId, e.getMessage());
        }
    }

    private static Path sidecarPath(long factSheetId) {
        String directory = System.getProperty(SIDECAR_DIR_PROPERTY);
        if (directory == null || directory.isBlank()) {
            return null;
        }
        return Path.of(directory, "factsheet-" + factSheetId + ".kgraph");
    }

    private static UnifiedGraph copy(UnifiedGraph graph) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        graph.save(out);
        return UnifiedGraph.load(new java.io.ByteArrayInputStream(out.toByteArray()));
    }
}
