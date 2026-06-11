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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code kompile graph link-sources} — auto-link document sources via shared
 * concepts, embedding similarity, or both. Also supports querying connectivity,
 * finding isolated sources, and viewing most-connected sources.
 *
 * <p>Examples:
 * <pre>
 *   # Auto-link all sources in fact sheet 42
 *   kompile graph link-sources --fact-sheet-id 42
 *
 *   # Link using similarity only
 *   kompile graph link-sources --fact-sheet-id 42 --mode similarity
 *
 *   # Show connectivity summary
 *   kompile graph link-sources --fact-sheet-id 42 --connectivity
 *
 *   # Find isolated (unconnected) sources
 *   kompile graph link-sources --fact-sheet-id 42 --isolated
 *
 *   # Show most connected sources
 *   kompile graph link-sources --fact-sheet-id 42 --most-connected --limit 10
 * </pre>
 */
@CommandLine.Command(
        name = "link-sources",
        description = "Auto-link document sources via shared concepts or similarity",
        mixinStandardHelpOptions = true
)
public class GraphLinkSourcesCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = {"--fact-sheet-id", "-f"},
            description = "Fact sheet ID to scope source linking")
    private Long factSheetId;

    @CommandLine.Option(names = "--mode", defaultValue = "all",
            description = "Linking mode: concepts, similarity, all (default: all)")
    private String mode;

    // ── Query options (mutually exclusive with linking) ──

    @CommandLine.Option(names = "--connectivity",
            description = "Show connectivity summary instead of linking")
    private boolean connectivity;

    @CommandLine.Option(names = "--isolated",
            description = "Find isolated (unconnected) sources")
    private boolean isolated;

    @CommandLine.Option(names = "--most-connected",
            description = "Show most-connected sources")
    private boolean mostConnected;

    @CommandLine.Option(names = "--list",
            description = "List existing source links")
    private boolean listLinks;

    @CommandLine.Option(names = "--limit", defaultValue = "10",
            description = "Limit for most-connected (default: 10)")
    private int limit;

    @Override
    public Integer call() {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;

        try {
            if (connectivity) return showConnectivity(client);
            if (isolated) return showIsolated(client);
            if (mostConnected) return showMostConnected(client);
            if (listLinks) return showLinks(client);
            return performLinking(client);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private int performLinking(KompileHttpClient client) throws Exception {
        String endpoint = switch (mode.toLowerCase()) {
            case "concepts" -> "/api/knowledge-graph/links/sources";
            case "similarity" -> "/api/knowledge-graph/links/sources/similarity";
            default -> "/api/knowledge-graph/links/sources/all";
        };

        Map<String, Object> body = new LinkedHashMap<>();
        if (factSheetId != null) {
            endpoint += "?factSheetId=" + factSheetId;
        }

        String response = client.postString(endpoint, body);
        if (app.isJsonOutput()) {
            OutputFormatter.printJson(response);
            return 0;
        }

        JsonNode result = client.getObjectMapper().readTree(response);
        System.out.println("Source Linking Results:");
        result.fields().forEachRemaining(e -> OutputFormatter.printKv(e.getKey(), e.getValue()));
        return 0;
    }

    private int showConnectivity(KompileHttpClient client) throws Exception {
        String url = "/api/knowledge-graph/links/connectivity";
        if (factSheetId != null) url += "?factSheetId=" + factSheetId;

        String response = client.getString(url);
        if (app.isJsonOutput()) {
            OutputFormatter.printJson(response);
            return 0;
        }

        JsonNode result = client.getObjectMapper().readTree(response);
        System.out.println("Source Connectivity:");
        result.fields().forEachRemaining(e -> OutputFormatter.printKv(e.getKey(), e.getValue()));
        return 0;
    }

    private int showIsolated(KompileHttpClient client) throws Exception {
        String url = "/api/knowledge-graph/links/isolated";
        if (factSheetId != null) url += "?factSheetId=" + factSheetId;

        String response = client.getString(url);
        if (app.isJsonOutput()) {
            OutputFormatter.printJson(response);
            return 0;
        }

        JsonNode result = client.getObjectMapper().readTree(response);
        if (result.isArray()) {
            if (result.isEmpty()) {
                System.out.println("No isolated sources found. All sources are connected.");
            } else {
                System.out.println("Isolated Sources (" + result.size() + "):");
                for (JsonNode sourceId : result) {
                    System.out.println("  " + sourceId.asText());
                }
            }
        }
        return 0;
    }

    private int showMostConnected(KompileHttpClient client) throws Exception {
        String url = "/api/knowledge-graph/links/most-connected?limit=" + limit;
        if (factSheetId != null) url += "&factSheetId=" + factSheetId;

        String response = client.getString(url);
        if (app.isJsonOutput()) {
            OutputFormatter.printJson(response);
            return 0;
        }

        JsonNode result = client.getObjectMapper().readTree(response);
        System.out.println("Most Connected Sources:");
        if (result.isArray()) {
            for (JsonNode source : result) {
                source.fields().forEachRemaining(e -> OutputFormatter.printKv(e.getKey(), e.getValue()));
                System.out.println();
            }
        }
        return 0;
    }

    private int showLinks(KompileHttpClient client) throws Exception {
        String url = "/api/knowledge-graph/links/sources";
        if (factSheetId != null) url += "?factSheetId=" + factSheetId;

        String response = client.getString(url);
        if (app.isJsonOutput()) {
            OutputFormatter.printJson(response);
            return 0;
        }

        JsonNode result = client.getObjectMapper().readTree(response);
        if (result.isArray()) {
            if (result.isEmpty()) {
                System.out.println("No source links found.");
            } else {
                System.out.println("Source Links (" + result.size() + "):");
                for (JsonNode link : result) {
                    String src1 = link.path("sourceNodeId1").asText("?");
                    String src2 = link.path("sourceNodeId2").asText("?");
                    double strength = link.path("strength").asDouble(0);
                    String desc = link.path("description").asText("");
                    System.out.printf("  %s <-> %s (strength: %.2f)", src1, src2, strength);
                    if (!desc.isEmpty()) System.out.printf(" - %s", desc);
                    System.out.println();
                }
            }
        }
        return 0;
    }
}
