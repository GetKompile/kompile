/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.graph;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.main.app.AppClientMixin;
import ai.kompile.cli.main.app.OutputFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code kompile graph} command tree. All operations talk to a running kompile-app
 * via REST; no graph backend code is loaded into the CLI itself.
 */
@CommandLine.Command(
        name = "graph",
        description = "Manage knowledge graphs (requires running kompile-app)",
        subcommands = {
                GraphCommand.StatsCmd.class,
                GraphCommand.AddNodeCmd.class,
                GraphCommand.GetNodeCmd.class,
                GraphCommand.DeleteNodeCmd.class,
                GraphCommand.ListNodesCmd.class,
                GraphCommand.SearchCmd.class,
                GraphCommand.AddEdgeCmd.class,
                GraphCommand.DeleteEdgeCmd.class,
                GraphCommand.TraverseCmd.class,
                GraphCommand.PathCmd.class,
                GraphCommand.AlgorithmCmd.class,
                GraphCommand.CommunitiesCmd.class,
                GraphQueryCommand.class,
                GraphImportCommand.class,
                GraphExportCommand.class,
                GraphShellCommand.class
        },
        mixinStandardHelpOptions = true
)
public class GraphCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @CommandLine.Command(name = "stats", description = "Show graph node/edge counts and breakdown")
    static class StatsCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/knowledge-graph/statistics");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode node = client.getObjectMapper().readTree(response);
                System.out.println("Knowledge Graph Statistics:");
                node.fields().forEachRemaining(e -> OutputFormatter.printKv(e.getKey(), e.getValue()));
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "add-node", description = "Create a new node")
    static class AddNodeCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = "--type", defaultValue = "ENTITY",
                description = "Node type (SOURCE, DOCUMENT, SNIPPET, ENTITY, CUSTOM)")
        private String type;

        @CommandLine.Option(names = "--external-id", required = true,
                description = "External identifier (must be unique within type)")
        private String externalId;

        @CommandLine.Option(names = "--title", required = true)
        private String title;

        @CommandLine.Option(names = "--description")
        private String description;

        @CommandLine.Option(names = "--metadata", description = "Metadata key=value (repeatable)")
        private Map<String, String> metadata;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("nodeType", type);
                body.put("externalId", externalId);
                body.put("title", title);
                if (description != null) body.put("description", description);
                if (metadata != null && !metadata.isEmpty()) body.put("metadata", new HashMap<>(metadata));
                String response = client.postString("/api/knowledge-graph/nodes", body);
                if (app.isJsonOutput()) OutputFormatter.printJson(response);
                else printNode(client.getObjectMapper().readTree(response));
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "get-node", description = "Fetch a node by UUID")
    static class GetNodeCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Parameters(index = "0", description = "Node UUID") private String nodeId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/knowledge-graph/nodes/" + nodeId);
                if (app.isJsonOutput()) OutputFormatter.printJson(response);
                else printNode(client.getObjectMapper().readTree(response));
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "delete-node", description = "Delete a node and its descendants")
    static class DeleteNodeCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Parameters(index = "0", description = "Node UUID") private String nodeId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                client.delete("/api/knowledge-graph/nodes/" + nodeId);
                System.out.println("Deleted node: " + nodeId);
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "list-nodes", description = "List nodes")
    static class ListNodesCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Option(names = "--type") private String type;
        @CommandLine.Option(names = "--limit", defaultValue = "50") private int limit;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                StringBuilder url = new StringBuilder("/api/knowledge-graph/nodes?limit=").append(limit);
                if (type != null) url.append("&type=").append(type);
                String response = client.getString(url.toString());
                if (app.isJsonOutput()) OutputFormatter.printJson(response);
                else OutputFormatter.printTable(client.getObjectMapper().readTree(response),
                        "nodeId", "nodeType", "externalId", "title");
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "search", description = "Full-text search across nodes")
    static class SearchCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Parameters(index = "0", description = "Search query") private String query;
        @CommandLine.Option(names = "--type") private String type;
        @CommandLine.Option(names = "--limit", defaultValue = "20") private int limit;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                StringBuilder url = new StringBuilder("/api/knowledge-graph/nodes?query=")
                        .append(java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8))
                        .append("&limit=").append(limit);
                if (type != null) url.append("&type=").append(type);
                String response = client.getString(url.toString());
                if (app.isJsonOutput()) OutputFormatter.printJson(response);
                else OutputFormatter.printTable(client.getObjectMapper().readTree(response),
                        "nodeId", "nodeType", "title", "description");
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "add-edge", description = "Create an edge between two nodes")
    static class AddEdgeCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Option(names = "--from", required = true, description = "Source node UUID") private String from;
        @CommandLine.Option(names = "--to", required = true, description = "Target node UUID") private String to;
        @CommandLine.Option(names = "--type", defaultValue = "USER_DEFINED") private String type;
        @CommandLine.Option(names = "--weight", defaultValue = "1.0") private double weight;
        @CommandLine.Option(names = "--description") private String description;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("sourceNodeId", from);
                body.put("targetNodeId", to);
                body.put("edgeType", type);
                body.put("weight", weight);
                if (description != null) body.put("description", description);
                String response = client.postString("/api/knowledge-graph/edges", body);
                if (app.isJsonOutput()) OutputFormatter.printJson(response);
                else {
                    JsonNode node = client.getObjectMapper().readTree(response);
                    System.out.println("Created edge:");
                    OutputFormatter.printKv("Edge ID", node.path("edgeId").asText());
                    OutputFormatter.printKv("Type", node.path("edgeType").asText());
                    OutputFormatter.printKv("Weight", node.path("weight").asText());
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "delete-edge", description = "Delete an edge by UUID")
    static class DeleteEdgeCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Parameters(index = "0") private String edgeId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                client.delete("/api/knowledge-graph/edges/" + edgeId);
                System.out.println("Deleted edge: " + edgeId);
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "traverse", description = "BFS traversal from a node grouped by depth")
    static class TraverseCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Parameters(index = "0", description = "Start node UUID") private String nodeId;
        @CommandLine.Option(names = "--depth", defaultValue = "3") private int depth;
        @CommandLine.Option(names = "--fact-sheet-id") private Long factSheetId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("startNodeId", nodeId);
                body.put("maxDepth", depth);
                if (factSheetId != null) body.put("factSheetId", factSheetId);
                String response = client.postString("/api/graph/algorithms/traverse/bfs", body);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode levels = client.getObjectMapper().readTree(response);
                System.out.println("BFS levels:");
                levels.fields().forEachRemaining(e -> {
                    int count = e.getValue().isArray() ? e.getValue().size() : 0;
                    OutputFormatter.printKv("level " + e.getKey(), count + " nodes");
                });
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "path", description = "Shortest path between two nodes")
    static class PathCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Parameters(index = "0", description = "From node UUID") private String fromId;
        @CommandLine.Parameters(index = "1", description = "To node UUID") private String toId;
        @CommandLine.Option(names = "--weighted", description = "Use Dijkstra weighted shortest path")
        private boolean weighted;
        @CommandLine.Option(names = "--fact-sheet-id") private Long factSheetId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("fromNodeId", fromId);
                body.put("toNodeId", toId);
                body.put("weighted", weighted);
                if (factSheetId != null) body.put("factSheetId", factSheetId);
                String response = client.postString("/api/graph/algorithms/path/shortest", body);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode result = client.getObjectMapper().readTree(response);
                if (!result.path("found").asBoolean()) {
                    System.out.println("No path found from " + fromId + " to " + toId);
                    return 0;
                }
                System.out.println("Path length: " + result.path("length").asDouble());
                JsonNode path = result.path("path");
                for (JsonNode n : path) System.out.println("  -> " + n.asText());
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "algorithm", description = "Run a graph algorithm")
    static class AlgorithmCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Parameters(index = "0",
                description = "Algorithm name: pagerank | degree | betweenness | wcc | jaccard")
        private String name;
        @CommandLine.Option(names = "--fact-sheet-id") private Long factSheetId;
        @CommandLine.Option(names = "--params", description = "Extra params as key=value")
        private Map<String, String> params;
        @CommandLine.Option(names = "--limit", defaultValue = "20",
                description = "Show only the top N results in non-JSON output")
        private int limit;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String endpoint = endpointFor(name);
                if (endpoint == null) {
                    System.err.println("Unknown algorithm: " + name);
                    return 1;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                if (factSheetId != null) body.put("factSheetId", factSheetId);
                if (params != null) body.putAll(params);
                String response = client.postString(endpoint, body);
                if (app.isJsonOutput()) OutputFormatter.printJson(response);
                else printRanked(client.getObjectMapper().readTree(response), limit);
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }

        private static String endpointFor(String name) {
            return switch (name.toLowerCase()) {
                case "pagerank" -> "/api/graph/algorithms/pagerank";
                case "degree" -> "/api/graph/algorithms/centrality/degree";
                case "betweenness" -> "/api/graph/algorithms/centrality/betweenness";
                case "wcc" -> "/api/graph/algorithms/components/wcc";
                case "jaccard" -> "/api/graph/algorithms/similarity/jaccard";
                default -> null;
            };
        }
    }

    @CommandLine.Command(name = "communities", description = "Detect communities (Louvain or WCC) and optionally summarize")
    static class CommunitiesCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Option(names = "--algorithm", defaultValue = "louvain") private String algorithm;
        @CommandLine.Option(names = "--summarize") private boolean summarize;
        @CommandLine.Option(names = "--fact-sheet-id") private Long factSheetId;
        @CommandLine.Option(names = "--max-nodes-per-prompt", defaultValue = "25") private int maxNodesPerPrompt;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                if (factSheetId != null) body.put("factSheetId", factSheetId);
                String endpoint;
                if (summarize) {
                    endpoint = "/api/graph/algorithms/communities/" + algorithm + "/summarize";
                    body.put("maxNodesPerPrompt", maxNodesPerPrompt);
                } else {
                    endpoint = "louvain".equalsIgnoreCase(algorithm)
                            ? "/api/graph/algorithms/communities/louvain"
                            : "/api/graph/algorithms/components/wcc";
                }
                String response = client.postString(endpoint, body);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode node = client.getObjectMapper().readTree(response);
                if (summarize) {
                    System.out.println("Community summaries:");
                    for (JsonNode entry : node) {
                        OutputFormatter.printKv("community " + entry.path("communityId").asInt(),
                                entry.path("summary").asText());
                    }
                } else {
                    Map<Integer, Integer> counts = new LinkedHashMap<>();
                    node.fields().forEachRemaining(e -> {
                        int comm = e.getValue().asInt();
                        counts.merge(comm, 1, Integer::sum);
                    });
                    System.out.println("Communities (" + counts.size() + " total):");
                    counts.entrySet().stream()
                            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                            .forEach(e -> OutputFormatter.printKv("community " + e.getKey(), e.getValue() + " nodes"));
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    private static void printNode(JsonNode node) {
        System.out.println("Node:");
        OutputFormatter.printKv("Node ID", node.path("nodeId").asText());
        OutputFormatter.printKv("Type", node.path("nodeType").asText());
        OutputFormatter.printKv("External ID", node.path("externalId").asText());
        OutputFormatter.printKv("Title", node.path("title").asText());
        if (node.has("description") && !node.get("description").isNull()) {
            OutputFormatter.printKv("Description", node.get("description").asText());
        }
    }

    private static void printRanked(JsonNode node, int limit) {
        if (node.isArray()) {
            for (int i = 0; i < node.size() && i < limit; i++) {
                System.out.println("  " + node.get(i));
            }
            return;
        }
        if (!node.isObject()) {
            System.out.println(node);
            return;
        }
        List<Map.Entry<String, JsonNode>> entries = new java.util.ArrayList<>();
        node.fields().forEachRemaining(entries::add);
        entries.sort((a, b) -> {
            double av = a.getValue().isNumber() ? a.getValue().asDouble() : 0;
            double bv = b.getValue().isNumber() ? b.getValue().asDouble() : 0;
            return Double.compare(bv, av);
        });
        for (int i = 0; i < entries.size() && i < limit; i++) {
            OutputFormatter.printKv(entries.get(i).getKey(), entries.get(i).getValue());
        }
    }

    // Re-export for test inspection
    @SuppressWarnings("unused")
    private static IOException io(String msg) { return new IOException(msg); }
}
