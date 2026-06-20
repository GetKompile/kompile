/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining.extract;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Object-centric case notion: each entity of a chosen <em>anchor type</em> (e.g. "ORDER", "INVOICE",
 * "TICKET") seeds one case, and every other node is attached to its <em>nearest</em> anchor (multi-source
 * BFS, bounded by {@code maxHops}). This is the right notion for dense graphs where
 * {@link ConnectedComponentCorrelation} would collapse everything into one giant case — it pins each
 * process instance to a real business object, the way object-centric process mining recommends.
 *
 * <p>Nodes not reachable from any anchor within {@code maxHops} are dropped (they belong to no instance).
 */
public final class AnchorTypeCorrelation implements CaseCorrelation {

    private final String anchorEntityType;
    private final int maxHops;

    public AnchorTypeCorrelation(String anchorEntityType) {
        this(anchorEntityType, 3);
    }

    public AnchorTypeCorrelation(String anchorEntityType, int maxHops) {
        this.anchorEntityType = anchorEntityType;
        this.maxHops = Math.max(1, maxHops);
    }

    @Override
    public Map<String, List<String>> correlate(List<GraphNode> nodes, List<GraphEdge> edges) {
        Set<String> ids = new LinkedHashSet<>();
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (GraphNode n : nodes) {
            if (n.getNodeId() != null) {
                ids.add(n.getNodeId());
                adjacency.putIfAbsent(n.getNodeId(), new ArrayList<>());
            }
        }
        if (edges != null) {
            for (GraphEdge e : edges) {
                String s = e.getSourceNode() != null ? e.getSourceNode().getNodeId() : null;
                String t = e.getTargetNode() != null ? e.getTargetNode().getNodeId() : null;
                if (s != null && t != null && ids.contains(s) && ids.contains(t)) {
                    adjacency.get(s).add(t);
                    adjacency.get(t).add(s);
                }
            }
        }

        // Multi-source BFS from every anchor at once ⇒ each node lands in its nearest anchor's case.
        Map<String, String> caseOf = new LinkedHashMap<>();
        Map<String, Integer> dist = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (GraphNode n : nodes) {
            Object entityType = n.getMetadata().get("entity_type");
            if (entityType instanceof String s && s.equalsIgnoreCase(anchorEntityType)) {
                caseOf.put(n.getNodeId(), n.getNodeId());
                dist.put(n.getNodeId(), 0);
                queue.add(n.getNodeId());
            }
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            int d = dist.get(current);
            if (d >= maxHops) {
                continue;
            }
            for (String neighbour : adjacency.getOrDefault(current, List.of())) {
                if (!caseOf.containsKey(neighbour)) {
                    caseOf.put(neighbour, caseOf.get(current));
                    dist.put(neighbour, d + 1);
                    queue.add(neighbour);
                }
            }
        }

        Map<String, List<String>> cases = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : caseOf.entrySet()) {
            cases.computeIfAbsent("case-" + e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        return cases;
    }
}
