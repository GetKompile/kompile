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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Exports the (optionally fact-sheet-scoped) knowledge graph to a file in the
 * requested format. Streams the response body straight to disk.
 */
@CommandLine.Command(
        name = "export",
        description = "Export the graph to JSON, JSON-LD, CSV (zip), GraphML, Cypher dump, ASCII, PNG, "
                + "or the full native .kgraph (--format kgraph)",
        mixinStandardHelpOptions = true
)
public class GraphExportCommand implements Callable<Integer> {

    static final Set<String> SUPPORTED_FORMATS =
            Set.of("json", "jsonld", "json-ld", "csv", "graphml", "cypher", "ascii", "png", "kgraph");

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = "--format", required = false, defaultValue = "kgraph",
            description = "One of: json, jsonld, csv, graphml, cypher, ascii, png, kgraph (default: kgraph — "
                    + "the only format that preserves full graph state for round-trip via 'graph import')")
    private String format;

    @CommandLine.Option(names = {"--output", "-o"}, required = true,
            description = "Destination file path")
    private Path output;

    @CommandLine.Option(names = "--fact-sheet-id",
            description = "Scope export to a fact sheet")
    private Long factSheetId;

    @CommandLine.Option(names = "--vectors", defaultValue = "summary",
            description = "For ASCII/PNG diagnostics, show vector summaries or values: summary, values (default: summary)")
    private String vectors;

    @CommandLine.Option(names = "--bundle", defaultValue = "true",
            description = "For PNG diagnostics, write a ZIP of complete pages; use --bundle=false for one PNG (default: true)")
    private boolean bundle;

    @Override
    public Integer call() {
        String normalizedFormat = format.toLowerCase(Locale.ROOT);
        String normalizedVectors = vectors.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_FORMATS.contains(normalizedFormat)) {
            System.err.println("Unknown format '" + format + "'. Supported: " + SUPPORTED_FORMATS);
            return 1;
        }
        if (!Set.of("summary", "values").contains(normalizedVectors)) {
            System.err.println("Unknown vector mode '" + vectors + "'. Supported: [summary, values]");
            return 1;
        }
        KompileHttpClient client = isUnifiedFormat(normalizedFormat) ? app.requireGraphClient() : app.requireClient();
        if (client == null) return 1;
        try {
            String url;
            if (isUnifiedFormat(normalizedFormat)) {
                // The full native graph (all vector layers, opinions, weights) is a distinct endpoint.
                url = unifiedExportUrl(normalizedFormat, factSheetId, normalizedVectors, bundle);
            } else {
                StringBuilder u = new StringBuilder("/api/graph/io/export?format=").append(normalizedFormat);
                if (factSheetId != null) u.append("&factSheetId=").append(factSheetId);
                url = u.toString();
            }
            String contentDisposition = client.downloadToFile(url, output);
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

    static boolean isUnifiedFormat(String format) {
        return "kgraph".equalsIgnoreCase(format)
                || "ascii".equalsIgnoreCase(format)
                || "png".equalsIgnoreCase(format);
    }

    static String unifiedExportUrl(String format, Long factSheetId, String vectors, boolean bundle) {
        StringBuilder url = new StringBuilder("/api/graph/unified/export?format=").append(format);
        if (factSheetId != null) url.append("&factSheetId=").append(factSheetId);
        if (!"summary".equalsIgnoreCase(vectors)) url.append("&vectors=values");
        if ("png".equalsIgnoreCase(format) && !bundle) url.append("&bundle=false");
        return url.toString();
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
