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
package ai.kompile.knowledgegraph.io;

import ai.kompile.knowledgegraph.io.format.PortableGraph;
import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Merges multiple {@link PortableGraph}s into one, performing entity-level
 * deduplication based on external ID and title similarity.
 */
@Service
public class GraphMergeService {

    private static final Logger log = LoggerFactory.getLogger(GraphMergeService.class);
    private static final double SIMILARITY_THRESHOLD = 0.85;

    public record MergeResult(PortableGraph merged, int totalSourceNodes, int totalSourceEdges,
                              int mergedNodes, int mergedEdges, int deduplicatedNodes) {}

    public MergeResult merge(List<PortableGraph> graphs) {
        if (graphs == null || graphs.isEmpty()) {
            return new MergeResult(PortableGraph.empty(), 0, 0, 0, 0, 0);
        }
        if (graphs.size() == 1) {
            PortableGraph g = graphs.get(0);
            return new MergeResult(g, g.nodes().size(), g.edges().size(),
                    g.nodes().size(), g.edges().size(), 0);
        }

        int totalNodes = 0, totalEdges = 0;
        for (PortableGraph g : graphs) {
            totalNodes += g.nodes().size();
            totalEdges += g.edges().size();
        }

        // Collect all nodes, dedup by externalId then by fuzzy title match
        Map<String, PortableNode> nodeByExtId = new LinkedHashMap<>();
        Map<String, String> idMapping = new HashMap<>(); // old external id -> canonical id

        for (PortableGraph g : graphs) {
            for (PortableNode n : g.nodes()) {
                String canonical = findCanonical(n, nodeByExtId);
                if (canonical != null) {
                    // Map this node's id to the canonical
                    idMapping.put(n.externalId(), canonical);
                    // Merge: keep longer description, combine metadata
                    PortableNode existing = nodeByExtId.get(canonical);
                    nodeByExtId.put(canonical, mergeNodes(existing, n));
                } else {
                    nodeByExtId.put(n.externalId(), n);
                    idMapping.put(n.externalId(), n.externalId());
                }
            }
        }

        // Remap and deduplicate edges
        Set<String> seenEdges = new HashSet<>();
        List<PortableEdge> mergedEdges = new ArrayList<>();
        for (PortableGraph g : graphs) {
            for (PortableEdge e : g.edges()) {
                String from = idMapping.getOrDefault(e.fromExternalId(), e.fromExternalId());
                String to = idMapping.getOrDefault(e.toExternalId(), e.toExternalId());
                String key = from + "|" + to + "|" + (e.edgeType() != null ? e.edgeType() : "");
                if (seenEdges.add(key)) {
                    mergedEdges.add(new PortableEdge(from, to, e.edgeType(), e.weight(),
                            e.description(), e.provenance(), e.confidence()));
                }
            }
        }

        int dedup = totalNodes - nodeByExtId.size();
        log.info("Merged {} graphs: {} nodes ({} deduped), {} edges",
                graphs.size(), nodeByExtId.size(), dedup, mergedEdges.size());

        PortableGraph merged = new PortableGraph(new ArrayList<>(nodeByExtId.values()), mergedEdges);
        return new MergeResult(merged, totalNodes, totalEdges,
                nodeByExtId.size(), mergedEdges.size(), dedup);
    }

    private String findCanonical(PortableNode node, Map<String, PortableNode> existing) {
        // Exact externalId match
        if (existing.containsKey(node.externalId())) {
            return node.externalId();
        }
        // Fuzzy title match within same type
        String nodeTitle = node.title() != null ? node.title().toLowerCase().trim() : "";
        String nodeType = node.nodeType() != null ? node.nodeType() : "";
        if (nodeTitle.isEmpty()) return null;

        for (Map.Entry<String, PortableNode> entry : existing.entrySet()) {
            PortableNode other = entry.getValue();
            String otherType = other.nodeType() != null ? other.nodeType() : "";
            if (!nodeType.equals(otherType)) continue;
            String otherTitle = other.title() != null ? other.title().toLowerCase().trim() : "";
            if (otherTitle.isEmpty()) continue;
            if (levenshteinSimilarity(nodeTitle, otherTitle) >= SIMILARITY_THRESHOLD) {
                return entry.getKey();
            }
        }
        return null;
    }

    private PortableNode mergeNodes(PortableNode a, PortableNode b) {
        String desc = longerOf(a.description(), b.description());
        Map<String, Object> meta = new LinkedHashMap<>();
        if (a.metadata() != null) meta.putAll(a.metadata());
        if (b.metadata() != null) meta.putAll(b.metadata());
        return new PortableNode(a.externalId(), a.title(), desc, a.nodeType(),
                meta.isEmpty() ? null : meta);
    }

    private static String longerOf(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.length() >= b.length() ? a : b;
    }

    private static double levenshteinSimilarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - (double) levenshteinDistance(a, b) / maxLen;
    }

    private static int levenshteinDistance(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1], curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }
}
