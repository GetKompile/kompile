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
import ai.kompile.project.KompileProjectFactSheet;
import ai.kompile.project.KompileProjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    @Autowired(required = false)
    private ProjectGraphDestinationMapper destinationMapper;

    private final AtomicBoolean rehydrating = new AtomicBoolean();

    @Value("${kompile.project.root:}")
    private String configuredRoot;

    /** Export every fact-sheet graph and the complete project graph into {@code <root>/data/graph}. */
    public void exportAllGraphs(Path root) {
        if (root == null || unifiedGraphBridge == null) {
            return;
        }
        Path graphDir = root.resolve(GRAPH_DIR);
        Map<Path, Path> stagedFiles = new LinkedHashMap<>();
        try {
            Files.createDirectories(graphDir);
            List<FactSheet> factSheets = factSheetService == null ? List.of()
                    : factSheetService.getAllSheets().stream()
                            .filter(sheet -> sheet.getId() != null)
                            .sorted(java.util.Comparator.comparing(FactSheet::getId))
                            .toList();
            Set<String> portableIds = new HashSet<>();
            for (FactSheet factSheet : factSheets) {
                FactSheet portable = factSheetService.ensurePortableId(factSheet);
                if (portable.getPortableId() == null || portable.getPortableId().isBlank()) {
                    throw new IllegalStateException("Fact sheet has no portable identity: " + portable.getId());
                }
                portableIds.add(portable.getPortableId());
            }
            validatePortableCatalog(root, factSheets);

            List<PreparedExport> prepared = new ArrayList<>();
            for (FactSheet factSheet : factSheets) {
                prepared.add(prepareUnifiedScope(
                        graphDir.resolve("factsheet-" + factSheet.getPortableId() + ".kgraph"),
                        factSheet.getId(), factSheet));
            }
            prepared.add(prepareUnifiedScope(graphDir.resolve("project.kgraph"), null, null));

            // Persist every replacement under a temporary name before mutating any published file.
            for (PreparedExport export : prepared) {
                if (export.graph() == null) continue;
                Path temporary = Files.createTempFile(
                        graphDir, export.file().getFileName().toString(), ".tmp");
                export.graph().save(temporary);
                stagedFiles.put(export.file(), temporary);
            }
            for (Map.Entry<Path, Path> staged : stagedFiles.entrySet()) {
                moveReplacing(staged.getValue(), staged.getKey());
            }
            stagedFiles.clear();
            for (PreparedExport export : prepared) {
                if (export.graph() == null) Files.deleteIfExists(export.file());
            }
            removeLegacyArtifacts(graphDir);
            removeStaleScopedGraphs(graphDir, portableIds);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish native project graphs under " + graphDir, e);
        } finally {
            for (Path temporary : stagedFiles.values()) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    log.warn("Could not remove staged graph export {}: {}",
                            temporary, cleanupFailure.getMessage());
                }
            }
        }
    }

    private PreparedExport prepareUnifiedScope(Path file, Long factSheetId, FactSheet factSheet) {
        UnifiedGraph graph = unifiedGraphBridge.export(factSheetId);
        if (graph == null || (graph.entities().isEmpty() && graph.relations().isEmpty())) {
            return new PreparedExport(file, null);
        }
        if (factSheet != null) {
            Map<String, Object> sourceScope = new LinkedHashMap<>();
            sourceScope.put("kind", "FACT_SHEET");
            sourceScope.put("portableId", factSheet.getPortableId());
            sourceScope.put("legacyFactSheetId", factSheet.getId());
            sourceScope.put("name", factSheet.getName());
            graph.meta(ProjectGraphDestinationMapper.SOURCE_SCOPE_META, sourceScope);
        }
        return new PreparedExport(file, graph);
    }

    private void validatePortableCatalog(Path root, List<FactSheet> factSheets) {
        Map<String, KompileProjectFactSheet> catalogByPortableId = new LinkedHashMap<>();
        for (KompileProjectFactSheet portable : store.listFactSheets(root)) {
            if (portable.getPortableId() == null || portable.getPortableId().isBlank()) continue;
            String portableId = FactSheetService.canonicalPortableId(portable.getPortableId());
            if (catalogByPortableId.putIfAbsent(portableId, portable) != null) {
                throw new IllegalStateException("Portable fact-sheet catalog contains duplicate identity: "
                        + portableId);
            }
        }
        for (FactSheet factSheet : factSheets) {
            String portableId = FactSheetService.canonicalPortableId(factSheet.getPortableId());
            KompileProjectFactSheet portable = catalogByPortableId.get(portableId);
            if (portable == null || !java.util.Objects.equals(portable.getId(), factSheet.getId())
                    || !java.util.Objects.equals(portable.getName(), factSheet.getName())) {
                throw new IllegalStateException("Publish the portable fact-sheet catalog before graph scope "
                        + portableId);
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

    private static void removeStaleScopedGraphs(Path graphDir, Set<String> activeIds) throws IOException {
        try (var paths = Files.list(graphDir)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String id = ProjectGraphDestinationMapper.scopeKey(path.getFileName().toString());
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
    public void importAllGraphs(Path root) {
        if (root == null || unifiedGraphBridge == null) {
            return;
        }
        Path graphDir = root.resolve(GRAPH_DIR);
        if (!Files.isDirectory(graphDir)) {
            return;
        }
        if (!rehydrating.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!importNativeGraphs(root, graphDir)) {
                log.debug("No native .kgraph project artifact found under {}", graphDir);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to rehydrate native project graphs from " + graphDir, e);
        } finally {
            rehydrating.set(false);
        }
    }

    private boolean importNativeGraphs(Path root, Path graphDir) throws Exception {
        List<Path> scopedFiles;
        try (var paths = Files.list(graphDir)) {
            scopedFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> ProjectGraphDestinationMapper.scopeKey(
                            path.getFileName().toString()) != null)
                    .sorted()
                    .toList();
        }

        if (scopedFiles.isEmpty()) {
            Path completeGraph = graphDir.resolve("project.kgraph");
            if (!Files.isRegularFile(completeGraph)) {
                return false;
            }
            throw new IllegalStateException("Unscoped project.kgraph cannot be safely restored without "
                    + "portable fact-sheet ownership; export scoped fact-sheet archives first");
        }

        // Load every archive before mutating the graph, so a corrupt scope cannot leave a partial import.
        List<ScopedGraph> staged = scopedFiles.stream()
                .map(path -> {
                    try {
                        UnifiedGraph graph = UnifiedGraph.load(path);
                        Long factSheetId;
                        if (destinationMapper != null) {
                            factSheetId = destinationMapper.resolve(root, path, graph);
                        } else {
                            factSheetId = legacyFactSheetId(path.getFileName().toString());
                            if (factSheetId == null) {
                                throw new IllegalStateException(
                                        "Portable destination mapper is not available for " + path.getFileName());
                            }
                        }
                        return new ScopedGraph(factSheetId, graph);
                    } catch (IOException e) {
                        throw new IllegalStateException("Invalid native graph " + path, e);
                    }
                })
                .toList();

        Set<Long> destinations = new HashSet<>();
        for (ScopedGraph scoped : staged) {
            if (!destinations.add(scoped.factSheetId())) {
                throw new IllegalStateException(
                        "Multiple portable graph archives resolve to fact sheet " + scoped.factSheetId());
            }
        }

        List<ScopedGraph> missingScopes = staged.stream()
                .filter(scoped -> isScopeEmpty(scoped.factSheetId()))
                .toList();
        if (missingScopes.isEmpty()) {
            log.debug("Every portable graph destination is already populated");
            return true;
        }

        UnifiedGraphBridge.BatchImportSummary batch = unifiedGraphBridge.importGraphs(missingScopes.stream()
                .map(scoped -> new UnifiedGraphBridge.ImportScope(scoped.graph(), scoped.factSheetId()))
                .toList());
        for (int i = 0; i < missingScopes.size(); i++) {
            ScopedGraph scoped = missingScopes.get(i);
            UnifiedGraphBridge.ImportSummary summary = batch.summaries().get(i);
            log.info("Rehydrated fact-sheet {} graph: {} nodes, {} edges",
                    scoped.factSheetId(), summary.nodes(), summary.edges());
        }
        return true;
    }

    private static Long legacyFactSheetId(String name) {
        String value = ProjectGraphDestinationMapper.scopeKey(name);
        if (value == null) return null;
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

    private boolean isScopeEmpty(Long factSheetId) {
        if (knowledgeGraphService == null) {
            return true;
        }
        try {
            return knowledgeGraphService.getNodesInFactSheet(factSheetId).isEmpty();
        } catch (Exception e) {
            log.warn("Could not inspect graph scope {}; skipping rehydrate to be safe: {}",
                    factSheetId, e.getMessage());
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
    private record PreparedExport(Path file, UnifiedGraph graph) {}
}
