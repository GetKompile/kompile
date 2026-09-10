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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool that searches the current folder's portable graph in local stdio mode,
 * or a configured kompile-app GraphRAG endpoint in managed mode. Queries ranked
 * entities and relationships; managed GLOBAL search may also return community summaries.
 * <p>
 * Uses {@link KompileBackendClient} for auto-detection, reconnection,
 * and configurable timeouts.
 */
public class GraphRagSearchTool implements CliTool {

    private final KompileBackendClient backend;
    private final ObjectMapper objectMapper;
    private final boolean remoteConfigured;

    public GraphRagSearchTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = KompileBackendClient.getInstance();
        this.remoteConfigured = baseUrl != null && !baseUrl.isBlank();
        if (remoteConfigured) {
            backend.setBaseUrl(baseUrl);
        }
    }

    @Override
    public String id() { return "graph_search"; }

    @Override
    public String description() {
        return "Search the knowledge graph for entities, relationships, and community summaries. " +
                "Use 'local' search for specific entity lookups and fact retrieval, or 'global' " +
                "search for broad structural context; 'hybrid' expands ranked matches one hop. " +
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
        searchType.put("description", "Search type: 'local' (ranked entity lookup), 'hybrid' " +
                "(ranked lookup plus one-hop expansion), or 'global' (two-hop structural expansion; " +
                "managed services may add community summaries). Default: 'local'");
        searchType.putArray("enum").add("local").add("hybrid").add("global");

        ObjectNode maxResults = props.putObject("max_results");
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum number of results to return (default: 5)");

        ObjectNode conversationId = props.putObject("conversation_id");
        conversationId.put("type", "string");
        conversationId.put("description", "Conversation ID for context tracking (optional)");

        ObjectNode knowledgeBase = props.putObject("knowledgeBase");
        knowledgeBase.put("type", "string");
        knowledgeBase.put("description", "Project-local knowledge-base id to search (optional)");

        ObjectNode factSheetId = props.putObject("factSheetId");
        factSheetId.put("type", "integer");
        factSheetId.put("description", "Fact-sheet selector for managed/legacy graphs (optional)");

        ObjectNode codeProjectId = props.putObject("code_project_id");
        codeProjectId.put("type", "string");
        codeProjectId.put("description", "Folder-local code-project id filter (optional)");

        ObjectNode entityType = props.putObject("entity_type");
        entityType.put("type", "string");
        entityType.put("description", "Folder-local entity type filter, e.g. CLASS or METHOD (optional)");

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

        if (!remoteConfigured) {
            ToolResult local = OfflineToolRuntime.execute(id(), params, context, objectMapper);
            if (local.isError()) return local;
            try {
                return formatResults(query, objectMapper.readTree(local.getOutput()), searchType);
            } catch (Exception malformedLocalResult) {
                return local;
            }
        }
        if (!backend.isAvailable()) {
            return ToolResult.error("The explicitly configured remote graph search service is unavailable at "
                    + backend.baseUrlFor("/api/chat/graph-rag/search") + ". Remove --url to use the in-process graph.");
        }

        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("query", query);
            request.put("searchType", searchType);
            request.put("maxResults", maxResults);
            if (conversationId != null && !conversationId.isEmpty()) {
                request.put("conversationId", conversationId);
            }
            if (params.hasNonNull("factSheetId")) {
                request.set("factSheetId", params.get("factSheetId"));
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
            return ToolResult.error("The explicitly configured remote graph search service became unavailable. "
                    + "Remove --url to continue with the in-process graph. " + e.getMessage());
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

        String provenance = formatProvenance(result);
        if (!provenance.isEmpty()) {
            sb.append(provenance).append("\n");
        }

        JsonNode entities = result.path("entities");
        if (entities.isArray() && !entities.isEmpty()) {
            sb.append("### Entities\n");
            for (JsonNode entity : entities) {
                String name = entity.path("name").asText(entity.path("label").asText("unnamed"));
                String type = entity.path("type").asText(entity.path("category").asText(""));
                String desc = entity.path("description").asText("");
                sb.append("- **").append(name).append("**");
                if (!type.isEmpty()) sb.append(" (").append(type).append(")");
                String id = entity.path("id").asText("");
                if (!id.isEmpty()) sb.append(" [id: `").append(id).append("`]");
                if (entity.has("score")) {
                    sb.append(" score=").append(String.format("%.3f", entity.path("score").asDouble()));
                }
                if (!desc.isEmpty()) sb.append(": ").append(desc);
                sb.append("\n");
            }
            sb.append("\n");
        }

        JsonNode relationships = result.path("relationships");
        if (relationships.isArray() && !relationships.isEmpty()) {
            sb.append("### Relationships\n");
            for (JsonNode rel : relationships) {
                String source = rel.path("sourceName").asText(
                        rel.path("source").asText(rel.path("from").asText("?")));
                String target = rel.path("targetName").asText(
                        rel.path("target").asText(rel.path("to").asText("?")));
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

        // Surface source chunks for provenance/traceability
        JsonNode sourceChunks = result.path("sourceChunks");
        JsonNode sourceChunkRefs = result.path("sourceChunkRefs");
        if (sourceChunks.isArray() && !sourceChunks.isEmpty()) {
            sb.append("\n### Source Chunks\n");
            int chunkIdx = 0;
            for (JsonNode chunk : sourceChunks) {
                chunkIdx++;
                double score = chunk.path("score").asDouble(chunk.path("relevance").asDouble(0.0));
                String chunkText = chunk.path("text").asText(chunk.path("content").asText(""));
                String chunkSource = chunk.path("source").asText(
                        chunk.path("metadata").path("source").asText("unknown"));
                sb.append("[").append(chunkIdx).append("] source: ").append(chunkSource);
                if (score > 0.0) sb.append(" (score: ").append(String.format("%.3f", score)).append(")");
                sb.append("\n");
                if (!chunkText.isEmpty()) {
                    String preview = chunkText.length() > 300
                            ? chunkText.substring(0, 300) + "..." : chunkText;
                    sb.append(preview.strip()).append("\n");
                }
                sb.append("\n");
            }
        } else if (sourceChunkRefs.isArray() && !sourceChunkRefs.isEmpty()) {
            sb.append("\n### Source References\n");
            for (JsonNode ref : sourceChunkRefs) {
                sb.append("- ").append(ref.asText(ref.toString())).append("\n");
            }
        }

        int entityCount = entities.isArray() ? entities.size() : 0;
        int relCount = relationships.isArray() ? relationships.size() : 0;
        int communityCount = communities.isArray() ? communities.size() : 0;
        int chunkCount = sourceChunks.isArray() ? sourceChunks.size()
                : (sourceChunkRefs.isArray() ? sourceChunkRefs.size() : 0);
        Map<String, Object> metadata = resultMetadata(query, searchType, result,
                entityCount, relCount, chunkCount);
        if (entityCount == 0 && relCount == 0 && communityCount == 0 && chunkCount == 0) {
            String noResults = "No graph results found for: " + query;
            if (!provenance.isEmpty()) {
                noResults += "\n\n" + provenance;
            }
            return ToolResult.success("", noResults, metadata);
        }

        return ToolResult.success("graph_search: " + query, sb.toString(), metadata);
    }

    private String formatProvenance(JsonNode result) {
        JsonNode data = valueNode(result, "data");
        String ranking = scalarText(valueNode(result, "ranking"));
        String scoreBasis = scalarText(data == null ? null : valueNode(data, "scoreBasis"));
        String storedPrior = scalarText(data == null ? null : valueNode(data, "storedPrior"));
        JsonNode inferenceInvoked = valueNode(result, "inferenceInvoked");
        if (inferenceInvoked == null && data != null) {
            inferenceInvoked = valueNode(data, "inferenceInvoked");
        }

        StringBuilder provenance = new StringBuilder();
        if (scoreBasis != null && !scoreBasis.isBlank()) {
            provenance.append("Score basis: ").append(scoreBasis).append("\n");
        } else if (ranking != null && !ranking.isBlank()) {
            provenance.append("Score basis: ").append(ranking).append("\n");
        }
        if (storedPrior != null && !storedPrior.isBlank()) {
            provenance.append("Stored prior: ").append(storedPrior).append("\n");
        }
        if (inferenceInvoked != null && inferenceInvoked.isValueNode()) {
            provenance.append("Inference invoked: ").append(inferenceInvoked.asText()).append("\n");
        }
        return provenance.toString().stripTrailing();
    }

    private Map<String, Object> resultMetadata(String query, String searchType, JsonNode result,
                                                int entityCount, int relationshipCount,
                                                int sourceChunkCount) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("query", query);
        metadata.put("searchType", searchType);
        metadata.put("entityCount", entityCount);
        metadata.put("relationshipCount", relationshipCount);
        metadata.put("sourceChunkCount", sourceChunkCount);

        putJsonMetadata(metadata, "ranking", valueNode(result, "ranking"));
        putJsonMetadata(metadata, "inferenceInvoked", valueNode(result, "inferenceInvoked"));

        JsonNode data = valueNode(result, "data");
        if (data != null && data.isObject()) {
            Map<String, Object> rankingData = new LinkedHashMap<>();
            putJsonMetadata(rankingData, "scoreBasis", valueNode(data, "scoreBasis"));
            putJsonMetadata(rankingData, "storedPrior", valueNode(data, "storedPrior"));
            putJsonMetadata(rankingData, "inferenceInvoked", valueNode(data, "inferenceInvoked"));
            if (!rankingData.isEmpty()) {
                metadata.put("data", rankingData);
            }
            if (!metadata.containsKey("inferenceInvoked")) {
                putJsonMetadata(metadata, "inferenceInvoked", valueNode(data, "inferenceInvoked"));
            }
        }
        return metadata;
    }

    private JsonNode valueNode(JsonNode parent, String field) {
        if (parent == null || !parent.has(field)) return null;
        JsonNode value = parent.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? null : value;
    }

    private String scalarText(JsonNode value) {
        return value != null && value.isValueNode() ? value.asText() : null;
    }

    private void putJsonMetadata(Map<String, Object> metadata, String key, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return;
        metadata.put(key, objectMapper.convertValue(value, Object.class));
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
