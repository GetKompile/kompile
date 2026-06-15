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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.tui.SidePanelManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

public class SidePanelTool implements CliTool {
    private final SidePanelManager sidePanelManager;

    public SidePanelTool(SidePanelManager sidePanelManager) {
        this.sidePanelManager = sidePanelManager;
    }

    public SidePanelManager getSidePanelManager() {
        return sidePanelManager;
    }

    @Override
    public String id() {
        return "side_panel";
    }

    @Override
    public String description() {
        return "Update the auxiliary side panel. Actions: show, hide, status.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("action")
                .put("type", "string")
                .put("description", "Action to perform: show, hide, status")
                .putArray("enum").add("show").add("hide").add("status");
        props.putObject("title")
                .put("type", "string")
                .put("description", "Panel title for show");
        props.putObject("content")
                .put("type", "string")
                .put("description", "Panel content for show");
        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "read";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.READ_ONLY;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) {
        String action = params.path("action").asText("status");
        switch (action) {
            case "show" -> {
                sidePanelManager.show(params.path("title").asText("Side Panel"),
                        params.path("content").asText(""));
                return ToolResult.success("side_panel",
                        "Side panel shown",
                        Map.of("visible", true, "title", sidePanelManager.getTitle()));
            }
            case "hide" -> {
                sidePanelManager.hide();
                return ToolResult.success("side_panel",
                        "Side panel hidden",
                        Map.of("visible", false));
            }
            default -> {
                return ToolResult.success("side_panel",
                        sidePanelManager.getContent(),
                        Map.of("visible", sidePanelManager.isVisible(),
                                "title", sidePanelManager.getTitle()));
            }
        }
    }
}
