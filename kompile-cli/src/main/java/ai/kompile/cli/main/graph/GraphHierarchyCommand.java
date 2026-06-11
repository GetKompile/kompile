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
 * {@code kompile graph hierarchy} — view the graph hierarchy tree starting from
 * a given root node or showing all root-level nodes.
 */
@CommandLine.Command(
        name = "hierarchy",
        description = "View the graph hierarchy tree",
        mixinStandardHelpOptions = true
)
public class GraphHierarchyCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = "--node-id",
            description = "Root node ID to start the hierarchy from (omit to show full hierarchy)")
    private String nodeId;

    @CommandLine.Option(names = "--depth", defaultValue = "5",
            description = "Maximum depth to traverse (default: 5)")
    private int depth;

    @CommandLine.Option(names = "--format", defaultValue = "tree",
            description = "Output format: tree (default), json, table")
    private String format;

    @Override
    public Integer call() {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;
        try {
            String url;
            if (nodeId != null && !nodeId.isBlank()) {
                url = "/api/knowledge-graph/nodes/" + nodeId + "/connected?depth=" + depth;
            } else {
                url = "/api/knowledge-graph/hierarchy?depth=" + depth;
            }

            String response = client.getString(url);

            switch (format.toLowerCase()) {
                case "json" -> OutputFormatter.printJson(response);
                case "table" -> {
                    JsonNode root = client.getObjectMapper().readTree(response);
                    printFlatTable(root, 0);
                }
                default -> {
                    JsonNode root = client.getObjectMapper().readTree(response);
                    System.out.println("Graph Hierarchy:");
                    printTree(root, "", true);
                }
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /** Recursively prints an ASCII tree using Unicode box-drawing characters. */
    private void printTree(JsonNode node, String prefix, boolean isLast) {
        if (node == null || node.isNull()) return;

        String connector = isLast ? "└── " : "├── ";
        String childPrefix = isLast ? "    " : "│   ";

        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                printTree(node.get(i), prefix, i == node.size() - 1);
            }
            return;
        }

        String title = node.path("title").asText(node.path("name").asText(node.path("nodeId").asText("?")));
        String type = node.path("nodeType").asText(node.path("type").asText(""));
        String id = node.path("nodeId").asText(node.path("id").asText(""));
        String label = type.isEmpty() ? title : "[" + type + "] " + title;
        if (!id.isEmpty()) {
            label += " (" + id + ")";
        }
        System.out.println(prefix + connector + label);

        JsonNode children = node.path("children");
        if (children.isArray() && !children.isEmpty()) {
            for (int i = 0; i < children.size(); i++) {
                printTree(children.get(i), prefix + childPrefix, i == children.size() - 1);
            }
        }
        JsonNode connected = node.path("connectedNodes");
        if (connected.isArray() && !connected.isEmpty()) {
            for (int i = 0; i < connected.size(); i++) {
                printTree(connected.get(i), prefix + childPrefix, i == connected.size() - 1);
            }
        }
    }

    /** Prints a flat table with depth column by traversing the hierarchy. */
    private void printFlatTable(JsonNode node, int currentDepth) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            for (JsonNode child : node) {
                printFlatTable(child, currentDepth);
            }
            return;
        }
        if (currentDepth == 0) {
            System.out.printf("  %-5s  %-12s  %-36s  %s%n", "DEPTH", "TYPE", "ID", "TITLE");
            System.out.printf("  %-5s  %-12s  %-36s  %s%n", "-----", "------------", "------------------------------------", "-----");
        }
        String title = node.path("title").asText(node.path("name").asText("-"));
        String type = node.path("nodeType").asText(node.path("type").asText("-"));
        String id = node.path("nodeId").asText(node.path("id").asText("-"));
        if (title.length() > 50) title = title.substring(0, 47) + "...";
        System.out.printf("  %-5d  %-12s  %-36s  %s%n", currentDepth, type, id, title);

        JsonNode children = node.path("children");
        if (children.isArray()) {
            for (JsonNode child : children) {
                printFlatTable(child, currentDepth + 1);
            }
        }
        JsonNode connected = node.path("connectedNodes");
        if (connected.isArray()) {
            for (JsonNode child : connected) {
                printFlatTable(child, currentDepth + 1);
            }
        }
    }
}
