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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unified knowledge search tool that aggregates {@link RagSearchTool},
 * {@link GraphRagSearchTool}, and {@link SemanticMemoryTool} behind a
 * single MCP entry point.
 *
 * <p>Routing logic based on {@code source} parameter:
 * <ul>
 *   <li><b>auto</b> (default) — searches all available backends and merges results</li>
 *   <li><b>documents</b> — vector/keyword search over ingested documents (RAG)</li>
 *   <li><b>graph</b> — knowledge graph entities and relationships (Neo4j)</li>
 *   <li><b>memory</b> — semantic memory from past interactions</li>
 * </ul>
 *
 * <p>The underlying tool implementations are preserved unchanged — this class
 * is a pure aggregator that eliminates the need for callers to choose between
 * RAG, graph, and memory search backends.
 */
public class UnifiedSearchTool implements CliTool {

    private final RagSearchTool ragSearchTool;
    private final GraphRagSearchTool graphSearchTool;
    private final SemanticMemoryTool semanticMemoryTool;

    public UnifiedSearchTool(String baseUrl, ObjectMapper objectMapper,
                             SemanticMemoryEngine semanticEngine) {
        this.ragSearchTool = new RagSearchTool(baseUrl, objectMapper);
        this.graphSearchTool = new GraphRagSearchTool(baseUrl, objectMapper);
        this.semanticMemoryTool = new SemanticMemoryTool(semanticEngine);
    }

    @Override
    public String id() { return "search"; }

