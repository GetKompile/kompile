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
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Unified knowledge search tool for CLI/MCP stdio.
 * Calls POST /api/knowledge/search on kompile-app, which fans out to all
 * available backends (vector, keyword, graph) and returns merged results.
 * <p>
 * This is the primary search tool for agents — just provide a query.
 */
public class KnowledgeSearchCliTool implements CliTool {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public KnowledgeSearchCliTool(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String id() { return "knowledge_search"; }

    @Override
    public String description() {
        return "Search all knowledge sources with a natural language question. " +
                "Automatically searches indexed documents and knowledge graph in parallel. " +
                "Returns relevant content with source attribution. " +
                "Optionally use topic to narrow results to a specific subject area.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("query")
                .put("type", "string")
                .put("description", "Natural language question to search for");

        props.putObject("topic")
                .put("type", "string")
                .put("description", "Optional: filter results to a specific topic or source collection");

        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public String permissionKey() { return "knowledge_search"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Search knowledge base");

        String query = params.path("query").asText("");
        String topic = params.path("topic").asText(null);

        if (query.isEmpty()) {
            return ToolResult.error("query is required");
        }

        if (baseUrl == null || baseUrl.isEmpty()) {
            return ToolResult.error("knowledge_search requires a running kompile-app. " +
                    "Start kompile-app or use --url to connect.");
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("query", query);
            if (topic != null && !topic.isEmpty()) {
                body.put("topic", topic);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/knowledge/search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(45))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ToolResult.error("Knowledge search failed (HTTP " + response.statusCode() + "): " +
                        extractError(response.body()));
            }

            JsonNode result = objectMapper.readTree(response.body());

            if (result.has("error")) {
                return ToolResult.error(result.path("error").asText("Unknown error"));
            }

            return formatResponse(query, result);

        } catch (java.net.ConnectException e) {
            return ToolResult.error("Cannot connect to kompile-app at " + baseUrl +
                    ". Is it running? Start with: kompile run");
        } catch (Exception e) {
            return ToolResult.error("Knowledge search error: " + e.getMessage());
        }
    }

    private ToolResult formatResponse(String query, JsonNode result) {
        StringBuilder sb = new StringBuilder();

        // Summary line
        String summary = result.path("summary").asText("");
        if (!summary.isEmpty()) {
            sb.append(summary).append("\n\n");
        }

        // Results
        JsonNode results = result.path("results");
        int idx = 0;
        if (results.isArray()) {
            for (JsonNode r : results) {
                idx++;
                String source = r.path("source").asText("Unknown");
                sb.append("### ").append(idx).append(". ").append(source);

                double rel = r.path("relevance").asDouble(0.0);
                if (rel > 0) {
                    sb.append(" (").append(String.format("%.2f", rel)).append(")");
                }
                sb.append("\n");

                String content = r.path("content").asText("");
                if (!content.isEmpty()) {
                    sb.append(content.strip()).append("\n\n");
                }
            }
        }

        // Graph context
        String graphContext = result.path("graph_context").asText("");
        if (!graphContext.isEmpty()) {
            sb.append("### Graph Context\n").append(graphContext).append("\n\n");
        }

        // Hint for empty results
        String hint = result.path("hint").asText("");
        if (!hint.isEmpty()) {
            sb.append("_").append(hint).append("_\n");
        }

        if (idx == 0 && graphContext.isEmpty()) {
            return ToolResult.success("No results found for: " + query);
        }

        return ToolResult.success("knowledge_search: " + query, sb.toString(),
                Map.of("query", query, "resultCount", idx));
    }

    private String extractError(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            String msg = json.path("message").asText(null);
            if (msg != null) return msg;
            msg = json.path("error").asText(null);
            if (msg != null) return msg;
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
