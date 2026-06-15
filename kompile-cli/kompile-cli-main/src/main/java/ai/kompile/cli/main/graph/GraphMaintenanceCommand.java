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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code kompile graph maintain} command tree for graph maintenance operations:
 * health diagnostics, pruning, validation, relabeling, bulk delete, edge cleanup.
 */
@CommandLine.Command(
        name = "maintain",
        description = "Graph maintenance tools: prune, validate, relabel, cleanup",
        subcommands = {
                GraphMaintenanceCommand.HealthCmd.class,
                GraphMaintenanceCommand.PruneCmd.class,
                GraphMaintenanceCommand.ValidateCmd.class,
                GraphMaintenanceCommand.RelabelCmd.class,
                GraphMaintenanceCommand.LabelsCmd.class,
                GraphMaintenanceCommand.BulkDeleteCmd.class,
                GraphMaintenanceCommand.EdgeCleanupCmd.class,
                GraphMaintenanceCommand.PatchCmd.class
        },
        mixinStandardHelpOptions = true
)
public class GraphMaintenanceCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HEALTH
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "health", description = "Show graph health diagnostics")
    static class HealthCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Option(names = "--fact-sheet-id") private Long factSheetId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/graph/maintenance/health";
                if (factSheetId != null) url += "?factSheetId=" + factSheetId;
                String response = client.getString(url);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode report = client.getObjectMapper().readTree(response);
                System.out.println("Graph Health Report");
                System.out.println("═══════════════════════════════════════");
                OutputFormatter.printKv("Total Nodes", report.path("totalNodes").asText());
                OutputFormatter.printKv("Total Edges", report.path("totalEdges").asText());

                System.out.println("\nNode Types:");
                report.path("nodeCountsByType").fields()
                        .forEachRemaining(e -> OutputFormatter.printKv("  " + e.getKey(), e.getValue().asText()));

                System.out.println("\nEdge Types:");
                report.path("edgeCountsByType").fields()
                        .forEachRemaining(e -> OutputFormatter.printKv("  " + e.getKey(), e.getValue().asText()));

                System.out.println("\nEntity Type Distribution:");
                report.path("entityTypeDistribution").fields()
                        .forEachRemaining(e -> OutputFormatter.printKv("  " + e.getKey(), e.getValue().asText()));

                JsonNode issues = report.path("issues");
                if (issues.isArray() && !issues.isEmpty()) {
                    System.out.println("\nIssues Found:");
                    for (JsonNode issue : issues) {
                        System.out.println("  ! " + issue.asText());
                    }
                } else {
                    System.out.println("\nNo issues found.");
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRUNE
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "prune", description = "Remove orphan entities and weak edges")
    static class PruneCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Option(names = "--fact-sheet-id") private Long factSheetId;
        @CommandLine.Option(names = "--dry-run", description = "Preview changes without applying")
        private boolean dryRun;
        @CommandLine.Option(names = "--confidence-threshold", defaultValue = "0.3",
                description = "Remove orphan entities below this confidence")
        private double confidenceThreshold;
        @CommandLine.Option(names = "--edge-weight-threshold", defaultValue = "0.25",
                description = "Remove weak edges below this weight")
        private double edgeWeightThreshold;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String endpoint = dryRun ? "/api/graph/maintenance/prune/preview" : "/api/graph/maintenance/prune";
                if (factSheetId != null) endpoint += "?factSheetId=" + factSheetId;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("dryRun", dryRun);
                body.put("confidenceThreshold", confidenceThreshold);
                body.put("edgeWeightThreshold", edgeWeightThreshold);
                String response = client.postString(endpoint, body);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode result = client.getObjectMapper().readTree(response);
                System.out.println(dryRun ? "Prune Preview (dry run):" : "Prune Results:");
                OutputFormatter.printKv("Nodes pruned", result.path("nodesPruned").asText());
                OutputFormatter.printKv("Edges pruned", result.path("edgesPruned").asText());
                JsonNode details = result.path("details");
                if (details.isArray()) {
                    for (JsonNode d : details) {
                        System.out.printf("  [%s] %s — %s%n",
                                d.path("reason").asText(),
                                d.path("title").asText("(untitled)"),
                                d.path("info").asText());
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VALIDATE
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "validate", description = "Fix blank titles, dangling/duplicate edges")
    static class ValidateCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Option(names = "--fact-sheet-id") private Long factSheetId;
        @CommandLine.Option(names = "--dry-run", description = "Preview changes without applying")
        private boolean dryRun;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String endpoint = dryRun ? "/api/graph/maintenance/validate/preview" : "/api/graph/maintenance/validate";
                StringBuilder url = new StringBuilder(endpoint);
                String sep = "?";
                if (factSheetId != null) { url.append(sep).append("factSheetId=").append(factSheetId); sep = "&"; }
                if (!dryRun) { url.append(sep).append("dryRun=false"); }
                String response = client.postString(url.toString(), Map.of());
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode result = client.getObjectMapper().readTree(response);
                System.out.println(dryRun ? "Validate Preview (dry run):" : "Validate Results:");
                OutputFormatter.printKv("Issues found", result.path("issuesFound").asText());
                JsonNode details = result.path("details");
                if (details.isArray()) {
                    for (JsonNode d : details) {
                        System.out.printf("  [%s] %s — %s%n",
                                d.path("action").asText(),
                                d.path("id").asText(),
                                d.path("description").asText());
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RELABEL
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "relabel", description = "Change entity type labels in bulk")
    static class RelabelCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Option(names = "--fact-sheet-id") private Long factSheetId;
        @CommandLine.Option(names = "--from", required = true, description = "Current entity type label")
        private String fromType;
        @CommandLine.Option(names = "--to", required = true, description = "New entity type label")
        private String toType;
        @CommandLine.Option(names = "--title-pattern", description = "Only relabel nodes matching this regex")
        private String titlePattern;
        @CommandLine.Option(names = "--dry-run", description = "Preview changes without applying")
        private boolean dryRun;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String endpoint = dryRun ? "/api/graph/maintenance/relabel/preview" : "/api/graph/maintenance/relabel";
                if (factSheetId != null) endpoint += "?factSheetId=" + factSheetId;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("dryRun", dryRun);
                body.put("fromType", fromType);
                body.put("toType", toType);
                if (titlePattern != null) body.put("titlePattern", titlePattern);
                String response = client.postString(endpoint, body);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode result = client.getObjectMapper().readTree(response);
                System.out.println(dryRun ? "Relabel Preview (dry run):" : "Relabel Results:");
                OutputFormatter.printKv("From", result.path("fromType").asText());
                OutputFormatter.printKv("To", result.path("toType").asText());
                OutputFormatter.printKv("Count", result.path("relabeledCount").asText());
                JsonNode details = result.path("details");
                if (details.isArray()) {
                    for (JsonNode d : details) {
                        System.out.printf("  %s: %s -> %s%n",
                                d.path("title").asText("(untitled)"),
                                d.path("oldType").asText(),
                                d.path("newType").asText());
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LABELS (list)
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "labels", description = "List all entity type labels and counts")
    static class LabelsCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Option(names = "--fact-sheet-id") private Long factSheetId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String url = "/api/graph/maintenance/labels";
                if (factSheetId != null) url += "?factSheetId=" + factSheetId;
                String response = client.getString(url);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode labels = client.getObjectMapper().readTree(response);
                System.out.println("Entity Type Labels:");
                if (labels.isArray()) {
                    for (JsonNode label : labels) {
                        OutputFormatter.printKv("  " + label.path("label").asText(),
                                label.path("count").asText() + " entities");
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BULK DELETE
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "bulk-delete", description = "Delete nodes matching criteria")
    static class BulkDeleteCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Option(names = "--fact-sheet-id") private Long factSheetId;
        @CommandLine.Option(names = "--node-type", description = "Filter by node type (ENTITY, SOURCE, etc.)")
        private String nodeType;
        @CommandLine.Option(names = "--entity-type", description = "Filter by entity type label (PERSON, ORG, etc.)")
        private String entityType;
        @CommandLine.Option(names = "--max-confidence", description = "Delete nodes with confidence at or below this value")
        private Double maxConfidence;
        @CommandLine.Option(names = "--orphans-only", description = "Only delete nodes with zero edges")
        private boolean orphansOnly;
        @CommandLine.Option(names = "--title-pattern", description = "Only delete nodes matching this title regex")
        private String titlePattern;
        @CommandLine.Option(names = "--dry-run", description = "Preview changes without applying")
        private boolean dryRun;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String endpoint = dryRun ? "/api/graph/maintenance/bulk-delete/preview" : "/api/graph/maintenance/bulk-delete";
                if (factSheetId != null) endpoint += "?factSheetId=" + factSheetId;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("dryRun", dryRun);
                if (nodeType != null) body.put("nodeType", nodeType);
                if (entityType != null) body.put("entityType", entityType);
                if (maxConfidence != null) body.put("maxConfidence", maxConfidence);
                if (orphansOnly) body.put("orphansOnly", true);
                if (titlePattern != null) body.put("titlePattern", titlePattern);
                String response = client.postString(endpoint, body);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode result = client.getObjectMapper().readTree(response);
                System.out.println(dryRun ? "Bulk Delete Preview (dry run):" : "Bulk Delete Results:");
                OutputFormatter.printKv("Deleted", result.path("deletedCount").asText());
                JsonNode details = result.path("details");
                if (details.isArray()) {
                    for (JsonNode d : details) {
                        System.out.printf("  [%s/%s] %s%n",
                                d.path("nodeType").asText(),
                                d.path("entityType").asText(""),
                                d.path("title").asText("(untitled)"));
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CLEANUP
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "edge-cleanup", description = "Remove dangling, duplicate, and weak edges")
    static class EdgeCleanupCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Option(names = "--fact-sheet-id") private Long factSheetId;
        @CommandLine.Option(names = "--min-weight", description = "Remove edges below this weight")
        private Double minWeight;
        @CommandLine.Option(names = "--edge-types", description = "Only clean these edge types (comma-separated)")
        private String edgeTypes;
        @CommandLine.Option(names = "--no-dangling", description = "Skip dangling edge removal")
        private boolean noDangling;
        @CommandLine.Option(names = "--no-duplicates", description = "Skip duplicate edge removal")
        private boolean noDuplicates;
        @CommandLine.Option(names = "--dry-run", description = "Preview changes without applying")
        private boolean dryRun;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String endpoint = dryRun ? "/api/graph/maintenance/edge-cleanup/preview" : "/api/graph/maintenance/edge-cleanup";
                if (factSheetId != null) endpoint += "?factSheetId=" + factSheetId;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("dryRun", dryRun);
                body.put("removeDangling", !noDangling);
                body.put("removeDuplicates", !noDuplicates);
                if (minWeight != null) body.put("minWeight", minWeight);
                if (edgeTypes != null) body.put("edgeTypes", List.of(edgeTypes.split(",")));
                String response = client.postString(endpoint, body);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode result = client.getObjectMapper().readTree(response);
                System.out.println(dryRun ? "Edge Cleanup Preview (dry run):" : "Edge Cleanup Results:");
                OutputFormatter.printKv("Dangling removed", result.path("danglingRemoved").asText());
                OutputFormatter.printKv("Duplicates removed", result.path("duplicatesRemoved").asText());
                OutputFormatter.printKv("Weak removed", result.path("weakRemoved").asText());
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PATCH (metadata)
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "patch", description = "Rule-based metadata patching")
    static class PatchCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;
        @CommandLine.Option(names = "--fact-sheet-id") private Long factSheetId;
        @CommandLine.Option(names = "--rule-file", required = true,
                description = "JSON file containing patch rules")
        private java.io.File ruleFile;
        @CommandLine.Option(names = "--dry-run", description = "Preview changes without applying")
        private boolean dryRun;
        @CommandLine.Option(names = "--limit", description = "Max nodes to update")
        private Integer limit;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String rulesJson = java.nio.file.Files.readString(ruleFile.toPath());
                JsonNode rulesNode = client.getObjectMapper().readTree(rulesJson);

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("factSheetId", factSheetId);
                body.put("dryRun", dryRun);
                if (limit != null) body.put("limit", limit);
                // Rules can be an array at top level or under "rules" key
                if (rulesNode.isArray()) {
                    body.put("rules", client.getObjectMapper().treeToValue(rulesNode, List.class));
                } else if (rulesNode.has("rules")) {
                    body.put("rules", client.getObjectMapper().treeToValue(rulesNode.get("rules"), List.class));
                    if (rulesNode.has("allowGlobal")) body.put("allowGlobal", rulesNode.get("allowGlobal").asBoolean());
                } else {
                    System.err.println("Rule file must contain a JSON array or object with 'rules' key");
                    return 1;
                }

                String endpoint = dryRun ? "/api/graph/maintenance/patch/preview" : "/api/graph/maintenance/patch";
                String response = client.postString(endpoint, body);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode result = client.getObjectMapper().readTree(response);
                System.out.println(dryRun ? "Patch Preview (dry run):" : "Patch Results:");
                OutputFormatter.printKv("Scanned", result.path("scannedCount").asText());
                OutputFormatter.printKv("Matched", result.path("matchedCount").asText());
                OutputFormatter.printKv("Changed", result.path("changedCount").asText());
                OutputFormatter.printKv("Updated", result.path("updatedCount").asText());
                JsonNode samples = result.path("samples");
                if (samples.isArray() && !samples.isEmpty()) {
                    System.out.println("\nSamples:");
                    for (JsonNode s : samples) {
                        System.out.printf("  %s (%s) — rules: %s%n",
                                s.path("title").asText("(untitled)"),
                                s.path("nodeId").asText(),
                                s.path("matchedRules").toString());
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }
}
