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
 * the kompile-app vector store and keyword index. Connects to the kompile-app
 * REST API for semantic and keyword search over indexed documents.
 * <p>
 * Uses {@link KompileBackendClient} for auto-detection, reconnection,
 * and configurable timeouts.
 */
public class RagSearchTool implements CliTool {

    private final KompileBackendClient backend;
    private final ObjectMapper objectMapper;

    public RagSearchTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = KompileBackendClient.getInstance();
        if (baseUrl != null && !baseUrl.isEmpty()) {
            backend.setBaseUrl(baseUrl);
        }
    }

    @Override
    public String id() { return "rag_search"; }

    @Override
    public String description() {
        return "Search the kompile knowledge base using RAG (Retrieval-Augmented Generation). " +
                "Performs semantic vector search and/or keyword search over indexed documents. " +
                "Returns relevant document chunks with similarity scores. " +
                "Use this to find information from ingested documents, PDFs, and other sources.";
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

        ObjectNode maxResults = props.putObject("max_results");
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum number of results to return (default: 5)");

        ObjectNode searchType = props.putObject("search_type");
        searchType.put("type", "string");
        searchType.put("description", "Search type: 'semantic' (vector), 'keyword', or 'hybrid' (both, default)");

        ObjectNode similarityThreshold = props.putObject("similarity_threshold");
        similarityThreshold.put("type", "number");
        similarityThreshold.put("description", "Minimum similarity score for results (0.0-1.0, default: 0.0)");

        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public String permissionKey() { return "rag_search"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Search knowledge base");

        String query = params.path("query").asText("");
        int maxResults = params.path("max_results").asInt(5);
        String searchType = params.path("search_type").asText("hybrid");
        double threshold = params.path("similarity_threshold").asDouble(0.0);

        if (query.isEmpty()) {
            return ToolResult.error("query is required");
        }

        if (!backend.isAvailable()) {
            return ToolResult.error("RAG search requires a running kompile-app instance. " +
                    "Start kompile-app or use --url to connect. " +
                    "The backend will be auto-detected when it comes online.");
        }

        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("query", query);
            request.put("maxResults", maxResults);
            request.put("similarityThreshold", threshold);

            boolean enableSemantic = "semantic".equals(searchType) || "hybrid".equals(searchType);
            boolean enableKeyword = "keyword".equals(searchType) || "hybrid".equals(searchType);
            request.put("enableSemanticSearch", enableSemantic);
            request.put("enableKeywordSearch", enableKeyword);

            HttpResponse<String> response = backend.post(
                    "/api/search/cross-index",
                    objectMapper.writeValueAsString(request),
                    Duration.ofSeconds(30));

            if (response.statusCode() != 200) {
                return ToolResult.error("RAG search failed (HTTP " + response.statusCode() + "): " +
                        extractError(response.body()));
            }

            JsonNode result = objectMapper.readTree(response.body());
            return formatResults(query, result, searchType);

        } catch (ConnectException e) {
            return ToolResult.error("Cannot connect to kompile-app. " + e.getMessage());
        } catch (java.net.http.HttpTimeoutException e) {
            return ToolResult.error("RAG search timed out after 30s. The query may be too broad " +
                    "or the backend is under heavy load. Try a more specific query.");
        } catch (Exception e) {
            return ToolResult.error("RAG search error: " + e.getMessage());
        }
    }

    private ToolResult formatResults(String query, JsonNode result, String searchType) {
        StringBuilder sb = new StringBuilder();

        JsonNode documents = result.path("documents");
        if (!documents.isArray() || documents.isEmpty()) {
            documents = result.path("results");
        }

        if (!documents.isArray() || documents.isEmpty()) {
            return ToolResult.success("No documents found for: " + query);
        }

        sb.append("RAG search results for: \"").append(query).append("\" (").append(searchType).append(")\n\n");

        int idx = 0;
        for (JsonNode doc : documents) {
            idx++;
            double score = doc.path("score").asDouble(
                    doc.path("similarity").asDouble(0.0));
            String content = doc.path("content").asText(
                    doc.path("text").asText("(no content)"));
            String source = doc.path("metadata").path("source").asText(
                    doc.path("source").asText("unknown"));

            sb.append("### Document ").append(idx).append(" (score: ")
                    .append(String.format("%.3f", score)).append(")\n");
            sb.append("Source: ").append(source).append("\n");
            sb.append(content.strip()).append("\n\n");
        }

        return ToolResult.success("rag_search: " + query, sb.toString(),
                Map.of("query", query, "resultCount", idx, "searchType", searchType));
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
