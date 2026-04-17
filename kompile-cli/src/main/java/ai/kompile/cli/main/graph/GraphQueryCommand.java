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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Executes a Cypher query against the configured Neo4j graph backend.
 * Reads a query string directly or, when prefixed with {@code @}, from a file.
 * Renders the result as a table or raw JSON depending on {@code --json}.
 */
@CommandLine.Command(
        name = "query",
        description = "Run a Cypher query against the Neo4j graph backend",
        mixinStandardHelpOptions = true
)
public class GraphQueryCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Parameters(index = "0",
            description = "Cypher query string, or '@path/to/file.cypher' to read from a file")
    private String cypherOrFile;

    @CommandLine.Option(names = "--params",
            description = "Query parameters as key=value (repeatable)")
    private Map<String, String> params;

    @CommandLine.Option(names = "--read-only",
            description = "Use read-only execution (rejects writes)")
    private boolean readOnly;

    @CommandLine.Option(names = "--limit", defaultValue = "100",
            description = "Limit rows shown in non-JSON output (default 100)")
    private int limit;

    @Override
    public Integer call() {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;
        try {
            String cypher = resolveCypher(cypherOrFile);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cypher", cypher);
            body.put("readOnly", readOnly);
            if (params != null && !params.isEmpty()) body.put("params", new LinkedHashMap<>(params));
            String response = client.postString("/api/graph/cypher/query", body);
            if (app.isJsonOutput()) {
                OutputFormatter.printJson(response);
                return 0;
            }
            JsonNode result = client.getObjectMapper().readTree(response);
            renderResult(result);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static String resolveCypher(String input) throws Exception {
        if (input == null || input.isBlank()) return "";
        if (input.startsWith("@")) {
            Path file = Path.of(input.substring(1));
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        return input;
    }

    void renderResult(JsonNode result) {
        JsonNode columns = result.path("columns");
        JsonNode rows = result.path("rows");
        long elapsed = result.path("elapsedMs").asLong(0);

        if (!columns.isArray() || columns.isEmpty()) {
            System.out.println("(empty result)");
            printStats(result, elapsed);
            return;
        }

        String[] colNames = new String[columns.size()];
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            colNames[i] = columns.get(i).asText();
            widths[i] = colNames[i].length();
        }
        int rowCount = rows.isArray() ? rows.size() : 0;
        int shown = Math.min(rowCount, limit);
        for (int r = 0; r < shown; r++) {
            JsonNode row = rows.get(r);
            for (int c = 0; c < colNames.length && c < row.size(); c++) {
                widths[c] = Math.max(widths[c], shorten(row.get(c)).length());
            }
        }
        for (int i = 0; i < widths.length; i++) widths[i] = Math.min(widths[i], 60);

        StringBuilder header = new StringBuilder("  ");
        StringBuilder sep = new StringBuilder("  ");
        for (int i = 0; i < colNames.length; i++) {
            header.append(String.format("%-" + (widths[i] + 2) + "s", colNames[i].toUpperCase()));
            sep.append("-".repeat(widths[i])).append("  ");
        }
        System.out.println(header);
        System.out.println(sep);

        for (int r = 0; r < shown; r++) {
            JsonNode row = rows.get(r);
            StringBuilder line = new StringBuilder("  ");
            for (int c = 0; c < colNames.length; c++) {
                String val = c < row.size() ? shorten(row.get(c)) : "-";
                if (val.length() > 60) val = val.substring(0, 57) + "...";
                line.append(String.format("%-" + (widths[c] + 2) + "s", val));
            }
            System.out.println(line);
        }
        if (rowCount > shown) {
            System.out.println("  ... " + (rowCount - shown) + " more row(s) (use --limit or --json)");
        }
        printStats(result, elapsed);
    }

    private static String shorten(JsonNode value) {
        if (value == null || value.isNull()) return "-";
        if (value.isTextual()) return value.asText();
        return value.toString();
    }

    private static void printStats(JsonNode result, long elapsed) {
        JsonNode stats = result.path("stats");
        System.out.println();
        OutputFormatter.printKv("Elapsed (ms)", elapsed);
        if (stats.isObject() && stats.size() > 0) {
            stats.fields().forEachRemaining(e -> {
                if (e.getValue().isNumber() && e.getValue().asInt() != 0) {
                    OutputFormatter.printKv(e.getKey(), e.getValue());
                }
            });
        }
    }
}
