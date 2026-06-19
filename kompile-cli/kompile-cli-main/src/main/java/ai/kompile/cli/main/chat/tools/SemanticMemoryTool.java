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

import java.util.List;
import java.util.Map;

/**
 * MCP tool for explicitly querying the semantic memory index.
 *
 * <p>Unlike the passive injection (which happens automatically per turn),
 * this tool allows agents to do targeted semantic searches across memories.
 * It wraps a {@link SemanticMemoryEngine} instance that is shared with the
 * passive-injection layer so both operate on the same index.
 *
 * <p>Supported actions:
 * <ul>
 *   <li><b>search</b> — vector similarity search, returns top-K results above threshold</li>
 *   <li><b>stats</b> — print index size and vocabulary statistics</li>
 *   <li><b>index_turn</b> — explicitly add a conversation turn to the index</li>
 * </ul>
 */
public class SemanticMemoryTool implements CliTool {

    private final SemanticMemoryEngine engine;

    public SemanticMemoryTool(SemanticMemoryEngine engine) {
        this.engine = engine;
    }

    @Override
    public String id() { return "semantic_memory"; }

    @Override
    public String description() {
        return "Search the semantic memory index for memories related to a query. "
             + "Uses kompile's SameDiff encoder (bge-base-en-v1.5, 768-dim dense embeddings) "
             + "when available, with TF-IDF cosine fallback. Searches across all indexed "
             + "memories (memory files, session turns, knowledge graph entries). "
             + "Use this for targeted recall when you need specific information "
             + "from past interactions or stored knowledge. "
             + "Parameters: query (required string), top_k (optional int, default 5), "
             + "threshold (optional number 0-1, default 0.15), "
             + "action: 'search' (default), 'stats' (show index stats), "
             + "'index_turn' (add a turn to the index).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: search, stats, or index_turn");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "The search query text");

        ObjectNode topK = props.putObject("top_k");
        topK.put("type", "integer");
        topK.put("description", "Number of results to return (default 5)");

        ObjectNode threshold = props.putObject("threshold");
        threshold.put("type", "number");
        threshold.put("description", "Minimum similarity threshold 0-1 (default 0.15)");

        ObjectNode content = props.putObject("content");
        content.put("type", "string");
        content.put("description", "Content to index (for index_turn action)");

        ObjectNode role = props.putObject("role");
        role.put("type", "string");
        role.put("description", "Role for the turn: user, assistant, etc. (for index_turn action)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "memory"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Semantic memory access");

        String action = params.path("action").asText("search");

        switch (action) {
            case "search": {
                String query = params.path("query").asText("");
                if (query.isEmpty()) {
                    return ToolResult.error("query is required for search action");
                }
                int topK = params.path("top_k").asInt(5);
                double threshold = params.path("threshold").asDouble(0.15);

                List<SemanticMemoryEngine.RetrievedMemory> results =
                        engine.query(query, topK, threshold);

                if (results.isEmpty()) {
                    return ToolResult.success("semantic_memory: search",
                            "No relevant memories found for: " + query);
                }

                StringBuilder sb = new StringBuilder();
                sb.append("Found ").append(results.size()).append(" relevant memories:\n\n");
                for (int i = 0; i < results.size(); i++) {
                    SemanticMemoryEngine.RetrievedMemory rm = results.get(i);
                    sb.append(String.format("%d. [similarity=%.3f] %s (%s)\n",
                            i + 1, rm.similarity, rm.entry.name, rm.entry.type));
                    sb.append("   ")
                      .append(rm.entry.content.replace("\n", "\n   "))
                      .append("\n\n");
                }
                return ToolResult.success("semantic_memory: search", sb.toString(),
                        Map.of("count", results.size(), "query", query));
            }

            case "stats": {
                return ToolResult.success("semantic_memory: stats", engine.stats());
            }

            case "index_turn": {
                String content = params.path("content").asText("");
                String role = params.path("role").asText("user");
                if (content.isEmpty()) {
                    return ToolResult.error("content is required for index_turn action");
                }

                engine.indexTurn(context.getSessionId(),
                        (int) (System.currentTimeMillis() % 100000), role, content);
                return ToolResult.success("semantic_memory: indexed",
                        "Turn indexed. Total memories: " + engine.size());
            }

            default:
                return ToolResult.error("Unknown action: " + action
                        + ". Use search, stats, or index_turn.");
        }
    }
}
