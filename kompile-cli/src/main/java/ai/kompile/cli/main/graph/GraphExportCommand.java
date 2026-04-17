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
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Exports the (optionally fact-sheet-scoped) knowledge graph to a file in the
 * requested format. Streams the response body straight to disk.
 */
@CommandLine.Command(
        name = "export",
        description = "Export the graph to JSON, JSON-LD, CSV (zip), GraphML, or Cypher dump",
        mixinStandardHelpOptions = true
)
public class GraphExportCommand implements Callable<Integer> {

    static final Set<String> SUPPORTED_FORMATS = Set.of("json", "jsonld", "json-ld", "csv", "graphml", "cypher");

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = "--format", required = true,
            description = "One of: json, jsonld, csv, graphml, cypher")
    private String format;

    @CommandLine.Option(names = {"--output", "-o"}, required = true,
            description = "Destination file path")
    private Path output;

    @CommandLine.Option(names = "--fact-sheet-id",
            description = "Scope export to a fact sheet")
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
            StringBuilder url = new StringBuilder("/api/graph/io/export?format=").append(format);
            if (factSheetId != null) url.append("&factSheetId=").append(factSheetId);
            String contentDisposition = client.downloadToFile(url.toString(), output);
            System.out.println("Wrote " + output.toAbsolutePath());
            if (contentDisposition != null) {
                String suggested = parseFilename(contentDisposition);
                if (suggested != null) System.out.println("Server-suggested filename: " + suggested);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static String parseFilename(String contentDisposition) {
        if (contentDisposition == null) return null;
        int idx = contentDisposition.toLowerCase().indexOf("filename=");
        if (idx < 0) return null;
        String tail = contentDisposition.substring(idx + "filename=".length()).trim();
        if (tail.startsWith("\"")) {
            int end = tail.indexOf('"', 1);
            return end > 0 ? tail.substring(1, end) : null;
        }
        int sep = tail.indexOf(';');
        return sep > 0 ? tail.substring(0, sep).trim() : tail;
    }
}
