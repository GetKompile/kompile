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
 * MCP tool: {@code graph_import}
 *
 * <p>Load a kompile {@code .kgraph} file into the live knowledge graph so the agent can reason over
 * it, via {@code POST /api/graph/unified/import} (multipart upload). The server-side import projects
 * the graph into its reasoning representation, so {@code graph_reasoning_query} can immediately
 * reason over the imported state. When routed through the compatibility app, its additional
 * {@code ask_graph_*} tools can consume the same import.</p>
 */
public class GraphImportTool implements CliTool {

    private final GroundingBackendClient client;
    private final ObjectMapper objectMapper;
    private final LocalProjectGraphBackend localBackend;

    public GraphImportTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = new GroundingBackendClient(baseUrl);
        this.localBackend = new LocalProjectGraphBackend(objectMapper);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    GraphImportTool(GroundingBackendClient client, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = client;
        this.localBackend = new LocalProjectGraphBackend(objectMapper);
    }

    @Override
    public String id() { return "graph_import"; }

    @Override
    public String description() {
        return "Load a kompile .kgraph file into the live knowledge graph so you can reason over it. "
                + "The imported graph is available to graph_reasoning_query immediately. "
                + "Provide the path to a .kgraph file (produced by graph export) and, optionally, the "
                + "fact sheet to import into (defaults to the graph's own recorded fact sheet).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("path")
                .put("type", "string")
                .put("description", "Filesystem path to the .kgraph file to load.");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Optional remote/legacy destination override. Locally the imported graph uses its recorded knowledge-base identity.");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public String permissionKey() { return "graph_import"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Import a .kgraph into the knowledge graph");

        String path = params.path("path").asText("");
        if (path.isBlank()) {
            return ToolResult.error("path is required");
        }
        if (!client.isAvailable()) {
            return localBackend.importGraph(params, context);
        }

        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            return ToolResult.error("No .kgraph file at: " + path);
        }

        JsonNode fsNode = params.path("factSheetId");
        Integer factSheetId = (fsNode.isMissingNode() || fsNode.isNull()) ? null : fsNode.asInt();

        try {
            var resp = client.postMultipartFile("/api/graph/unified/import", file, factSheetId);
            if (resp.statusCode() != 200) {
                return ToolResult.error("graph_import failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            JsonNode r = objectMapper.readTree(resp.body());
            int nodes = r.path("nodes").asInt();
            int edges = r.path("edges").asInt();
            int embeddings = r.path("embeddings").asInt();
            int atoms = r.path("atoms").asInt();

            StringBuilder sb = new StringBuilder();
            sb.append("Loaded ").append(nodes).append(" nodes, ").append(edges).append(" edges, ")
                    .append(embeddings).append(" embeddings from ").append(file.getFileName());
            if (atoms > 0) {
                sb.append(".\n").append(atoms).append(" facts projected — the graph is reasoning-ready: ")
                        .append("use graph_reasoning_query to reason over it.");
            } else {
                sb.append(".\nGraph search and traversal work now; supply factSheetId to enable claim verification.");
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("nodes", nodes);
            metadata.put("edges", edges);
            metadata.put("embeddings", embeddings);
            metadata.put("atoms", atoms);
            if (factSheetId != null) {
                metadata.put("factSheetId", factSheetId);
            }

            return ToolResult.success("graph_import: " + file.getFileName(), sb.toString(), metadata);

        } catch (Exception e) {
            return ToolResult.error("graph_import error: " + e.getMessage());
        }
    }

    private String extractError(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            String msg = json.path("message").asText(null);
            if (msg != null) return msg;
            msg = json.path("error").asText(null);
            if (msg != null) return msg;
        } catch (Exception ignored) {
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
