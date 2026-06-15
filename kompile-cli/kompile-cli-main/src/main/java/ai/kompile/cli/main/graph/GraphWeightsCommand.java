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
 * {@code kompile graph weights} — manage per-source RAG retrieval weights.
 * Allows listing, setting, removing, and previewing source weights that
 * influence how document sources are ranked during retrieval.
 *
 * <p>Examples:
 * <pre>
 *   # List all source weights
 *   kompile graph weights list
 *
 *   # Set weight for a specific source
 *   kompile graph weights set --source-id abc-123 --weight 2.5
 *
 *   # Set topic-specific weight
 *   kompile graph weights set --source-id abc-123 --weight 3.0 --topic security
 *
 *   # Preview how weights affect a query
 *   kompile graph weights preview --query "authentication best practices"
 *
 *   # Remove a source weight
 *   kompile graph weights remove --source-id abc-123
 * </pre>
 */
@CommandLine.Command(
        name = "weights",
        description = "Manage per-source RAG retrieval weights",
        subcommands = {
                GraphWeightsCommand.ListCmd.class,
                GraphWeightsCommand.SetCmd.class,
                GraphWeightsCommand.RemoveCmd.class,
                GraphWeightsCommand.PreviewCmd.class,
                GraphWeightsCommand.FeedbackCmd.class
        },
        mixinStandardHelpOptions = true
)
public class GraphWeightsCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @CommandLine.Command(name = "list", description = "List all source weights")
    static class ListCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = "--source-id",
                description = "Filter by source ID")
        private String sourceId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/knowledge-graph/weights";
                if (sourceId != null) url += "?sourceId=" + enc(sourceId);

                String response = client.getString(url);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode weights = client.getObjectMapper().readTree(response);
                if (weights.isArray()) {
                    if (weights.isEmpty()) {
                        System.out.println("No source weights configured.");
                        return 0;
                    }
                    System.out.println("Source Weights:");
                    for (JsonNode w : weights) {
                        String sid = w.path("sourceNodeId").asText(w.path("sourceId").asText("?"));
                        double baseWeight = w.path("baseWeight").asDouble(1.0);
                        String topic = w.path("topic").asText("");
                        System.out.printf("  Source: %s  Weight: %.2f", sid, baseWeight);
                        if (!topic.isEmpty()) System.out.printf("  Topic: %s", topic);
                        System.out.println();
                    }
                } else {
                    // Single weight returned
                    OutputFormatter.printKv("Source", weights.path("sourceNodeId").asText(""));
                    OutputFormatter.printKv("Weight", weights.path("baseWeight").asDouble(1.0));
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "set", description = "Set a source weight")
    static class SetCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = "--source-id", required = true,
                description = "Source node UUID")
        private String sourceId;

        @CommandLine.Option(names = "--weight", required = true,
                description = "Base weight value (0.0-3.0)")
        private double weight;

        @CommandLine.Option(names = "--topic",
                description = "Optional topic to scope the weight")
        private String topic;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("sourceNodeId", sourceId);
                body.put("baseWeight", weight);
                if (topic != null) body.put("topic", topic);

                String response = client.postString("/api/knowledge-graph/weights", body);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.printf("Set weight %.2f for source %s", weight, sourceId);
                    if (topic != null) System.out.printf(" (topic: %s)", topic);
                    System.out.println();
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "remove", description = "Remove a source weight")
    static class RemoveCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = "--source-id", required = true,
                description = "Source node UUID")
        private String sourceId;

        @CommandLine.Option(names = "--topic",
                description = "Topic to remove weight for (omit for base weight)")
        private String topic;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/knowledge-graph/weights/" + enc(sourceId);
                if (topic != null) url += "?topic=" + enc(topic);

                client.delete(url);
                System.out.println("Removed weight for source: " + sourceId);
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "preview", description = "Preview how weights affect retrieval for a query")
    static class PreviewCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = {"--query", "-q"}, required = true,
                description = "Query text to preview weighted results for")
        private String query;

        @CommandLine.Option(names = "--max-results", defaultValue = "10")
        private int maxResults;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("query", query);
                body.put("maxResults", maxResults);

                String response = client.postString("/api/knowledge-graph/weights/preview", body);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode result = client.getObjectMapper().readTree(response);
                System.out.println("Weight Preview for: \"" + query + "\"");
                System.out.println();
                result.fields().forEachRemaining(e ->
                        OutputFormatter.printKv(e.getKey(), e.getValue()));
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "feedback", description = "Submit relevance feedback for a source")
    static class FeedbackCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = "--source-id", required = true,
                description = "Source node UUID")
        private String sourceId;

        @CommandLine.Option(names = "--helpful", required = true,
                description = "Whether the source was helpful (true/false)")
        private boolean helpful;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("sourceNodeId", sourceId);
                body.put("wasHelpful", helpful);

                client.postString("/api/knowledge-graph/weights/feedback", body);
                System.out.printf("Feedback recorded: source %s was %s%n",
                        sourceId, helpful ? "helpful" : "not helpful");
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
