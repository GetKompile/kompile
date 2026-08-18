/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
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

import java.time.Duration;
import java.util.Locale;

import org.springframework.web.client.ResourceAccessException;

/**
 * Unified-crawl lifecycle adapter used by the production `kompile crawl` profile.
 *
 * <p>This keeps backend transport in a tool, while the agent loop owns policy
 * (approval, pause, step and budgets). A request body is the production
 * UnifiedCrawlRequest JSON, so multi-source loading and per-run model/runtime
 * policy remain server-owned.</p>
 */
public final class CrawlControlTool implements CliTool {
    private final GroundingBackendClient client;
    private final ObjectMapper mapper;
    private final LocalProjectCrawlBackend localBackend;

    public CrawlControlTool(String baseUrl, ObjectMapper mapper) {
        this(new GroundingBackendClient(baseUrl), mapper);
    }

    CrawlControlTool(GroundingBackendClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
        this.localBackend = new LocalProjectCrawlBackend(mapper);
    }

    @Override
    public String id() { return "crawl_control"; }

    @Override
    public String description() {
        return "Inspect or control a crawl through the configured backend. Project-local starts return immediately "
                + "with a pollable jobId; call operation=status with that id (respect pollAfterMs) until terminal=true, "
                + "then call crawl_result. Project-local crawls support "
                + "preflight, start, status, list, transcript, cancel, source_types, graph_stats, and runtime_config; "
                + "a distributed manager additionally supports clear_graph, cancel, retry, run_step, and archive_step. "
                + "After a terminal status, call crawl_result with the same jobId for a structured result handle "
                + "and executable knowledge/graph follow-up actions.";
    }

    @Override
    public String compactHint() {
        return "Unified crawl lifecycle: start returns jobId immediately; poll operation=status (respect pollAfterMs), "
                + "then crawl_result. operation=preflight|clear_graph|start|status|list|cancel|retry|run_step|archive_step|"
                + "transcript|source_types|graph_stats|runtime_config.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("operation").put("type", "string")
                .put("description", "Lifecycle operation. For long crawls use status polling instead of waiting in start.")
                .putArray("enum").add("preflight").add("start").add("status").add("list")
                .add("cancel").add("retry").add("run_step").add("archive_step").add("transcript")
                .add("source_types").add("graph_stats").add("runtime_config").add("clear_graph");
        props.putObject("jobId").put("type", "string")
                .put("description", "Backend unified-crawl job id.");
        props.putObject("stepId").put("type", "string")
                .put("description", "Step id for run_step/archive_step.");
        props.putObject("body").put("type", "object")
                .put("description", "UnifiedCrawlRequest JSON for start, or endpoint body.");
        props.putObject("page").put("type", "integer");
        props.putObject("size").put("type", "integer");
        props.putObject("factSheetId").put("type", "integer")
                .put("description", "Optional remote/legacy selector; local crawl jobs are folder-scoped.");
        props.putObject("async").put("type", "boolean")
                .put("description", "For start requests, request asynchronous execution (default true). Local starts are always pollable.");
        props.putObject("pollAfterMs").put("type", "integer")
                .put("description", "Optional client hint; status responses return the server's recommended delay.");
        return schema;
    }

