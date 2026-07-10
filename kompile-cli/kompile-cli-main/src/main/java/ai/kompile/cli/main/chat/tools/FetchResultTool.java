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
        return "Read a large tool result that was cached instead of returned inline. When a tool's " +
                "output is big, the system stores the full result and hands back a summary + result_id " +
                "(this is normal, not an error). Call fetch_result with that result_id to read the " +
                "already-computed result — page with offset (1-based line) and limit, or pass a pattern " +
                "to return only matching lines (grep over the cached result) so you pull just what you " +
                "need. Prefer this over re-running the tool or falling back to bash to dodge the handle — " +
                "that repeats work and can miss data; only re-run when you genuinely need a narrower or " +
                "different query.";
    }

    @Override
    public String compactHint() {
        return "READ a large cached result (not an error) by result_id — don't re-run or use bash. "
                + "offset=1-based line; limit=lines(200); pattern=<regex> filters to matching lines "
                + "(grep it). Expires ~15min.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode resultId = props.putObject("result_id");
        resultId.put("type", "string");
        resultId.put("description", "The reference handle returned by a previous tool call");

        ObjectNode offset = props.putObject("offset");
        offset.put("type", "integer");
        offset.put("description", "Starting line to retrieve (1-based, like the read tool). Default: 1.");

        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "Maximum lines to return. Default: 200.");

        ObjectNode pattern = props.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "Optional: return only lines matching this regex (case-insensitive; "
                + "literal substring if not a valid regex), like grep over the cached result — each match "
                + "is prefixed with its 1-based line number. Use it to pull just the relevant lines instead "
                + "of paging the whole result.");

        schema.putArray("required").add("result_id");
        return schema;
    }

    @Override
    public String permissionKey() { return "read"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Fetch cached result");

        String resultId = params.path("result_id").asText("");
        if (resultId.isEmpty()) {
            return ToolResult.error("result_id is required");
        }

        int offset = params.path("offset").asInt(1);
        if (offset < 1) offset = 1;
        int limit = params.path("limit").asInt(200);
        String pattern = params.path("pattern").asText("");

        // Public contract is a 1-based line offset (consistent with the read tool); the cache
        // slice primitive is 0-based, so convert here. A blank pattern means a plain slice.
        return cache.getSlice(resultId, offset - 1, limit, pattern.isBlank() ? null : pattern);
    }
}
