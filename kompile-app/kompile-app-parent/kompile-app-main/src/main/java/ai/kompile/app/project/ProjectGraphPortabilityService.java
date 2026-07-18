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
import ai.kompile.knowledgegraph.io.GraphEmbeddingSidecar;
import ai.kompile.knowledgegraph.io.GraphIOService;
import ai.kompile.knowledgegraph.io.model.ExportResult;
import ai.kompile.knowledgegraph.io.model.ImportResult;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bakes the runtime knowledge graph into the versioned project tree so it
 * survives a {@code git clone}, and rehydrates it when a project is opened.
 *
 * <p>Graphs are written under {@code data/graph/} in two compatible forms. Legacy
 * structure JSON and embedding sidecars remain available for older clones. Rich
 * {@code .kgraph} files preserve reasoning assets per fact sheet, while
 * {@code project.kgraph} provides a complete read-only view for local chat.
 * Export runs on commit and graph-completion events; import runs on open and is
 * guarded so it only rehydrates an empty graph.</p>
 */
@Service
public class ProjectGraphPortabilityService {

    private static final Logger log = LoggerFactory.getLogger(ProjectGraphPortabilityService.class);
    private static final String GRAPH_DIR = "data/graph";

    private final KompileProjectStore store = new KompileProjectStore();

    @Autowired(required = false)
    private GraphIOService graphIOService;
    @Autowired(required = false)
    private FactSheetService factSheetService;
    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;
    @Autowired(required = false)
    private GraphEmbeddingSidecar embeddingSidecar;
    @Autowired(required = false)
    private UnifiedGraphBridge unifiedGraphBridge;
    @Autowired
    private ObjectMapper mapper;

    private final AtomicBoolean rehydrating = new AtomicBoolean();

    @Value("${kompile.project.root:}")
    private String configuredRoot;

    /** Export every fact-sheet graph plus the global bucket into {@code <root>/data/graph}. */
    public void exportAllGraphs(Path root) {
        if (root == null || (graphIOService == null && unifiedGraphBridge == null)) {
            return;
        }
        Path graphDir = root.resolve(GRAPH_DIR);
        try {
            Files.createDirectories(graphDir);
            List<Long> factSheetIds = factSheetService == null ? List.of()
                    : factSheetService.getAllSheets().stream()
                            .map(FactSheet::getId)
                            .filter(id -> id != null)
                            .toList();
            if (graphIOService != null) {
                for (Long factSheetId : factSheetIds) {
                    exportFactSheetScope(graphDir, factSheetId);
                }
                exportScope(graphDir.resolve("global.json"), () -> graphIOService.exportGlobalGraph("json"));
            }
            exportUnifiedGraphs(graphDir, factSheetIds);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish portable project graphs under " + graphDir, e);
        }
    }

