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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.client.ResourceAccessException;

/**
 * MCP tool: {@code crawl_source}
 *
 * <p>Run a single source (local file path, URL, or inline text) through the real unified-crawl
 * pipeline into the knowledge graph, via {@code POST /api/unified-crawl/single-source}.
 * Pass {@code dryRun=true} for a synchronous LLM-extraction preview with zero persistence.
 * The {@code steps} parameter selects which pipeline stages to execute; server-side dependency
 * resolution ensures required predecessors are always included. Requires a running kompile-app;
 * the default wait timeout is 900 s — behind a reverse proxy the value must stay under the
 * proxy's read timeout.</p>
 */
public class CrawlSourceTool implements CliTool {

    private final GroundingBackendClient client;
    private final ObjectMapper objectMapper;
    private final LocalProjectCrawlBackend localBackend;

    public CrawlSourceTool(String baseUrl, ObjectMapper objectMapper) {
        this(new GroundingBackendClient(baseUrl), objectMapper);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    CrawlSourceTool(GroundingBackendClient client, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = client;
        this.localBackend = new LocalProjectCrawlBackend(objectMapper);
    }

    @Override
    public String id() { return "crawl_source"; }

    @Override
    public String description() {
        return "Run ONE source through the crawl backend. Without a configured manager, local paths "
                + "and inline text are persisted in the project knowledge base; with a manager, paths and URLs "
                + "can run the full distributed pipeline and persist extracted entities and relations. "
                + "Use dryRun=true for a synchronous LLM-extraction preview with ZERO persistence. "
                + "The steps parameter selects which pipeline stages execute "
                + "(PREPROCESSING, GRAPH_EXTRACTION, ENTITY_RESOLUTION, EDGE_COMPUTATION, "
                + "VECTOR_INDEXING, ENTITY_PARTITIONS, ENRICHMENT); loading, conversion, and "
                + "chunking always run. "
                + "Server-side dependency resolution ensures required predecessor stages are always "
                + "included for distributed runs. Default remote wait is 900 s; behind a reverse "
                + "proxy the timeoutSeconds value must stay under the proxy read timeout. "
                + "NOTE: exactly one of path, url, or text must be supplied.";
    }

    @Override
    public String compactHint() {
        return "Crawl ONE source (path|url|text) into the KG. "
                + "dryRun=true=preview; steps=[...] selects stages; "
                + "factSheetId via list_fact_sheets; deriveOntology default on. "
                + "Default 900s wait.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("path")
                .put("type", "string")
                .put("description", "Absolute or relative filesystem path to the file to crawl.");
        props.putObject("url")
                .put("type", "string")
                .put("description", "URL to crawl (HTTP/HTTPS).");
        props.putObject("text")
                .put("type", "string")
                .put("description", "Inline text content to crawl.");
        props.putObject("title")
                .put("type", "string")
                .put("description", "Optional label / document title for inline text.");
        props.putObject("dryRun")
                .put("type", "boolean")
                .put("description", "true = synchronous extraction preview with zero persistence.");
        ObjectNode stepsNode = props.putObject("steps");
        stepsNode.put("type", "array");
        stepsNode.putObject("items").put("type", "string");
        stepsNode.put("description",
                "Pipeline stages to run. Selectable: PREPROCESSING, GRAPH_EXTRACTION, "
                + "ENTITY_RESOLUTION, EDGE_COMPUTATION, VECTOR_INDEXING, ENTITY_PARTITIONS, "
                + "ENRICHMENT. "
                + "Dependencies are auto-added server-side. Omit to run all stages.");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Fact sheet to crawl into. Omit to use the default sheet.");
        props.putObject("model")
                .put("type", "string")
                .put("description", "Override the extraction model name.");
        props.putObject("deriveOntology")
                .put("type", "boolean")
                .put("description", "Whether to derive ontology types during the crawl (default true). "
                        + "Set false to skip ontology inference and speed up short extraction tasks.");
        props.putObject("timeoutSeconds")
                .put("type", "integer")
                .put("description", "Max seconds to wait for completion (default 900).");
        return schema;
    }

    @Override
    public String permissionKey() { return "crawl_source"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Crawl a source into the knowledge graph");

        // ── Resolve exactly one source (validate before checking availability) ───
        String path   = params.path("path").asText(null);
        String url    = params.path("url").asText(null);
        String text   = params.path("text").asText(null);

        int sourceCount = (path != null && !path.isBlank() ? 1 : 0)
                        + (url  != null && !url.isBlank()  ? 1 : 0)
                        + (text != null && !text.isBlank() ? 1 : 0);
        if (sourceCount == 0) {
            return ToolResult.error("Provide exactly one of: path, url, or text.");
        }
        if (sourceCount > 1) {
            return ToolResult.error("Provide only one of: path, url, or text.");
        }

        if (!client.isAvailable()) {
            return localBackend.crawlSource(params, context);
        }

        // ── Timeout ───────────────────────────────────────────────────────
        int timeoutSeconds = params.path("timeoutSeconds").asInt(0);
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 900;
        }

        // ── Build request body ────────────────────────────────────────────
        try {
            ObjectNode body = objectMapper.createObjectNode();

            if (path != null && !path.isBlank()) {
                body.put("pathOrUrl", path);
            } else if (url != null && !url.isBlank()) {
                body.put("pathOrUrl", url);
            } else {
                // text / inline content
                body.put("content", text);
                String title = params.path("title").asText(null);
                if (title != null && !title.isBlank()) {
                    body.put("label", title);
                }
            }

            boolean dryRun = params.path("dryRun").asBoolean(false);
            body.put("dryRun", dryRun);
            body.put("waitTimeoutSeconds", timeoutSeconds);

            // Optional steps array
            JsonNode stepsNode = params.path("steps");
            if (stepsNode.isArray() && stepsNode.size() > 0) {
                ArrayNode stepsArr = body.putArray("steps");
                stepsNode.forEach(s -> stepsArr.add(s.asText()));
            }

            // Optional factSheetId
            JsonNode fsNode = params.path("factSheetId");
            if (!fsNode.isMissingNode() && !fsNode.isNull()) {
                body.put("factSheetId", fsNode.asLong());
            }

            // Optional model override
            String model = params.path("model").asText(null);
            if (model != null && !model.isBlank()) {
                body.put("modelName", model);
            }

            // Optional deriveOntology (default true — omit to let server use its default)
            JsonNode deriveOntologyNode = params.path("deriveOntology");
            if (!deriveOntologyNode.isMissingNode() && !deriveOntologyNode.isNull()) {
                body.put("deriveOntology", deriveOntologyNode.asBoolean(true));
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            // Use timeoutSeconds + 30s buffer for the HTTP read timeout
            Duration readTimeout = Duration.ofSeconds(timeoutSeconds + 30L);
            GroundingBackendClient.GroundingResponse resp =
                    client.post("/api/unified-crawl/single-source", jsonBody, readTimeout);

            // ── Error handling ────────────────────────────────────────────
            if (resp.statusCode() == 503) {
                return ToolResult.error("crawl_source: backend busy or unavailable (HTTP 503): "
                        + extractError(resp.body()));
            }
            if (resp.statusCode() != 200) {
                return ToolResult.error("crawl_source failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            // ── Parse 200 response ────────────────────────────────────────
            JsonNode r = objectMapper.readTree(resp.body());

            boolean isDryRun   = r.path("dryRun").asBoolean(false);
            boolean completed  = r.path("completed").asBoolean(false);
            boolean persisted  = r.path("persisted").asBoolean(false);
            String  status     = r.path("status").asText("");
            String  jobId      = r.path("jobId").asText(null);
            long    fsId       = r.path("factSheetId").asLong(0);
            int     entityCount   = r.path("entityCount").asInt(0);
            int     relationCount = r.path("relationCount").asInt(0);
            int     chunksCreated = r.path("chunksCreated").asInt(0);
            int     docsLoaded    = r.path("documentsLoaded").asInt(0);
            int     errorCount    = r.path("errorCount").asInt(0);

            StringBuilder sb = new StringBuilder();

            // ── Status headline ───────────────────────────────────────────
            if (isDryRun) {
                sb.append("Dry-run complete (no data persisted).");
            } else if (persisted && completed) {
                sb.append("Crawl complete. persisted=true, status=").append(status).append(".");
            } else if (!completed && jobId != null && !jobId.isBlank()) {
                sb.append("Crawl still running as job ").append(jobId)
                  .append(" — poll GET /api/unified-crawl/jobs/").append(jobId).append(".");
            } else {
                sb.append("Crawl status=").append(status).append(".");
            }
            sb.append("\n");

            // ── Source line ───────────────────────────────────────────────
            if (path != null && !path.isBlank()) {
                sb.append("Source: ").append(path).append("\n");
            } else if (url != null && !url.isBlank()) {
                sb.append("Source: ").append(url).append("\n");
            } else {
                sb.append("Source: [inline text]\n");
            }

            // ── Counts ────────────────────────────────────────────────────
            sb.append("Entities: ").append(entityCount)
              .append("  Relations: ").append(relationCount)
              .append("  Chunks: ").append(chunksCreated)
              .append("  Documents: ").append(docsLoaded).append("\n");

            // ── Fact sheet ────────────────────────────────────────────────
            if (fsId != 0) {
                sb.append("Fact sheet: ").append(fsId).append("\n");
            }

            // ── Ontology derived flag (if present in response) ────────────
            JsonNode ontologyDerivedNode = r.path("ontologyDerived");
            if (!ontologyDerivedNode.isMissingNode()) {
                sb.append("Ontology derived: ").append(ontologyDerivedNode.asBoolean()).append("\n");
            }

            // ── Warnings (first few) ──────────────────────────────────────
            JsonNode warningsNode = r.path("warnings");
            if (warningsNode.isArray() && warningsNode.size() > 0) {
                int shown = Math.min(warningsNode.size(), 3);
                sb.append("Warnings (").append(warningsNode.size()).append("):");
                for (int i = 0; i < shown; i++) {
                    sb.append("\n  ").append(warningsNode.get(i).asText());
                }
                if (warningsNode.size() > shown) {
                    sb.append("\n  ... and ").append(warningsNode.size() - shown).append(" more.");
                }
                sb.append("\n");
            }

            // ── Error count ───────────────────────────────────────────────
            if (errorCount > 0) {
                sb.append("Errors: ").append(errorCount).append("\n");
            }

            // ── Per-step summary ──────────────────────────────────────────
            JsonNode stepsPlannedNode = r.path("stepsPlanned");
            JsonNode stepsResultNode  = r.path("steps");
            if (!stepsPlannedNode.isMissingNode() && stepsPlannedNode.isObject()
                    && stepsPlannedNode.size() > 0) {
                // Build a lookup from stepId → steps[] snapshot
                Map<String, JsonNode> stepSnapshots = new LinkedHashMap<>();
                if (stepsResultNode.isArray()) {
                    stepsResultNode.forEach(s -> {
                        String sid = s.path("stepId").asText(null);
                        if (sid != null) stepSnapshots.put(sid, s);
                    });
                }

                List<String> stepParts = new ArrayList<>();
                Iterator<Map.Entry<String, JsonNode>> it = stepsPlannedNode.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    String stepId   = entry.getKey();
                    String decision = entry.getValue().asText("");
                    if ("SKIP".equals(decision)) {
                        stepParts.add(stepId + " skipped");
                    } else {
                        JsonNode snap = stepSnapshots.get(stepId);
                        if (snap != null) {
                            String stepStatus = snap.path("status").asText("");
                            long elapsedMs    = snap.path("elapsedMs").asLong(0);
                            String elapsedStr = elapsedMs > 0
                                    ? String.format("%.1fs", elapsedMs / 1000.0) : "?";
                            stepParts.add(stepId + " ✓ " + elapsedStr
                                    + (stepStatus.isBlank() ? "" : " [" + stepStatus + "]"));
                        } else {
                            stepParts.add(stepId + " " + decision);
                        }
                    }
                }
                if (!stepParts.isEmpty()) {
                    sb.append("Steps: ").append(String.join(" · ", stepParts)).append("\n");
                }
            }

            // ── Metadata map ──────────────────────────────────────────────
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("entityCount",    entityCount);
            metadata.put("relationCount",  relationCount);
            metadata.put("chunksCreated",  chunksCreated);
            metadata.put("documentsLoaded", docsLoaded);
            metadata.put("persisted",  persisted);
            metadata.put("completed",  completed);
            metadata.put("status",     status);
            if (jobId != null && !jobId.isBlank()) {
                metadata.put("jobId", jobId);
            }
            if (fsId != 0) {
                metadata.put("factSheetId", fsId);
            }
            if (!stepsPlannedNode.isMissingNode() && stepsPlannedNode.isObject()
                    && stepsPlannedNode.size() > 0) {
                Map<String, String> spMap = new LinkedHashMap<>();
                stepsPlannedNode.fields().forEachRemaining(e -> spMap.put(e.getKey(), e.getValue().asText()));
                metadata.put("stepsPlanned", spMap);
            }

            String sourceLabel = (path != null && !path.isBlank()) ? path
                               : (url  != null && !url.isBlank())  ? url
                               : "[inline text]";
            return ToolResult.success("crawl_source: " + sourceLabel,
                    sb.toString().trim(), metadata);

        } catch (ResourceAccessException e) {
            return localBackend.crawlSource(params, context);
        } catch (Exception e) {
            return ToolResult.error("crawl_source error: " + e.getMessage());
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
