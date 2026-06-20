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

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The general, domain-agnostic case notion: each weakly-connected component of the graph (optionally
 * restricted to a set of edge types) is one case. No configuration is required, so any KG yields an
 * event log; restricting {@code allowedEdgeTypes} (e.g. to flow/causal edges) gives tighter,
 * more meaningful process instances on dense graphs.
 */
public final class ConnectedComponentCorrelation implements CaseCorrelation {

    private final Set<EdgeType> allowedEdgeTypes; // null ⇒ all edge types connect

    public ConnectedComponentCorrelation() {
        this(null);
    }

    public ConnectedComponentCorrelation(Set<EdgeType> allowedEdgeTypes) {
        this.allowedEdgeTypes = (allowedEdgeTypes == null || allowedEdgeTypes.isEmpty())
                ? null : EnumSet.copyOf(allowedEdgeTypes);
    }

    @Override
    public Map<String, List<String>> correlate(List<GraphNode> nodes, List<GraphEdge> edges) {
        Map<String, String> parent = new LinkedHashMap<>();
        for (GraphNode n : nodes) {
            if (n.getNodeId() != null) {
                parent.put(n.getNodeId(), n.getNodeId());
            }
        }
        if (edges != null) {
            for (GraphEdge e : edges) {
                if (allowedEdgeTypes != null && !allowedEdgeTypes.contains(e.getEdgeType())) {
                    continue;
                }
                String s = endpointId(e.getSourceNode());
                String t = endpointId(e.getTargetNode());
                if (s != null && t != null && parent.containsKey(s) && parent.containsKey(t)) {
                    union(parent, s, t);
                }
            }
        }
        // Group by component root, then relabel to stable case-1, case-2, … in encounter order.
        Map<String, List<String>> byRoot = new LinkedHashMap<>();
        for (String id : parent.keySet()) {
            byRoot.computeIfAbsent(find(parent, id), k -> new ArrayList<>()).add(id);
        }
        Map<String, List<String>> cases = new LinkedHashMap<>();
        int i = 1;
        for (List<String> members : byRoot.values()) {
            cases.put("case-" + (i++), members);
        }
        return cases;
    }

    private static String endpointId(GraphNode node) {
        return node == null ? null : node.getNodeId();
    }

    private static String find(Map<String, String> parent, String x) {
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

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }
}
