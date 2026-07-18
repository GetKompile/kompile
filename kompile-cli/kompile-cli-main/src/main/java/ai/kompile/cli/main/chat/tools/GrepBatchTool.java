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
 * Run MULTIPLE grep queries in ONE tool call. Each entry has exactly the same
 * parameters and semantics as {@code grep} (delegation — one shared
 * {@link GrepTool} executes every entry); results come back as per-query
 * sections and one failing query never blocks the rest.
 */
public class GrepBatchTool implements CliTool {

    private static final int MAX_QUERIES = 20;

    private final GrepTool grep = new GrepTool();

    @Override
    public String id() { return "grep_batch"; }

    @Override
    public String description() {
        return "Run MULTIPLE regex content searches in a single call — use this instead of several "
                + "sequential grep calls when exploring (e.g. sweep a set of symbols or patterns at "
                + "once). Each queries[] entry takes the same parameters as grep: pattern (required, "
                + "REGEX — use [0-9] not \\\\d), path, glob, output_mode (content|files|count), "
                + "context_lines, case_insensitive, hidden. Results are returned as one section per "
                + "query, in order; a query that fails or matches nothing reports in its section "
                + "without affecting the others.";
    }

    @Override
    public String compactHint() {
        return "MANY greps in ONE call: queries=[{pattern,path?,glob?,output_mode?,context_lines?,"
                + "case_insensitive?}] — same rules as grep (pattern is REGEX, use [0-9] not \\\\d; "
                + "scope big trees with path/glob). Per-query sections; failures don't block the rest.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode queries = props.putObject("queries");
        queries.put("type", "array");
        queries.put("description", "Grep queries to run, in order (max " + MAX_QUERIES + ")");
        ObjectNode item = queries.putObject("items");
        item.put("type", "object");
        ObjectNode itemProps = item.putObject("properties");
        itemProps.putObject("pattern").put("type", "string")
                .put("description", "Regex to search for (same syntax rules as grep)");
        itemProps.putObject("path").put("type", "string")
                .put("description", "File or directory to scope the search");
        itemProps.putObject("glob").put("type", "string")
                .put("description", "Filename filter, e.g. *.java");
        ObjectNode outputMode = itemProps.putObject("output_mode");
        outputMode.put("type", "string");
        outputMode.putArray("enum").add("content").add("files").add("count");
        itemProps.putObject("context_lines").put("type", "integer")
                .put("description", "Context lines around content matches (0-20)");
        itemProps.putObject("case_insensitive").put("type", "boolean");
        itemProps.putObject("hidden").put("type", "boolean");
        item.putArray("required").add("pattern");

        schema.putArray("required").add("queries");
        return schema;
    }

    @Override
    public String permissionKey() { return "grep"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        JsonNode queries = params.path("queries");
        if (!queries.isArray() || queries.isEmpty()) {
            return ToolResult.error("queries (non-empty array) is required");
        }
        if (queries.size() > MAX_QUERIES) {
            return ToolResult.error("queries has " + queries.size() + " entries (max " + MAX_QUERIES
                    + ") — split into several grep_batch calls");
        }

        StringBuilder out = new StringBuilder();
        int succeeded = 0;
        int failed = 0;
        int index = 0;
        for (JsonNode query : queries) {
            index++;
            String pattern = query.path("pattern").asText("");
            String label = "[" + index + "] /" + pattern + "/"
                    + (query.hasNonNull("path") ? " in " + query.path("path").asText() : "")
                    + (query.hasNonNull("glob") ? " (" + query.path("glob").asText() + ")" : "");
            if (pattern.isEmpty()) {
                failed++;
                out.append("== ").append(label).append(" — ERROR: pattern is required\n\n");
                continue;
            }
            try {
                ToolResult result = grep.execute(query, context);
                if (result.isError()) {
                    failed++;
                    out.append("== ").append(label).append(" — ERROR: ")
                            .append(firstLine(result.getOutput())).append("\n\n");
                } else {
                    succeeded++;
                    out.append("== ").append(label).append('\n');
                    String body = result.getOutput();
                    out.append(body == null || body.isBlank() ? "(no matches)" : body.stripTrailing());
                    out.append("\n\n");
                }
            } catch (Exception e) {
                failed++;
                out.append("== ").append(label).append(" — ERROR: ").append(e.getMessage()).append("\n\n");
            }
        }

        String summary = succeeded + "/" + queries.size() + " queries succeeded"
                + (failed > 0 ? ", " + failed + " failed" : "");
        String output = summary + "\n\n" + out.toString().stripTrailing();
        if (succeeded == 0) {
            return ToolResult.error(output);
        }
        return ToolResult.success("grep_batch", output,
                Map.of("queries", queries.size(), "succeeded", succeeded, "failed", failed));
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        return text.lines().findFirst().orElse("");
    }
}
