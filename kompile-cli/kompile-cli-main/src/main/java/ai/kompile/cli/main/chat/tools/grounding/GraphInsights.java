/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import static ai.kompile.cli.main.chat.tools.grounding.CrawlInsights.count;
import static ai.kompile.cli.main.chat.tools.grounding.CrawlInsights.first;
import static ai.kompile.cli.main.chat.tools.grounding.CrawlInsights.message;
import static ai.kompile.cli.main.chat.tools.grounding.CrawlInsights.text;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.insights.Charts;
import ai.kompile.cli.insights.Format;
import ai.kompile.cli.insights.InsightReport;
import ai.kompile.cli.insights.InsightSource;
import ai.kompile.cli.insights.InsightsConfig;
import ai.kompile.cli.insights.InsightsQuery;
import ai.kompile.cli.insights.Neighborhood;
import ai.kompile.cli.insights.Neighborhood.Edge;
import ai.kompile.cli.insights.Neighborhood.Node;
import ai.kompile.cli.insights.TextTable;
import ai.kompile.cli.main.chat.tools.grounding.GroundingBackendClient.GroundingResponse;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Knowledge graphs for the {@code insights} tool: the project's knowledge graphs and, when the
 * session has a graph server, its fact sheets, each with its predicates, its entity types and
 * the relations one hop around one node.
 *
 * <p>The project's graphs are the knowledge bases {@code knowledge_graph list_fact_sheets} lists,
 * drawn from the {@code graph.kgraph} file each crawl wrote; a server's are its fact sheets, from
 * {@code GET /api/fact-sheets} and {@code GET /api/graph/{id}/reasoning-layers}. Reading sets up no
 * knowledge base and saves no graph.</p>
 */
public final class GraphInsights implements InsightSource {

    public static final String TOPIC = "graph";

    static final String LOCAL = "local";
    static final String SERVER = "server";
    static final String FACT_SHEETS_PATH = "/api/fact-sheets";
    /** How long the report waits for the server's fact sheet list; loading a graph keeps the client's longer default. */
    static final Duration LIST_READ_TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    /** Relations drawn around the focus; the rest are counted. */
    private static final int MAX_EDGES = 12;
    private static final int MAX_NAME = 40;
    private static final int MAX_NOTE = 200;
    /** A relation without a predicate, or an entity without a type. */
    private static final String NONE = "(none)";
    /** The graph a question names none of: one with something to draw, then the server's, the active one, the largest. */
    private static final Comparator<Sheet> DEFAULT_FIRST = Comparator.comparing(Sheet::drawable)
            .thenComparing(sheet -> !sheet.local())
            .thenComparing(Sheet::active)
            .thenComparingLong(Sheet::size);

    private final Path workDir;
    private final String serverUrl;
    private final GroundingBackendClient server;
    private final InsightsConfig config;

    /**
     * @param workDir      a directory of the project whose knowledge graphs to read
     * @param graphBaseUrl the server whose fact sheets to read, or null when the session's graphs
     *                     are project-local
     */
    public GraphInsights(Path workDir, String graphBaseUrl, InsightsConfig config) {
        this(workDir, graphBaseUrl,
                graphBaseUrl == null || graphBaseUrl.isBlank() ? null : new GroundingBackendClient(graphBaseUrl),
                config);
    }

