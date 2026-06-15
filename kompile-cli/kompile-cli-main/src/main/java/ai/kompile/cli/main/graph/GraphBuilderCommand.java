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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code kompile graph builder} — manage knowledge graph builder jobs,
 * extraction logs, and builder discovery.
 *
 * <p>Examples:
 * <pre>
 *   # List available builders
 *   kompile graph builder list
 *
 *   # Start an extraction job
 *   kompile graph builder start --fact-sheet-id 42 --builder llm
 *
 *   # Show job status
 *   kompile graph builder status abc-123
 *
 *   # List jobs for a fact sheet
 *   kompile graph builder jobs --fact-sheet-id 42
 *
 *   # View extraction logs for a job
 *   kompile graph builder logs abc-123
 *
 *   # Cancel a running job
 *   kompile graph builder cancel abc-123
 * </pre>
 */
@CommandLine.Command(
        name = "builder",
        description = "Manage knowledge graph extraction builders and jobs",
        subcommands = {
                GraphBuilderCommand.ListBuildersCmd.class,
                GraphBuilderCommand.StartJobCmd.class,
                GraphBuilderCommand.JobsCmd.class,
                GraphBuilderCommand.StatusCmd.class,
                GraphBuilderCommand.CancelCmd.class,
                GraphBuilderCommand.LogsCmd.class,
                GraphBuilderCommand.StatsCmd.class,
                GraphBuilderCommand.StorageTypesCmd.class
        },
        mixinStandardHelpOptions = true
)
public class GraphBuilderCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // builder list — list available builders
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "list", description = "List available graph builders",
            mixinStandardHelpOptions = true)
    static class ListBuildersCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/knowledge-graph/builder/builders");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode builders = client.getObjectMapper().readTree(response);
                if (!builders.isArray() || builders.isEmpty()) {
                    System.out.println("No graph builders available.");
                    return 0;
                }
                System.out.println("Available Graph Builders:");
                System.out.println();
                for (JsonNode b : builders) {
                    OutputFormatter.printKv("ID", b.path("id").asText());
                    OutputFormatter.printKv("Name", b.path("displayName").asText());
                    OutputFormatter.printKv("Type", b.path("type").asText());
                    String desc = b.path("description").asText("");
                    if (!desc.isEmpty()) OutputFormatter.printKv("Description", desc);
                    OutputFormatter.printKv("Supports Logs", b.path("supportsExtractionLog").asBoolean());
                    System.out.println();
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // builder start — start an extraction job
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "start", description = "Start a graph extraction job",
            mixinStandardHelpOptions = true)
    static class StartJobCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = {"--fact-sheet-id", "-f"}, required = true,
                description = "Fact sheet ID to extract from")
        private long factSheetId;

        @CommandLine.Option(names = {"--builder", "-b"},
                description = "Builder type (e.g., llm, pattern, hybrid)")
        private String builderType;

        @CommandLine.Option(names = "--model-provider",
                description = "LLM provider (e.g., openai, anthropic)")
        private String modelProvider;

        @CommandLine.Option(names = "--model-name",
                description = "LLM model name (e.g., gpt-4, claude-3-5-sonnet)")
        private String modelName;

        @CommandLine.Option(names = "--temperature",
                description = "LLM temperature (0.0-2.0)")
        private Double temperature;

        @CommandLine.Option(names = "--max-tokens",
                description = "LLM max tokens per request")
        private Integer maxTokens;

        @CommandLine.Option(names = "--entity-types", split = ",",
                description = "Entity types to extract (comma-separated)")
        private List<String> entityTypes;

        @CommandLine.Option(names = "--relationship-types", split = ",",
                description = "Relationship types to extract (comma-separated)")
        private List<String> relationshipTypes;

        @CommandLine.Option(names = "--min-confidence",
                description = "Minimum confidence threshold (0.0-1.0)")
        private Double minConfidence;

        @CommandLine.Option(names = "--auto-accept",
                description = "Auto-accept proposals above threshold")
        private Boolean autoAccept;

        @CommandLine.Option(names = "--auto-accept-threshold",
                description = "Auto-accept confidence threshold (0.0-1.0, default: 0.5)")
        private Double autoAcceptThreshold;

        @CommandLine.Option(names = "--custom-prompt",
                description = "Custom extraction prompt text")
        private String customPrompt;

        @CommandLine.Option(names = "--chunk-ids", split = ",",
                description = "Specific chunk IDs to process (comma-separated)")
        private List<String> chunkIds;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("factSheetId", factSheetId);
                if (builderType != null) body.put("builderType", builderType);
                if (chunkIds != null && !chunkIds.isEmpty()) body.put("chunkIds", chunkIds);

                // Build config sub-object
                Map<String, Object> config = new LinkedHashMap<>();
                if (modelProvider != null) config.put("modelProvider", modelProvider);
                if (modelName != null) config.put("modelName", modelName);
                if (temperature != null) config.put("temperature", temperature);
                if (maxTokens != null) config.put("maxTokens", maxTokens);
                if (entityTypes != null && !entityTypes.isEmpty()) config.put("entityTypes", entityTypes);
                if (relationshipTypes != null && !relationshipTypes.isEmpty()) config.put("relationshipTypes", relationshipTypes);
                if (minConfidence != null) config.put("minConfidence", minConfidence);
                if (autoAccept != null) config.put("autoAccept", autoAccept);
                if (autoAcceptThreshold != null) config.put("autoAcceptThreshold", autoAcceptThreshold);
                if (customPrompt != null) config.put("customPrompt", customPrompt);
                if (!config.isEmpty()) body.put("config", config);

                String response = client.postString("/api/knowledge-graph/builder/jobs", body);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode job = client.getObjectMapper().readTree(response);
                System.out.println("Extraction job started.");
                OutputFormatter.printKv("Job ID", job.path("jobId").asText());
                OutputFormatter.printKv("Status", job.path("status").asText());
                OutputFormatter.printKv("Builder", job.path("builderType").asText());
                OutputFormatter.printKv("Total Chunks", job.path("totalChunks"));
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // builder jobs — list extraction jobs
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "jobs", description = "List extraction jobs",
            mixinStandardHelpOptions = true)
    static class JobsCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = {"--fact-sheet-id", "-f"},
                description = "Filter by fact sheet ID")
        private Long factSheetId;

        @CommandLine.Option(names = "--page", defaultValue = "0",
                description = "Page number (default: 0)")
        private int page;

        @CommandLine.Option(names = "--size", defaultValue = "20",
                description = "Page size (default: 20)")
        private int size;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                StringBuilder url = new StringBuilder("/api/knowledge-graph/builder/jobs?page=")
                        .append(page).append("&size=").append(size);
                if (factSheetId != null) url.append("&factSheetId=").append(factSheetId);

                String response = client.getString(url.toString());
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode result = client.getObjectMapper().readTree(response);
                JsonNode content = result.has("content") ? result.get("content") : result;
                if (!content.isArray() || content.isEmpty()) {
                    System.out.println("No extraction jobs found.");
                    return 0;
                }
                System.out.println("Extraction Jobs:");
                OutputFormatter.printTable(content,
                        "jobId", "status", "builderType", "totalChunks", "processedChunks",
                        "proposalsCreated", "progressPercent");
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // builder status <jobId> — show job detail
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "status", description = "Show extraction job details",
            mixinStandardHelpOptions = true)
    static class StatusCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Job ID")
        private String jobId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/knowledge-graph/builder/jobs/" + jobId);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode job = client.getObjectMapper().readTree(response);
                System.out.println("Extraction Job: " + jobId);
                OutputFormatter.printKv("Status", job.path("status").asText());
                OutputFormatter.printKv("Builder", job.path("builderType").asText());
                OutputFormatter.printKv("Fact Sheet", job.path("factSheetId"));
                OutputFormatter.printKv("Total Chunks", job.path("totalChunks"));
                OutputFormatter.printKv("Processed", job.path("processedChunks"));
                OutputFormatter.printKv("Progress", job.path("progressPercent").asInt() + "%");
                OutputFormatter.printKv("Proposals Created", job.path("proposalsCreated"));
                OutputFormatter.printKv("Proposals Accepted", job.path("proposalsAccepted"));
                OutputFormatter.printKv("Proposals Rejected", job.path("proposalsRejected"));
                if (job.has("createdAt")) OutputFormatter.printKv("Created", job.path("createdAt").asText());
                if (job.has("startedAt")) OutputFormatter.printKv("Started", job.path("startedAt").asText());
                if (job.has("completedAt") && !job.path("completedAt").isNull()) {
                    OutputFormatter.printKv("Completed", job.path("completedAt").asText());
                }
                String error = job.path("errorMessage").asText("");
                if (!error.isEmpty()) OutputFormatter.printKv("Error", error);
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // builder cancel <jobId> — cancel a running job
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "cancel", description = "Cancel a running extraction job",
            mixinStandardHelpOptions = true)
    static class CancelCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Job ID to cancel")
        private String jobId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postEmpty("/api/knowledge-graph/builder/jobs/" + jobId + "/cancel");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.println("Cancelled extraction job: " + jobId);
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // builder logs <jobId> — view extraction logs
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "logs", description = "View extraction logs for a job",
            mixinStandardHelpOptions = true)
    static class LogsCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Job ID")
        private String jobId;

        @CommandLine.Option(names = "--chunk-id",
                description = "Show log for a specific chunk")
        private String chunkId;

        @CommandLine.Option(names = "--page", defaultValue = "0",
                description = "Page number (default: 0)")
        private int page;

        @CommandLine.Option(names = "--size", defaultValue = "20",
                description = "Page size (default: 20)")
        private int size;

        @CommandLine.Option(names = "--verbose", description = "Show full prompt/response text")
        private boolean verbose;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                if (chunkId != null) {
                    return showSingleLog(client);
                }
                return showLogList(client);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }

        private int showSingleLog(KompileHttpClient client) throws Exception {
            String url = "/api/knowledge-graph/builder/jobs/" + jobId + "/logs/" + chunkId;
            String response = client.getString(url);
            if (app.isJsonOutput()) {
                OutputFormatter.printJson(response);
                return 0;
            }
            JsonNode log = client.getObjectMapper().readTree(response);
            printLogEntry(log, true);
            return 0;
        }

        private int showLogList(KompileHttpClient client) throws Exception {
            String url = "/api/knowledge-graph/builder/jobs/" + jobId + "/logs?page=" + page + "&size=" + size;
            String response = client.getString(url);
            if (app.isJsonOutput()) {
                OutputFormatter.printJson(response);
                return 0;
            }
            JsonNode result = client.getObjectMapper().readTree(response);
            JsonNode content = result.has("content") ? result.get("content") : result;
            if (!content.isArray() || content.isEmpty()) {
                System.out.println("No extraction logs found for job: " + jobId);
                return 0;
            }
            System.out.println("Extraction Logs for job " + jobId + ":");
            System.out.println();
            for (JsonNode log : content) {
                printLogEntry(log, verbose);
                System.out.println();
            }
            return 0;
        }

        private void printLogEntry(JsonNode log, boolean full) {
            OutputFormatter.printKv("Chunk ID", log.path("chunkId").asText());
            OutputFormatter.printKv("Document", log.path("documentId").asText());
            OutputFormatter.printKv("Model", log.path("modelProvider").asText() + "/" + log.path("modelName").asText());
            OutputFormatter.printKv("Success", log.path("success").asBoolean());
            OutputFormatter.printKv("Entities", log.path("entitiesCount"));
            OutputFormatter.printKv("Relationships", log.path("relationshipsCount"));
            OutputFormatter.printKv("Latency", log.path("latencyMs").asLong() + "ms");
            OutputFormatter.printKv("Tokens", log.path("promptTokens").asInt() + " prompt / " +
                    log.path("responseTokens").asInt() + " response");
            String error = log.path("errorMessage").asText("");
            if (!error.isEmpty()) OutputFormatter.printKv("Error", error);
            if (full) {
                String input = log.path("inputText").asText("");
                if (!input.isEmpty()) {
                    System.out.println("  Input Text:");
                    System.out.println("    " + (input.length() > 500 ? input.substring(0, 500) + "..." : input));
                }
                String prompt = log.path("promptText").asText("");
                if (!prompt.isEmpty()) {
                    System.out.println("  Prompt:");
                    System.out.println("    " + (prompt.length() > 500 ? prompt.substring(0, 500) + "..." : prompt));
                }
                String resp = log.path("responseText").asText("");
                if (!resp.isEmpty()) {
                    System.out.println("  Response:");
                    System.out.println("    " + (resp.length() > 500 ? resp.substring(0, 500) + "..." : resp));
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // builder stats <jobId> — show job statistics
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "stats", description = "Show extraction job statistics",
            mixinStandardHelpOptions = true)
    static class StatsCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Job ID")
        private String jobId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/knowledge-graph/builder/jobs/" + jobId + "/statistics");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode stats = client.getObjectMapper().readTree(response);
                System.out.println("Job Statistics: " + jobId);
                stats.fields().forEachRemaining(e -> OutputFormatter.printKv(e.getKey(), e.getValue()));
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // builder storage-types — list available storage types
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "storage-types", description = "List available proposal storage types",
            mixinStandardHelpOptions = true)
    static class StorageTypesCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/knowledge-graph/builder/storage-types");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode result = client.getObjectMapper().readTree(response);
                System.out.println("Storage Types:");
                OutputFormatter.printKv("Default", result.path("defaultType").asText());
                JsonNode available = result.path("availableTypes");
                if (available.isArray()) {
                    List<String> types = new ArrayList<>();
                    for (JsonNode t : available) types.add(t.asText());
                    OutputFormatter.printKv("Available", String.join(", ", types));
                }
                JsonNode all = result.path("allTypes");
                if (all.isArray()) {
                    List<String> types = new ArrayList<>();
                    for (JsonNode t : all) types.add(t.asText());
                    OutputFormatter.printKv("All Types", String.join(", ", types));
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }
}
