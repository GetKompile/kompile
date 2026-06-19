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
import ai.kompile.graphchangetracking.event.GraphChangesetCompletedEvent;
import ai.kompile.knowledgegraph.io.GraphEmbeddingSidecar;
import ai.kompile.knowledgegraph.io.GraphIOService;
import ai.kompile.knowledgegraph.io.model.ExportResult;
import ai.kompile.knowledgegraph.io.model.ImportResult;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Bakes the runtime knowledge graph into the versioned project tree so it
 * survives a {@code git clone}, and rehydrates it when a project is opened.
 *
 * <p>Graphs are written as portable JSON under {@code data/graph/}: one file per
 * fact sheet ({@code factsheet-<id>.json}) plus {@code global.json} for nodes not
 * scoped to any fact sheet, so the dump covers the whole graph exactly once.
 * Export runs on {@code commit} and whenever a graph changeset completes; import
 * runs on {@code open}, guarded so it only rehydrates into an empty graph (a fresh
 * clone) and never duplicates an already-populated one.</p>
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
    @Autowired
    private ObjectMapper mapper;

    @Value("${kompile.project.root:}")
    private String configuredRoot;

    /** Export every fact-sheet graph plus the global bucket into {@code <root>/data/graph}. */
    public void exportAllGraphs(Path root) {
        if (graphIOService == null || root == null) {
            return;
        }
        Path graphDir = root.resolve(GRAPH_DIR);
        try {
            Files.createDirectories(graphDir);
        } catch (IOException e) {
            log.warn("Could not create graph dir {}: {}", graphDir, e.getMessage());
            return;
        }
        if (factSheetService != null) {
            for (FactSheet sheet : factSheetService.getAllSheets()) {
                if (sheet.getId() != null) {
                    exportFactSheetScope(graphDir, sheet.getId());
                }
            }
        }
        exportScope(graphDir.resolve("global.json"), () -> graphIOService.exportGlobalGraph("json"));
    }

    /** Export one fact sheet's structure JSON plus its KG-embedding sidecar. */
    private void exportFactSheetScope(Path graphDir, Long factSheetId) {
        exportScope(graphDir.resolve("factsheet-" + factSheetId + ".json"),
                () -> graphIOService.exportGraph("json", factSheetId));
        byte[] embeddings = (embeddingSidecar != null) ? embeddingSidecar.export(factSheetId) : null;
        writeBinaryOrDelete(graphDir.resolve("embeddings").resolve("factsheet-" + factSheetId + ".bin"), embeddings);
    }

    /** Write a binary sidecar, or delete a stale file when there is nothing to write. */
    private void writeBinaryOrDelete(Path file, byte[] data) {
        try {
            if (data == null || data.length == 0) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            Files.write(file, data);
        } catch (IOException e) {
            log.warn("Failed to write embedding sidecar {}: {}", file, e.getMessage());
        }
    }

    private interface ExportCall {
        ExportResult call() throws Exception;
    }

    /** Write the export to {@code file}, or delete a stale empty file when the scope has no nodes. */
    private void exportScope(Path file, ExportCall call) {
        try {
            ExportResult result = call.call();
            if (result.nodesExported() == 0 && result.edgesExported() == 0) {
                Files.deleteIfExists(file);
                return;
            }
            Files.write(file, result.data());
            log.debug("Exported graph {} ({} nodes, {} edges)",
                    file.getFileName(), result.nodesExported(), result.edgesExported());
        } catch (Exception e) {
            log.warn("Failed to export graph {}: {}", file, e.getMessage());
        }
    }

    /**
     * Rehydrate graph files from {@code <root>/data/graph} into the runtime store.
     * No-op unless the graph is currently empty, so opening an already-populated
     * project never duplicates data.
     */
    public void importAllGraphs(Path root) {
        if (graphIOService == null || root == null) {
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
        List<Path> files;
        try (var paths = Files.list(graphDir)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list graph dir {}: {}", graphDir, e.getMessage());
            return;
        }
        if (files.isEmpty()) {
            return;
        }
        try {
            byte[] merged = mergeGraphFiles(files);
            ImportResult result = graphIOService.importGraph("json", merged, null);
            log.info("Rehydrated graph from {} file(s): {} nodes created, {} updated, {} edges, {} errors",
                    files.size(), result.nodesCreated(), result.nodesUpdated(),
                    result.edgesCreated(), result.errors());
            importEmbeddings(graphDir);
        } catch (Exception e) {
            log.warn("Failed to rehydrate graph from {}: {}", graphDir, e.getMessage(), e);
        }
    }

    /** Reattach KG-embedding sidecars onto the rehydrated nodes (one file per fact sheet). */
    private void importEmbeddings(Path graphDir) {
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
                try {
                    int applied = embeddingSidecar.importInto(factSheetId, Files.readAllBytes(bin));
                    if (applied > 0) {
                        log.info("Reattached {} KG embeddings for fact sheet {}", applied, factSheetId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to load embedding sidecar {}: {}", bin, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Could not list embeddings dir {}: {}", embDir, e.getMessage());
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

    /**
     * Keep the on-disk graph current between commits: when a changeset finishes
     * (e.g. a crawl), re-export the affected fact sheet so the next git commit
     * captures it without an explicit export step.
     */
    @EventListener
    public void onChangesetCompleted(GraphChangesetCompletedEvent event) {
        if (graphIOService == null) {
            return;
        }
        resolveRoot().ifPresent(root -> {
            Path graphDir = root.resolve(GRAPH_DIR);
            try {
                Files.createDirectories(graphDir);
                if (event.getFactSheetId() != null) {
                    exportFactSheetScope(graphDir, event.getFactSheetId());
                } else {
                    exportScope(graphDir.resolve("global.json"), () -> graphIOService.exportGlobalGraph("json"));
                }
            } catch (IOException e) {
                log.warn("Auto-export after changeset {} failed: {}", event.getChangesetId(), e.getMessage());
            }
        });
    }

    private Optional<Path> resolveRoot() {
        Path start = (configuredRoot != null && !configuredRoot.isBlank())
                ? Path.of(configuredRoot)
                : Path.of(System.getProperty("user.dir"));
        return store.findProjectRoot(start);
    }
}
