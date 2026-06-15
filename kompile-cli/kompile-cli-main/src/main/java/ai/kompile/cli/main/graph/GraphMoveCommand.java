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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code kompile graph move-graph} — reparent a named graph by moving it under a
 * new parent graph. Omit {@code --new-parent-id} to make the graph a root-level graph.
 */
@CommandLine.Command(
        name = "move-graph",
        description = "Move a graph to a new parent (reparent)",
        mixinStandardHelpOptions = true
)
public class GraphMoveCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = "--graph-id", required = true,
            description = "Graph ID to move")
    private String graphId;

    @CommandLine.Option(names = "--new-parent-id",
            description = "New parent graph ID (omit to make root-level)")
    private String newParentId;

    @Override
    public Integer call() {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            if (newParentId != null && !newParentId.isBlank()) {
                body.put("newParentGraphId", newParentId);
            } else {
                body.put("newParentGraphId", null);
            }

            String response = client.postString("/api/graphs/" + graphId + "/move", body);

            if (app.isJsonOutput()) {
                OutputFormatter.printJson(response);
                return 0;
            }

            System.out.println("Moved graph: " + graphId);
            if (newParentId != null && !newParentId.isBlank()) {
                OutputFormatter.printKv("New parent", newParentId);
            } else {
                OutputFormatter.printKv("New parent", "(root)");
            }

            // Print updated graph details if the response contains them
            if (response != null && !response.isBlank() && response.startsWith("{")) {
                JsonNode result = client.getObjectMapper().readTree(response);
                if (result.has("graphId") || result.has("id")) {
                    OutputFormatter.printKv("Graph ID", result.path("graphId").asText(result.path("id").asText("-")));
                    OutputFormatter.printKv("Name", result.path("name").asText("-"));
                }
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
