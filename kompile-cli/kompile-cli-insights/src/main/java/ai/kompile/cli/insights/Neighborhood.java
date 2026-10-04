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

package ai.kompile.cli.insights;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The relations one hop around a focus node, drawn as a small tree for the terminal and an
 * agent's context, and kept as the nodes and edges a {@link Charts#graph graph chart} draws:
 * <pre>
 * Alice (PERSON)
 * ├─ worksFor → Acme (ORGANIZATION)
 * ├─ ← manages ─ Carol (PERSON)
 * └─ (+3 more relations)
 * </pre>
 * Outgoing relations come first, then incoming, then undirected ones, each kind sorted by
 * relation and neighbour, so the same graph always draws the same way. When there are more
 * relations than fit, every relation kind keeps one line before any kind gets a second.
 */
public final class Neighborhood {

    private static final int MAX_TEXT = 60;
    private static final String NO_LABEL = "related";

    /** A graph node; {@code label} and {@code type} may be null. */
    public record Node(String id, String label, String type) {

        /** The label, or the id when there is none, on one line. */
        public String name() {
            return Format.clamp(label == null || label.isBlank() ? id : label, MAX_TEXT);
        }

        /** "Acme (ORGANIZATION)". */
        public String display() {
            return type == null || type.isBlank() ? name() : name() + " (" + Format.clamp(type, MAX_TEXT) + ")";
        }
    }

    /** A relation; {@code label} is its predicate or type and may be null. */
    public record Edge(String source, String target, String label, boolean directed) {
    }

    /** A relation with its other end resolved and the text it sorts by. */
    private record Line(Edge edge, Node neighbour, String sortKey) {
    }

    private final Node focus;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Line> lines = new ArrayList<>();
    private final int omitted;

    /**
     * @param relations the relations that touch the focus, in any order
     * @param lookup    the node with an id, or null when the graph does not have it (it then
     *                  shows as its id)
     * @param maxEdges  how many relations to draw; the rest are counted as omitted
     * @throws IllegalArgumentException when a relation does not touch the focus
     */
    public Neighborhood(Node focus, Collection<Edge> relations, Function<String, Node> lookup, int maxEdges) {
        this.focus = focus;
        nodes.put(focus.id(), focus);
        Map<String, List<Line>> kinds = new TreeMap<>();
        for (Edge edge : relations) {
            if (edge.source() == null || edge.target() == null
                    || (!focus.id().equals(edge.source()) && !focus.id().equals(edge.target()))) {
                throw new IllegalArgumentException("Relation " + edge.source() + " -> " + edge.target()
                        + " does not touch " + focus.id());
            }
            Node neighbour = neighbour(edge, lookup);
            String label = edge.label() == null ? "" : edge.label().toLowerCase(Locale.ROOT);
            kinds.computeIfAbsent(direction(edge) + " " + label, k -> new ArrayList<>())
                    .add(new Line(edge, neighbour, neighbour.display()));
        }
        List<List<Line>> groups = new ArrayList<>(kinds.values());
        groups.forEach(group -> group.sort(Comparator.comparing(Line::sortKey)));
        // Deal one line per kind per round until the view is full.
        int[] take = new int[groups.size()];
        int kept = 0;
        boolean more = true;
        for (int round = 0; more && kept < maxEdges; round++) {
            more = false;
            for (int g = 0; g < groups.size() && kept < maxEdges; g++) {
                if (round < groups.get(g).size()) {
                    take[g]++;
                    kept++;
                    more = true;
                }
            }
        }
        for (int g = 0; g < groups.size(); g++) {
            for (Line line : groups.get(g).subList(0, take[g])) {
                lines.add(line);
                nodes.putIfAbsent(line.neighbour().id(), line.neighbour());
            }
        }
        this.omitted = relations.size() - kept;
    }

    public Node focus() {
        return focus;
    }

    /** The focus, then the drawn neighbours, each once. */
    public List<Node> nodes() {
        return List.copyOf(nodes.values());
    }

    /** The drawn relations, in drawing order. */
    public List<Edge> edges() {
        List<Edge> edges = new ArrayList<>(lines.size());
        lines.forEach(line -> edges.add(line.edge()));
        return Collections.unmodifiableList(edges);
    }

    /** Relations that touch the focus but were not drawn. */
    public int omitted() {
        return omitted;
    }

    public String render() {
        StringBuilder out = new StringBuilder(focus.display()).append('\n');
        if (lines.isEmpty() && omitted == 0) {
            return out.append("└─ (no relations)\n").toString();
        }
        for (int i = 0; i < lines.size(); i++) {
            boolean last = i == lines.size() - 1 && omitted == 0;
            out.append(last ? "└─ " : "├─ ").append(describe(lines.get(i))).append('\n');
        }
        if (omitted > 0) {
            out.append("└─ (+").append(Format.count(omitted, "more relation", "more relations")).append(")\n");
        }
        return out.toString();
    }

    private String describe(Line line) {
        Edge edge = line.edge();
        String relation = edge.label() == null || edge.label().isBlank()
                ? NO_LABEL : Format.clamp(edge.label(), MAX_TEXT);
        String neighbour = line.neighbour().display();
        if (!edge.directed()) {
            return relation + " ─ " + neighbour;
        }
        return focus.id().equals(edge.source())
                ? relation + " → " + neighbour
                : "← " + relation + " ─ " + neighbour;
    }

    /** 0 outgoing (a self-loop counts as outgoing), 1 incoming, 2 undirected. */
    private int direction(Edge edge) {
        if (!edge.directed()) {
            return 2;
        }
        return focus.id().equals(edge.source()) ? 0 : 1;
    }

    private Node neighbour(Edge edge, Function<String, Node> lookup) {
        String id = focus.id().equals(edge.source()) ? edge.target() : edge.source();
        if (id.equals(focus.id())) {
            return focus;
        }
        Node node = lookup.apply(id);
        return node != null ? node : new Node(id, null, null);
    }
}
