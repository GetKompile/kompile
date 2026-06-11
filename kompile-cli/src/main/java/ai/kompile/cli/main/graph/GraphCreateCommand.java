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
 * {@code kompile graph create-graph} — create a named graph container, optionally
 * nested under a parent graph.
 */
@CommandLine.Command(
        name = "create-graph",
        description = "Create a named graph container",
        mixinStandardHelpOptions = true
)
public class GraphCreateCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = "--name", required = true,
            description = "Name for the new graph")
    private String name;

    @CommandLine.Option(names = "--description",
            description = "Optional description")
    private String description;

    @CommandLine.Option(names = "--parent-graph-id",
            description = "Optional parent graph ID for nesting")
    private String parentGraphId;

    @CommandLine.Option(names = "--fact-sheet-id",
            description = "Optional fact sheet ID to scope the graph")
    private Long factSheetId;

    @CommandLine.Option(names = "--ontology-type",
            description = "Optional ontology type: domain_ontology, taxonomy, knowledge_graph")
    private String ontologyType;

    @Override
    public Integer call() {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            if (description != null) body.put("description", description);
            if (parentGraphId != null) body.put("parentGraphId", parentGraphId);
            if (factSheetId != null) body.put("factSheetId", factSheetId);
            if (ontologyType != null) body.put("ontologyType", ontologyType);

            String response = client.postString("/api/graphs", body);

            if (app.isJsonOutput()) {
                OutputFormatter.printJson(response);
                return 0;
            }
            JsonNode result = client.getObjectMapper().readTree(response);
            System.out.println("Created graph:");
            OutputFormatter.printKv("Graph ID", result.path("graphId").asText(result.path("id").asText("-")));
            OutputFormatter.printKv("Name", result.path("name").asText("-"));
            if (result.has("description") && !result.get("description").isNull()) {
                OutputFormatter.printKv("Description", result.path("description").asText());
            }
            if (result.has("parentGraphId") && !result.get("parentGraphId").isNull()) {
                OutputFormatter.printKv("Parent Graph ID", result.path("parentGraphId").asText());
            }
            if (result.has("ontologyType") && !result.get("ontologyType").isNull()) {
                OutputFormatter.printKv("Ontology Type", result.path("ontologyType").asText());
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
