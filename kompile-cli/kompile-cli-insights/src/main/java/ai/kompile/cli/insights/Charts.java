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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Chart descriptions that travel with a report, so the web tool card can draw the same answer
 * the terminal shows as a table:
 * <pre>{"v":1, "kind":"bar"|"line", "title":..., "unit":..., "labels":[...],
 *  "series":[{"name":..., "values":[...]}]}</pre>
 * A missing data point is written as null. A graph answer is a {@link #graph graph chart}
 * instead.
 */
public final class Charts {

    public static final int VERSION = 1;
    public static final String BAR = "bar";
    public static final String LINE = "line";
    public static final String GRAPH = "graph";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Charts() {
    }

    public static ObjectNode bar(String title, String unit, List<String> labels, Map<String, double[]> series) {
        return series(BAR, title, unit, labels, series);
    }

    public static ObjectNode line(String title, String unit, List<String> labels, Map<String, double[]> series) {
        return series(LINE, title, unit, labels, series);
    }

    /** @param series name to values, in display order; each has one value per label */
    public static ObjectNode series(String kind, String title, String unit, List<String> labels,
                                    Map<String, double[]> series) {
        ObjectNode chart = NODES.objectNode();
        chart.put("v", VERSION);
        chart.put("kind", kind);
        chart.put("title", title);
        if (unit != null) {
            chart.put("unit", unit);
        }
        ArrayNode labelArray = chart.putArray("labels");
        labels.forEach(labelArray::add);
        ArrayNode seriesArray = chart.putArray("series");
        series.forEach((name, values) -> {
            ObjectNode entry = seriesArray.addObject();
            entry.put("name", name);
            ArrayNode valueArray = entry.putArray("values");
            for (double value : values) {
                addNumber(valueArray, value);
            }
        });
        return chart;
    }

    /**
     * A node and its neighbours, for the web card's small graph view:
     * <pre>{"v":1, "kind":"graph", "title":..., "factSheet":{"id":..., "name":...},
     *  "focus":node id, "nodes":[{"id":..., "label":..., "type":...}],
     *  "edges":[{"source":..., "target":..., "label":..., "directed":...}],
     *  "omitted":relations not drawn, "link":...}</pre>
     * A node's type and an edge's label are left out when unknown.
     *
     * @param link the web route that opens the full graph at the focus, or null when the graph
     *             is not on a server the web app can show
     */
    public static ObjectNode graph(String title, String factSheetId, String factSheetName,
                                   Neighborhood neighborhood, String link) {
        ObjectNode chart = NODES.objectNode();
        chart.put("v", VERSION);
        chart.put("kind", GRAPH);
        chart.put("title", title);
        ObjectNode factSheet = chart.putObject("factSheet");
        factSheet.put("id", factSheetId);
        factSheet.put("name", factSheetName);
        chart.put("focus", neighborhood.focus().id());
        ArrayNode nodes = chart.putArray("nodes");
        for (Neighborhood.Node node : neighborhood.nodes()) {
            ObjectNode entry = nodes.addObject();
            entry.put("id", node.id());
            entry.put("label", node.name());
            if (node.type() != null && !node.type().isBlank()) {
                entry.put("type", node.type());
            }
        }
        ArrayNode edges = chart.putArray("edges");
        for (Neighborhood.Edge edge : neighborhood.edges()) {
            ObjectNode entry = edges.addObject();
            entry.put("source", edge.source());
            entry.put("target", edge.target());
            if (edge.label() != null && !edge.label().isBlank()) {
                entry.put("label", edge.label());
            }
            entry.put("directed", edge.directed());
        }
        chart.put("omitted", neighborhood.omitted());
        if (link != null) {
            chart.put("link", link);
        }
        return chart;
    }

    /** Whole numbers stay integers; others keep two decimals; NaN and infinities become null. */
    static void addNumber(ArrayNode array, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            array.addNull();
        } else if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            array.add((long) value);
        } else {
            array.add(Math.round(value * 100) / 100.0);
        }
    }

    static double[] toDoubles(long[] values) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }
}
