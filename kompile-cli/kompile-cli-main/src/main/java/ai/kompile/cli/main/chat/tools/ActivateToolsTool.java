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
 * <p>In dynamic mode, only a compact project-work surface is listed initially.
 * This tool exposes focused capability groups and activates only the group
 * needed for the next step.
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
        List<String> groups = toolManager.availableGroupNames();
        return "Load one focused tool group when the current task needs capabilities not already shown. "
                + "Call action='activate' with group="
                + (groups.isEmpty() ? "'all'" : String.join("|", groups))
                + "; the tools appear on the next step. Use action='list' only when unsure.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.putArray("enum").add("list").add("describe").add("activate");
        action.put("description", "Use activate to load a group; list/describe are discovery helpers.");

        ObjectNode group = props.putObject("group");
        group.put("type", "string");
        var groupEnum = group.putArray("enum");
        for (String groupName : toolManager.availableGroupNames()) {
            groupEnum.add(groupName);
        }
        groupEnum.add("all");
        group.put("description", "One focused group for describe/activate; use all only when explicitly necessary.");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "read"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String action = params.path("action").asText("list").trim().toLowerCase();
        String group = params.path("group").asText("").trim().toLowerCase();

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
                                "\nThese tools are available on the next model step.",
                        Map.of("group", group, "toolsAdded", added.size()));
            }

            default -> ToolResult.error("Unknown action: " + action + ". Use list, describe, or activate.");
        };
    }
}
