/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * MCP tool for managing web and filesystem crawl jobs via the kompile-app
 * REST API. Supports starting, monitoring, pausing, resuming, and cancelling
 * crawls as well as unified crawl-to-graph pipelines.
 * <p>
 * Actions map to the {@code /api/crawlers/*} and {@code /api/unified-crawl/*}
 * endpoints. Requires a running kompile-app instance.
 */
public class CrawlTool implements CliTool {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CrawlTool(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String id() { return "crawl"; }

    @Override
    public String description() {
        return "Crawl websites, filesystems, and other sources to ingest documents. " +
                "Actions: 'start' (begin a crawl), 'status' (check job progress), " +
                "'list' (list all jobs), 'pause', 'resume', 'cancel' (manage jobs), " +
                "'crawlers' (list available crawler types), 'sources' (list source types), " +
                "'unified_start' (multi-source crawl with graph extraction + vector indexing). " +
                "Supports web (BFS link-following), filesystem, HTML, Excel, Gmail, Slack, Discord, " +
                "Google Docs, Google Workspace, and email inbox crawlers.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description",
                "Action to perform: 'start', 'status', 'list', 'pause', 'resume', " +
                "'cancel', 'crawlers', 'sources', 'cleanup', 'unified_start', 'unified_status', 'unified_list'");

        ObjectNode source = props.putObject("source");
        source.put("type", "string");
        source.put("description",
                "URL or path to crawl (for 'start'). E.g. 'https://docs.example.com' or '/data/docs'");

        ObjectNode jobId = props.putObject("job_id");
        jobId.put("type", "string");
        jobId.put("description", "Job ID (for 'status', 'pause', 'resume', 'cancel')");

        ObjectNode maxDepth = props.putObject("max_depth");
        maxDepth.put("type", "integer");
        maxDepth.put("description", "Maximum crawl depth (default: 3)");

        ObjectNode maxDocs = props.putObject("max_docs");
        maxDocs.put("type", "integer");
        maxDocs.put("description", "Maximum documents to crawl (default: 1000)");

        ObjectNode sameDomain = props.putObject("same_domain");
        sameDomain.put("type", "boolean");
        sameDomain.put("description", "Restrict web crawl to same domain (default: true)");

        ObjectNode respectRobots = props.putObject("respect_robots");
        respectRobots.put("type", "boolean");
        respectRobots.put("description", "Respect robots.txt (default: true)");

        ObjectNode delayMs = props.putObject("delay_ms");
        delayMs.put("type", "integer");
        delayMs.put("description", "Delay between requests in milliseconds (default: 500)");

        ObjectNode includePatterns = props.putObject("include_patterns");
        includePatterns.put("type", "string");
        includePatterns.put("description", "Comma-separated include URL/path patterns (regex)");

        ObjectNode excludePatterns = props.putObject("exclude_patterns");
        excludePatterns.put("type", "string");
        excludePatterns.put("description", "Comma-separated exclude URL/path patterns (regex)");

        // Unified crawl fields
        ObjectNode name = props.putObject("name");
        name.put("type", "string");
        name.put("description", "Job name (for 'unified_start')");

        ObjectNode sources = props.putObject("sources");
        sources.put("type", "array");
        sources.put("description",
                "Array of source objects for unified crawl. " +
                "Each: {label, sourceType, pathOrUrl, maxDepth?}. " +
                "Source types: DIRECTORY, WEB_CRAWL, FILE, GMAIL, SLACK, DISCORD, GOOGLE_DOCS, GOOGLE_WORKSPACE, EMAIL_INBOX");

        ObjectNode graphExtraction = props.putObject("graph_extraction");
        graphExtraction.put("type", "boolean");
        graphExtraction.put("description", "Enable knowledge graph extraction during unified crawl (default: false)");

        ObjectNode entityTypes = props.putObject("entity_types");
        entityTypes.put("type", "string");
        entityTypes.put("description", "Comma-separated entity types for graph extraction (e.g. PERSON,ORGANIZATION,PRODUCT)");

        ObjectNode vectorIndex = props.putObject("vector_index");
        vectorIndex.put("type", "boolean");
        vectorIndex.put("description", "Enable vector indexing during unified crawl (default: true)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "crawl"; }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.NETWORK;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Manage crawl jobs");

        String action = params.path("action").asText("").toLowerCase();
        if (action.isEmpty()) {
            return ToolResult.error("action is required");
        }

        if (baseUrl == null || baseUrl.isEmpty()) {
            return ToolResult.error("Crawl tool requires a running kompile-app instance. " +
                    "Start kompile-app or use --url to connect.");
        }

        try {
            return switch (action) {
                case "start" -> startCrawl(params, context);
                case "status" -> getJobStatus(params);
                case "list" -> listJobs();
                case "pause" -> manageJob(params, "pause");
                case "resume" -> manageJob(params, "resume");
                case "cancel" -> manageJob(params, "cancel");
                case "crawlers" -> listCrawlers();
                case "sources" -> listSourceTypes();
                case "cleanup" -> cleanup();
                case "unified_start" -> unifiedStart(params, context);
                case "unified_status" -> unifiedStatus(params);
                case "unified_list" -> unifiedList();
                default -> ToolResult.error("Unknown action: " + action +
                        ". Use: start, status, list, pause, resume, cancel, crawlers, sources, " +
                        "cleanup, unified_start, unified_status, unified_list");
            };
        } catch (java.net.ConnectException e) {
            return ToolResult.error("Cannot connect to kompile-app at " + baseUrl + ". Is it running?");
        } catch (Exception e) {
            return ToolResult.error("Crawl error: " + e.getMessage());
        }
    }

    // ── Standard crawl actions ────────────────────────────────────────────────

    private ToolResult startCrawl(JsonNode params, ToolContext context) throws Exception {
        String source = params.path("source").asText("");
        if (source.isEmpty()) {
            return ToolResult.error("source is required for 'start' action");
        }

        var oc = context.getOutputConsumer();
        if (oc != null) oc.accept("Starting crawl: " + source);

        ObjectNode config = objectMapper.createObjectNode();
        config.put("source", source);
        config.put("maxDepth", params.path("max_depth").asInt(3));
        config.put("maxDocuments", params.path("max_docs").asInt(1000));
        config.put("sameDomain", params.path("same_domain").asBoolean(true));
        config.put("respectRobotsTxt", params.path("respect_robots").asBoolean(true));
        config.put("rateLimitMs", params.path("delay_ms").asInt(500));

        String include = params.path("include_patterns").asText("");
        if (!include.isEmpty()) {
            ArrayNode arr = config.putArray("includePatterns");
            for (String p : include.split(",")) arr.add(p.trim());
        }

        String exclude = params.path("exclude_patterns").asText("");
        if (!exclude.isEmpty()) {
            ArrayNode arr = config.putArray("excludePatterns");
            for (String p : exclude.split(",")) arr.add(p.trim());
        }

        JsonNode response = post("/api/crawlers/start", config);
        String jobId = response.path("jobId").asText(response.path("id").asText("unknown"));
        String crawlerType = response.path("crawlerType").asText(response.path("type").asText(""));

        return ToolResult.success("Crawl started",
                "Crawl job started:\n- Job ID: " + jobId +
                "\n- Source: " + source +
                "\n- Crawler: " + crawlerType +
                "\n\nUse action 'status' with job_id to check progress.",
                Map.of("jobId", jobId, "source", source));
    }

    private ToolResult getJobStatus(JsonNode params) throws Exception {
        String jobId = params.path("job_id").asText("");
        if (jobId.isEmpty()) {
            return ToolResult.error("job_id is required for 'status' action");
        }

        JsonNode response = get("/api/crawlers/jobs/" + jobId);

        String status = response.path("status").asText("unknown");
        int discovered = response.path("discovered").asInt(response.path("totalDiscovered").asInt(0));
        int processed = response.path("processed").asInt(response.path("totalProcessed").asInt(0));
        int errors = response.path("errors").asInt(response.path("totalErrors").asInt(0));

        StringBuilder sb = new StringBuilder();
        sb.append("Crawl job: ").append(jobId).append("\n");
        sb.append("- Status: ").append(status).append("\n");
        sb.append("- Discovered: ").append(discovered).append("\n");
        sb.append("- Processed: ").append(processed).append("\n");
        sb.append("- Errors: ").append(errors).append("\n");

        JsonNode progress = response.path("progress");
        if (!progress.isMissingNode()) {
            double pct = progress.path("percentage").asDouble(0);
            sb.append("- Progress: ").append(String.format("%.1f%%", pct)).append("\n");
        }

        return ToolResult.success("crawl_status: " + jobId, sb.toString(),
                Map.of("jobId", jobId, "status", status, "processed", processed));
    }

    private ToolResult listJobs() throws Exception {
        JsonNode response = get("/api/crawlers/jobs");
        if (!response.isArray()) {
            return ToolResult.success("No crawl jobs found.", "No crawl jobs.", Map.of());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Crawl jobs (").append(response.size()).append("):\n\n");
        for (JsonNode job : response) {
            String id = job.path("jobId").asText(job.path("id").asText("?"));
            String status = job.path("status").asText("unknown");
            String source = job.path("source").asText(job.path("config").path("source").asText("?"));
            int processed = job.path("processed").asInt(job.path("totalProcessed").asInt(0));
            sb.append("- ").append(id).append(" [").append(status).append("] ")
              .append(source).append(" (").append(processed).append(" docs)\n");
        }

        return ToolResult.success("crawl_jobs", sb.toString(), Map.of("count", response.size()));
    }

    private ToolResult manageJob(JsonNode params, String action) throws Exception {
        String jobId = params.path("job_id").asText("");
        if (jobId.isEmpty()) {
            return ToolResult.error("job_id is required for '" + action + "' action");
        }

        post("/api/crawlers/jobs/" + jobId + "/" + action, null);
        return ToolResult.success("Crawl job " + jobId + " " + action + "d.",
                "Job " + jobId + " " + action + "d successfully.",
                Map.of("jobId", jobId, "action", action));
    }

    private ToolResult listCrawlers() throws Exception {
        JsonNode response = get("/api/crawlers");
        if (!response.isArray()) {
            return ToolResult.success("No crawlers available.", "No crawlers.", Map.of());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Available crawlers (").append(response.size()).append("):\n\n");
        for (JsonNode crawler : response) {
            String id = crawler.path("id").asText(crawler.path("crawlerId").asText("?"));
            String desc = crawler.path("description").asText("");
            sb.append("- **").append(id).append("**");
            if (!desc.isEmpty()) sb.append(": ").append(desc);
            sb.append("\n");
        }

        return ToolResult.success("crawlers", sb.toString(), Map.of("count", response.size()));
    }

    private ToolResult listSourceTypes() throws Exception {
        JsonNode response = get("/api/unified-crawl/source-types");
        if (!response.isArray()) {
            return ToolResult.success("Source types: DIRECTORY, WEB_CRAWL, FILE, GMAIL, SLACK, DISCORD, " +
                    "GOOGLE_DOCS, GOOGLE_WORKSPACE, EMAIL_INBOX",
                    "Standard source types available.", Map.of());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Available source types:\n\n");
        for (JsonNode st : response) {
            sb.append("- ").append(st.asText()).append("\n");
        }

        return ToolResult.success("source_types", sb.toString(), Map.of("count", response.size()));
    }

    private ToolResult cleanup() throws Exception {
        post("/api/crawlers/jobs/cleanup", null);
        return ToolResult.success("Finished crawl jobs cleaned up.", "Cleanup complete.", Map.of());
    }

    // ── Unified crawl actions ────────────────────────────────────────────────

    private ToolResult unifiedStart(JsonNode params, ToolContext context) throws Exception {
        var oc = context.getOutputConsumer();
        if (oc != null) oc.accept("Starting unified crawl-to-graph pipeline");

        ObjectNode request = objectMapper.createObjectNode();
        request.put("name", params.path("name").asText("MCP crawl job"));

        // Build sources array
        JsonNode sourcesParam = params.path("sources");
        if (sourcesParam.isArray() && !sourcesParam.isEmpty()) {
            request.set("sources", sourcesParam);
        } else {
            // Fall back to single source
            String source = params.path("source").asText("");
            if (source.isEmpty()) {
                return ToolResult.error("'sources' array or 'source' string required for 'unified_start'");
            }
            ArrayNode sourcesArr = request.putArray("sources");
            ObjectNode s = sourcesArr.addObject();
            s.put("label", "Source");
            s.put("sourceType", source.startsWith("http") ? "WEB_CRAWL" : "DIRECTORY");
            s.put("pathOrUrl", source);
            s.put("maxDepth", params.path("max_depth").asInt(3));
        }

        // Graph extraction config
        if (params.path("graph_extraction").asBoolean(false)) {
            ObjectNode ge = request.putObject("graphExtraction");
            ge.put("enabled", true);
            String entityTypesStr = params.path("entity_types").asText("");
            if (!entityTypesStr.isEmpty()) {
                ArrayNode et = ge.putArray("entityTypes");
                for (String t : entityTypesStr.split(",")) et.add(t.trim());
            }
        }

        // Vector index config
        ObjectNode vi = request.putObject("vectorIndex");
        vi.put("enabled", params.path("vector_index").asBoolean(true));

        JsonNode response = post("/api/unified-crawl/start", request);
        String jobId = response.path("jobId").asText(response.path("id").asText("unknown"));

        return ToolResult.success("Unified crawl started",
                "Unified crawl-to-graph job started:\n- Job ID: " + jobId +
                "\n\nUse action 'unified_status' with job_id to check progress.",
                Map.of("jobId", jobId));
    }

    private ToolResult unifiedStatus(JsonNode params) throws Exception {
        String jobId = params.path("job_id").asText("");
        if (jobId.isEmpty()) {
            return ToolResult.error("job_id is required for 'unified_status' action");
        }

        JsonNode response = get("/api/unified-crawl/jobs/" + jobId);

        String status = response.path("status").asText("unknown");
        StringBuilder sb = new StringBuilder();
        sb.append("Unified crawl job: ").append(jobId).append("\n");
        sb.append("- Status: ").append(status).append("\n");

        JsonNode sourceProgress = response.path("sourceProgress");
        if (sourceProgress.isObject()) {
            sb.append("- Sources:\n");
            var it = sourceProgress.fields();
            while (it.hasNext()) {
                var entry = it.next();
                sb.append("  - ").append(entry.getKey()).append(": ")
                  .append(entry.getValue().path("status").asText("")).append("\n");
            }
        }

        int entities = response.path("totalEntities").asInt(0);
        int relationships = response.path("totalRelationships").asInt(0);
        if (entities > 0 || relationships > 0) {
            sb.append("- Graph: ").append(entities).append(" entities, ")
              .append(relationships).append(" relationships\n");
        }

        return ToolResult.success("unified_crawl_status: " + jobId, sb.toString(),
                Map.of("jobId", jobId, "status", status));
    }

    private ToolResult unifiedList() throws Exception {
        JsonNode response = get("/api/unified-crawl/jobs");
        if (!response.isArray()) {
            return ToolResult.success("No unified crawl jobs.", "No jobs.", Map.of());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Unified crawl jobs (").append(response.size()).append("):\n\n");
        for (JsonNode job : response) {
            String id = job.path("jobId").asText(job.path("id").asText("?"));
            String status = job.path("status").asText("unknown");
            String name = job.path("name").asText("");
            sb.append("- ").append(id).append(" [").append(status).append("]");
            if (!name.isEmpty()) sb.append(" ").append(name);
            sb.append("\n");
        }

        return ToolResult.success("unified_crawl_jobs", sb.toString(), Map.of("count", response.size()));
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private JsonNode get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new ToolExecutionException("HTTP " + response.statusCode() + ": " + extractError(response.body()));
        }
        return objectMapper.readTree(response.body());
    }

    private JsonNode post(String path, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30));

        if (body != null) {
            builder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new ToolExecutionException("HTTP " + response.statusCode() + ": " + extractError(response.body()));
        }
        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(responseBody);
    }

    private String extractError(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            String msg = json.path("message").asText(null);
            if (msg != null) return msg;
            msg = json.path("error").asText(null);
            if (msg != null) return msg;
        } catch (Exception ignored) {}
        return body != null && body.length() > 200 ? body.substring(0, 200) + "..." : (body != null ? body : "");
    }
}
