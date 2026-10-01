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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraphMutationJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-fact-sheet store of analysis {@link UnifiedGraph} snapshots, shared between processes
 * through one sidecar file per fact sheet.
 *
 * <p>Analysis passes (reasoning exports, learned layers and opinions, candidate materialization)
 * produce UnifiedGraph snapshots that are expensive to rebuild. The crawl manager publishes them
 * while chat and the admin console read them, so every snapshot is written through to
 * {@code <dir>/factsheet-<id>.assets}, a UnifiedGraph archive. {@code <dir>} is
 * {@code kompile.unifiedgraph.assets.dir} when set, otherwise
 * {@code <project>/data/graph/analysis-assets}: sidecars are keyed by the fact-sheet ids of the
 * project database under {@code data/}, so they are wiped together with it. The extension is
 * deliberately not {@code .kgraph} — project archives and chat-local pick the project graph by
 * scanning for {@code .kgraph} files, and a sidecar is not a project graph.</p>
 *
 * <p>Every read compares the sidecar's modification time, size and file key with the version this
 * process last loaded or wrote, so a snapshot another process rewrote or deleted is picked up on
 * the next read. Writes go through a temporary file in the same directory and an atomic move, so
 * a reader never sees a partial file. Writers in different processes are last-write-wins: import
 * locks are per process. Sidecar IO in {@link #put}, {@link #get} and {@link #snapshot} is
 * best-effort — failures are logged and never propagate; the strict methods throw.</p>
 *
 * <p>{@link #removeForFactSheet(Long)} evicts the cache entry and deletes the sidecar — invoked
 * from fact-sheet deletion cleanup so snapshots cannot outlive their sheet.</p>
 */
@Service
public class UnifiedGraphAnalysisAssetStore {

    private static final Logger log = LoggerFactory.getLogger(UnifiedGraphAnalysisAssetStore.class);

    /** System property naming the sidecar directory; overrides the project default. */
    public static final String SIDECAR_DIR_PROPERTY = "kompile.unifiedgraph.assets.dir";

    /** Sidecar file extension. Not {@code .kgraph}, so scans for project graphs skip sidecars. */
    public static final String SIDECAR_EXTENSION = ".assets";

    /** Sidecar directory; {@code null} keeps snapshots in this process only. */
    private final Path directory;

    private final ConcurrentHashMap<Long, Entry> snapshots = new ConcurrentHashMap<>();

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

    /** Sidecars under {@code kompile.unifiedgraph.assets.dir}, else {@code <project>/data/graph/analysis-assets}. */
    public UnifiedGraphAnalysisAssetStore() {
        this(defaultDirectory());
    }

    /** Sidecars under {@code directory}; {@code null} keeps snapshots in this process only. */
    public UnifiedGraphAnalysisAssetStore(Path directory) {
        this.directory = directory != null ? directory.toAbsolutePath().normalize() : null;
    }

    /** A store that never touches disk, for callers and tests that must not share snapshots. */
    public static UnifiedGraphAnalysisAssetStore inMemory() {
        return new UnifiedGraphAnalysisAssetStore(null);
    }

    /** Store (replace) the snapshot for a fact sheet; best-effort sidecar write-through. */
    public void put(long factSheetId, UnifiedGraph graph) {
        if (graph == null) {
            return;
        }
        Path sidecar = sidecarPath(factSheetId);
        Version written = null;
        if (sidecar != null) {
            try {
                written = write(sidecar, graph);
            } catch (Exception e) {
                log.warn("Analysis-asset sidecar write failed for factSheet={}: {}",
                        factSheetId, e.getMessage());
                // Serve this snapshot here until the sidecar changes on disk.
                written = versionQuietly(sidecar);
            }
        }
        snapshots.put(factSheetId, new Entry(graph, written));
    }

    /** The current snapshot for a fact sheet, reloaded when another process changed its sidecar. */
    public Optional<UnifiedGraph> get(long factSheetId) {
        Entry cached = snapshots.get(factSheetId);
        Path sidecar = sidecarPath(factSheetId);
        if (sidecar == null) {
            return cached != null ? Optional.of(cached.graph) : Optional.empty();
        }
        try {
            Version current = version(sidecar);
            if (cached != null && Objects.equals(cached.version, current)) {
                return Optional.of(cached.graph);
            }
            if (current == null) {
                // Another process deleted the sidecar (fact-sheet cleanup, import rollback).
                if (cached != null) snapshots.remove(factSheetId, cached);
                return Optional.empty();
            }
            Entry loaded = new Entry(UnifiedGraph.load(sidecar), current);
            return Optional.of(install(factSheetId, cached, loaded).graph);
        } catch (Exception e) {
            log.warn("Analysis-asset sidecar read failed for factSheet={}: {}",
                    factSheetId, e.getMessage());
            return cached != null ? Optional.of(cached.graph) : Optional.empty();
        }
    }

    /**
     * Read the current snapshot without populating the in-memory cache. Used by import rollback
     * preparation so capturing a compensating snapshot does not change live cache state.
     */
    public Optional<UnifiedGraph> snapshot(long factSheetId) {
        Entry cached = snapshots.get(factSheetId);
        Path sidecar = sidecarPath(factSheetId);
        if (sidecar == null) {
            return cached != null ? Optional.of(cached.graph) : Optional.empty();
        }
        try {
            Version current = version(sidecar);
            if (cached != null && Objects.equals(cached.version, current)) {
                return Optional.of(cached.graph);
            }
            return current != null ? Optional.of(UnifiedGraph.load(sidecar)) : Optional.empty();
        } catch (Exception e) {
            log.warn("Analysis-asset snapshot read failed for factSheet={}: {}",
                    factSheetId, e.getMessage());
            return cached != null ? Optional.of(cached.graph) : Optional.empty();
        }
    }

    /** Capture exact state without swallowing corrupt-sidecar failures. */
    public AssetState captureStrict(long factSheetId) throws IOException {
        Entry cached = snapshots.get(factSheetId);
        Path sidecar = sidecarPath(factSheetId);
        Version current = sidecar != null ? version(sidecar) : null;
        if (cached != null && (sidecar == null || Objects.equals(cached.version, current))) {
            return new AssetState(true, copy(cached.graph));
        }
        if (current == null) return AssetState.absent();
        return new AssetState(true, UnifiedGraph.load(sidecar));
    }

    /** Atomically replace the persistent sidecar before publishing the in-memory snapshot. */
    public void replaceStrict(long factSheetId, UnifiedGraph graph) throws IOException {
        if (graph == null) throw new IllegalArgumentException("graph is required");
        Path sidecar = sidecarPath(factSheetId);
        Version written = sidecar != null ? write(sidecar, graph) : null;
        snapshots.put(factSheetId, new Entry(graph, written));
    }

    /** Restore exact prior presence or absence. */
    public void restoreStrict(long factSheetId, AssetState state) throws IOException {
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

    /** Cache a snapshot loaded from the sidecar unless this process replaced the entry meanwhile. */
    private Entry install(long factSheetId, Entry expected, Entry loaded) {
        if (expected == null) {
            Entry raced = snapshots.putIfAbsent(factSheetId, loaded);
            return raced != null ? raced : loaded;
        }
        if (snapshots.replace(factSheetId, expected, loaded)) {
            return loaded;
        }
        Entry raced = snapshots.get(factSheetId);
        return raced != null ? raced : loaded;
    }

    private Path sidecarPath(long factSheetId) {
        return directory != null ? directory.resolve("factsheet-" + factSheetId + SIDECAR_EXTENSION) : null;
    }

    private static Path defaultDirectory() {
        String configured = System.getProperty(SIDECAR_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return KompileHome.resolvedProjectDirectory().toPath()
                .resolve("data").resolve("graph").resolve("analysis-assets");
    }

    /**
     * Write through a temporary file in the sidecar's directory and move it into place. The version
     * is read from the temporary file before the move, so a replacement by another process right
     * after the move still reads as a change.
     */
    private static Version write(Path sidecar, UnifiedGraph graph) throws IOException {
        Path parent = sidecar.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "." + sidecar.getFileName() + "-", ".tmp");
        try {
            graph.save(temporary);
            Version written = version(temporary);
            try {
                Files.move(temporary, sidecar,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, sidecar, StandardCopyOption.REPLACE_EXISTING);
                written = version(sidecar);
            }
            UnifiedGraphMutationJournal.clear(sidecar);
            return written;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The file's current version, or {@code null} when it does not exist. */
    private static Version version(Path file) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException missing) {
            return null;
        }
        if (!attributes.isRegularFile()) {
            throw new IOException("Analysis asset is not a regular file: " + file);
        }
        return new Version(attributes.lastModifiedTime(), attributes.size(), attributes.fileKey());
    }

    private static Version versionQuietly(Path file) {
        try {
            return version(file);
        } catch (IOException e) {
            return null;
        }
    }

    private static UnifiedGraph copy(UnifiedGraph graph) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        graph.save(out);
        return UnifiedGraph.load(new ByteArrayInputStream(out.toByteArray()));
    }

    /** A snapshot and the sidecar version it matches ({@code null}: none). Compared by identity. */
    private static final class Entry {
        private final UnifiedGraph graph;
        private final Version version;

        private Entry(UnifiedGraph graph, Version version) {
            this.graph = graph;
            this.version = version;
        }
    }

    /** One write of a sidecar: a rewrite or replacement changes at least one component. */
    private record Version(FileTime modified, long size, Object fileKey) {
    }
}
