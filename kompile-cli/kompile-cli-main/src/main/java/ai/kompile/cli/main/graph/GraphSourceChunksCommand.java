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
 * {@code kompile graph source-chunks} — show the source text chunks that
 * produced a given entity node. This provides provenance information showing
 * which document snippets an entity was extracted from.
 *
 * <p>Examples:
 * <pre>
 *   # Show source chunks for an entity
 *   kompile graph source-chunks abc-def-123
 *
 *   # JSON output
 *   kompile graph source-chunks abc-def-123 --json
 * </pre>
 */
@CommandLine.Command(
        name = "source-chunks",
        description = "Show source text chunks that produced an entity (provenance)",
        mixinStandardHelpOptions = true
)
public class GraphSourceChunksCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Parameters(index = "0", description = "Entity node UUID")
    private String nodeId;

    @Override
    public Integer call() {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;

        try {
            String response = client.getString(
                    "/api/knowledge-graph/nodes/" + nodeId + "/source-chunks");

            if (app.isJsonOutput()) {
                OutputFormatter.printJson(response);
                return 0;
            }

            JsonNode chunks = client.getObjectMapper().readTree(response);
            if (!chunks.isArray() || chunks.isEmpty()) {
                System.out.println("No source chunks found for node: " + nodeId);
                return 0;
            }

            System.out.println("Source Chunks for entity " + nodeId);
            System.out.println("=".repeat(50));

            for (int i = 0; i < chunks.size(); i++) {
                JsonNode chunk = chunks.get(i);
                System.out.println();
                System.out.println("Chunk " + (i + 1) + ":");

                printIfPresent(chunk, "chunkTitle", "Title");
                printIfPresent(chunk, "chunkNodeId", "Chunk ID");
                printIfPresent(chunk, "extractedAs", "Extracted As");
                printIfPresent(chunk, "predicate", "Predicate");

                // Confidence
                double conf = chunk.path("confidence").asDouble(-1);
                if (conf >= 0) {
                    OutputFormatter.printKv("Confidence", String.format("%.0f%%", conf * 100));
                }

                // Source provenance chain
                String docTitle = chunk.path("documentTitle").asText("");
                String sourceTitle = chunk.path("sourceTitle").asText("");
                String sourcePath = chunk.path("sourcePathOrUrl").asText("");
                String sourceType = chunk.path("sourceType").asText("");

                if (!sourceTitle.isEmpty() || !docTitle.isEmpty()) {
                    System.out.println("  Provenance:");
                    if (!sourceTitle.isEmpty()) {
                        System.out.printf("    Source: %s", sourceTitle);
                        if (!sourceType.isEmpty()) System.out.printf(" [%s]", sourceType);
                        System.out.println();
                    }
                    if (!sourcePath.isEmpty()) {
                        System.out.printf("    Path: %s%n", sourcePath);
                    }
                    if (!docTitle.isEmpty()) {
                        System.out.printf("    Document: %s%n", docTitle);
                    }
                }

                // Content preview
                String content = chunk.path("contentPreview").asText("");
                if (!content.isEmpty()) {
                    System.out.println("  Content:");
                    // Truncate long content
                    if (content.length() > 300) content = content.substring(0, 300) + "...";
                    for (String line : content.split("\n")) {
                        System.out.println("    " + line);
                    }
                }
            }

            System.out.println();
            System.out.println(chunks.size() + " source chunk(s) found.");
            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private void printIfPresent(JsonNode node, String field, String label) {
        JsonNode val = node.get(field);
        if (val != null && !val.isNull() && !val.asText("").isEmpty()) {
            OutputFormatter.printKv(label, val.asText());
        }
    }
}
