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
package ai.kompile.app.project;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import ai.kompile.project.KompileProjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes the runtime knowledge graph as the single native project artifact.
 *
 * <p>Every scope is stored as a versioned {@code .kgraph}: one file per fact sheet
 * plus {@code project.kgraph} for the complete graph. The old JSON, named-graph, and
 * embedding sidecar files are deliberately removed; the native archive contains all
 * node properties, relations, schema, vectors, and learned state.</p>
 */
@Service
public class ProjectGraphPortabilityService {

    private static final Logger log = LoggerFactory.getLogger(ProjectGraphPortabilityService.class);
    private static final String GRAPH_DIR = "data/graph";

    private final KompileProjectStore store = new KompileProjectStore();

    @Autowired(required = false)
    private FactSheetService factSheetService;
    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;
    @Autowired(required = false)
    private UnifiedGraphBridge unifiedGraphBridge;

    private final AtomicBoolean rehydrating = new AtomicBoolean();

    @Value("${kompile.project.root:}")
    private String configuredRoot;

    /** Export every fact-sheet graph and the complete project graph into {@code <root>/data/graph}. */
    public void exportAllGraphs(Path root) {
        if (root == null || unifiedGraphBridge == null) {
            return;
        }
        Path graphDir = root.resolve(GRAPH_DIR);
        try {
            Files.createDirectories(graphDir);
            List<Long> factSheetIds = factSheetService == null ? List.of()
                    : factSheetService.getAllSheets().stream()
                            .map(FactSheet::getId)
                            .filter(id -> id != null)
                            .sorted()
                            .toList();
            removeLegacyArtifacts(graphDir);
            removeStaleScopedGraphs(graphDir, new HashSet<>(factSheetIds));
            for (Long factSheetId : factSheetIds) {
                exportUnifiedScope(graphDir.resolve("factsheet-" + factSheetId + ".kgraph"), factSheetId);
            }
            exportUnifiedScope(graphDir.resolve("project.kgraph"), null);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish native project graphs under " + graphDir, e);
        }
    }

    private void exportUnifiedScope(Path file, Long factSheetId) throws Exception {
        Path temporary = null;
        try {
            UnifiedGraph graph = unifiedGraphBridge.export(factSheetId);
            if (graph == null || (graph.entities().isEmpty() && graph.relations().isEmpty())) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            graph.save(temporary);
            moveReplacing(temporary, file);
            temporary = null;
            log.debug("Exported native graph {} ({} entities, {} relations)",
                    file.getFileName(), graph.entities().size(), graph.relations().size());
        } finally {
            if (temporary != null) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void removeLegacyArtifacts(Path graphDir) throws IOException {
        Files.deleteIfExists(graphDir.resolve("global.json"));
        Files.deleteIfExists(graphDir.resolve("named-graphs.json"));
        try (var paths = Files.list(graphDir)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith("factsheet-") && name.endsWith(".json")) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Path embeddings = graphDir.resolve("embeddings");
        if (Files.isDirectory(embeddings)) {
            try (var paths = Files.list(embeddings)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    Files.deleteIfExists(path);
                }
            }
            Files.deleteIfExists(embeddings);
        }
    }

    private static void removeStaleScopedGraphs(Path graphDir, Set<Long> activeIds) throws IOException {
        try (var paths = Files.list(graphDir)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Long id = factSheetIdFromKgraphName(path.getFileName().toString());
                if (id != null && !activeIds.contains(id)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    /**
     * Rehydrate native graph files from {@code <root>/data/graph}. Import is skipped
     * when the runtime graph already contains nodes.
     */
    @Transactional
    public void importAllGraphs(Path root) {
        if (root == null || unifiedGraphBridge == null) {
            return;
        }
        Path graphDir = root.resolve(GRAPH_DIR);
        if (!Files.isDirectory(graphDir) || !isGraphEmpty()) {
            return;
        }
        if (!rehydrating.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!importNativeGraphs(graphDir)) {
                log.debug("No native .kgraph project artifact found under {}", graphDir);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to rehydrate native project graphs from " + graphDir, e);
        } finally {
            rehydrating.set(false);
        }
    }

    private boolean importNativeGraphs(Path graphDir) throws Exception {
        List<Path> scopedFiles;
        try (var paths = Files.list(graphDir)) {
            scopedFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> factSheetIdFromKgraphName(path.getFileName().toString()) != null)
                    .sorted()
                    .toList();
        }

        if (scopedFiles.isEmpty()) {
            Path completeGraph = graphDir.resolve("project.kgraph");
            if (!Files.isRegularFile(completeGraph)) {
                return false;
            }
            UnifiedGraph graph = UnifiedGraph.load(completeGraph);
            UnifiedGraphBridge.ImportSummary summary = unifiedGraphBridge.importGraph(graph, null);
            log.info("Rehydrated project graph: {} nodes, {} edges", summary.nodes(), summary.edges());
            return true;
        }

        // Load every archive before mutating the graph, so a corrupt scope cannot leave a partial import.
        List<ScopedGraph> staged = scopedFiles.stream()
                .map(path -> {
                    try {
                        Long factSheetId = factSheetIdFromKgraphName(path.getFileName().toString());
                        return new ScopedGraph(factSheetId, UnifiedGraph.load(path));
                    } catch (IOException e) {
                        throw new IllegalStateException("Invalid native graph " + path, e);
                    }
                })
                .toList();

        for (ScopedGraph scoped : staged) {
            UnifiedGraphBridge.ImportSummary summary =
                    unifiedGraphBridge.importGraph(scoped.graph(), scoped.factSheetId());
            log.info("Rehydrated fact-sheet {} graph: {} nodes, {} edges",
                    scoped.factSheetId(), summary.nodes(), summary.edges());
        }
        return true;
    }

    private static Long factSheetIdFromKgraphName(String name) {
        if (!name.startsWith("factsheet-") || !name.endsWith(".kgraph")) {
            return null;
        }
        String value = name.substring("factsheet-".length(), name.length() - ".kgraph".length());
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @EventListener
    public void onGraphBuildCompleted(GraphBuildCompletedEvent event) {
        if (rehydrating.get()) {
            return;
        }
        resolveRoot().ifPresent(root -> {
            try {
                exportAllGraphs(root);
            } catch (RuntimeException e) {
                log.warn("Could not refresh native project graphs after graph build: {}", e.getMessage(), e);
            }
        });
    }

    private boolean isGraphEmpty() {
        if (knowledgeGraphService == null) {
            return true;
        }
        try {
            Object total = knowledgeGraphService.getGraphStatistics().get("totalNodes");
            return !(total instanceof Number) || ((Number) total).longValue() == 0L;
        } catch (Exception e) {
            log.warn("Could not read graph statistics; skipping rehydrate to be safe: {}", e.getMessage());
            return false;
        }
    }

    private Optional<Path> resolveRoot() {
        Path start = (configuredRoot != null && !configuredRoot.isBlank())
                ? Path.of(configuredRoot)
                : Path.of(System.getProperty("user.dir"));
        return store.findProjectRoot(start);
    }

    private record ScopedGraph(Long factSheetId, UnifiedGraph graph) {}
}
