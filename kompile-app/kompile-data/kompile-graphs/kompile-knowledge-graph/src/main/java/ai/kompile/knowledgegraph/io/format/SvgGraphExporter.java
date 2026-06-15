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
package ai.kompile.knowledgegraph.io.format;

import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Exports a {@link PortableGraph} as a static SVG vector graphic.
 * Nodes are positioned using a simple force-directed layout computed in Java
 * (no browser required). Suitable for embedding in documents or printing.
 */
public final class SvgGraphExporter {

    private static final int WIDTH = 1600;
    private static final int HEIGHT = 1200;
    private static final int ITERATIONS = 300;
    private static final double REPULSION = 5000.0;
    private static final double ATTRACTION = 0.01;
    private static final double DAMPING = 0.85;
    private static final String[] PALETTE = {
            "#4fc3f7", "#81c784", "#ffb74d", "#e57373", "#ba68c8",
            "#4db6ac", "#fff176", "#f06292", "#90a4ae", "#aed581",
            "#7986cb", "#ff8a65"
    };

    public byte[] toBytes(PortableGraph graph) {
        if (graph.nodes().isEmpty()) {
            return emptySvg();
        }

        // Assign positions via simple force layout
        Map<String, double[]> pos = layout(graph);

        // Degree for sizing
        Map<String, Integer> degree = new HashMap<>();
        for (PortableNode n : graph.nodes()) degree.put(n.externalId(), 0);
        for (PortableEdge e : graph.edges()) {
            degree.merge(e.fromExternalId(), 1, Integer::sum);
            degree.merge(e.toExternalId(), 1, Integer::sum);
        }
        int maxDeg = degree.values().stream().mapToInt(Integer::intValue).max().orElse(1);

        // Colour map
        List<String> types = graph.nodes().stream()
                .map(n -> n.nodeType() != null ? n.nodeType() : "UNKNOWN")
                .distinct().toList();
        Map<String, String> colorMap = new HashMap<>();
        for (int i = 0; i < types.size(); i++) {
            colorMap.put(types.get(i), PALETTE[i % PALETTE.length]);
        }

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(WIDTH)
                .append("\" height=\"").append(HEIGHT).append("\" viewBox=\"0 0 ")
                .append(WIDTH).append(" ").append(HEIGHT).append("\">\n");
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#0f1117\"/>\n");

        // Defs for arrow markers
        sb.append("<defs>\n");
        sb.append("  <marker id=\"arrow\" viewBox=\"0 0 10 10\" refX=\"10\" refY=\"5\" ");
        sb.append("markerWidth=\"6\" markerHeight=\"6\" orient=\"auto-start-reverse\">\n");
        sb.append("    <path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"#666\"/>\n");
        sb.append("  </marker>\n</defs>\n");

        // Edges
        for (PortableEdge e : graph.edges()) {
            double[] from = pos.get(e.fromExternalId());
            double[] to = pos.get(e.toExternalId());
            if (from == null || to == null) continue;
            String prov = e.provenance() != null ? e.provenance() : "";
            String stroke = switch (prov.toUpperCase()) {
                case "EXTRACTED" -> "#4caf50";
                case "INFERRED" -> "#ff9800";
                case "AMBIGUOUS" -> "#f44336";
                default -> "#555";
            };
            String dash = switch (prov.toUpperCase()) {
                case "INFERRED" -> " stroke-dasharray=\"6,3\"";
                case "AMBIGUOUS" -> " stroke-dasharray=\"3,5\"";
                default -> "";
            };
            sb.append("<line x1=\"").append(fmt(from[0])).append("\" y1=\"").append(fmt(from[1]))
                    .append("\" x2=\"").append(fmt(to[0])).append("\" y2=\"").append(fmt(to[1]))
                    .append("\" stroke=\"").append(stroke).append("\" stroke-width=\"1.2\" stroke-opacity=\"0.6\"")
                    .append(dash).append(" marker-end=\"url(#arrow)\"/>\n");
            // Edge label
            double mx = (from[0] + to[0]) / 2, my = (from[1] + to[1]) / 2;
            if (e.edgeType() != null) {
                sb.append("<text x=\"").append(fmt(mx)).append("\" y=\"").append(fmt(my - 4))
                        .append("\" font-size=\"8\" fill=\"#777\" text-anchor=\"middle\">")
                        .append(esc(e.edgeType())).append("</text>\n");
            }
        }

        // Nodes
        for (PortableNode n : graph.nodes()) {
            double[] p = pos.get(n.externalId());
            if (p == null) continue;
            int deg = degree.getOrDefault(n.externalId(), 0);
            double r = 6 + 14.0 * deg / Math.max(maxDeg, 1);
            String color = colorMap.getOrDefault(
                    n.nodeType() != null ? n.nodeType() : "UNKNOWN", "#888");
            sb.append("<circle cx=\"").append(fmt(p[0])).append("\" cy=\"").append(fmt(p[1]))
                    .append("\" r=\"").append(fmt(r)).append("\" fill=\"").append(color)
                    .append("\" stroke=\"#222\" stroke-width=\"1.5\"/>\n");
            // Label
            String label = n.title() != null ? n.title() : n.externalId();
            if (label.length() > 20) label = label.substring(0, 19) + "…";
            sb.append("<text x=\"").append(fmt(p[0])).append("\" y=\"").append(fmt(p[1] + r + 12))
                    .append("\" font-size=\"10\" fill=\"#bbb\" text-anchor=\"middle\" font-family=\"system-ui,sans-serif\">")
                    .append(esc(label)).append("</text>\n");
        }

        // Legend
        int ly = 30;
        sb.append("<rect x=\"").append(WIDTH - 160).append("\" y=\"10\" width=\"150\" height=\"")
                .append(20 + types.size() * 20).append("\" rx=\"6\" fill=\"#1a1d27\" stroke=\"#333\"/>\n");
        sb.append("<text x=\"").append(WIDTH - 145).append("\" y=\"").append(ly)
                .append("\" font-size=\"11\" fill=\"#e0e0e0\" font-weight=\"bold\" font-family=\"system-ui,sans-serif\">Node Types</text>\n");
        for (String t : types) {
            ly += 20;
            sb.append("<circle cx=\"").append(WIDTH - 140).append("\" cy=\"").append(ly - 4)
                    .append("\" r=\"5\" fill=\"").append(colorMap.get(t)).append("\"/>\n");
            sb.append("<text x=\"").append(WIDTH - 130).append("\" y=\"").append(ly)
                    .append("\" font-size=\"10\" fill=\"#bbb\" font-family=\"system-ui,sans-serif\">")
                    .append(esc(t)).append("</text>\n");
        }

        sb.append("</svg>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ─── Simple force-directed layout ────────────────────────────────

    private Map<String, double[]> layout(PortableGraph graph) {
        Random rng = new Random(42);
        Map<String, double[]> pos = new LinkedHashMap<>();
        Map<String, double[]> vel = new LinkedHashMap<>();
        for (PortableNode n : graph.nodes()) {
            pos.put(n.externalId(), new double[]{
                    WIDTH * 0.2 + rng.nextDouble() * WIDTH * 0.6,
                    HEIGHT * 0.2 + rng.nextDouble() * HEIGHT * 0.6});
            vel.put(n.externalId(), new double[]{0, 0});
        }

        List<String> ids = new ArrayList<>(pos.keySet());
        for (int iter = 0; iter < ITERATIONS; iter++) {
            // Repulsion between all pairs (Barnes-Hut would be better for large graphs)
            for (int i = 0; i < ids.size(); i++) {
                double[] pi = pos.get(ids.get(i));
                double[] vi = vel.get(ids.get(i));
                for (int j = i + 1; j < ids.size(); j++) {
                    double[] pj = pos.get(ids.get(j));
                    double[] vj = vel.get(ids.get(j));
                    double dx = pi[0] - pj[0], dy = pi[1] - pj[1];
                    double dist2 = dx * dx + dy * dy + 0.01;
                    double force = REPULSION / dist2;
                    double dist = Math.sqrt(dist2);
                    double fx = force * dx / dist, fy = force * dy / dist;
                    vi[0] += fx; vi[1] += fy;
                    vj[0] -= fx; vj[1] -= fy;
                }
            }
            // Attraction along edges
            for (PortableEdge e : graph.edges()) {
                double[] ps = pos.get(e.fromExternalId());
                double[] pt = pos.get(e.toExternalId());
                if (ps == null || pt == null) continue;
                double[] vs = vel.get(e.fromExternalId());
                double[] vt = vel.get(e.toExternalId());
                double dx = pt[0] - ps[0], dy = pt[1] - ps[1];
                double fx = ATTRACTION * dx, fy = ATTRACTION * dy;
                vs[0] += fx; vs[1] += fy;
                vt[0] -= fx; vt[1] -= fy;
            }
            // Apply velocity with damping, constrain to bounds
            double temp = 1.0 - (double) iter / ITERATIONS; // cooling
            for (String id : ids) {
                double[] p = pos.get(id);
                double[] v = vel.get(id);
                p[0] += v[0] * temp;
                p[1] += v[1] * temp;
                p[0] = Math.max(40, Math.min(WIDTH - 40, p[0]));
                p[1] = Math.max(40, Math.min(HEIGHT - 40, p[1]));
                v[0] *= DAMPING;
                v[1] *= DAMPING;
            }
        }
        return pos;
    }

    private byte[] emptySvg() {
        String s = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"600\">\n"
                + "<rect width=\"100%\" height=\"100%\" fill=\"#0f1117\"/>\n"
                + "<text x=\"400\" y=\"300\" font-size=\"16\" fill=\"#666\" text-anchor=\"middle\" "
                + "font-family=\"system-ui\">Empty graph</text>\n</svg>\n";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
