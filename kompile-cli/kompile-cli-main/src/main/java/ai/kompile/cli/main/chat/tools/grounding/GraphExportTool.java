/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool: {@code graph_export}
 *
 * <p>Download the live knowledge graph from the running kompile-app as a portable
 * {@code .kgraph} file and write it to the local filesystem. The exported file captures
 * every aspect of the graph — entities, edges, all embedding-vector layers (sentence + KGE),
 * subjective-logic opinions, learned weight maps, and provenance — in a single compact archive.
 * The file can later be reloaded with {@code graph_import} to restore the full graph state
 * so the {@code ask_graph_*} and {@code graph_reason} tools can reason over it immediately.</p>
 */
public class GraphExportTool implements CliTool {

    private final GroundingBackendClient client;
    private final ObjectMapper objectMapper;

    public GraphExportTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = new GroundingBackendClient(baseUrl);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    GraphExportTool(GroundingBackendClient client, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = client;
    }

    @Override
    public String id() { return "graph_export"; }

    @Override
    public String description() {
        return "Saves the whole graph to one .kgraph file — structure, confidence, and learned state — "
                + "for backup or moving between instances. The file can later be reloaded with graph_import "
                + "so the ask_graph_* tools and graph_reason can reason over it immediately. "
                + "Optionally scope the export to a specific fact sheet; omit factSheetId for the global graph.";
    }

    @Override
    public String compactHint() {
        return "Save the whole graph to a .kgraph file (structure, confidence, learned state). "
                + "path = local file path to write. factSheetId optional — omit for the global graph. "
                + "Use graph_import to reload it on any instance.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("path")
                .put("type", "string")
                .put("description", "Local filesystem path to write the .kgraph file to.");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Fact sheet to export. Omit for the global/default graph.");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public String permissionKey() { return "graph_export"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Export the knowledge graph to a .kgraph file");

        String path = params.path("path").asText("");
        if (path.isBlank()) {
            return ToolResult.error("path is required");
        }
        if (!client.isAvailable()) {
            return ToolResult.error("graph_export requires a running kompile-app.");
        }

        JsonNode fsNode = params.path("factSheetId");
        Integer factSheetId = (fsNode.isMissingNode() || fsNode.isNull()) ? null : fsNode.asInt();

        try {
            String apiPath = "/api/graph/unified/export";
            if (factSheetId != null) {
                apiPath += "?factSheetId=" + factSheetId;
            }

            byte[] bytes = client.getBytes(apiPath);
            if (bytes.length == 0) {
                return ToolResult.error("graph_export: server returned an empty response.");
            }

            Files.write(Path.of(path), bytes);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("path", path);
            metadata.put("bytes", bytes.length);
            if (factSheetId != null) {
                metadata.put("factSheetId", factSheetId);
            }

            return ToolResult.success(
                    "graph_export: " + Path.of(path).getFileName(),
                    "Exported the live graph (" + bytes.length + " bytes) to " + path + ".",
                    metadata);

        } catch (Exception e) {
            return ToolResult.error("graph_export error: " + e.getMessage());
        }
    }
}