    @Override
    public String description() {
        return "Unified knowledge search across all backends. Searches ingested documents "
                + "(RAG vector/keyword), knowledge graph (entities and relationships), "
                + "and semantic memory (past interactions) in a single call. "
                + "Use source='auto' (default) to search everywhere, or target a specific backend: "
                + "'documents' (RAG), 'graph' (Neo4j knowledge graph), 'memory' (semantic memory). "
                + "Additional actions: 'memory_stats' (show memory index stats), "
                + "'index_turn' (add content to memory index).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "The search query to find relevant information");

        ObjectNode source = props.putObject("source");
        source.put("type", "string");
        source.put("description", "Search backend: 'auto' (all backends, default), "
                + "'documents' (RAG vector/keyword search), "
                + "'graph' (knowledge graph entities/relationships), "
                + "'memory' (semantic memory from past interactions)");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: 'search' (default), 'memory_stats', 'index_turn'");

        ObjectNode maxResults = props.putObject("max_results");
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results per backend (default: 5)");

        ObjectNode searchType = props.putObject("search_type");
        searchType.put("type", "string");
        searchType.put("description", "For documents: 'semantic', 'keyword', or 'hybrid' (default). "
                + "For graph: 'local' (entity-centric) or 'global' (community summaries).");

        ObjectNode similarityThreshold = props.putObject("similarity_threshold");
        similarityThreshold.put("type", "number");
        similarityThreshold.put("description", "Minimum similarity score 0-1 (default: 0.0 for docs, 0.15 for memory)");

        ObjectNode conversationId = props.putObject("conversation_id");
        conversationId.put("type", "string");
        conversationId.put("description", "Conversation ID for graph search context tracking");

        ObjectNode content = props.putObject("content");
        content.put("type", "string");
        content.put("description", "Content to index (for action='index_turn')");

        ObjectNode role = props.putObject("role");
        role.put("type", "string");
        role.put("description", "Role for the turn: user, assistant (for action='index_turn')");

        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public String permissionKey() { return "search"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        // Permission check delegated to underlying tools (rag_search, graph_search, memory)
        // to avoid double-prompting the user.

        String action = params.path("action").asText("search");

        // Non-search actions route directly to semantic memory
        if ("memory_stats".equals(action)) {
            ObjectNode memParams = new ObjectMapper().createObjectNode();
            memParams.put("action", "stats");
            return semanticMemoryTool.execute(memParams, context);
        }
        if ("index_turn".equals(action)) {
            ObjectNode memParams = new ObjectMapper().createObjectNode();
            memParams.put("action", "index_turn");
            memParams.put("content", params.path("content").asText(""));
            memParams.put("role", params.path("role").asText("user"));
            return semanticMemoryTool.execute(memParams, context);
        }

        String query = params.path("query").asText("");
        if (query.isEmpty()) {
            return ToolResult.error("'query' is required");
        }

        String source = params.path("source").asText("auto");

        return switch (source) {
            case "documents" -> executeRag(params, context);
            case "graph" -> executeGraph(params, context);
            case "memory" -> executeMemory(params, context);
            case "auto" -> executeAll(params, context, query);
            default -> ToolResult.error("Unknown source: " + source
                    + ". Use 'auto', 'documents', 'graph', or 'memory'.");
        };
    }

    private ToolResult executeRag(JsonNode params, ToolContext context) throws ToolExecutionException {
        return ragSearchTool.execute(params, context);
    }

    private ToolResult executeGraph(JsonNode params, ToolContext context) throws ToolExecutionException {
        return graphSearchTool.execute(params, context);
    }

    private ToolResult executeMemory(JsonNode params, ToolContext context) throws ToolExecutionException {
        ObjectNode memParams = new ObjectMapper().createObjectNode();
        memParams.put("action", "search");
        memParams.put("query", params.path("query").asText(""));
        memParams.put("top_k", params.path("max_results").asInt(5));
        double threshold = params.path("similarity_threshold").asDouble(0.15);
        memParams.put("threshold", threshold);
        return semanticMemoryTool.execute(memParams, context);
    }

    /**
     * Auto mode: query all available backends and merge results.
     * Backends that fail (e.g. server not running) are silently skipped.
     */
    private ToolResult executeAll(JsonNode params, ToolContext context, String query)
            throws ToolExecutionException {
        List<String> sections = new ArrayList<>();
        int totalResults = 0;

        // 1. Semantic memory (always available, offline)
        try {
            ToolResult memResult = executeMemory(params, context);
            if (memResult.getOutput() != null && !memResult.getOutput().contains("No relevant memories")) {
                sections.add("## Memory\n" + memResult.getOutput());
                if (memResult.getMetadata() != null && memResult.getMetadata().containsKey("count")) {
                    totalResults += ((Number) memResult.getMetadata().get("count")).intValue();
                }
            }
        } catch (Exception ignored) {}

        // 2. RAG document search (requires kompile-app)
        try {
            ToolResult ragResult = executeRag(params, context);
            if (ragResult.getOutput() != null && !ragResult.getOutput().contains("No documents found")
                    && !ragResult.getOutput().contains("Cannot connect")
                    && !ragResult.getOutput().contains("requires a running")) {
                sections.add("## Documents\n" + ragResult.getOutput());
                if (ragResult.getMetadata() != null && ragResult.getMetadata().containsKey("resultCount")) {
                    totalResults += ((Number) ragResult.getMetadata().get("resultCount")).intValue();
                }
            }
        } catch (Exception ignored) {}

        // 3. Knowledge graph (requires kompile-app + Neo4j)
        try {
            ToolResult graphResult = executeGraph(params, context);
            if (graphResult.getOutput() != null && !graphResult.getOutput().contains("No graph results")
                    && !graphResult.getOutput().contains("Cannot connect")
                    && !graphResult.getOutput().contains("requires a running")) {
                sections.add("## Knowledge Graph\n" + graphResult.getOutput());
                if (graphResult.getMetadata() != null && graphResult.getMetadata().containsKey("entityCount")) {
                    totalResults += ((Number) graphResult.getMetadata().get("entityCount")).intValue();
                }
            }
        } catch (Exception ignored) {}

        if (sections.isEmpty()) {
            return ToolResult.success("No results found across any backend for: " + query);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Search results for: \"").append(query).append("\" (")
                .append(totalResults).append(" total results across ")
                .append(sections.size()).append(" source(s))\n\n");
        for (String section : sections) {
            sb.append(section).append("\n\n");
        }

        return ToolResult.success("search: " + query, sb.toString(),
                Map.of("query", query, "totalResults", totalResults,
                        "sourcesSearched", sections.size()));
    }
}