    GraphInsights(Path workDir, String serverUrl, GroundingBackendClient server, InsightsConfig config) {
        this.workDir = workDir;
        this.serverUrl = serverUrl;
        this.server = server;
        this.config = config;
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String description() {
        return "graphs: the project's knowledge graphs and the server's fact sheets, their predicates and "
                + "entity types, and the relations around a node";
    }

    @Override
    public List<String> keywords() {
        return List.of("graph", "node", "entit", "relation", "edge", "predicate", "fact", "neighbo", "connect",
                "ontolog");
    }

    /** A knowledge graph as listed: a project knowledge base or a server fact sheet; a count is -1 when unknown. */
    record Sheet(String id, String name, String where, boolean active, long entities, long relations, long facts,
                 String graphPath) {

        boolean local() {
            return LOCAL.equals(where);
        }

        /** Whether the listing shows something to draw. */
        boolean drawable() {
            return local() ? graphPath != null && relations != 0 : facts != 0;
        }

        long size() {
            return Math.max(relations, facts);
        }
    }

    /** A loaded graph: its nodes by id and its relations. */
    record Graph(Map<String, Node> nodes, List<Edge> edges) {
    }

    private static final class Inventory {
        final List<Sheet> sheets = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
    }

    @Override
    public InsightReport report(InsightsQuery query) {
        Inventory inventory = inventory();
        List<String> notes = new ArrayList<>(inventory.notes);
        if (inventory.sheets.isEmpty()) {
            String headline = noGraphs();
            return finish(headline, new StringBuilder(headline).append('\n'), null, query, notes);
        }
        Map<String, Sheet> sheetNames = sheetNames(inventory.sheets);
        String named = query.matchSubject(sheetNames.keySet());
        Sheet sheet = named != null ? sheetNames.get(named) : inventory.sheets.stream().max(DEFAULT_FIRST).orElseThrow();
        Graph graph = load(sheet, notes);
        if (graph == null || (graph.nodes().isEmpty() && graph.edges().isEmpty())) {
            String headline = "Graph " + sheet.name() + ": " + (graph == null ? "not available" : "empty");
            StringBuilder text = new StringBuilder(headline).append("\n\n");
            source(text, sheet);
            appendSheets(text, inventory.sheets, sheet, graph);
            return finish(headline, text, null, query, notes);
        }

        Map<String, Integer> degrees = degrees(graph);
        Map<String, String> nodeNames = nodeNames(graph, degrees);
        if (named != null) {
            // The words that chose the graph do not also choose a node in it.
            nodeNames.keySet().removeIf(name -> name.equalsIgnoreCase(named));
        }
        String nodeSubject = query.matchSubject(nodeNames.keySet());
        String focusId = nodeSubject != null ? nodeNames.get(nodeSubject) : mostConnected(graph, degrees);
        Node focus = graph.nodes().getOrDefault(focusId, new Node(focusId, null, null));
        List<Edge> touching = graph.edges().stream()
                .filter(edge -> edge.source().equals(focusId) || edge.target().equals(focusId)).toList();
        Neighborhood neighborhood = new Neighborhood(focus, touching, graph.nodes()::get, MAX_EDGES);

        Map<String, Long> predicates = tally(graph.edges().stream().map(Edge::label));
        Map<String, Long> types = tally(graph.nodes().values().stream().map(Node::type));
        long predicateCount = predicates.keySet().stream().filter(name -> !NONE.equals(name)).count();
        String headline = "Graph " + sheet.name() + ": " + Format.count(graph.nodes().size(), "entity", "entities")
                + ", " + Format.count(graph.edges().size(), "relation") + ", " + Format.count(predicateCount, "predicate")
                + "; around " + focus.display();

        StringBuilder text = new StringBuilder(headline).append("\n\n");
        source(text, sheet);
        text.append('\n').append(nodeSubject != null ? "Relations around the node the question names:"
                : "Relations around the most connected node (" + Format.count(touching.size(), "relation")
                + "); name another node in quotes to center on it:").append('\n');
        text.append(neighborhood.render());
        appendTally(text, "Predicates, most used first:", "predicate", "relations", predicates,
                graph.edges().size(), "predicates");
        appendTally(text, "Entity types, most common first:", "type", "entities", types,
                graph.nodes().size(), "entity types");
        appendSheets(text, inventory.sheets, sheet, graph);
        ObjectNode chart = Charts.graph("Around " + focus.name() + " in " + sheet.name(), sheet.id(), sheet.name(),
                neighborhood, sheet.local() ? null : link(sheet, focusId));
        return finish(headline, text, chart, query, notes);
    }

    /** The overview line, from the listings alone: no graph is loaded. */
    @Override
    public String headline(InsightsQuery query) {
        Inventory inventory = inventory();
        String unlisted = inventory.notes.isEmpty() ? "" : "; some graphs could not be listed";
        if (inventory.sheets.isEmpty()) {
            return noGraphs() + unlisted;
        }
        long local = inventory.sheets.stream().filter(Sheet::local).count();
        long remote = inventory.sheets.size() - local;
        String where = local > 0 && remote > 0 ? " (" + Format.count(local) + " in this project, "
                + Format.count(remote) + " on the server)" : local > 0 ? " in this project" : " on the server";
        Sheet largest = inventory.sheets.stream().max(Comparator.comparingLong(Sheet::size)).orElseThrow();
        String size = largest.relations() >= 0 ? Format.count(largest.relations(), "relation")
                : largest.facts() >= 0 ? Format.count(largest.facts(), "fact") : null;
        return "Graphs: " + Format.count(inventory.sheets.size(), "knowledge graph") + where
                + (size == null ? "" : "; largest " + largest.name() + " with " + size) + unlisted;
    }

    private String noGraphs() {
        return "Graphs: no knowledge graphs" + (server == null ? " in this project" : "");
    }

    private void source(StringBuilder text, Sheet sheet) {
        text.append("Source: ").append(sheet.local()
                ? "knowledge base " + sheet.id() + " of this project"
                + (sheet.graphPath() == null ? "" : ", " + sheet.graphPath())
                : "fact sheet " + sheet.id() + " on the graph server " + serverUrl).append('\n');
    }

    private void appendTally(StringBuilder text, String title, String name, String unit, Map<String, Long> tally,
                             long total, String noun) {
        if (tally.isEmpty()) {
            return;
        }
        TextTable table = new TextTable(name, unit, "share").maxCell(MAX_NAME);
        int shown = Math.min(tally.size(), config.getMaxRows());
        tally.entrySet().stream().limit(shown).forEach(entry -> table.row(entry.getKey(),
                Format.count(entry.getValue()), Format.percent(entry.getValue(), total)));
        text.append('\n').append(title).append('\n').append(table.render());
        more(text, tally.size(), shown, noun);
    }

    /** Every graph, the shown one first and marked, when there is more than one to choose from. */
    private void appendSheets(StringBuilder text, List<Sheet> sheets, Sheet shown, Graph graph) {
        if (sheets.size() < 2) {
            return;
        }
        List<Sheet> ordered = new ArrayList<>(sheets);
        ordered.remove(shown);
        ordered.sort(Comparator.comparingLong(Sheet::size).reversed().thenComparing(Sheet::name));
        ordered.add(0, shown);
        boolean showFacts = sheets.stream().anyMatch(sheet -> sheet.facts() >= 0);
        boolean showWhere = sheets.stream().map(Sheet::where).distinct().count() > 1;
        // The marker has a column of its own: a table cell loses its leading spaces.
        List<String> headers = new ArrayList<>(List.of("", "knowledge graph", "entities", "relations"));
        if (showFacts) {
            headers.add("facts");
        }
        if (showWhere) {
            headers.add("where");
        }
        TextTable table = new TextTable(headers.toArray(String[]::new)).left(1).maxCell(MAX_NAME);
        if (showWhere) {
            table.left(headers.size() - 1);
        }
        int rows = Math.min(ordered.size(), config.getMaxRows());
        for (Sheet sheet : ordered.subList(0, rows)) {
            boolean current = sheet == shown;
            // The shown graph's counts are the loaded ones; a listing's may be stale or missing.
            long entities = current && graph != null ? graph.nodes().size() : sheet.entities();
            long relations = current && graph != null ? graph.edges().size() : sheet.relations();
            List<Object> row = new ArrayList<>(List.of(current ? "*" : "", sheet.name(), shown(entities),
                    shown(relations)));
            if (showFacts) {
                row.add(shown(sheet.facts()));
            }
            if (showWhere) {
                row.add(sheet.where());
            }
            table.row(row.toArray());
        }
        text.append("\nKnowledge graphs (* shown):\n").append(table.render());
        more(text, ordered.size(), rows, "knowledge graphs");
    }

    private InsightReport finish(String headline, StringBuilder text, ObjectNode chart, InsightsQuery query,
                                 List<String> notes) {
        List<String> all = new ArrayList<>(notes);
        if (query.getWindow().explicit()) {
            all.add("Graphs are shown as they are now; the time window does not apply to them.");
        }
        if (query.sessionScoped()) {
            all.add("Knowledge graphs are not kept per chat session, so this covers all of them.");
        }
        if (config.getWarning() != null) {
            all.add(config.getWarning());
        }
        if (!all.isEmpty()) {
            text.append('\n');
            all.forEach(note -> text.append(note).append('\n'));
        }
        return InsightReport.builder().topic(TOPIC).headline(headline).text(text.toString()).chart(chart).build();
    }

    private Inventory inventory() {
        Inventory inventory = new Inventory();
        listProject(inventory);
        listServer(inventory);
        return inventory;
    }

    private void listProject(Inventory inventory) {
        if (workDir == null) {
            return;
        }
        ArrayNode warnings = MAPPER.createArrayNode();
        ArrayNode items;
        try {
            items = new LocalProjectGraphBackend(MAPPER).factSheetInventory(workDir, warnings);
        } catch (IOException | RuntimeException e) {
            inventory.notes.add("The project's knowledge graphs could not be listed (" + message(e) + ").");
            return;
        }
        for (JsonNode item : items) {
            String id = text(item, "id");
            if (id != null) {
                inventory.sheets.add(new Sheet(id, first(text(item, "name"), id), LOCAL, false,
                        count(item.get("graphEntityCount")), count(item.get("graphRelationCount")), -1,
                        text(item, "graphPath")));
            }
        }
        for (JsonNode warning : warnings) {
            inventory.notes.add("Knowledge base " + first(text(warning, "id"), "(no id)") + ": "
                    + Format.clamp(first(text(warning, "message"), "unreadable"), MAX_NOTE));
        }
    }

    private void listServer(Inventory inventory) {
        if (server == null || !server.isAvailable()) {
            return;
        }
        GroundingResponse response;
        try {
            response = server.get(FACT_SHEETS_PATH, LIST_READ_TIMEOUT);
        } catch (RuntimeException e) {
            inventory.notes.add("The graph server at " + serverUrl + " could not be reached ("
                    + Format.clamp(GroundingBackendClient.failureReason(e), MAX_NOTE)
                    + "), so its fact sheets are missing.");
            return;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String reason = GroundingBackendClient.errorMessage(response.body());
            inventory.notes.add("The graph server at " + serverUrl + " answered HTTP " + response.statusCode()
                    + (reason.isBlank() ? "" : " (" + Format.clamp(reason, MAX_NOTE) + ")")
                    + ", so its fact sheets are missing.");
            return;
        }
        JsonNode sheets;
        try {
            sheets = MAPPER.readTree(response.body());
        } catch (IOException e) {
            sheets = null;
        }
        if (sheets == null || !sheets.isArray()) {
            inventory.notes.add("The graph server at " + serverUrl
                    + " sent a fact sheet list that could not be read, so its fact sheets are missing.");
            return;
        }
        for (JsonNode node : sheets) {
            String id = text(node, "id");
            if (id != null) {
                JsonNode active = node.has("isActive") ? node.get("isActive") : node.get("active");
                inventory.sheets.add(new Sheet(id, first(text(node, "name"), "fact sheet " + id), SERVER,
                        active != null && active.asBoolean(false), -1, -1, count(node.get("factCount")), null));
            }
        }
    }

    /** The graph, or null with a note saying why it is missing. */
    private Graph load(Sheet sheet, List<String> notes) {
        if (sheet.local() && sheet.graphPath() == null) {
            notes.add("Knowledge base " + sheet.name() + " has no graph file, so there is nothing to draw.");
            return null;
        }
        try {
            return sheet.local() ? projectGraph(Path.of(sheet.graphPath())) : serverGraph(sheet.id());
        } catch (IOException | RuntimeException e) {
            notes.add("The graph of " + sheet.name() + " could not be read (" + message(e) + ").");
            return null;
        }
    }

    static Graph projectGraph(Path graphPath) throws IOException {
        UnifiedGraph graph = UnifiedGraph.load(graphPath);
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (GraphEntity entity : graph.entities()) {
            if (entity.id() != null) {
                nodes.put(entity.id(), new Node(entity.id(), entity.label(), entity.type()));
            }
        }
        List<Edge> edges = new ArrayList<>();
        for (GraphRelation relation : graph.relations()) {
            if (relation.sourceId() != null && relation.targetId() != null) {
                edges.add(new Edge(relation.sourceId(), relation.targetId(), relation.type(), relation.directed()));
            }
        }
        return new Graph(nodes, edges);
    }

    private Graph serverGraph(String factSheetId) throws IOException {
        GroundingResponse response;
        try {
            response = server.get("/api/graph/" + factSheetId + "/reasoning-layers");
        } catch (RuntimeException e) {
            throw new IOException("the graph server could not be reached: "
                    + GroundingBackendClient.failureReason(e), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String reason = GroundingBackendClient.errorMessage(response.body());
            throw new IOException("the graph server answered HTTP " + response.statusCode()
                    + (reason.isBlank() ? "" : ": " + Format.clamp(reason, MAX_NOTE)));
        }
        return layersGraph(MAPPER.readTree(response.body()));
    }

    /** The reasoning-layers view of a fact sheet; its relations carry no direction, so all are read as directed. */
    static Graph layersGraph(JsonNode layers) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (JsonNode node : layers.path("nodes")) {
            String id = text(node, "nodeId");
            if (id != null) {
                nodes.putIfAbsent(id, new Node(id, text(node, "label"), text(node, "nodeType")));
            }
        }
        List<Edge> edges = new ArrayList<>();
        for (JsonNode edge : layers.path("edges")) {
            String source = text(edge, "sourceNodeId");
            String target = text(edge, "targetNodeId");
            if (source != null && target != null) {
                edges.add(new Edge(source, target, first(text(edge, "relationType"), text(edge, "edgeType")), true));
            }
        }
        return new Graph(nodes, edges);
    }

    /** The names a question may use for each graph; a name two graphs share goes to the first listed. */
    private static Map<String, Sheet> sheetNames(List<Sheet> sheets) {
        Map<String, Sheet> names = new LinkedHashMap<>();
        for (Sheet sheet : sheets) {
            if (sheet.local()) {
                if (nameable(sheet.id())) {
                    names.putIfAbsent(sheet.id(), sheet);
                }
            } else {
                // A server's ids are numbers, which a question names as "fact sheet 3", never bare.
                names.putIfAbsent("fact sheet " + sheet.id(), sheet);
                names.putIfAbsent("sheet " + sheet.id(), sheet);
            }
            if (nameable(sheet.name())) {
                names.putIfAbsent(sheet.name(), sheet);
            }
        }
        return names;
    }

    /** The names a question may use for each node, its id and its label; a shared name goes to the better connected. */
    private static Map<String, String> nodeNames(Graph graph, Map<String, Integer> degrees) {
        Map<String, String> names = new TreeMap<>();
        for (Node node : graph.nodes().values()) {
            for (String name : new String[] {node.id(), node.label()}) {
                if (nameable(name)) {
                    names.merge(name, node.id(), (kept, other) -> better(kept, other, degrees));
                }
            }
        }
        return names;
    }

    /** A name a question can use: not one character and not a bare number, which "last 3 days" would match. */
    private static boolean nameable(String name) {
        return name != null && name.strip().length() > 1 && !name.strip().chars().allMatch(Character::isDigit);
    }

    /** Relations per node; a relation from a node to itself counts once. */
    private static Map<String, Integer> degrees(Graph graph) {
        Map<String, Integer> degrees = new HashMap<>();
        for (Edge edge : graph.edges()) {
            degrees.merge(edge.source(), 1, Integer::sum);
            if (!edge.target().equals(edge.source())) {
                degrees.merge(edge.target(), 1, Integer::sum);
            }
        }
        return degrees;
    }

    /** The node with the most relations, the smallest id among equals. */
    private static String mostConnected(Graph graph, Map<String, Integer> degrees) {
        Stream<String> ids = degrees.isEmpty() ? graph.nodes().keySet().stream() : degrees.keySet().stream();
        return ids.reduce((a, b) -> better(a, b, degrees)).orElseThrow();
    }

    private static String better(String a, String b, Map<String, Integer> degrees) {
        int byDegree = Integer.compare(degrees.getOrDefault(a, 0), degrees.getOrDefault(b, 0));
        return byDegree > 0 || (byDegree == 0 && a.compareTo(b) <= 0) ? a : b;
    }

    /** Occurrences of each value, most frequent first, then by name; a blank value counts as {@value #NONE}. */
    private static Map<String, Long> tally(Stream<String> values) {
        Map<String, Long> counts = new HashMap<>();
        values.forEach(value -> counts.merge(value == null || value.isBlank() ? NONE : value, 1L, Long::sum));
        Map<String, Long> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    /** The chat app's graph page, on the fact sheet, centered on the focus. */
    static String link(Sheet sheet, String focusId) {
        return "#/graph?factSheetId=" + encode(sheet.id()) + "&focusNode=" + encode(focusId);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String shown(long n) {
        return n < 0 ? "-" : Format.count(n);
    }

    private static void more(StringBuilder text, int total, int shown, String noun) {
        if (total > shown) {
            text.append("(+").append(Format.count(total - shown)).append(" more ").append(noun).append(")\n");
        }
    }
}
