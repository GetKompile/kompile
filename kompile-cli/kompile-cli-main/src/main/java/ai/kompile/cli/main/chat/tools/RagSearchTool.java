/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.ConnectException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Tool that performs RAG (Retrieval-Augmented Generation) searches against
 * the kompile-app knowledge base. Connects to the kompile-app REST API
 * via {@code POST /api/knowledge/search} (UnifiedKnowledgeTool), which
 * fans out to all active document retrievers and the knowledge graph in parallel.
 * <p>
 * Uses {@link KompileBackendClient} for auto-detection, reconnection,
 * and configurable timeouts.
 */
public class RagSearchTool implements CliTool {

    private final KompileBackendClient backend;
    private final ObjectMapper objectMapper;
    private final boolean remoteConfigured;

    public RagSearchTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = KompileBackendClient.getInstance();
        this.remoteConfigured = baseUrl != null && !baseUrl.isBlank();
        if (remoteConfigured) {
            backend.setBaseUrl(baseUrl);
        }
    }

    @Override
    public String id() { return "rag_search"; }

    @Override
    public String description() {
        return "Search the Kompile knowledge base using RAG (Retrieval-Augmented Generation). " +
                "Local stdio initializes and searches only the current folder, using its local " +
                "encoder subprocess plus lexical retrieval. An explicitly configured remote service " +
                "fans out to its document retrievers and knowledge graph. Returns relevant chunks with " +
                "source attribution and relevance scores. " +
                "Use this to find information from ingested documents, PDFs, and other sources. " +
                "Optionally supply a topic to narrow results to a specific subject area.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "The search query to find relevant documents");

        ObjectNode topic = props.putObject("topic");
        topic.put("type", "string");
        topic.put("description", "Optional topic / subject area to narrow results (e.g. 'finance', 'security')");

        ObjectNode knowledgeBase = props.putObject("knowledgeBase");
        knowledgeBase.put("type", "string");
        knowledgeBase.put("description", "Local mode only: optional knowledge base inside the current folder");

        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("minimum", 1);
        limit.put("maximum", 50);
        limit.put("default", 20);
        limit.put("description", "Local mode only: maximum number of results (default 20)");

        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public String permissionKey() { return "rag_search"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Search knowledge base");

        String query = params.path("query").asText("");
        String topic = params.path("topic").asText(null);

        if (query.isEmpty()) {
            return ToolResult.error("query is required");
        }

        if (!remoteConfigured) {
            return OfflineToolRuntime.execute(id(), params, context, objectMapper);
        }
        if (!backend.isAvailable()) {
            return ToolResult.error("The explicitly configured remote RAG service is unavailable at "
                    + backend.baseUrlFor("/api/chat/rag/search") + ". Remove --url to use the in-process index.");
        }

        try {
            // POST /api/knowledge/search — KnowledgeSearchController → UnifiedKnowledgeTool
            ObjectNode request = objectMapper.createObjectNode();
            request.put("query", query);
            if (topic != null && !topic.isBlank()) {
                request.put("topic", topic);
            }

            HttpResponse<String> response = backend.post(
                    "/api/knowledge/search",
                    objectMapper.writeValueAsString(request),
                    Duration.ofSeconds(30));

            if (response.statusCode() != 200) {
                return ToolResult.error("RAG search failed (HTTP " + response.statusCode() + "): " +
                        extractError(response.body()));
            }

            JsonNode result = objectMapper.readTree(response.body());
            return formatResults(query, result, topic);

        } catch (ConnectException e) {
            return ToolResult.error("The explicitly configured remote RAG service became unavailable. "
                    + "Remove --url to continue with the in-process index. " + e.getMessage());
        } catch (java.net.http.HttpTimeoutException e) {
            return ToolResult.error("RAG search timed out after 30s. The query may be too broad " +
                    "or the backend is under heavy load. Try a more specific query.");
        } catch (Exception e) {
            return ToolResult.error("RAG search error: " + e.getMessage());
        }
    }

    private ToolResult formatResults(String query, JsonNode result, String topic) {
        StringBuilder sb = new StringBuilder();

        // /api/knowledge/search returns {results: [...], graph_context: "...", summary: "..."}
        String summary = result.path("summary").asText("");
        String hint = result.path("hint").asText("");

        JsonNode documents = result.path("results");

        if (!documents.isArray() || documents.isEmpty()) {
            String msg = summary.isEmpty()
                    ? "No documents found for: " + query
                    : summary + (hint.isEmpty() ? "" : " " + hint);
            return ToolResult.success(msg);
        }

        sb.append("RAG search results for: \"").append(query).append("\"");
        if (topic != null && !topic.isBlank()) {
            sb.append(" (topic: ").append(topic).append(")");
        }
        sb.append("\n");
        if (!summary.isEmpty()) {
            sb.append(summary).append("\n");
        }
        sb.append("\n");

        int idx = 0;
        for (JsonNode doc : documents) {
            idx++;
            double relevance = doc.path("relevance").asDouble(0.0);
            String content = doc.path("content").asText("(no content)");
            String source = doc.path("source").asText("unknown");

            sb.append("### Document ").append(idx);
            if (relevance > 0.0) {
                sb.append(" (relevance: ").append(String.format("%.2f", relevance)).append(")");
            }
            sb.append("\nSource: ").append(source);

            // Surface citation fields when available
            if (!doc.path("page").isMissingNode()) {
                sb.append(", page ").append(doc.path("page").asInt());
            }
            if (!doc.path("chunk").isMissingNode()) {
                sb.append(", chunk ").append(doc.path("chunk").asInt());
            }
            sb.append("\n");
            sb.append(content.strip()).append("\n\n");
        }

        // Append graph context when available
        String graphContext = result.path("graph_context").asText("");
        if (!graphContext.isEmpty()) {
            sb.append("### Graph Context\n").append(graphContext).append("\n");
        }

        return ToolResult.success("rag_search: " + query, sb.toString(),
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
