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
 * Meta-tool that lets the LLM discover and activate tool groups on demand.
 *
 * <p>In dynamic mode, only core tools (read, write, edit, grep, glob, bash, list)
 * are listed by default. This tool exposes the available tool groups and lets
 * the LLM activate them when needed — reducing the default tool schema from
 * ~7000 tokens to ~2000 tokens.
 *
 * <p>Actions:
 * <ul>
 *   <li>{@code list} — show available tool groups and their status</li>
 *   <li>{@code describe} — show the tools in a specific group</li>
 *   <li>{@code activate} — activate a group (or 'all' for everything)</li>
 * </ul>
 */
public class ActivateToolsTool implements CliTool {

    private final DynamicToolManager toolManager;

    public ActivateToolsTool(DynamicToolManager toolManager) {
        this.toolManager = toolManager;
    }

    @Override
    public String id() { return "activate_tools"; }

    @Override
    public String description() {
        return "Discover and activate additional tool groups. " +
                "Use action='list' to see available groups, " +
                "action='describe' with group name for details, " +
                "action='activate' with group name (or 'all') to enable tools.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "list, describe, or activate");

        ObjectNode group = props.putObject("group");
        group.put("type", "string");
        group.put("description", "Group name for describe/activate (or 'all')");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "read"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String action = params.path("action").asText("list");
        String group = params.path("group").asText("");

        return switch (action) {
            case "list" -> ToolResult.success("tool_groups",
                    toolManager.describeAvailableGroups());

            case "describe" -> {
                if (group.isEmpty()) {
                    yield ToolResult.error("group is required for describe action");
                }
                yield ToolResult.success("tool_group: " + group,
                        toolManager.describeGroup(group));
            }

            case "activate" -> {
                if (group.isEmpty()) {
                    yield ToolResult.error("group is required for activate action");
                }
                List<String> added;
                if ("all".equalsIgnoreCase(group)) {
                    added = toolManager.activateAll();
                } else {
                    added = toolManager.activateGroup(group);
                }
                if (added.isEmpty()) {
                    yield ToolResult.error("Unknown group: " + group + ". Use action='list' to see available groups.");
                }
                yield ToolResult.success("activated: " + group,
                        "Activated " + added.size() + " tools: " + String.join(", ", added) +
                                "\n\nThese tools are now available. Call tools/list to see the updated list.",
                        Map.of("group", group, "toolsAdded", added.size()));
            }

            default -> ToolResult.error("Unknown action: " + action + ". Use list, describe, or activate.");
        };
    }
}
