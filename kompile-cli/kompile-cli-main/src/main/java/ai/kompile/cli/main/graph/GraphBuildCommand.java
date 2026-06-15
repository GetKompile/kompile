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
 * {@code kompile graph build} — build a knowledge graph from a fact sheet's
 * indexed documents. Triggers the server-side graph building pipeline and
 * optionally polls for completion.
 *
 * <p>Examples:
 * <pre>
 *   # Build graph for fact sheet 42
 *   kompile graph build --fact-sheet-id 42
 *
 *   # Build and wait for completion
 *   kompile graph build --fact-sheet-id 42 --wait
 *
 *   # Check status of a running build job
 *   kompile graph build --status --job-id abc-123 --fact-sheet-id 42
 *
 *   # Cancel a running build
 *   kompile graph build --cancel --job-id abc-123 --fact-sheet-id 42
 * </pre>
 */
@CommandLine.Command(
        name = "build",
        description = "Build a knowledge graph from a fact sheet's indexed documents",
        mixinStandardHelpOptions = true
)
public class GraphBuildCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = {"--fact-sheet-id", "-f"}, required = true,
            description = "Fact sheet ID to build graph from")
    private long factSheetId;

    @CommandLine.Option(names = "--wait",
            description = "Poll until the build completes (default: fire and forget)")
    private boolean wait;

    @CommandLine.Option(names = "--status",
            description = "Check status of an existing build job (requires --job-id)")
    private boolean checkStatus;

    @CommandLine.Option(names = "--cancel",
            description = "Cancel a running build job (requires --job-id)")
    private boolean cancel;

    @CommandLine.Option(names = "--job-id",
            description = "Job ID for status/cancel operations")
    private String jobId;

    @CommandLine.Option(names = "--poll-interval", defaultValue = "3",
            description = "Poll interval in seconds when using --wait (default: 3)")
    private int pollInterval;

    @Override
    public Integer call() {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;

        try {
            if (checkStatus) return handleStatus(client);
            if (cancel) return handleCancel(client);
            return handleBuild(client);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private int handleBuild(KompileHttpClient client) throws Exception {
        System.out.println("Starting graph build for fact sheet " + factSheetId + "...");

        String response = client.postString(
                "/api/fact-sheets/" + factSheetId + "/graph/build",
                new LinkedHashMap<>());

        JsonNode result = client.getObjectMapper().readTree(response);
        String resultJobId = result.path("jobId").asText(result.path("id").asText(""));
        String status = result.path("status").asText("unknown");

        if (app.isJsonOutput()) {
            OutputFormatter.printJson(response);
        } else {
            OutputFormatter.printKv("Job ID", resultJobId);
            OutputFormatter.printKv("Status", status);
        }

        if (wait && !resultJobId.isEmpty()) {
            return pollUntilComplete(client, resultJobId);
        }

        return 0;
    }

    private int handleStatus(KompileHttpClient client) throws Exception {
        if (jobId == null || jobId.isBlank()) {
            System.err.println("--job-id is required with --status");
            return 1;
        }

        String response = client.getString(
                "/api/fact-sheets/" + factSheetId + "/graph/build/status/" + jobId);

        if (app.isJsonOutput()) {
            OutputFormatter.printJson(response);
            return 0;
        }

        JsonNode result = client.getObjectMapper().readTree(response);
        printBuildStatus(result);
        return 0;
    }

    private int handleCancel(KompileHttpClient client) throws Exception {
        if (jobId == null || jobId.isBlank()) {
            System.err.println("--job-id is required with --cancel");
            return 1;
        }

        String response = client.postString(
                "/api/fact-sheets/" + factSheetId + "/graph/build/cancel/" + jobId,
                new LinkedHashMap<>());

        if (app.isJsonOutput()) {
            OutputFormatter.printJson(response);
        } else {
            System.out.println("Cancelled build job: " + jobId);
        }

        return 0;
    }

    private int pollUntilComplete(KompileHttpClient client, String pollJobId) throws Exception {
        System.out.println("Waiting for build to complete (poll every " + pollInterval + "s)...");

        while (true) {
            Thread.sleep(pollInterval * 1000L);

            String response = client.getString(
                    "/api/fact-sheets/" + factSheetId + "/graph/build/status/" + pollJobId);
            JsonNode result = client.getObjectMapper().readTree(response);
            String status = result.path("status").asText("unknown").toUpperCase();

            // Print progress
            int docCount = result.path("documentsProcessed").asInt(0);
            int totalDocs = result.path("totalDocuments").asInt(0);
            int concepts = result.path("conceptsExtracted").asInt(0);
            if (totalDocs > 0) {
                System.out.printf("\r  Progress: %d/%d documents, %d concepts extracted...",
                        docCount, totalDocs, concepts);
            }

            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                System.out.println();
                printBuildStatus(result);
                return "COMPLETED".equals(status) ? 0 : 1;
            }
        }
    }

    private void printBuildStatus(JsonNode result) {
        OutputFormatter.printKv("Job ID", result.path("jobId").asText(result.path("id").asText("")));
        OutputFormatter.printKv("Status", result.path("status").asText("unknown"));
        int docCount = result.path("documentsProcessed").asInt(0);
        int totalDocs = result.path("totalDocuments").asInt(0);
        if (totalDocs > 0) {
            OutputFormatter.printKv("Documents", docCount + "/" + totalDocs);
        }
        int concepts = result.path("conceptsExtracted").asInt(0);
        if (concepts > 0) {
            OutputFormatter.printKv("Concepts extracted", concepts);
        }
    }
}
