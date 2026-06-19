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

import ai.kompile.cli.main.app.AppClientMixin;
import ai.kompile.cli.main.app.OutputFormatter;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * {@code kompile graph merge} — merges multiple exported graph JSON files into
 * one, performing entity-level deduplication. Works locally (no server required)
 * or optionally via the REST API.
 */
@CommandLine.Command(
        name = "merge",
        description = "Merge multiple graph JSON files into one with entity deduplication",
        mixinStandardHelpOptions = true
)
public class GraphMergeCommand implements Callable<Integer> {

    private static final double SIMILARITY_THRESHOLD = 0.85;

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Parameters(description = "Graph JSON files to merge", arity = "2..*")
    private List<Path> inputFiles;

    @CommandLine.Option(names = {"--output", "-o"}, required = true,
            description = "Output file for merged graph")
    private Path output;

    @Override
    public Integer call() {
        try {
            ObjectMapper mapper = JsonUtils.standardMapper();
            // Parse all input graphs
            List<JsonNode> graphs = new ArrayList<>();
            for (Path p : inputFiles) {
                if (!Files.exists(p)) {
                    System.err.println("File not found: " + p);
                    return 1;
                }
                graphs.add(mapper.readTree(Files.readAllBytes(p)));
            }

            // Local merge: collect all nodes, dedup by externalId / title similarity
            Map<String, JsonNode> nodeByExtId = new LinkedHashMap<>();
            Map<String, String> idMapping = new HashMap<>();
            int totalNodes = 0, totalEdges = 0;

            for (JsonNode g : graphs) {
                JsonNode nodes = g.get("nodes");
                if (nodes != null && nodes.isArray()) {
                    totalNodes += nodes.size();
                    for (JsonNode n : nodes) {
                        String extId = n.has("externalId") ? n.get("externalId").asText() : null;
                        if (extId == null) continue;
                        String canonical = findCanonical(extId, n, nodeByExtId);
                        if (canonical != null) {
                            idMapping.put(extId, canonical);
                        } else {
                            nodeByExtId.put(extId, n);
                            idMapping.put(extId, extId);
                        }
                    }
                }
                JsonNode edges = g.get("edges");
                if (edges != null && edges.isArray()) totalEdges += edges.size();
            }

            // Collect and remap edges
            Set<String> seenEdges = new HashSet<>();
            ArrayNode mergedEdges = mapper.createArrayNode();
            for (JsonNode g : graphs) {
                JsonNode edges = g.get("edges");
                if (edges == null || !edges.isArray()) continue;
                for (JsonNode e : edges) {
                    String from = idMapping.getOrDefault(
                            e.has("fromExternalId") ? e.get("fromExternalId").asText() : "",
                            e.has("fromExternalId") ? e.get("fromExternalId").asText() : "");
                    String to = idMapping.getOrDefault(
                            e.has("toExternalId") ? e.get("toExternalId").asText() : "",
                            e.has("toExternalId") ? e.get("toExternalId").asText() : "");
                    String edgeType = e.has("edgeType") ? e.get("edgeType").asText() : "";
                    String key = from + "|" + to + "|" + edgeType;
                    if (seenEdges.add(key)) {
                        ObjectNode remapped = ((ObjectNode) e.deepCopy());
                        remapped.put("fromExternalId", from);
                        remapped.put("toExternalId", to);
                        mergedEdges.add(remapped);
                    }
                }
            }

            // Build output
            ObjectNode result = mapper.createObjectNode();
            ArrayNode mergedNodes = mapper.createArrayNode();
            nodeByExtId.values().forEach(mergedNodes::add);
            result.set("nodes", mergedNodes);
            result.set("edges", mergedEdges);

            Files.write(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));

            int dedup = totalNodes - nodeByExtId.size();
            System.out.println("Merged " + inputFiles.size() + " graphs:");
            OutputFormatter.printKv("Input nodes", String.valueOf(totalNodes));
            OutputFormatter.printKv("Input edges", String.valueOf(totalEdges));
            OutputFormatter.printKv("Merged nodes", String.valueOf(nodeByExtId.size()));
            OutputFormatter.printKv("Merged edges", String.valueOf(mergedEdges.size()));
            OutputFormatter.printKv("Deduplicated", String.valueOf(dedup));
            System.out.println("Output: " + output.toAbsolutePath());
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private String findCanonical(String extId, JsonNode node, Map<String, JsonNode> existing) {
        if (existing.containsKey(extId)) return extId;
        String title = node.has("title") ? node.get("title").asText("").toLowerCase().trim() : "";
        String type = node.has("nodeType") ? node.get("nodeType").asText("") : "";
        if (title.isEmpty()) return null;
        for (Map.Entry<String, JsonNode> entry : existing.entrySet()) {
            JsonNode other = entry.getValue();
            String otherType = other.has("nodeType") ? other.get("nodeType").asText("") : "";
            if (!type.equals(otherType)) continue;
            String otherTitle = other.has("title") ? other.get("title").asText("").toLowerCase().trim() : "";
            if (otherTitle.isEmpty()) continue;
            if (similarity(title, otherTitle) >= SIMILARITY_THRESHOLD) return entry.getKey();
        }
        return null;
    }

    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1], curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return 1.0 - (double) prev[n] / maxLen;
    }
}
