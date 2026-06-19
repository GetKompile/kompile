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

import ai.kompile.core.graphrag.conformance.GraphConformanceChecker;
import ai.kompile.core.graphrag.conformance.GraphConformanceSummary;
import ai.kompile.core.graphrag.maintenance.model.GraphComparison;
import ai.kompile.core.graphrag.maintenance.model.GraphHealthSnapshot;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes a fact sheet's knowledge-graph {@link GraphHealthSnapshot} (a cheap O(N+E) health vector)
 * store-agnostically via {@link KnowledgeGraphService}, persists it as a time series under the
 * versioned project tree ({@code <dataDir>/data/graph/health/<factSheetId>/}) so health is trackable
 * across the project's git history, and compares two fact sheets' graphs ({@link #compareGraphs}).
 *
 * <p>This is the analyzable half of "graph as an asset". It deliberately reuses the same
 * {@code dataDir} relocation pattern as {@link SnapshotManager} (so snapshots travel with a clone) and
 * pulls ontology conformance via the optional {@link GraphConformanceChecker} SPI — the same seam the
 * maintenance layer uses — without depending on the OntologySchema model.
 */
@Slf4j
@Service
public class GraphHealthService {

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.5;
    private static final int SAMPLE_CAP = 50;

    /** Node levels that count as real content for orphan detection (mirrors STATS_REFRESH; excludes SOURCE/CUSTOM). */
    private static final Set<NodeLevel> ORPHAN_LEVELS = EnumSet.of(
            NodeLevel.ENTITY, NodeLevel.DOCUMENT, NodeLevel.SNIPPET,
            NodeLevel.TABLE, NodeLevel.ATTACHMENT, NodeLevel.IDENTIFIER);

    /**
     * Base directory for the health time series. Inside a project ({@code kompile.data.dir} set) it
     * lives under the versioned tree so it travels with a git clone; otherwise it falls back to
     * {@code ~/.kompile/graph-health}.
     */
    @Value("${kompile.data.dir:}")
    private String dataDir;

    private final KnowledgeGraphService knowledgeGraphService;
    private final ObjectProvider<GraphConformanceChecker> conformanceCheckerProvider;
    private final ObjectMapper objectMapper;

    public GraphHealthService(KnowledgeGraphService knowledgeGraphService,
                              ObjectProvider<GraphConformanceChecker> conformanceCheckerProvider,
                              ObjectMapper objectMapper) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.conformanceCheckerProvider = conformanceCheckerProvider;
        this.objectMapper = objectMapper;
    }

    // ── compute ──────────────────────────────────────────────────────────────────

    /** Compute the current health vector for a fact sheet's graph (does not persist). */
    public GraphHealthSnapshot computeSnapshot(Long factSheetId) {
        List<GraphNode> nodes = activeNodes(factSheetId);
        List<GraphEdge> edges = activeEdges(factSheetId);
        int n = nodes.size();
        int e = edges.size();

        Map<String, Integer> nodesByType = new LinkedHashMap<>();
        Set<String> nodeIds = new HashSet<>();
        for (GraphNode node : nodes) {
            String type = node.getNodeType() != null ? node.getNodeType().name() : "UNKNOWN";
            nodesByType.merge(type, 1, Integer::sum);
            if (node.getNodeId() != null) {
                nodeIds.add(node.getNodeId());
            }
        }

        // Degree map + weakly-connected components over the active subgraph, in one edge pass.
        Map<String, Integer> degree = new HashMap<>();
        UnionFind uf = new UnionFind(nodeIds);
        for (GraphEdge edge : edges) {
            String s = endpointId(edge.getSourceNode());
            String t = endpointId(edge.getTargetNode());
            if (s != null) degree.merge(s, 1, Integer::sum);
            if (t != null) degree.merge(t, 1, Integer::sum);
            if (s != null && t != null && nodeIds.contains(s) && nodeIds.contains(t)) {
                uf.union(s, t);
            }
        }
        int maxDegree = degree.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        double averageDegree = n == 0 ? 0.0 : (2.0 * e) / n;
        double density = n < 2 ? 0.0 : Math.min(1.0, (2.0 * e) / ((double) n * (n - 1)));

        int orphanCount = 0;
        int lowConfidenceNodes = 0;
        for (GraphNode node : nodes) {
            if (node.getConfidence() != null && node.getConfidence() < LOW_CONFIDENCE_THRESHOLD) {
                lowConfidenceNodes++;
            }
            if (node.getNodeType() != null && ORPHAN_LEVELS.contains(node.getNodeType())) {
                int d = node.getNodeId() != null ? degree.getOrDefault(node.getNodeId(), 0) : 0;
                if (d == 0) orphanCount++;
            }
        }
        double orphanRate = n == 0 ? 0.0 : (double) orphanCount / n;

        int lowConfidenceEdges = (int) edges.stream()
                .filter(edge -> edge.getConfidence() != null && edge.getConfidence() < LOW_CONFIDENCE_THRESHOLD)
                .count();

        UnionFind.Result components = uf.summarize();
        double largestComponentFraction = n == 0 ? 0.0 : (double) components.largestSize() / n;

        boolean ontologyBound = false;
        Double conformanceScore = null;
        GraphConformanceChecker checker = conformanceCheckerProvider.getIfAvailable();
        if (checker != null) {
            try {
                GraphConformanceSummary summary = checker.checkFactSheet(factSheetId);
                if (summary != null) {
                    ontologyBound = summary.ontologyBound();
                    conformanceScore = summary.conformanceScore();
                }
            } catch (Exception ex) {
                log.warn("Conformance check failed during health snapshot for factSheet={}: {}",
                        factSheetId, ex.getMessage());
            }
        }

        return new GraphHealthSnapshot(factSheetId, Instant.now(), n, e, nodesByType,
                round(density), round(averageDegree), maxDegree, orphanCount, round(orphanRate),
                lowConfidenceNodes, lowConfidenceEdges, components.componentCount(),
                round(largestComponentFraction), ontologyBound, conformanceScore);
    }

    // ── time series ────────────────────────────────────────────────────────────────

    /** Compute and append a snapshot to the fact sheet's health time series. */
    public GraphHealthSnapshot persistSnapshot(Long factSheetId) {
        GraphHealthSnapshot snapshot = computeSnapshot(factSheetId);
        Path dir = healthBaseDir().resolve(String.valueOf(factSheetId));
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(snapshot.computedAt().toEpochMilli() + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), snapshot);
            log.info("Persisted graph health snapshot for factSheet={} -> {}", factSheetId, file);
        } catch (IOException ex) {
            throw new RuntimeException("Cannot persist health snapshot for factSheet=" + factSheetId, ex);
        }
        return snapshot;
    }

    /** The fact sheet's health time series, oldest first. */
    public List<GraphHealthSnapshot> listHistory(Long factSheetId) {
        Path dir = healthBaseDir().resolve(String.valueOf(factSheetId));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        File[] files = dir.toFile().listFiles(f -> f.getName().endsWith(".json"));
        if (files == null || files.length == 0) {
            return List.of();
        }
        List<GraphHealthSnapshot> out = new ArrayList<>();
        for (File f : files) {
            try {
                out.add(objectMapper.readValue(f, GraphHealthSnapshot.class));
            } catch (IOException ex) {
                log.warn("Failed to read health snapshot {}: {}", f.getName(), ex.getMessage());
            }
        }
        out.sort(Comparator.comparing(GraphHealthSnapshot::computedAt));
        return out;
    }

    // ── compare ──────────────────────────────────────────────────────────────────

    /**
     * Compare two fact sheets' graphs: ENTITY-name set overlap (by normalized title) plus each side's
     * health snapshot for metric deltas.
     */
    public GraphComparison compareGraphs(Long factSheetIdA, Long factSheetIdB) {
        GraphHealthSnapshot healthA = computeSnapshot(factSheetIdA);
        GraphHealthSnapshot healthB = computeSnapshot(factSheetIdB);

        Map<String, String> entitiesA = entityNames(factSheetIdA);
        Map<String, String> entitiesB = entityNames(factSheetIdB);

        List<String> shared = new ArrayList<>();
        List<String> onlyInA = new ArrayList<>();
        for (Map.Entry<String, String> en : entitiesA.entrySet()) {
            if (entitiesB.containsKey(en.getKey())) {
                shared.add(en.getValue());
            } else {
                onlyInA.add(en.getValue());
            }
        }
        List<String> onlyInB = new ArrayList<>();
        for (Map.Entry<String, String> en : entitiesB.entrySet()) {
            if (!entitiesA.containsKey(en.getKey())) {
                onlyInB.add(en.getValue());
            }
        }

        return new GraphComparison(factSheetIdA, factSheetIdB,
                shared.size(), onlyInA.size(), onlyInB.size(),
                cap(shared), cap(onlyInA), cap(onlyInB), healthA, healthB);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private List<GraphNode> activeNodes(Long factSheetId) {
        List<GraphNode> all = knowledgeGraphService.getNodesInFactSheet(factSheetId);
        List<GraphNode> active = new ArrayList<>();
        for (GraphNode node : all) {
            if (!Boolean.TRUE.equals(node.getStale())) {
                active.add(node);
            }
        }
        return active;
    }

    private List<GraphEdge> activeEdges(Long factSheetId) {
        List<GraphEdge> all = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        List<GraphEdge> active = new ArrayList<>();
        for (GraphEdge edge : all) {
            if (!Boolean.TRUE.equals(edge.getStale())) {
                active.add(edge);
            }
        }
        return active;
    }

    /** Normalized ENTITY title → first-seen display title, for the active ENTITY nodes. */
    private Map<String, String> entityNames(Long factSheetId) {
        Map<String, String> byNormalized = new LinkedHashMap<>();
        for (GraphNode node : knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY)) {
            if (Boolean.TRUE.equals(node.getStale())) {
                continue;
            }
            String title = node.getTitle();
            if (title == null || title.isBlank()) {
                continue;
            }
            byNormalized.putIfAbsent(title.trim().toLowerCase(), title.trim());
        }
        return byNormalized;
    }

    private Path healthBaseDir() {
        if (dataDir != null && !dataDir.isBlank()) {
            return Path.of(dataDir, "data", "graph", "health");
        }
        return Path.of(System.getProperty("user.home"), ".kompile", "graph-health");
    }

    private static String endpointId(GraphNode node) {
        return node != null ? node.getNodeId() : null;
    }

    private static List<String> cap(List<String> list) {
        return list.size() <= SAMPLE_CAP ? List.copyOf(list) : List.copyOf(list.subList(0, SAMPLE_CAP));
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** Minimal union-find for weakly-connected-component counting over the active node-id set. */
    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        UnionFind(Set<String> ids) {
            for (String id : ids) {
                parent.put(id, id);
            }
        }

        String find(String x) {
            String root = x;
            while (!root.equals(parent.get(root))) {
                root = parent.get(root);
            }
            while (!x.equals(root)) {
                String next = parent.get(x);
                parent.put(x, root);
                x = next;
            }
            return root;
        }

        void union(String a, String b) {
            String ra = find(a);
            String rb = find(b);
            if (!ra.equals(rb)) {
                parent.put(ra, rb);
            }
        }

        Result summarize() {
            Map<String, Integer> sizes = new HashMap<>();
            for (String id : parent.keySet()) {
                sizes.merge(find(id), 1, Integer::sum);
            }
            int largest = sizes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            return new Result(sizes.size(), largest);
        }

        record Result(int componentCount, int largestSize) {}
    }
}
