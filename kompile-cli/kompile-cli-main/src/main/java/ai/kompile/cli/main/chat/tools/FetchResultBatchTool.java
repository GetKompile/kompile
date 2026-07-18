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

import java.util.Map;

/**
 * Read MULTIPLE cached result slices in ONE tool call — several pages of one
 * handle, or slices of several handles. Each entry has exactly the same
 * parameters and semantics as {@code fetch_result} ({@link ToolResultReferenceCache}
 * slices); per-entry sections, and an expired/unknown handle reports in its
 * section without failing the rest.
 */
public class FetchResultBatchTool implements CliTool {

    private static final int MAX_REQUESTS = 20;

    private final ToolResultReferenceCache cache;

    public FetchResultBatchTool(ToolResultReferenceCache cache) {
        this.cache = cache;
    }

    @Override
    public String id() { return "fetch_result_batch"; }

    @Override
    public String description() {
        return "Read MULTIPLE cached-result slices in a single call — use this instead of several "
                + "sequential fetch_result calls (e.g. pull a pattern from three cached greps, or "
                + "several windows of one big result). Each requests[] entry takes the same "
                + "parameters as fetch_result: result_id (required), offset (1-based line), limit "
                + "(default 200), pattern (regex filter over lines). Sections come back in order; "
                + "an expired or unknown handle reports in its section without failing the rest.";
    }

    @Override
    public String compactHint() {
        return "MANY fetch_results in ONE call: requests=[{result_id,offset?,limit?,pattern?}] — "
                + "same semantics as fetch_result (1-based offset, pattern greps the cached result). "
                + "Per-entry sections; expired handles don't fail the rest.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode requests = props.putObject("requests");
        requests.put("type", "array");
        requests.put("description", "Slices to read, in order (max " + MAX_REQUESTS + ")");
        ObjectNode item = requests.putObject("items");
        item.put("type", "object");
        ObjectNode itemProps = item.putObject("properties");
        itemProps.putObject("result_id").put("type", "string")
                .put("description", "Reference handle from a previous tool call");
        itemProps.putObject("offset").put("type", "integer")
                .put("description", "Starting line (1-based). Default: 1.");
        itemProps.putObject("limit").put("type", "integer")
                .put("description", "Maximum lines. Default: 200.");
        itemProps.putObject("pattern").put("type", "string")
                .put("description", "Optional regex — return only matching lines (grep the cached result)");
        item.putArray("required").add("result_id");

        schema.putArray("required").add("requests");
        return schema;
    }

    @Override
    public String permissionKey() { return "read"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Fetch cached results");

        JsonNode requests = params.path("requests");
        if (!requests.isArray() || requests.isEmpty()) {
            return ToolResult.error("requests (non-empty array) is required");
        }
        if (requests.size() > MAX_REQUESTS) {
            return ToolResult.error("requests has " + requests.size() + " entries (max " + MAX_REQUESTS
                    + ") — split into several fetch_result_batch calls");
        }

        StringBuilder out = new StringBuilder();
        int succeeded = 0;
        int failed = 0;
        int index = 0;
        for (JsonNode request : requests) {
            index++;
            String resultId = request.path("result_id").asText("");
            String pattern = request.path("pattern").asText("");
            int offset = request.path("offset").asInt(1);
            if (offset < 1) offset = 1;
            int limit = request.path("limit").asInt(200);
            String label = "[" + index + "] " + resultId
                    + (pattern.isBlank() ? "" : " /" + pattern + "/")
                    + (offset > 1 ? " @" + offset : "");
            if (resultId.isEmpty()) {
                failed++;
                out.append("== ").append(label).append(" — ERROR: result_id is required\n\n");
                continue;
            }
            ToolResult result = cache.getSlice(resultId, offset - 1, limit,
                    pattern.isBlank() ? null : pattern);
            if (result.isError()) {
                failed++;
                out.append("== ").append(label).append(" — ERROR: ")
                        .append(result.getOutput() != null
                                ? result.getOutput().lines().findFirst().orElse("") : "")
                        .append("\n\n");
            } else {
                succeeded++;
                out.append("== ").append(label).append('\n');
                String body = result.getOutput();
                out.append(body == null || body.isBlank() ? "(empty)" : body.stripTrailing());
                out.append("\n\n");
            }
        }

        String summary = succeeded + "/" + requests.size() + " slices read"
                + (failed > 0 ? ", " + failed + " failed" : "");
        String output = summary + "\n\n" + out.toString().stripTrailing();
        if (succeeded == 0) {
            return ToolResult.error(output);
        }
        return ToolResult.success("fetch_result_batch", output,
                Map.of("requests", requests.size(), "succeeded", succeeded, "failed", failed));
    }
}
