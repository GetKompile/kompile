/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.main.app.AppClientMixin;
import ai.kompile.cli.main.app.OutputFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Server-backed graph operations for the code index. Bridges the local
 * {@code code-index} command to the running kompile-app's code graph
 * endpoints for building, querying, and exporting code graphs.
 *
 * <p>Usage:
 * <pre>
 *   kompile code-index graph build /path/to/project
 *   kompile code-index graph export --format=svg -o graph.svg
 *   kompile code-index graph symbol com.example.MyClass
 *   kompile code-index graph callers processOrder
 *   kompile code-index graph relations com.example.MyClass
 *   kompile code-index graph file src/main/java/MyClass.java
 *   kompile code-index graph search "service"
 *   kompile code-index graph stats
 * </pre>
 */
@CommandLine.Command(
        name = "graph",
        description = "Code graph operations (requires running kompile-app)",
        subcommands = {
                CodeIndexGraphCommand.BuildCmd.class,
                CodeIndexGraphCommand.ExportCmd.class,
                CodeIndexGraphCommand.SymbolCmd.class,
                CodeIndexGraphCommand.CallersCmd.class,
                CodeIndexGraphCommand.RelationsCmd.class,
                CodeIndexGraphCommand.FileCmd.class,
                CodeIndexGraphCommand.SearchCmd.class,
                CodeIndexGraphCommand.StatsCmd.class,
                CodeIndexGraphCommand.ConnectivityCmd.class,
                CodeIndexGraphCommand.SpathCmd.class,
                CodeIndexGraphCommand.ImpactCmd.class,
                CodeIndexGraphCommand.DepsCmd.class,
                CodeIndexGraphCommand.ComponentCmd.class,
                CodeIndexGraphCommand.DossierCmd.class,
                CodeIndexGraphCommand.ExportLocalCmd.class,
                CodeIndexGraphCommand.TestFrameworksCmd.class,
                CodeIndexGraphCommand.TestCoverageCmd.class,
                CodeIndexGraphCommand.TestsForCmd.class,
                CodeIndexGraphCommand.CodePathsCmd.class
        },
        mixinStandardHelpOptions = true
)
public class CodeIndexGraphCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    // ── build ────────────────────────────────────────────────────────────────

    @CommandLine.Command(name = "build",
            description = "Index a directory and build its code knowledge graph")
    static class BuildCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", arity = "0..1",
                description = "Directory to index (default: current directory)")
        private String directory;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default",
                description = "Project identifier")
        private String projectId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                Path dir = directory != null
                        ? Paths.get(directory).toAbsolutePath()
                        : Paths.get(System.getProperty("user.dir"));

                System.out.println("Building code graph for: " + dir);
                String response = client.postString("/api/code-indexer/graph/build",
                        Map.of("projectId", projectId, "directoryPath", dir.toString()));

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode result = client.getObjectMapper().readTree(response);
                System.out.println();
                System.out.println("Build Result:");
                printField(result, "filesProcessed", "  Files indexed");
                printField(result, "entitiesFound", "  Entities found");
                printField(result, "relationsCreated", "  Relations created");
                printField(result, "nodesAdded", "  Graph nodes added");
                printField(result, "edgesAdded", "  Graph edges added");
                printField(result, "durationMs", "  Duration (ms)");
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── export ────────���──────────────────────────────────────────────────────

    @CommandLine.Command(name = "export",
            description = "Export the code graph as SVG, HTML, or JSON")
    static class ExportCmd implements Callable<Integer> {
        static final Set<String> FORMATS = Set.of("svg", "html", "json");

        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = "--format", required = true,
                description = "Export format: svg, html, json")
        private String format;

        @CommandLine.Option(names = {"--output", "-o"}, required = true,
                description = "Output file path")
        private Path output;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default",
                description = "Project identifier")
        private String projectId;

        @CommandLine.Option(names = "--max-nodes", defaultValue = "500",
                description = "Maximum nodes to include in the graph")
        private int maxNodes;

        @Override
        public Integer call() {
            if (!FORMATS.contains(format.toLowerCase())) {
                System.err.println("Unsupported format: " + format + ". Supported: " + FORMATS);
                return 1;
            }
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/export?projectId="
                        + enc(projectId)
                        + "&format=" + enc(format)
                        + "&maxNodes=" + maxNodes;
                client.downloadToFile(url, output);
                System.out.println("Exported code graph to: " + output.toAbsolutePath());
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ���─ symbol ─────────���─────────────────────────────────────────────────────

    @CommandLine.Command(name = "symbol",
            description = "Look up a symbol by FQN and show its graph connections")
    static class SymbolCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0",
                description = "Fully qualified name of the symbol")
        private String fqn;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @CommandLine.Option(names = {"--depth", "-d"}, defaultValue = "2",
                description = "Traversal depth (hops)")
        private int depth;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/symbol?projectId=" + enc(projectId)
                        + "&fqn=" + enc(fqn)
                        + "&depth=" + depth;
                String response = client.getString(url);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                JsonNode entity = root.get("entity");
                if (entity != null && !entity.isNull()) {
                    System.out.println("Symbol: " + textOr(entity, "fullyQualifiedName", fqn));
                    System.out.println("  Type:      " + textOr(entity, "entityType", "?"));
                    System.out.println("  Language:  " + textOr(entity, "language", "?"));
                    System.out.println("  File:      " + textOr(entity, "filePath", "?"));
                    if (entity.has("startLine"))
                        System.out.println("  Lines:     " + entity.get("startLine").asText()
                                + "-" + textOr(entity, "endLine", "?"));
                    if (entity.has("signature") && !entity.get("signature").isNull())
                        System.out.println("  Signature: " + entity.get("signature").asText());
                } else {
                    System.out.println("Symbol '" + fqn + "' not found in code index.");
                }

                JsonNode connected = root.get("connectedNodes");
                if (connected != null && connected.isArray() && !connected.isEmpty()) {
                    System.out.println();
                    System.out.println("Connected Nodes (" + connected.size() + "):");
                    for (JsonNode n : connected) {
                        System.out.printf("  %-10s  %s%n",
                                textOr(n, "nodeType", "?"),
                                textOr(n, "title", textOr(n, "externalId", "?")));
                    }
                }

                JsonNode viz = root.path("visualization");
                if (viz.has("edges") && viz.get("edges").isArray()) {
                    int edgeCount = viz.get("edges").size();
                    int nodeCount = viz.has("nodes") ? viz.get("nodes").size() : 0;
                    System.out.println();
                    System.out.println("Subgraph: " + nodeCount + " nodes, " + edgeCount + " edges");
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── callers ───────────��───────────────────────────���──────────────────────

    @CommandLine.Command(name = "callers",
            description = "Find functions/methods that call a given symbol")
    static class CallersCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0",
                description = "Function or method name to find callers of")
        private String name;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/callers?projectId=" + enc(projectId)
                        + "&name=" + enc(name);
                String response = client.getString(url);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                if (root.isArray()) {
                    if (root.isEmpty()) {
                        System.out.println("No callers found for '" + name + "'.");
                    } else {
                        System.out.println("Callers of '" + name + "' (" + root.size() + "):");
                        for (JsonNode caller : root) {
                            String callerFqn = textOr(caller, "fullyQualifiedName",
                                    textOr(caller, "name", "?"));
                            String file = textOr(caller, "filePath", "");
                            String line = caller.has("startLine") ? ":" + caller.get("startLine").asText() : "";
                            System.out.printf("  %-50s  %s%s%n", callerFqn, file, line);
                        }
                    }
                } else {
                    OutputFormatter.printJson(response);
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── relations ───────────��────────────────────────────────────────────────

    @CommandLine.Command(name = "relations",
            description = "Show code relations for a symbol (extends, implements, calls, etc.)")
    static class RelationsCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0",
                description = "Fully qualified name of the entity")
        private String fqn;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @CommandLine.Option(names = {"--type", "-t"},
                description = "Filter by relation type (EXTENDS, IMPLEMENTS, CALLS, etc.)")
        private String relationType;

        @CommandLine.Option(names = {"--reverse", "-r"},
                description = "Show reverse relations (who extends/implements/calls THIS symbol)")
        private boolean reverse;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String endpoint = reverse ? "/api/code-indexer/reverse-relations"
                        : "/api/code-indexer/relations";
                StringBuilder url = new StringBuilder(endpoint)
                        .append("?projectId=").append(enc(projectId))
                        .append("&fqn=").append(enc(fqn));
                if (relationType != null) url.append("&type=").append(enc(relationType));

                String response = client.getString(url.toString());

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                if (root.isArray()) {
                    String direction = reverse ? "Reverse relations" : "Relations";
                    if (root.isEmpty()) {
                        System.out.println("No " + direction.toLowerCase() + " found for '" + fqn + "'.");
                    } else {
                        System.out.println(direction + " for '" + fqn + "' (" + root.size() + "):");
                        for (JsonNode rel : root) {
                            String type = textOr(rel, "relationType", "?");
                            String source = textOr(rel, "sourceFqn", "?");
                            String target = textOr(rel, "targetFqn",
                                    textOr(rel, "targetName", "?"));
                            System.out.printf("  %-14s  %s -> %s%n", type, source, target);
                        }
                    }
                } else {
                    OutputFormatter.printJson(response);
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── file ──────���──────────────────────���───────────────────────────────────

    @CommandLine.Command(name = "file",
            description = "Show all code symbols in a file and their graph connections")
    static class FileCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0",
                description = "File path (relative to project root)")
        private String filePath;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/file?projectId=" + enc(projectId)
                        + "&filePath=" + enc(filePath);
                String response = client.getString(url);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                JsonNode entities = root.get("entities");
                if (entities != null && entities.isArray() && !entities.isEmpty()) {
                    System.out.println("Symbols in " + filePath + " (" + entities.size() + "):");
                    for (JsonNode e : entities) {
                        String type = textOr(e, "entityType", "?");
                        String name = textOr(e, "name", "?");
                        String line = e.has("startLine") ? ":" + e.get("startLine").asText() : "";
                        System.out.printf("  %-12s  %-40s  %s%s%n",
                                type, name,
                                textOr(e, "fullyQualifiedName", ""),
                                line);
                    }
                } else {
                    System.out.println("No symbols found in '" + filePath + "'.");
                }

                JsonNode viz = root.path("visualization");
                if (viz.has("edges") && viz.get("edges").isArray()) {
                    System.out.println();
                    System.out.println("Graph: " +
                            (viz.has("nodes") ? viz.get("nodes").size() : 0) + " nodes, " +
                            viz.get("edges").size() + " edges");
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── search ───────��───────────────────────────────────────────────────────

    @CommandLine.Command(name = "search",
            description = "Search the code graph (entities + knowledge graph nodes)")
    static class SearchCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Search query")
        private String query;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @CommandLine.Option(names = {"--max", "-m"}, defaultValue = "20",
                description = "Maximum results")
        private int maxResults;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/search?projectId=" + enc(projectId)
                        + "&query=" + enc(query)
                        + "&maxResults=" + maxResults;
                String response = client.getString(url);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                JsonNode codeEntities = root.get("codeEntities");
                if (codeEntities != null && codeEntities.isArray() && !codeEntities.isEmpty()) {
                    System.out.println("Code Entities (" + codeEntities.size() + "):");
                    for (JsonNode e : codeEntities) {
                        String type = textOr(e, "entityType", "?");
                        String fqn = textOr(e, "fullyQualifiedName",
                                textOr(e, "name", "?"));
                        String file = textOr(e, "filePath", "");
                        String line = e.has("startLine") ? ":" + e.get("startLine").asText() : "";
                        System.out.printf("  %-12s  %-50s  %s%s%n", type, fqn, file, line);
                    }
                }

                JsonNode graphNodes = root.get("graphNodes");
                if (graphNodes != null && graphNodes.isArray() && !graphNodes.isEmpty()) {
                    System.out.println();
                    System.out.println("Graph Nodes (" + graphNodes.size() + "):");
                    for (JsonNode n : graphNodes) {
                        System.out.printf("  %-10s  %s%n",
                                textOr(n, "nodeType", "?"),
                                textOr(n, "title", textOr(n, "externalId", "?")));
                    }
                }

                if ((codeEntities == null || codeEntities.isEmpty()) &&
                    (graphNodes == null || graphNodes.isEmpty())) {
                    System.out.println("No results found for '" + query + "'.");
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── stats ────────────────────────────────────────────────────────────────

    @CommandLine.Command(name = "stats",
            description = "Show combined code index and graph statistics")
    static class StatsCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/statistics?projectId=" + enc(projectId);
                String response = client.getString(url);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);

                JsonNode codeIndex = root.get("codeIndex");
                if (codeIndex != null) {
                    System.out.println("Code Index:");
                    codeIndex.fields().forEachRemaining(e ->
                            OutputFormatter.printKv("  " + e.getKey(), e.getValue()));
                }

                JsonNode kg = root.get("knowledgeGraph");
                if (kg != null) {
                    System.out.println();
                    System.out.println("Knowledge Graph:");
                    kg.fields().forEachRemaining(e ->
                            OutputFormatter.printKv("  " + e.getKey(), e.getValue()));
                }

                if (root.has("trackedDirectories")) {
                    System.out.println();
                    System.out.println("Tracked Directories: " + root.get("trackedDirectories").asText());
                    JsonNode dirs = root.get("directories");
                    if (dirs != null && dirs.isArray()) {
                        for (JsonNode d : dirs) {
                            System.out.printf("  %-60s  %s  files=%s  entities=%s%n",
                                    textOr(d, "path", "?"),
                                    textOr(d, "status", "?"),
                                    textOr(d, "filesIndexed", "0"),
                                    textOr(d, "entitiesFound", "0"));
                        }
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── connectivity ─────��───────────────────────────────────────────────────

    @CommandLine.Command(name = "connectivity",
            description = "Ensure all cross-reference edges are created in the graph")
    static class ConnectivityCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postEmpty(
                        "/api/code-indexer/graph/ensure-connectivity?projectId=" + enc(projectId));

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                int edges = root.has("edgesAdded") ? root.get("edgesAdded").asInt() : 0;
                System.out.println("Connectivity pass complete: " + edges + " edges added.");
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── spath ──────────────────────────────────────────────────────────────

    @CommandLine.Command(name = "spath", description = "Find shortest path between two symbols",
            mixinStandardHelpOptions = true)
    static class SpathCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Source symbol FQN")
        private String from;

        @CommandLine.Parameters(index = "1", description = "Target symbol FQN")
        private String to;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @CommandLine.Option(names = {"--edge-type"}, description = "Filter by edge type")
        private String edgeType;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                StringBuilder url = new StringBuilder("/api/code-indexer/graph/shortest-path?projectId=")
                        .append(enc(projectId))
                        .append("&from=").append(enc(from))
                        .append("&to=").append(enc(to));
                if (edgeType != null && !edgeType.isEmpty()) {
                    url.append("&edgeType=").append(enc(edgeType));
                }

                String response = client.getString(url.toString());

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);

                if (root.has("error")) {
                    System.err.println("Error: " + root.get("error").asText());
                    return 1;
                }

                boolean pathFound = root.has("pathFound") && root.get("pathFound").asBoolean();
                if (!pathFound) {
                    System.out.println("No path found between:");
                    System.out.println("  from: " + from);
                    System.out.println("  to:   " + to);
                    return 0;
                }

                int hops = root.get("hops").asInt();
                System.out.println("Shortest path (" + hops + " hop" + (hops != 1 ? "s" : "") + "):");
                System.out.println();

                JsonNode path = root.get("path");
                for (int i = 0; i < path.size(); i++) {
                    JsonNode node = path.get(i);
                    String externalId = textOr(node, "externalId", "");
                    String title = textOr(node, "title", externalId);
                    String type = textOr(node, "type", "");
                    String prefix = i == 0 ? "  START" : i == path.size() - 1 ? "  END  " : "  [" + i + "]  ";
                    System.out.printf("%-8s %-10s %s%n", prefix, "[" + type + "]", title);
                    if (!externalId.equals(title) && !externalId.isEmpty()) {
                        System.out.printf("%-8s %-10s   %s%n", "", "", externalId);
                    }
                    if (i < path.size() - 1) {
                        System.out.println("         |");
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── impact ─────────────────────────────────────────────────────────────

    @CommandLine.Command(name = "impact",
            description = "Blast-radius analysis: what breaks if this symbol changes?")
    static class ImpactCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "FQN of the symbol to analyze")
        private String fqn;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/impact-analysis?projectId=" + enc(projectId)
                        + "&fqn=" + enc(fqn);
                String response = client.getString(url);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                System.out.println("Impact Analysis: " + fqn);
                System.out.println();

                JsonNode summary = root.get("summary");
                if (summary != null) {
                    System.out.println("Summary:");
                    summary.fields().forEachRemaining(e ->
                            System.out.printf("  %-20s %s%n", e.getKey() + ":", e.getValue().asText()));
                }

                printRelationSection(root, "callers", "Callers");
                printRelationSection(root, "subclasses", "Subclasses");
                printRelationSection(root, "implementors", "Implementors");
                printRelationSection(root, "importedBy", "Imported By");
                printRelationSection(root, "otherDependants", "Other Dependants");
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── deps ──────────────────────────────────────────────────────────────

    @CommandLine.Command(name = "deps",
            description = "Dependency tree: what does this symbol depend on?")
    static class DepsCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "FQN of the root symbol")
        private String fqn;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @CommandLine.Option(names = {"--depth", "-d"}, defaultValue = "3",
                description = "Maximum depth to traverse")
        private int maxDepth;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/dependency-tree?projectId=" + enc(projectId)
                        + "&fqn=" + enc(fqn) + "&maxDepth=" + maxDepth;
                String response = client.getString(url);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                int total = root.has("totalDependencies") ? root.get("totalDependencies").asInt() : 0;
                System.out.println("Dependency Tree: " + fqn);
                System.out.println("Total dependencies: " + total);

                JsonNode layers = root.get("layers");
                if (layers != null && layers.isArray()) {
                    for (JsonNode layer : layers) {
                        int depth = layer.get("depth").asInt();
                        System.out.println();
                        System.out.println("  Depth " + depth + " (" + layer.get("count").asInt() + "):");
                        JsonNode deps = layer.get("dependencies");
                        if (deps != null && deps.isArray()) {
                            for (JsonNode dep : deps) {
                                String depFqn = textOr(dep, "fqn", "?");
                                String rel = textOr(dep, "relationType", "?");
                                String type = textOr(dep, "entityType", "");
                                String indent = "    ";
                                System.out.printf("%s%-14s %-12s %s%n", indent, rel, type.isEmpty() ? "" : "[" + type + "]", depFqn);
                            }
                        }
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── component ─────────────────────────────────────────────────────────

    @CommandLine.Command(name = "component",
            description = "Component map: all types in a file or package with their relationships")
    static class ComponentCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0",
                description = "File path or parent FQN (package) to map")
        private String scope;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/component-map?projectId=" + enc(projectId)
                        + "&scope=" + enc(scope);
                String response = client.getString(url);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                String scopeType = textOr(root, "scopeType", "?");
                int memberCount = root.has("memberCount") ? root.get("memberCount").asInt() : 0;
                System.out.println("Component Map: " + scope + " (" + scopeType + ")");
                System.out.println("Members: " + memberCount);

                JsonNode breakdown = root.get("typeBreakdown");
                if (breakdown != null && breakdown.isObject()) {
                    System.out.println();
                    System.out.println("Type Breakdown:");
                    breakdown.fields().forEachRemaining(e ->
                            System.out.printf("  %-20s %s%n", e.getKey() + ":", e.getValue().asText()));
                }

                JsonNode members = root.get("members");
                if (members != null && members.isArray() && !members.isEmpty()) {
                    System.out.println();
                    System.out.println("Members:");
                    for (JsonNode m : members) {
                        String type = textOr(m, "entityType", "?");
                        String name = textOr(m, "name", "?");
                        String mFqn = textOr(m, "fqn", "");
                        System.out.printf("  %-12s %-30s %s%n", type, name, mFqn);
                    }
                }

                JsonNode inheritance = root.get("inheritance");
                if (inheritance != null && inheritance.isArray() && !inheritance.isEmpty()) {
                    System.out.println();
                    System.out.println("Inheritance (" + inheritance.size() + "):");
                    for (JsonNode rel : inheritance) {
                        System.out.printf("  %-14s %s -> %s%n",
                                textOr(rel, "relationType", "?"),
                                textOr(rel, "sourceFqn", "?"),
                                textOr(rel, "targetFqn", textOr(rel, "targetName", "?")));
                    }
                }

                int extCount = root.has("externalReferenceCount")
                        ? root.get("externalReferenceCount").asInt() : 0;
                if (extCount > 0) {
                    System.out.println();
                    System.out.println("External References (" + extCount + "):");
                    JsonNode extRefs = root.get("externalReferences");
                    if (extRefs != null && extRefs.isArray()) {
                        for (JsonNode ref : extRefs) {
                            System.out.printf("  %-14s %s -> %s%n",
                                    textOr(ref, "relationType", "?"),
                                    textOr(ref, "sourceFqn", "?"),
                                    textOr(ref, "targetFqn", textOr(ref, "targetName", "?")));
                        }
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── dossier ───────────────────────────────────────────────────────────

    @CommandLine.Command(name = "dossier",
            description = "Complete dossier for a symbol: details, parent, children, all relations")
    static class DossierCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "FQN of the symbol")
        private String fqn;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/symbol-dossier?projectId=" + enc(projectId)
                        + "&fqn=" + enc(fqn);
                String response = client.getString(url);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                if (root.has("found") && !root.get("found").asBoolean()) {
                    System.out.println("Symbol not found: " + fqn);
                    return 0;
                }

                JsonNode entity = root.get("entity");
                if (entity != null) {
                    System.out.println("Symbol Dossier: " + fqn);
                    System.out.println();
                    System.out.println("  Type:       " + textOr(entity, "entityType", "?"));
                    System.out.println("  Language:   " + textOr(entity, "language", "?"));
                    System.out.println("  File:       " + textOr(entity, "filePath", "?"));
                    if (entity.has("startLine"))
                        System.out.println("  Lines:      " + entity.get("startLine").asText()
                                + "-" + textOr(entity, "endLine", "?"));
                    System.out.println("  Visibility: " + textOr(entity, "visibility", "?"));
                    if (entity.has("signature") && !entity.get("signature").isNull())
                        System.out.println("  Signature:  " + entity.get("signature").asText());
                    if (entity.has("parentFqn") && !entity.get("parentFqn").isNull())
                        System.out.println("  Parent:     " + entity.get("parentFqn").asText());
                }

                int childCount = root.has("childCount") ? root.get("childCount").asInt() : 0;
                if (childCount > 0) {
                    System.out.println();
                    System.out.println("Children (" + childCount + "):");
                    JsonNode children = root.get("children");
                    if (children != null && children.isArray()) {
                        for (JsonNode c : children) {
                            System.out.printf("  %-12s %s%n",
                                    textOr(c, "entityType", "?"),
                                    textOr(c, "fqn", textOr(c, "name", "?")));
                        }
                    }
                }

                printGroupedRelations(root, "outgoingRelations", "Outgoing Relations", root.has("outgoingCount") ? root.get("outgoingCount").asInt() : 0);
                printGroupedRelations(root, "incomingRelations", "Incoming Relations", root.has("incomingCount") ? root.get("incomingCount").asInt() : 0);

                int callerCount = root.has("callerCount") ? root.get("callerCount").asInt() : 0;
                if (callerCount > 0) {
                    System.out.println();
                    System.out.println("Callers (" + callerCount + "):");
                    JsonNode callers = root.get("callers");
                    if (callers != null && callers.isArray()) {
                        for (JsonNode c : callers) {
                            System.out.printf("  %s  (%s:%s)%n",
                                    textOr(c, "sourceFqn", "?"),
                                    textOr(c, "filePath", "?"),
                                    textOr(c, "line", "?"));
                        }
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── export-local ──────────────────────────────────────────────────────

    @CommandLine.Command(name = "export-local",
            description = "Export a localized graph (symbol neighborhood or file) as SVG/HTML/JSON")
    static class ExportLocalCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0",
                description = "FQN of a symbol or file path to center the export on")
        private String focus;

        @CommandLine.Option(names = "--format", defaultValue = "svg",
                description = "Export format: svg, html, json")
        private String format;

        @CommandLine.Option(names = {"--output", "-o"}, required = true,
                description = "Output file path")
        private Path output;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @CommandLine.Option(names = {"--depth", "-d"}, defaultValue = "2",
                description = "Traversal depth (for symbol graphs)")
        private int depth;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/export-local?projectId=" + enc(projectId)
                        + "&focus=" + enc(focus)
                        + "&format=" + enc(format)
                        + "&depth=" + depth;
                client.downloadToFile(url, output);
                System.out.println("Exported localized graph to: " + output.toAbsolutePath());
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── test-frameworks ──────────────────────────────────────────────────

    @CommandLine.Command(name = "test-frameworks",
            description = "Detect test frameworks in the indexed project")
    static class TestFrameworksCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/test-frameworks?projectId=" + enc(projectId);
                String response = client.getString(url);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                System.out.println("Test Frameworks (project: " + projectId + ")");

                JsonNode languages = root.get("languages");
                if (languages != null && languages.isArray()) {
                    System.out.println("Languages: " + languages);
                }

                JsonNode frameworks = root.get("frameworksByLanguage");
                if (frameworks != null && frameworks.isObject()) {
                    System.out.println();
                    System.out.println("Detected Frameworks:");
                    frameworks.fields().forEachRemaining(e -> {
                        System.out.println("  " + e.getKey() + ": " + e.getValue());
                    });
                }

                JsonNode annotCounts = root.get("testAnnotationCounts");
                if (annotCounts != null && annotCounts.isObject() && annotCounts.size() > 0) {
                    System.out.println();
                    System.out.println("Test Annotation Counts:");
                    annotCounts.fields().forEachRemaining(e ->
                            System.out.printf("  %-30s %s%n", e.getKey() + ":", e.getValue().asText()));
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── test-coverage ───────────────────────────────────────────────────

    @CommandLine.Command(name = "test-coverage",
            description = "Test coverage report: tested/untested methods, coverage %, frameworks")
    static class TestCoverageCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/test-coverage?projectId=" + enc(projectId);
                String response = client.getString(url);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                System.out.println("Test Coverage Report (project: " + projectId + ")");
                System.out.println();
                printField(root, "testCount", "  Test entities");
                printField(root, "productionMethodCount", "  Production methods");
                printField(root, "testedMethodCount", "  Tested methods");
                printField(root, "untestedMethodCount", "  Untested methods");
                printField(root, "coveragePercent", "  Coverage");

                JsonNode frameworks = root.get("frameworksByLanguage");
                if (frameworks != null && frameworks.isObject() && frameworks.size() > 0) {
                    System.out.println();
                    System.out.println("  Frameworks:");
                    frameworks.fields().forEachRemaining(e ->
                            System.out.println("    " + e.getKey() + ": " + e.getValue()));
                }

                JsonNode untested = root.get("untestedMethods");
                if (untested != null && untested.isArray() && !untested.isEmpty()) {
                    System.out.println();
                    System.out.println("  Untested Methods (top " + untested.size() + "):");
                    for (JsonNode m : untested) {
                        System.out.printf("    %-50s %s%n",
                                textOr(m, "fqn", textOr(m, "name", "?")),
                                textOr(m, "filePath", ""));
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── tests-for ───────────────────────────────────────────────────────

    @CommandLine.Command(name = "tests-for",
            description = "Find tests that cover a given production symbol")
    static class TestsForCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0",
                description = "FQN of the production symbol")
        private String fqn;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/tests-for-symbol?projectId=" + enc(projectId)
                        + "&fqn=" + enc(fqn);
                String response = client.getString(url);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                boolean tested = root.has("tested") && root.get("tested").asBoolean();
                int total = root.has("totalTestCount") ? root.get("totalTestCount").asInt() : 0;

                System.out.println("Tests for: " + fqn);
                System.out.println("Status: " + (tested ? "TESTED (" + total + " tests)" : "UNTESTED"));

                JsonNode direct = root.get("directTests");
                if (direct != null && direct.isArray() && !direct.isEmpty()) {
                    System.out.println();
                    System.out.println("Direct Tests (" + direct.size() + "):");
                    for (JsonNode t : direct) {
                        System.out.printf("  %-50s %s%n",
                                textOr(t, "fqn", textOr(t, "name", "?")),
                                textOr(t, "filePath", ""));
                    }
                }

                JsonNode indirect = root.get("indirectTests");
                if (indirect != null && indirect.isArray() && !indirect.isEmpty()) {
                    System.out.println();
                    System.out.println("Indirect Tests (" + indirect.size() + "):");
                    for (JsonNode t : indirect) {
                        System.out.printf("  %-50s %s%n",
                                textOr(t, "fqn", textOr(t, "name", "?")),
                                textOr(t, "filePath", ""));
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── code-paths ──────────────────────────────────────────────────────

    @CommandLine.Command(name = "code-paths",
            description = "Trace execution paths from an entry point through CALLS relations")
    static class CodePathsCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0",
                description = "FQN of the entry point to trace from")
        private String fqn;

        @CommandLine.Option(names = {"--project"}, defaultValue = "default")
        private String projectId;

        @CommandLine.Option(names = {"--depth", "-d"}, defaultValue = "5",
                description = "Maximum depth to trace")
        private int maxDepth;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/code-indexer/graph/code-paths?projectId=" + enc(projectId)
                        + "&fqn=" + enc(fqn) + "&maxDepth=" + maxDepth;
                String response = client.getString(url);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode root = client.getObjectMapper().readTree(response);
                int total = root.has("totalReachable") ? root.get("totalReachable").asInt() : 0;
                System.out.println("Code Paths from: " + fqn);
                System.out.println("Reachable symbols: " + total);

                JsonNode layers = root.get("layers");
                if (layers != null && layers.isArray()) {
                    for (JsonNode layer : layers) {
                        int depth = layer.get("depth").asInt();
                        System.out.println();
                        System.out.println("  Depth " + depth + " (" + layer.get("count").asInt() + " calls):");
                        JsonNode calls = layer.get("calls");
                        if (calls != null && calls.isArray()) {
                            for (JsonNode call : calls) {
                                String callFqn = textOr(call, "fqn", "?");
                                String type = textOr(call, "entityType", "");
                                String callSite = textOr(call, "callSite", "");
                                System.out.printf("    %-12s %-50s %s%n",
                                        type.isEmpty() ? "" : "[" + type + "]",
                                        callFqn,
                                        callSite);
                            }
                        }
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static void printRelationSection(JsonNode root, String key, String label) {
        JsonNode section = root.get(key);
        if (section != null && section.isArray() && !section.isEmpty()) {
            System.out.println();
            System.out.println(label + " (" + section.size() + "):");
            for (JsonNode rel : section) {
                String src = textOr(rel, "sourceFqn", "?");
                String file = textOr(rel, "filePath", "");
                String line = rel.has("line") && !rel.get("line").isNull()
                        ? ":" + rel.get("line").asText() : "";
                System.out.printf("  %-50s  %s%s%n", src, file, line);
            }
        }
    }

    private static void printGroupedRelations(JsonNode root, String key, String label, int count) {
        JsonNode grouped = root.get(key);
        if (grouped != null && grouped.isObject() && count > 0) {
            System.out.println();
            System.out.println(label + " (" + count + "):");
            grouped.fields().forEachRemaining(e -> {
                System.out.println("  " + e.getKey() + ":");
                if (e.getValue().isArray()) {
                    for (JsonNode rel : e.getValue()) {
                        String target = textOr(rel, "targetFqn",
                                textOr(rel, "targetName", "?"));
                        String src = textOr(rel, "sourceFqn", "?");
                        System.out.printf("    %s -> %s%n", src, target);
                    }
                }
            });
        }
    }

    private static void printField(JsonNode node, String field, String label) {
        if (node.has(field) && !node.get(field).isNull()) {
            System.out.printf("  %-22s %s%n", label + ":", node.get(field).asText());
        }
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return fallback;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
