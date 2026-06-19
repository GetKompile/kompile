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
 * Tool that performs knowledge graph searches via the kompile-app GraphRAG
 * endpoint. Queries entities, relationships, and community summaries in
 * a Neo4j-backed knowledge graph.
 * <p>
 * Uses {@link KompileBackendClient} for auto-detection, reconnection,
 * and configurable timeouts.
 */
public class GraphRagSearchTool implements CliTool {

    private final KompileBackendClient backend;
    private final ObjectMapper objectMapper;

    public GraphRagSearchTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = KompileBackendClient.getInstance();
        if (baseUrl != null && !baseUrl.isEmpty()) {
            backend.setBaseUrl(baseUrl);
        }
    }

    @Override
    public String id() { return "graph_search"; }

    @Override
    public String description() {
        return "Search the knowledge graph for entities, relationships, and community summaries. " +
                "Use 'local' search for specific entity lookups and fact retrieval, or 'global' " +
                "search for broad thematic summaries across the knowledge base. " +
                "Returns structured results including entities, relationships, and context.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "The search query to find entities and relationships");

        ObjectNode searchType = props.putObject("search_type");
        searchType.put("type", "string");
        searchType.put("description", "Search type: 'local' (entity-centric, specific facts) " +
                "or 'global' (community-level, broad themes). Default: 'local'");

        ObjectNode maxResults = props.putObject("max_results");
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum number of results to return (default: 5)");

        ObjectNode conversationId = props.putObject("conversation_id");
        conversationId.put("type", "string");
        conversationId.put("description", "Conversation ID for context tracking (optional)");

        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public String permissionKey() { return "graph_search"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Search knowledge graph");

        String query = params.path("query").asText("");
        String searchType = params.path("search_type").asText("local").toUpperCase();
        int maxResults = params.path("max_results").asInt(5);
        String conversationId = params.path("conversation_id").asText(null);

        if (query.isEmpty()) {
            return ToolResult.error("query is required");
        }

        if (!backend.isAvailable()) {
            return ToolResult.error("Graph search requires a running kompile-app instance with Neo4j. " +
                    "Start kompile-app with Neo4j enabled or use --url to connect. " +
                    "The backend will be auto-detected when it comes online.");
        }

        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("query", query);
            request.put("searchType", searchType);
            request.put("maxResults", maxResults);
            if (conversationId != null && !conversationId.isEmpty()) {
                request.put("conversationId", conversationId);
            }

            HttpResponse<String> response = backend.post(
                    "/api/graph-rag/search",
                    objectMapper.writeValueAsString(request),
                    Duration.ofSeconds(30));

            if (response.statusCode() != 200) {
                return ToolResult.error("Graph search failed (HTTP " + response.statusCode() + "): " +
                        extractError(response.body()));
            }

            JsonNode result = objectMapper.readTree(response.body());
            return formatResults(query, result, searchType);

        } catch (ConnectException e) {
            return ToolResult.error("Cannot connect to kompile-app. " + e.getMessage());
        } catch (java.net.http.HttpTimeoutException e) {
            return ToolResult.error("Graph search timed out after 30s. " +
                    "The graph query may be too broad. Try a more specific query.");
        } catch (Exception e) {
            return ToolResult.error("Graph search error: " + e.getMessage());
        }
    }

    private ToolResult formatResults(String query, JsonNode result, String searchType) {
        StringBuilder sb = new StringBuilder();
        sb.append("Knowledge graph search (").append(searchType.toLowerCase()).append("): \"")
                .append(query).append("\"\n\n");

        JsonNode entities = result.path("entities");
        if (entities.isArray() && !entities.isEmpty()) {
            sb.append("### Entities\n");
            for (JsonNode entity : entities) {
                String name = entity.path("name").asText(entity.path("label").asText("unnamed"));
                String type = entity.path("type").asText(entity.path("category").asText(""));
                String desc = entity.path("description").asText("");
                sb.append("- **").append(name).append("**");
                if (!type.isEmpty()) sb.append(" (").append(type).append(")");
                if (!desc.isEmpty()) sb.append(": ").append(desc);
                sb.append("\n");
            }
            sb.append("\n");
        }

        JsonNode relationships = result.path("relationships");
        if (relationships.isArray() && !relationships.isEmpty()) {
            sb.append("### Relationships\n");
            for (JsonNode rel : relationships) {
                String source = rel.path("source").asText(rel.path("from").asText("?"));
                String target = rel.path("target").asText(rel.path("to").asText("?"));
                String relType = rel.path("type").asText(rel.path("relationship").asText("related_to"));
                String desc = rel.path("description").asText("");
                sb.append("- ").append(source).append(" -> [").append(relType).append("] -> ").append(target);
                if (!desc.isEmpty()) sb.append(": ").append(desc);
                sb.append("\n");
            }
            sb.append("\n");
        }

        JsonNode communities = result.path("communities");
        if (communities.isArray() && !communities.isEmpty()) {
            sb.append("### Community Summaries\n");
            for (JsonNode community : communities) {
                String title = community.path("title").asText(community.path("name").asText("Community"));
                String summary = community.path("summary").asText(community.path("description").asText(""));
                sb.append("**").append(title).append("**: ").append(summary).append("\n\n");
            }
        }

        String contextText = result.path("context").asText(
                result.path("summary").asText(""));
        if (!contextText.isEmpty()) {
            sb.append("### Context\n").append(contextText).append("\n");
        }

        if (sb.toString().trim().endsWith("\"")) {
            return ToolResult.success("No graph results found for: " + query);
        }

        int entityCount = entities.isArray() ? entities.size() : 0;
        int relCount = relationships.isArray() ? relationships.size() : 0;

        return ToolResult.success("graph_search: " + query, sb.toString(),
                Map.of("query", query, "searchType", searchType,
                        "entityCount", entityCount, "relationshipCount", relCount));
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
