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
import org.springframework.web.client.ResourceAccessException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only handoff from a crawl job to the resulting corpus and graph.
 */
public final class CrawlResultTool implements CliTool {
    private final GroundingBackendClient client;
    private final ObjectMapper mapper;
    private final LocalProjectCrawlBackend localBackend;

    public CrawlResultTool(String baseUrl, ObjectMapper mapper) {
        this(new GroundingBackendClient(baseUrl), mapper);
    }

    CrawlResultTool(GroundingBackendClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
        this.localBackend = new LocalProjectCrawlBackend(mapper);
    }

    @Override
    public String id() {
        return "crawl_result";
    }

    @Override
    public String description() {
        return "Read a crawl result by jobId after starting an asynchronous crawl. If terminal=false, keep polling "
                + "crawl_control operation=status using the returned pollAfterMs; do not start the crawl again. Returns the raw lifecycle result "
                + "plus a stable crawlResult handle with executable nextActions for monitoring, knowledge "
                + "search, graph inspection, reasoning, and graph updates. Uses the current folder locally "
                + "over stdio; a configured crawl URL is an optional managed override.";
    }

    @Override
    public String compactHint() {
        return "Read-only crawl handoff: pass jobId after status reaches terminal=true; while running, use crawl_control status "
                + "and respect pollAfterMs. Then use crawlResult.nextActions to inspect or act on the corpus and graph.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("jobId").put("type", "string")
                .put("description", "Job id returned by crawl_documents, crawl_source, or crawl_control start.");
        schema.putArray("required").add("jobId");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "crawl_result";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.READ_ONLY;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Read crawl result");
        String jobId = params.path("jobId").asText("").trim();
        if (jobId.isEmpty()) {
            return ToolResult.error("crawl_result requires jobId");
        }
        if (!client.isAvailable()) {
            return localBackend.result(jobId, context);
        }

        try {
            GroundingBackendClient.GroundingResponse response =
                    client.get("/api/unified-crawl/jobs/" + path(jobId));
            if (response.statusCode() == 404) {
                response = client.get("/api/unified-crawl/jobs/" + path(jobId) + "/history");
            }
            if (response.statusCode() >= 400) {
                return ToolResult.error("crawl_result returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }

            JsonNode body = mapper.readTree(response.body());
            CrawlResultHandle handle = CrawlResultHandle.from(body, "managed", jobId, null);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("backend", "managed");
            metadata.put("jobId", jobId);
            handle.attachTo(metadata);
            return ToolResult.success("crawl_result", body.toPrettyString(), metadata);
        } catch (ResourceAccessException e) {
            return localBackend.result(jobId, context);
        } catch (Exception e) {
            return ToolResult.error("crawl_result failed: " + e.getMessage());
        }
    }

    private static String path(String value) {
        return value.replace("/", "%2F").replace("?", "%3F").replace("#", "%23");
    }
}
