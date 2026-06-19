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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP tool annotations that hint to clients about a tool's behavior.
 * These map to the MCP spec's {@code annotations} object on tool definitions.
 *
 * @param readOnlyHint     true if the tool does not modify state
 * @param destructiveHint  true if the tool may perform destructive operations
 * @param idempotentHint   true if calling the tool multiple times with the same args has the same effect
 * @param openWorldHint    true if the tool interacts with external entities beyond the local environment
 */
public record McpToolAnnotations(
        boolean readOnlyHint,
        boolean destructiveHint,
        boolean idempotentHint,
        boolean openWorldHint
) {
    /** Read-only tools: grep, glob, list, read, etc. */
    public static final McpToolAnnotations READ_ONLY =
            new McpToolAnnotations(true, false, true, false);

    /** Tools that write to local state but are not destructive: write, edit, patch. */
    public static final McpToolAnnotations WRITE =
            new McpToolAnnotations(false, false, false, false);

    /** Tools that may destroy data or have irreversible effects: bash, rm, etc. */
    public static final McpToolAnnotations DESTRUCTIVE =
            new McpToolAnnotations(false, true, false, false);

    /** Tools that interact with external services: webfetch, websearch, etc. */
    public static final McpToolAnnotations NETWORK =
            new McpToolAnnotations(false, false, false, true);

    /** Tools that delegate to external agents or spawn subprocesses. */
    public static final McpToolAnnotations DELEGATION =
            new McpToolAnnotations(false, false, false, true);

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    /**
     * Convert to a JSON ObjectNode for inclusion in MCP tools/list response.
     */
    public ObjectNode toJsonNode() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("readOnlyHint", readOnlyHint);
        node.put("destructiveHint", destructiveHint);
        node.put("idempotentHint", idempotentHint);
        node.put("openWorldHint", openWorldHint);
        return node;
    }
}
