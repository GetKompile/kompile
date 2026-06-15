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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Retrieves full or sliced content from a previously stored reference handle.
 *
 * <p>When a tool output exceeds the compression threshold, it is stored in the
 * {@link ToolResultReferenceCache} and the agent receives a compact summary
 * with a {@code result_id}. This tool lets the agent retrieve the full content
 * (or a slice) when needed — keeping large payloads out of the context window
 * until they're actually required.
 *
 * <p>Based on the MCP resource reference pattern (Cloudflare Code Mode, arxiv 2511.22729)
 * which achieves 86-94% context reduction by routing data tool-to-tool via handles
 * rather than through the LLM context.
 */
public class FetchResultTool implements CliTool {

    private final ToolResultReferenceCache cache;

    public FetchResultTool(ToolResultReferenceCache cache) {
        this.cache = cache;
    }

    @Override
    public String id() { return "fetch_result"; }

    @Override
    public String description() {
        return "Retrieve a previously cached tool result by its reference handle (result_id). " +
                "When tool outputs are large, they are stored and a summary + result_id is returned. " +
                "Use this tool to fetch the full content or a specific slice (offset/limit by lines) " +
                "when you need the complete data.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode resultId = props.putObject("result_id");
        resultId.put("type", "string");
        resultId.put("description", "The reference handle returned by a previous tool call");

        ObjectNode offset = props.putObject("offset");
        offset.put("type", "integer");
        offset.put("description", "Starting line to retrieve (0-based). Default: 0.");

        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "Maximum lines to return. Default: 200.");

        schema.putArray("required").add("result_id");
        return schema;
    }

    @Override
    public String permissionKey() { return "read"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Fetch cached result");

        String resultId = params.path("result_id").asText("");
        if (resultId.isEmpty()) {
            return ToolResult.error("result_id is required");
        }

        int offset = params.path("offset").asInt(0);
        int limit = params.path("limit").asInt(200);

        return cache.getSlice(resultId, offset, limit);
    }
}