    /**
     * Export the rich reasoning representation used by project archives and
     * {@code kompile-chat-local}. Per-fact-sheet files preserve application
     * scope during rehydrate; {@code project.kgraph} is the complete read-only
     * view selected by local chat.
     */
    private void exportUnifiedGraphs(Path graphDir, List<Long> factSheetIds) throws Exception {
        if (unifiedGraphBridge == null) {
            return;
        }
        for (Long factSheetId : factSheetIds) {
            exportUnifiedScope(
                    graphDir.resolve("factsheet-" + factSheetId + ".kgraph"), factSheetId);
        }
        exportUnifiedScope(graphDir.resolve("project.kgraph"), null);
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
            log.debug("Exported unified graph {} ({} entities, {} relations)",
                    file.getFileName(), graph.entities().size(), graph.relations().size());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    log.debug("Could not remove temporary graph {}: {}", temporary, cleanupFailure.getMessage());
                }
            }
        }
    }

    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, data);
            moveReplacing(temporary, target);
            temporary = null;
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

    /** Export one fact sheet's structure JSON plus its KG-embedding sidecar. */
    private void exportFactSheetScope(Path graphDir, Long factSheetId) throws Exception {
        exportScope(graphDir.resolve("factsheet-" + factSheetId + ".json"),
                () -> graphIOService.exportGraph("json", factSheetId));
        byte[] embeddings = (embeddingSidecar != null) ? embeddingSidecar.export(factSheetId) : null;
        writeBinaryOrDelete(graphDir.resolve("embeddings").resolve("factsheet-" + factSheetId + ".bin"), embeddings);
    }

    /** Write a binary sidecar, or delete a stale file when there is nothing to write. */
    private void writeBinaryOrDelete(Path file, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            Files.deleteIfExists(file);
            return;
        }
        writeAtomically(file, data);
    }

    private interface ExportCall {
        ExportResult call() throws Exception;
    }

    /** Write the export to {@code file}, or delete a stale empty file when the scope has no nodes. */
    private void exportScope(Path file, ExportCall call) throws Exception {
        ExportResult result = call.call();
        if (result.nodesExported() == 0 && result.edgesExported() == 0) {
            Files.deleteIfExists(file);
            return;
        }
        writeAtomically(file, result.data());
        log.debug("Exported graph {} ({} nodes, {} edges)",
                file.getFileName(), result.nodesExported(), result.edgesExported());
    }

    /**
     * Rehydrate graph files from {@code <root>/data/graph} into the runtime store.
     * No-op unless the graph is currently empty, so opening an already-populated
     * project never duplicates data.
     */
    @Transactional
    public void importAllGraphs(Path root) {
        if (root == null || (graphIOService == null && unifiedGraphBridge == null)) {
            return;
        }
        Path graphDir = root.resolve(GRAPH_DIR);
        if (!Files.isDirectory(graphDir)) {
            return;
        }
        if (!isGraphEmpty()) {
            log.debug("Graph already populated; skipping rehydrate from {}", graphDir);
            return;
        }
        if (!rehydrating.compareAndSet(false, true)) {
            return;
        }
        try {
            if (importUnifiedGraphs(graphDir)) {
                return;
            }
            importLegacyGraphs(graphDir);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to rehydrate portable project graphs from " + graphDir, e);
        } finally {
            rehydrating.set(false);
        }
    }

    private boolean importUnifiedGraphs(Path graphDir) throws Exception {
        if (unifiedGraphBridge == null) {
            return false;
        }
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
            log.info("Rehydrated fallback project graph: {} nodes, {} edges",
                    summary.nodes(), summary.edges());
            return true;
        }

        List<ScopedGraph> staged = new java.util.ArrayList<>(scopedFiles.size());
        for (Path scopedFile : scopedFiles) {
            Long factSheetId = factSheetIdFromKgraphName(scopedFile.getFileName().toString());
            staged.add(new ScopedGraph(factSheetId, UnifiedGraph.load(scopedFile)));
        }
        Path globalJson = graphDir.resolve("global.json");
        byte[] globalPayload = null;
        if (Files.isRegularFile(globalJson)) {
            if (graphIOService == null) {
                throw new IOException("global.json is present but GraphIOService is unavailable");
            }
            globalPayload = Files.readAllBytes(globalJson);
            mapper.readTree(globalPayload);
        }
        for (ScopedGraph scoped : staged) {
            UnifiedGraphBridge.ImportSummary summary =
                    unifiedGraphBridge.importGraph(scoped.graph(), scoped.factSheetId());
            log.info("Rehydrated fact-sheet {} graph: {} nodes, {} edges",
                    scoped.factSheetId(), summary.nodes(), summary.edges());
        }
        if (globalPayload != null) {
            ImportResult global = graphIOService.importGraph("json", globalPayload, null);
            requireCompleteImport(global, "global.json");
            log.info("Rehydrated global graph: {} nodes created, {} updated, {} edges",
                    global.nodesCreated(), global.nodesUpdated(), global.edgesCreated());
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

    private void importLegacyGraphs(Path graphDir) throws Exception {
        if (graphIOService == null) {
            return;
        }
        List<Path> files;
        try (var paths = Files.list(graphDir)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
        if (files.isEmpty()) {
            return;
        }
        byte[] merged = mergeGraphFiles(files);
        ImportResult result = graphIOService.importGraph("json", merged, null);
        requireCompleteImport(result, "legacy graph set");
        log.info("Rehydrated graph from {} file(s): {} nodes created, {} updated, {} edges, {} errors",
                files.size(), result.nodesCreated(), result.nodesUpdated(),
                result.edgesCreated(), result.errors());
        importEmbeddings(graphDir);
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
                log.warn("Could not refresh portable project graphs after graph build: {}", e.getMessage(), e);
            }
        });
    }

    /** Reattach KG-embedding sidecars onto the rehydrated nodes (one file per fact sheet). */
    private void importEmbeddings(Path graphDir) throws Exception {
        if (embeddingSidecar == null) {
            return;
        }
        Path embDir = graphDir.resolve("embeddings");
        if (!Files.isDirectory(embDir)) {
            return;
        }
        try (var paths = Files.list(embDir)) {
            List<Path> bins = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .toList();
            for (Path bin : bins) {
                Long factSheetId = factSheetIdFromBinName(bin.getFileName().toString());
                if (factSheetId == null) {
                    continue;
                }
                int applied = embeddingSidecar.importInto(factSheetId, Files.readAllBytes(bin));
                if (applied > 0) {
                    log.info("Reattached {} KG embeddings for fact sheet {}", applied, factSheetId);
                }
            }
        }
    }

    private static void requireCompleteImport(ImportResult result, String source) throws IOException {
        if (result == null) {
            throw new IOException("Graph import returned no result for " + source);
        }
        if (result.errors() > 0) {
            throw new IOException("Graph import reported " + result.errors() + " error(s) for "
                    + source + ": " + result.errorMessages());
        }
    }

    private static Long factSheetIdFromBinName(String name) {
        if (!name.startsWith("factsheet-") || !name.endsWith(".bin")) {
            return null;
        }
        String mid = name.substring("factsheet-".length(), name.length() - ".bin".length());
        try {
            return Long.parseLong(mid);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Merge per-fact-sheet files into one PortableGraph payload so that all nodes
     * are created before any edges — edges in one file may reference nodes that
     * live in another (e.g. cross-source relationships).
     */
    private byte[] mergeGraphFiles(List<Path> files) throws IOException {
        ObjectNode merged = mapper.createObjectNode();
        ArrayNode nodes = merged.putArray("nodes");
        ArrayNode edges = merged.putArray("edges");
        for (Path f : files) {
            JsonNode root = mapper.readTree(Files.readAllBytes(f));
            JsonNode ns = root.get("nodes");
            if (ns != null && ns.isArray()) {
                ns.forEach(nodes::add);
            }
            JsonNode es = root.get("edges");
            if (es != null && es.isArray()) {
                es.forEach(edges::add);
            }
        }
        return mapper.writeValueAsBytes(merged);
    }

    private boolean isGraphEmpty() {
        if (knowledgeGraphService == null) {
            return true;
        }
        try {
            Object total = knowledgeGraphService.getGraphStatistics().get("totalNodes");
            return !(total instanceof Number) || ((Number) total).longValue() == 0L;
        } catch (Exception e) {
            // If we can't tell, assume non-empty so we never duplicate on rehydrate.
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
