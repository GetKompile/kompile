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

import java.util.concurrent.Callable;

/**
 * {@code kompile graph list-graphs} — list named graphs, optionally filtered by
 * a search query. Renders as an ASCII tree, JSON, or flat table.
 */
@CommandLine.Command(
        name = "list-graphs",
        description = "List named graphs",
        mixinStandardHelpOptions = true
)
public class GraphListCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = "--query",
            description = "Optional search filter")
    private String query;

    @CommandLine.Option(names = "--format", defaultValue = "tree",
            description = "Output format: tree (default), json, table")
    private String format;

    @Override
    public Integer call() {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;
        try {
            StringBuilder url = new StringBuilder("/api/graphs");
            if (query != null && !query.isBlank()) {
                url.append("?query=").append(
                        java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));
            }

            String response = client.getString(url.toString());

            switch (format.toLowerCase()) {
                case "json" -> OutputFormatter.printJson(response);
                case "table" -> {
                    JsonNode root = client.getObjectMapper().readTree(response);
                    OutputFormatter.printTable(root.isArray() ? root : root.path("graphs"),
                            "graphId", "name", "parentGraphId", "ontologyType");
                }
                default -> {
                    // tree view — group by parent
                    JsonNode root = client.getObjectMapper().readTree(response);
                    JsonNode items = root.isArray() ? root : root.path("graphs");
                    System.out.println("Named Graphs:");
                    printGraphTree(items);
                }
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Renders graphs as a hierarchy tree. Root graphs (no parentGraphId) are at the
     * top level; children are indented under them.
     */
    private void printGraphTree(JsonNode items) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            System.out.println("  (no graphs found)");
            return;
        }

        // Build lookup: graphId -> node
        java.util.Map<String, JsonNode> byId = new java.util.LinkedHashMap<>();
        java.util.List<JsonNode> roots = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            String id = item.path("graphId").asText(item.path("id").asText(""));
            if (!id.isEmpty()) byId.put(id, item);
        }

        // Separate roots from children
        for (JsonNode item : items) {
            JsonNode parentId = item.get("parentGraphId");
            if (parentId == null || parentId.isNull() || parentId.asText().isBlank()) {
                roots.add(item);
            }
        }

        if (roots.isEmpty()) {
            // No clear hierarchy; just list all flat
            for (JsonNode item : items) {
                printGraphLine(item, "  ");
            }
        } else {
            for (int i = 0; i < roots.size(); i++) {
                printGraphNode(roots.get(i), "", i == roots.size() - 1, byId);
            }
        }
    }

    private void printGraphNode(JsonNode item, String prefix, boolean isLast,
                                java.util.Map<String, JsonNode> byId) {
        String connector = isLast ? "└── " : "├── ";
        String childPrefix = isLast ? "    " : "│   ";
        printGraphLine(item, prefix + connector);

        String id = item.path("graphId").asText(item.path("id").asText(""));
        java.util.List<JsonNode> children = new java.util.ArrayList<>();
        for (JsonNode candidate : byId.values()) {
            String parentId = candidate.path("parentGraphId").asText("");
            if (id.equals(parentId)) {
                children.add(candidate);
            }
        }
        for (int i = 0; i < children.size(); i++) {
            printGraphNode(children.get(i), prefix + childPrefix, i == children.size() - 1, byId);
        }
    }

    private void printGraphLine(JsonNode item, String prefix) {
        String id = item.path("graphId").asText(item.path("id").asText("-"));
        String name = item.path("name").asText("-");
        String type = item.path("ontologyType").asText("");
        String typeLabel = type.isEmpty() ? "" : " [" + type + "]";
        System.out.printf("%s%s%s  (id: %s)%n", prefix, name, typeLabel, id);
    }
}