    @Override
    public String permissionKey() { return "crawl_control"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Control a unified crawl");
        String operation = params.path("operation").asText("").toLowerCase(Locale.ROOT);
        if (operation.isBlank()) return ToolResult.error("operation is required");
        if (!client.isAvailable()) {
            return localBackend.control(params, context);
        }
        String jobId = params.path("jobId").asText("");
        String stepId = params.path("stepId").asText("");
        int page = Math.max(0, params.path("page").asInt(0));
        int size = Math.min(200, Math.max(1, params.path("size").asInt(50)));
        int factSheetId = params.path("factSheetId").asInt(-1);

        try {
            GroundingBackendClient.GroundingResponse response;
            switch (operation) {
                case "preflight":
                    return preflight();
                case "clear_graph":
                    if (factSheetId < 0) return ToolResult.error("clear_graph requires factSheetId");
                    response = client.delete("/api/fact-sheets/" + factSheetId + "/graph");
                    break;
                case "start":
                    JsonNode body = params.path("body");
                    if (body.isMissingNode() || !body.isObject()) {
                        body = params.path("request");
                    }
                    if (body.isMissingNode() || !body.isObject()) {
                        return ToolResult.error("start requires body containing a UnifiedCrawlRequest.");
                    }
                    response = client.post("/api/unified-crawl/start",
                            mapper.writeValueAsString(body), Duration.ofSeconds(60));
                    break;
                case "status":
                    response = client.get(jobId.isBlank()
                            ? "/api/unified-crawl/jobs/active"
                            : "/api/unified-crawl/jobs/" + path(jobId));
                    break;
                case "list":
                    response = client.get("/api/unified-crawl/jobs?page=" + page + "&size=" + size);
                    break;
                case "cancel":
                    require(jobId, operation);
                    response = client.post("/api/unified-crawl/jobs/" + path(jobId) + "/cancel", "{}");
                    break;
                case "retry":
                    require(jobId, operation);
                    response = client.post("/api/unified-crawl/jobs/" + path(jobId) + "/retry", "{}");
                    break;
                case "run_step":
                    require(jobId, operation);
                    require(stepId, operation);
                    response = client.post("/api/unified-crawl/jobs/" + path(jobId)
                            + "/steps/" + path(stepId) + "/run", "{}");
                    break;
                case "archive_step":
                    require(jobId, operation);
                    require(stepId, operation);
                    response = client.post("/api/unified-crawl/jobs/" + path(jobId)
                            + "/steps/" + path(stepId) + "/archive", "{}");
                    break;
                case "transcript":
                    require(jobId, operation);
                    response = client.get("/api/indexing/jobs/" + path(jobId)
                            + "/logs?source=LLM_TRANSCRIPT&page=" + page + "&size=" + size);
                    break;
                case "source_types":
                    response = client.get("/api/unified-crawl/source-types");
                    break;
                case "graph_stats":
                    response = client.get("/api/unified-crawl/graph-stats");
                    break;
                case "runtime_config":
                    response = client.get("/api/unified-crawl/runtime-config");
                    break;
                default:
                    return ToolResult.error("Unknown operation: " + operation);
            }
            return result(operation, response);
        } catch (ResourceAccessException e) {
            return localBackend.control(params, context);
        } catch (Exception e) {
            return ToolResult.error("crawl_control " + operation + " failed: " + e.getMessage());
        }
    }

    private ToolResult preflight() {
        ObjectNode result = mapper.createObjectNode();
        addGet(result, "sourceTypes", "/api/unified-crawl/source-types");
        addGet(result, "processingCapacity", "/api/unified-crawl/processing-capacity");
        addGet(result, "runtimeConfig", "/api/unified-crawl/runtime-config");
        addGet(result, "graphStats", "/api/unified-crawl/graph-stats");
        return ToolResult.success("crawl_preflight", result.toPrettyString());
    }

    private void addGet(ObjectNode target, String name, String endpoint) {
        try {
            GroundingBackendClient.GroundingResponse response = client.get(endpoint);
            ObjectNode value = target.putObject(name);
            value.put("status", response.statusCode());
            try {
                value.set("body", mapper.readTree(response.body()));
            } catch (Exception ignored) {
                value.put("body", response.body());
            }
        } catch (Exception e) {
            target.putObject(name).put("error", String.valueOf(e.getMessage()));
        }
    }

    private ToolResult result(String operation, GroundingBackendClient.GroundingResponse response) {
        if (response.statusCode() >= 400) {
            return ToolResult.error("crawl_control " + operation + " returned HTTP "
                    + response.statusCode() + ": " + response.body());
        }
        return ToolResult.success("crawl_" + operation, response.body());
    }

    private static void require(String value, String operation) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(operation + " requires jobId/stepId");
        }
    }

    private static String path(String value) {
        return value.replace("/", "%2F").replace("?", "%3F").replace("#", "%23");
    }
}
