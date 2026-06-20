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

package ai.kompile.process.discovery.mining.export;

import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph.Arc;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import ai.kompile.process.discovery.mining.tree.ProcessTreeNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders discovered artifacts as <a href="https://mermaid.js.org/">Mermaid</a> diagram source — the
 * format the existing {@code ProcessDiagram} surface already renders. Pure string functions with no
 * framework dependency, so the UI can fetch ready-to-draw text and the logic stays unit-testable.
 */
public final class ProcessMermaidExporter {

    private ProcessMermaidExporter() {
    }

    /**
     * The directly-follows "process map": activities as nodes, directly-follows frequencies as labelled
     * edges, with explicit start/end markers. This is the canonical, business-readable process-mining view.
     */
    public static String dfgToMermaid(DirectlyFollowsGraph dfg) {
        StringBuilder sb = new StringBuilder("flowchart TD\n");
        sb.append("  start((start))\n  stop((end))\n");

        Map<String, String> id = new LinkedHashMap<>();
        int i = 0;
        for (String a : dfg.activities()) {
            String nid = "a" + (i++);
            id.put(a, nid);
            sb.append("  ").append(nid).append("[\"").append(escape(a)).append("\"]\n");
        }
        for (Map.Entry<String, Long> e : dfg.startActivities().entrySet()) {
            sb.append("  start -->|").append(e.getValue()).append("| ").append(id.get(e.getKey())).append('\n');
        }
        for (Map.Entry<Arc, Long> e : dfg.arcs().entrySet()) {
            sb.append("  ").append(id.get(e.getKey().from()))
                    .append(" -->|").append(e.getValue()).append("| ")
                    .append(id.get(e.getKey().to())).append('\n');
        }
        for (Map.Entry<String, Long> e : dfg.endActivities().entrySet()) {
            sb.append("  ").append(id.get(e.getKey())).append(" -->|").append(e.getValue()).append("| stop\n");
        }
        return sb.toString();
    }

    /** The process tree as a block-structure diagram: operator nodes (→ × ∧ ↺) over their children. */
    public static String treeToMermaid(ProcessTree tree) {
        StringBuilder sb = new StringBuilder("flowchart TD\n");
        emit(tree.root(), sb, new int[]{0}, null);
        return sb.toString();
    }

    private static void emit(ProcessTreeNode node, StringBuilder sb, int[] counter, String parentId) {
        String myId = "n" + (counter[0]++);
        String label = switch (node.operator()) {
            case ACTIVITY -> node.activity();
            case TAU -> node.operator().symbol();
            default -> node.operator().symbol();
        };
        sb.append("  ").append(myId).append("[\"").append(escape(label)).append("\"]\n");
        if (parentId != null) {
            sb.append("  ").append(parentId).append(" --> ").append(myId).append('\n');
        }
        for (ProcessTreeNode child : node.children()) {
            emit(child, sb, counter, myId);
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace('"', '\'').replace('\n', ' ');
    }
}
