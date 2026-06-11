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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code kompile graph browse} — non-interactively browse the knowledge graph.
 * Shows node details, children, edges, and ancestor breadcrumb for a given node.
 * When no start node is given, lists root (SOURCE type) nodes as a starting point.
 */
@CommandLine.Command(
        name = "browse",
        description = "Interactively browse the knowledge graph",
        mixinStandardHelpOptions = true
)
public class GraphBrowseCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = "--start-node",
            description = "Node ID to start browsing from (omit to show root SOURCE nodes)")
    private String startNode;

    @Override
    public Integer call() {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;
        try {
            if (startNode == null || startNode.isBlank()) {
                // Show root SOURCE nodes as entry points
                String response = client.getString("/api/knowledge-graph/nodes?type=SOURCE&limit=20");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode nodes = client.getObjectMapper().readTree(response);
                System.out.println("Root source nodes (use --start-node <id> to browse a node):");
                System.out.println();
                if (nodes.isArray() && !nodes.isEmpty()) {
                    for (JsonNode n : nodes) {
                        printNodeSummary(n);
                        System.out.println();
                    }
                } else {
                    System.out.println("  (no SOURCE nodes found)");
                }
                return 0;
            }

            // Fetch the requested node
            String nodeResponse = client.getString("/api/knowledge-graph/nodes/" + startNode);
            if (app.isJsonOutput()) {
                OutputFormatter.printJson(nodeResponse);
                return 0;
            }
            JsonNode node = client.getObjectMapper().readTree(nodeResponse);
            printNodeDetail(node);

            // Fetch connected nodes (children / edges)
            try {
                String connResponse = client.getString(
                        "/api/knowledge-graph/nodes/" + startNode + "/connected?depth=1");
                JsonNode connected = client.getObjectMapper().readTree(connResponse);
                printConnectedSection(connected);
            } catch (Exception e) {
                // Connected endpoint may not be available; skip silently
            }

            // Fetch ancestor path
            try {
                String ancestorsResponse = client.getString(
                        "/api/knowledge-graph/nodes/" + startNode + "/ancestors");
                JsonNode ancestors = client.getObjectMapper().readTree(ancestorsResponse);
                printBreadcrumb(ancestors);
            } catch (Exception e) {
                // Ancestors endpoint may not be available; skip silently
            }

            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private void printNodeSummary(JsonNode node) {
        String id = node.path("nodeId").asText("-");
        String type = node.path("nodeType").asText("-");
        String title = node.path("title").asText("-");
        System.out.printf("  [%s] %s%n", type, title);
        System.out.printf("    ID: %s%n", id);
        JsonNode desc = node.get("description");
        if (desc != null && !desc.isNull() && !desc.asText().isBlank()) {
            String text = desc.asText();
            if (text.length() > 120) text = text.substring(0, 117) + "...";
            System.out.printf("    %s%n", text);
        }
    }

    private void printNodeDetail(JsonNode node) {
        System.out.println("Node Details:");
        System.out.println("=============");
        OutputFormatter.printKv("Node ID", node.path("nodeId").asText("-"));
        OutputFormatter.printKv("Type", node.path("nodeType").asText("-"));
        OutputFormatter.printKv("External ID", node.path("externalId").asText("-"));
        OutputFormatter.printKv("Title", node.path("title").asText("-"));
        JsonNode desc = node.get("description");
        if (desc != null && !desc.isNull()) {
            OutputFormatter.printKv("Description", desc.asText());
        }
        JsonNode childCount = node.get("childCount");
        if (childCount != null && !childCount.isNull()) {
            OutputFormatter.printKv("Child count", childCount.asInt());
        }
        JsonNode edgeCount = node.get("edgeCount");
        if (edgeCount != null && !edgeCount.isNull()) {
            OutputFormatter.printKv("Edge count", edgeCount.asInt());
        }
        System.out.println();
    }

    private void printConnectedSection(JsonNode connected) {
        if (connected == null || connected.isNull()) return;

        // Handle both array and object-with-nodes forms
        JsonNode nodes = connected.isArray() ? connected : connected.path("nodes");
        JsonNode edges = connected.path("edges");

        if (nodes.isArray() && !nodes.isEmpty()) {
            System.out.println("Connected Nodes:");
            for (JsonNode n : nodes) {
                String id = n.path("nodeId").asText(n.path("id").asText("-"));
                String type = n.path("nodeType").asText(n.path("type").asText("-"));
                String title = n.path("title").asText("-");
                String rel = n.path("relationshipType").asText(n.path("edgeType").asText(""));
                String relLabel = rel.isEmpty() ? "" : " via " + rel;
                System.out.printf("  [%s] %s%s  (id: %s)%n", type, title, relLabel, id);
            }
            System.out.println();
        }

        if (edges.isArray() && !edges.isEmpty()) {
            System.out.println("Edges:");
            for (JsonNode e : edges) {
                String edgeId = e.path("edgeId").asText("-");
                String edgeType = e.path("edgeType").asText("-");
                String from = e.path("sourceNodeId").asText(e.path("fromNodeId").asText("-"));
                String to = e.path("targetNodeId").asText(e.path("toNodeId").asText("-"));
                double weight = e.path("weight").asDouble(1.0);
                System.out.printf("  [%s] %s → %s  (weight: %.2f, id: %s)%n",
                        edgeType, from, to, weight, edgeId);
            }
            System.out.println();
        }
    }

    private void printBreadcrumb(JsonNode ancestors) {
        if (ancestors == null || ancestors.isNull()) return;
        List<String> crumbs = new ArrayList<>();
        JsonNode list = ancestors.isArray() ? ancestors : ancestors.path("ancestors");
        if (!list.isArray() || list.isEmpty()) return;
        for (JsonNode a : list) {
            String title = a.path("title").asText(a.path("nodeId").asText("?"));
            crumbs.add(title);
        }
        if (!crumbs.isEmpty()) {
            System.out.println("Ancestor path: " + String.join(" > ", crumbs));
        }
    }
}
