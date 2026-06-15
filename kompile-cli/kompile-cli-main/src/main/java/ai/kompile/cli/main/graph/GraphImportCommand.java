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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Imports a graph from a file in one of several portable formats. For CSV the
 * caller can supply a separate edges file; otherwise a single payload covers
 * both nodes and edges.
 */
@CommandLine.Command(
        name = "import",
        description = "Bulk import nodes and edges from JSON, JSON-LD, CSV, or Cypher dump",
        mixinStandardHelpOptions = true
)
public class GraphImportCommand implements Callable<Integer> {

    static final Set<String> SUPPORTED_FORMATS = Set.of("json", "jsonld", "json-ld", "csv", "cypher");

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Parameters(index = "0", description = "Path to file to import")
    private Path filePath;

    @CommandLine.Option(names = "--format", required = true,
            description = "One of: json, jsonld, csv, cypher")
    private String format;

    @CommandLine.Option(names = "--edges-file",
            description = "Optional second file (CSV-only: separate edges.csv)")
    private Path edgesFile;

    @CommandLine.Option(names = "--fact-sheet-id",
            description = "Scope import to a fact sheet")
    private Long factSheetId;

    @Override
    public Integer call() {
        if (!SUPPORTED_FORMATS.contains(format.toLowerCase())) {
            System.err.println("Unknown format '" + format + "'. Supported: " + SUPPORTED_FORMATS);
            return 1;
        }
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;
        try {
            Map<String, Path> files = new LinkedHashMap<>();
            files.put("file", filePath);
            if (edgesFile != null) files.put("edgesFile", edgesFile);

            Map<String, String> form = new LinkedHashMap<>();
            form.put("format", format);
            if (factSheetId != null) form.put("factSheetId", String.valueOf(factSheetId));

            String response = client.uploadMultipart("/api/graph/io/import", files, form);
            if (app.isJsonOutput()) {
                OutputFormatter.printJson(response);
                return 0;
            }
            JsonNode result = client.getObjectMapper().readTree(response);
            System.out.println("Import complete:");
            OutputFormatter.printKv("Format", result.path("format").asText());
            OutputFormatter.printKv("Nodes created", result.path("nodesCreated").asInt());
            OutputFormatter.printKv("Nodes updated", result.path("nodesUpdated").asInt());
            OutputFormatter.printKv("Edges created", result.path("edgesCreated").asInt());
            OutputFormatter.printKv("Errors", result.path("errors").asInt());
            JsonNode messages = result.path("errorMessages");
            if (messages.isArray() && !messages.isEmpty()) {
                System.out.println("Error details:");
                int max = Math.min(messages.size(), 10);
                for (int i = 0; i < max; i++) {
                    System.out.println("  - " + messages.get(i).asText());
                }
                if (messages.size() > max) {
                    System.out.println("  ... " + (messages.size() - max) + " more (use --json for full list)");
                }
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
