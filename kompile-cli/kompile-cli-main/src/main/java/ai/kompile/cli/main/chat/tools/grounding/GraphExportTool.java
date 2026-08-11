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
import java.util.Set;

/**
 * MCP tool: {@code graph_export}
 *
 * <p>Download the live knowledge graph from the standalone graph service (or the compatible
 * kompile-app route) as a portable
 * {@code .kgraph} file and write it to the local filesystem. The exported file captures
 * every aspect of the graph — entities, edges, all embedding-vector layers (sentence + KGE),
 * subjective-logic opinions, learned weight maps, and provenance — in a single compact archive.
 * The file can later be reloaded with {@code graph_import} to restore the full graph state for
 * {@code graph_reasoning_query}; the compatibility app route may expose additional graph tools.</p>
 */
public class GraphExportTool implements CliTool {

    private final GroundingBackendClient client;
    private final ObjectMapper objectMapper;
    private final LocalProjectGraphBackend localBackend;

    public GraphExportTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = new GroundingBackendClient(baseUrl);
        this.localBackend = new LocalProjectGraphBackend(objectMapper);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    GraphExportTool(GroundingBackendClient client, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = client;
        this.localBackend = new LocalProjectGraphBackend(objectMapper);
    }

    @Override
    public String id() { return "graph_export"; }

    @Override
    public String description() {
        return "Exports the whole graph for backup or debugging: .kgraph preserves state, ASCII includes "
                + "properties/connections/schema, and PNG renders the same diagnostics. Use vectors=values for "
                + "full vector values or bundle=false for one PNG. Optionally scope to a fact sheet.";
    }

    @Override
    public String compactHint() {
        return "Save graph diagnostics or a .kgraph archive. path = local file path; format = kgraph, ascii, "
                + "or png; vectors = summary|values; bundle=false returns one PNG; factSheetId is optional.";
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
        ObjectNode formatProperty = props.putObject("format");
        formatProperty.put("type", "string");
        formatProperty.set("enum", objectMapper.createArrayNode().add("kgraph").add("ascii").add("png"));
        formatProperty.put("description", "Output format; kgraph preserves state, ascii is text, png is a PNG bundle.");
        ObjectNode vectorsProperty = props.putObject("vectors");
        vectorsProperty.put("type", "string");
        vectorsProperty.set("enum", objectMapper.createArrayNode().add("summary").add("values"));
        vectorsProperty.put("default", "summary");
        vectorsProperty.put("description", "For ASCII/PNG diagnostics, emit vector dimensions/hashes or full values.");
        ObjectNode bundleProperty = props.putObject("bundle");
        bundleProperty.put("type", "boolean");
        bundleProperty.put("default", true);
        bundleProperty.put("description", "For PNG diagnostics, write a complete ZIP page bundle; false returns one PNG.");
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
            return localBackend.exportGraph(params, context);
        }

        JsonNode fsNode = params.path("factSheetId");
        Integer factSheetId = (fsNode.isMissingNode() || fsNode.isNull()) ? null : fsNode.asInt();
        String format = params.path("format").asText("kgraph").toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("kgraph", "ascii", "png").contains(format)) {
            return ToolResult.error("format must be kgraph, ascii or png");
        }
        String vectors = params.path("vectors").asText("summary").toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("summary", "values").contains(vectors)) {
            return ToolResult.error("vectors must be summary or values");
        }
        boolean bundle = params.path("bundle").isMissingNode() || params.path("bundle").asBoolean(true);

        try {
            StringBuilder apiPathBuilder = new StringBuilder("/api/graph/unified/export?format=").append(format);
            if (!"summary".equals(vectors)) apiPathBuilder.append("&vectors=values");
            if ("png".equals(format) && !bundle) apiPathBuilder.append("&bundle=false");
            String apiPath = apiPathBuilder.toString();
            if (factSheetId != null) {
                apiPath += "&factSheetId=" + factSheetId;
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
