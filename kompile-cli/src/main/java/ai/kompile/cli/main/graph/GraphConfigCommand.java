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
 * {@code kompile graph config} — manage graph extraction configuration,
 * schema modes, model providers, and schema presets.
 *
 * <p>Examples:
 * <pre>
 *   # Show current extraction config
 *   kompile graph config show
 *
 *   # Update config settings
 *   kompile graph config set --model-provider openai --model-name gpt-4 --temperature 0.2
 *
 *   # Toggle extraction on/off
 *   kompile graph config toggle
 *
 *   # Reset to defaults
 *   kompile graph config reset
 *
 *   # List available schema modes
 *   kompile graph config schema-modes
 *
 *   # List LLM providers
 *   kompile graph config providers
 *
 *   # List and apply schema presets
 *   kompile graph config presets
 *   kompile graph config apply-preset biomedical
 *
 *   # Show suggested entity/relationship types
 *   kompile graph config entity-types
 *   kompile graph config relationship-types
 * </pre>
 */
@CommandLine.Command(
        name = "config",
        description = "Manage graph extraction configuration",
        subcommands = {
                GraphConfigCommand.ShowCmd.class,
                GraphConfigCommand.SetCmd.class,
                GraphConfigCommand.ToggleCmd.class,
                GraphConfigCommand.ResetCmd.class,
                GraphConfigCommand.StatusCmd.class,
                GraphConfigCommand.SchemaModes.class,
                GraphConfigCommand.EntityTypesCmd.class,
                GraphConfigCommand.RelationshipTypesCmd.class,
                GraphConfigCommand.ProvidersCmd.class,
                GraphConfigCommand.PresetsCmd.class,
                GraphConfigCommand.PresetDetailCmd.class,
                GraphConfigCommand.ApplyPresetCmd.class
        },
        mixinStandardHelpOptions = true
)
public class GraphConfigCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // config show — display current extraction config
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "show", description = "Show current graph extraction configuration",
            mixinStandardHelpOptions = true)
    static class ShowCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/graph-extraction/config");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode config = client.getObjectMapper().readTree(response);
                System.out.println("Graph Extraction Configuration:");
                System.out.println();
                OutputFormatter.printKv("Enabled", config.path("enabled").asBoolean());
                OutputFormatter.printKv("Schema Mode", config.path("schemaEnforcement").asText("NONE"));
                OutputFormatter.printKv("Batch Size", config.path("batchSize"));
                System.out.println();
                System.out.println("  LLM Settings:");
                OutputFormatter.printKv("Provider", config.path("extractionModelProvider").asText("-"));
                OutputFormatter.printKv("Model", config.path("extractionModelName").asText("-"));
                OutputFormatter.printKv("Temperature", config.path("extractionTemperature"));
                OutputFormatter.printKv("Max Tokens", config.path("extractionMaxTokens"));
                System.out.println();
                System.out.println("  Extraction Limits:");
                OutputFormatter.printKv("Max Entities/Chunk", config.path("maxEntitiesPerChunk"));
                OutputFormatter.printKv("Max Relations/Chunk", config.path("maxRelationshipsPerChunk"));
                OutputFormatter.printKv("Auto-Accept Threshold", config.path("autoAcceptThreshold"));
                System.out.println();
                System.out.println("  Deduplication:");
                OutputFormatter.printKv("Enabled", config.path("deduplicationEnabled").asBoolean());
                OutputFormatter.printKv("Similarity Threshold", config.path("similarityThreshold"));
                System.out.println();
                System.out.println("  Schema:");
                JsonNode entityTypes = config.path("entityTypes");
                if (entityTypes.isArray() && !entityTypes.isEmpty()) {
                    List<String> types = new ArrayList<>();
                    for (JsonNode t : entityTypes) types.add(t.asText());
                    OutputFormatter.printKv("Entity Types", String.join(", ", types));
                }
                JsonNode relTypes = config.path("relationshipTypes");
                if (relTypes.isArray() && !relTypes.isEmpty()) {
                    List<String> types = new ArrayList<>();
                    for (JsonNode t : relTypes) types.add(t.asText());
                    OutputFormatter.printKv("Relationship Types", String.join(", ", types));
                }
                String presetId = config.path("activeSchemaPresetId").asText("");
                if (!presetId.isEmpty()) OutputFormatter.printKv("Active Preset", presetId);
                System.out.println();
                System.out.println("  Neo4j:");
                OutputFormatter.printKv("Enabled", config.path("neo4jEnabled").asBoolean());
                String uri = config.path("neo4jUri").asText("");
                if (!uri.isEmpty()) OutputFormatter.printKv("URI", uri);
                String db = config.path("neo4jDatabase").asText("");
                if (!db.isEmpty()) OutputFormatter.printKv("Database", db);
                String prompt = config.path("customExtractionPrompt").asText("");
                if (!prompt.isEmpty()) {
                    System.out.println();
                    System.out.println("  Custom Prompt:");
                    System.out.println("    " + (prompt.length() > 300 ? prompt.substring(0, 300) + "..." : prompt));
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // config set — update extraction config
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "set", description = "Update graph extraction configuration",
            mixinStandardHelpOptions = true)
    static class SetCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = "--enabled", description = "Enable/disable extraction")
        private Boolean enabled;

        @CommandLine.Option(names = "--schema-mode",
                description = "Schema enforcement: NONE, LENIENT, STRICT")
        private String schemaEnforcement;

        @CommandLine.Option(names = "--batch-size", description = "Batch size for extraction")
        private Integer batchSize;

        @CommandLine.Option(names = "--model-provider",
                description = "LLM provider (e.g., openai, anthropic)")
        private String modelProvider;

        @CommandLine.Option(names = "--model-name",
                description = "LLM model name")
        private String modelName;

        @CommandLine.Option(names = "--temperature",
                description = "LLM temperature (0.0-2.0)")
        private Double temperature;

        @CommandLine.Option(names = "--max-tokens",
                description = "LLM max tokens")
        private Integer maxTokens;

        @CommandLine.Option(names = "--max-entities",
                description = "Max entities per chunk")
        private Integer maxEntities;

        @CommandLine.Option(names = "--max-relationships",
                description = "Max relationships per chunk")
        private Integer maxRelationships;

        @CommandLine.Option(names = "--auto-accept-threshold",
                description = "Auto-accept confidence threshold (0.0-1.0)")
        private Double autoAcceptThreshold;

        @CommandLine.Option(names = "--deduplication",
                description = "Enable/disable deduplication")
        private Boolean deduplication;

        @CommandLine.Option(names = "--similarity-threshold",
                description = "Deduplication similarity threshold")
        private Double similarityThreshold;

        @CommandLine.Option(names = "--entity-types", split = ",",
                description = "Entity types (comma-separated)")
        private List<String> entityTypes;

        @CommandLine.Option(names = "--relationship-types", split = ",",
                description = "Relationship types (comma-separated)")
        private List<String> relationshipTypes;

        @CommandLine.Option(names = "--custom-prompt",
                description = "Custom extraction prompt")
        private String customPrompt;

        @CommandLine.Option(names = "--neo4j-enabled",
                description = "Enable/disable Neo4j storage")
        private Boolean neo4jEnabled;

        @CommandLine.Option(names = "--neo4j-uri",
                description = "Neo4j connection URI")
        private String neo4jUri;

        @CommandLine.Option(names = "--neo4j-user",
                description = "Neo4j username")
        private String neo4jUser;

        @CommandLine.Option(names = "--neo4j-password",
                description = "Neo4j password")
        private String neo4jPassword;

        @CommandLine.Option(names = "--neo4j-database",
                description = "Neo4j database name")
        private String neo4jDatabase;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                if (enabled != null) body.put("enabled", enabled);
                if (schemaEnforcement != null) body.put("schemaEnforcement", schemaEnforcement);
                if (batchSize != null) body.put("batchSize", batchSize);
                if (modelProvider != null) body.put("extractionModelProvider", modelProvider);
                if (modelName != null) body.put("extractionModelName", modelName);
                if (temperature != null) body.put("extractionTemperature", temperature);
                if (maxTokens != null) body.put("extractionMaxTokens", maxTokens);
                if (maxEntities != null) body.put("maxEntitiesPerChunk", maxEntities);
                if (maxRelationships != null) body.put("maxRelationshipsPerChunk", maxRelationships);
                if (autoAcceptThreshold != null) body.put("autoAcceptThreshold", autoAcceptThreshold);
                if (deduplication != null) body.put("deduplicationEnabled", deduplication);
                if (similarityThreshold != null) body.put("similarityThreshold", similarityThreshold);
                if (entityTypes != null) body.put("entityTypes", entityTypes);
                if (relationshipTypes != null) body.put("relationshipTypes", relationshipTypes);
                if (customPrompt != null) body.put("customExtractionPrompt", customPrompt);
                if (neo4jEnabled != null) body.put("neo4jEnabled", neo4jEnabled);
                if (neo4jUri != null) body.put("neo4jUri", neo4jUri);
                if (neo4jUser != null) body.put("neo4jUsername", neo4jUser);
                if (neo4jPassword != null) body.put("neo4jPassword", neo4jPassword);
                if (neo4jDatabase != null) body.put("neo4jDatabase", neo4jDatabase);

                if (body.isEmpty()) {
                    System.err.println("No settings specified. Use --help to see available options.");
                    return 1;
                }

                String response = client.put("/api/graph-extraction/config", body, String.class);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.println("Configuration updated.");
                    body.forEach((k, v) -> OutputFormatter.printKv(k, v));
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // config toggle — toggle extraction on/off
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "toggle", description = "Toggle graph extraction on/off",
            mixinStandardHelpOptions = true)
    static class ToggleCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postEmpty("/api/graph-extraction/config/toggle");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode config = client.getObjectMapper().readTree(response);
                    boolean enabled = config.path("enabled").asBoolean();
                    System.out.println("Graph extraction " + (enabled ? "ENABLED" : "DISABLED"));
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // config reset — reset to defaults
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "reset", description = "Reset extraction configuration to defaults",
            mixinStandardHelpOptions = true)
    static class ResetCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postEmpty("/api/graph-extraction/config/reset");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.println("Configuration reset to defaults.");
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // config status — quick extraction status
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "status", description = "Show extraction status summary",
            mixinStandardHelpOptions = true)
    static class StatusCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/graph-extraction/status");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode status = client.getObjectMapper().readTree(response);
                System.out.println("Extraction Status:");
                status.fields().forEachRemaining(e -> OutputFormatter.printKv(e.getKey(), e.getValue()));
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // config schema-modes — list available schema enforcement modes
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "schema-modes", description = "List available schema enforcement modes",
            mixinStandardHelpOptions = true)
    static class SchemaModes implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/graph-extraction/schema-modes");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode modes = client.getObjectMapper().readTree(response);
                System.out.println("Schema Enforcement Modes:");
                System.out.println();
                if (modes.isArray()) {
                    for (JsonNode mode : modes) {
                        OutputFormatter.printKv(mode.path("value").asText(), mode.path("label").asText());
                        String desc = mode.path("description").asText("");
                        if (!desc.isEmpty()) System.out.println("    " + desc);
                        System.out.println();
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
    // config entity-types — show suggested entity types
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "entity-types", description = "Show suggested entity types",
            mixinStandardHelpOptions = true)
    static class EntityTypesCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/graph-extraction/suggested-entity-types");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode types = client.getObjectMapper().readTree(response);
                System.out.println("Suggested Entity Types:");
                if (types.isArray()) {
                    for (JsonNode t : types) System.out.println("  - " + t.asText());
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // config relationship-types — show suggested relationship types
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "relationship-types", description = "Show suggested relationship types",
            mixinStandardHelpOptions = true)
    static class RelationshipTypesCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/graph-extraction/suggested-relationship-types");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode types = client.getObjectMapper().readTree(response);
                System.out.println("Suggested Relationship Types:");
                if (types.isArray()) {
                    for (JsonNode t : types) System.out.println("  - " + t.asText());
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // config providers — list LLM providers
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "providers", description = "List available LLM providers for extraction",
            mixinStandardHelpOptions = true)
    static class ProvidersCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/graph-extraction/model-providers");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode providers = client.getObjectMapper().readTree(response);
                System.out.println("LLM Providers:");
                System.out.println();
                if (providers.isArray()) {
                    for (JsonNode p : providers) {
                        String status = p.path("available").asBoolean() ? "available" : "not configured";
                        OutputFormatter.printKv(p.path("name").asText(), status);
                        OutputFormatter.printKv("  ID", p.path("id").asText());
                        JsonNode models = p.path("models");
                        if (models.isArray() && !models.isEmpty()) {
                            List<String> modelNames = new ArrayList<>();
                            for (JsonNode m : models) modelNames.add(m.asText());
                            OutputFormatter.printKv("  Models", String.join(", ", modelNames));
                        }
                        System.out.println();
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
    // config presets — list schema presets
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "presets", description = "List available schema presets",
            mixinStandardHelpOptions = true)
    static class PresetsCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/graph-extraction/schema-presets");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode presets = client.getObjectMapper().readTree(response);
                if (!presets.isArray() || presets.isEmpty()) {
                    System.out.println("No schema presets available.");
                    return 0;
                }
                System.out.println("Schema Presets:");
                System.out.println();
                for (JsonNode p : presets) {
                    OutputFormatter.printKv("ID", p.path("id").asText());
                    OutputFormatter.printKv("Name", p.path("name").asText());
                    String desc = p.path("description").asText("");
                    if (!desc.isEmpty()) OutputFormatter.printKv("Description", desc);
                    OutputFormatter.printKv("Node Types", p.path("nodeTypeCount"));
                    OutputFormatter.printKv("Relationship Types", p.path("relationshipTypeCount"));
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
    // config preset-detail <presetId> — show full preset schema
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "preset-detail", description = "Show full schema preset details",
            mixinStandardHelpOptions = true)
    static class PresetDetailCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Preset ID")
        private String presetId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/graph-extraction/schema-presets/" + presetId);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode preset = client.getObjectMapper().readTree(response);
                System.out.println("Schema Preset: " + preset.path("name").asText());
                OutputFormatter.printKv("ID", preset.path("id").asText());
                OutputFormatter.printKv("Version", preset.path("version"));
                String desc = preset.path("description").asText("");
                if (!desc.isEmpty()) OutputFormatter.printKv("Description", desc);
                System.out.println();

                JsonNode nodeTypes = preset.path("nodeTypes");
                if (nodeTypes.isArray() && !nodeTypes.isEmpty()) {
                    System.out.println("  Node Types:");
                    for (JsonNode nt : nodeTypes) {
                        System.out.println("    - " + nt.path("name").asText()
                                + (nt.has("description") ? " — " + nt.path("description").asText() : ""));
                    }
                    System.out.println();
                }

                JsonNode relTypes = preset.path("relationshipTypes");
                if (relTypes.isArray() && !relTypes.isEmpty()) {
                    System.out.println("  Relationship Types:");
                    for (JsonNode rt : relTypes) {
                        System.out.println("    - " + rt.path("name").asText()
                                + (rt.has("description") ? " — " + rt.path("description").asText() : ""));
                    }
                    System.out.println();
                }

                JsonNode patterns = preset.path("patterns");
                if (patterns.isArray() && !patterns.isEmpty()) {
                    System.out.println("  Patterns:");
                    for (JsonNode pt : patterns) {
                        System.out.println("    - " + pt.asText());
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
    // config apply-preset <presetId> — apply a schema preset
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "apply-preset", description = "Apply a schema preset to extraction config",
            mixinStandardHelpOptions = true)
    static class ApplyPresetCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Preset ID to apply")
        private String presetId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postEmpty("/api/graph-extraction/schema-presets/" + presetId + "/apply");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }
                JsonNode result = client.getObjectMapper().readTree(response);
                System.out.println("Applied schema preset: " + presetId);
                JsonNode entityTypes = result.path("entityTypes");
                if (entityTypes.isArray()) {
                    List<String> types = new ArrayList<>();
                    for (JsonNode t : entityTypes) types.add(t.asText());
                    OutputFormatter.printKv("Entity Types", String.join(", ", types));
                }
                JsonNode relTypes = result.path("relationshipTypes");
                if (relTypes.isArray()) {
                    List<String> types = new ArrayList<>();
                    for (JsonNode t : relTypes) types.add(t.asText());
                    OutputFormatter.printKv("Relationship Types", String.join(", ", types));
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }
}
