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

import java.nio.file.*;
import java.util.*;

/**
 * MCP tool for controlling the ambient memory gardener.
 * Actions: run (immediate garden cycle), status, start, stop, reports.
 */
public class AmbientGardenTool implements CliTool {

    private final AmbientMemoryGardener gardener;

    public AmbientGardenTool(AmbientMemoryGardener gardener) {
        this.gardener = gardener;
    }

    @Override
    public String id() { return "ambient_garden"; }

    @Override
    public String description() {
        return "Control the ambient memory gardener -- a background service that maintains "
             + "memory health by detecting duplicates, pruning stale entries, finding conflicts, "
             + "and suggesting consolidations. "
             + "Actions: 'run' (immediate garden cycle), 'status' (gardener state), "
             + "'start' (start background gardening), 'stop' (stop gardening), "
             + "'reports' (view recent garden reports).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: run, status, start, stop, reports");

        ObjectNode interval = props.putObject("interval_minutes");
        interval.put("type", "integer");
        interval.put("description", "Interval for background gardening in minutes (default 30, for start action)");

        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "Max reports to return (default 5, for reports action)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "memory"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Ambient memory gardening");

        String action = params.path("action").asText("");

        switch (action) {
            case "run": {
                AmbientMemoryGardener.GardenReport report = gardener.gardenNow();
                return ToolResult.success("ambient_garden: report", report.toString(),
                    Map.of("issues", report.hasIssues(), "totalFiles", report.totalFiles));
            }

            case "status":
                return ToolResult.success("ambient_garden: status",
                    "Running: " + gardener.isRunning() + "\n"
                    + "Reports: " + gardener.getReports(100).size());

            case "start": {
                int interval = params.path("interval_minutes").asInt(30);
                gardener.start(interval);
                return ToolResult.success("ambient_garden: started",
                    "Ambient gardening started with " + interval + " minute interval.");
            }

            case "stop":
                gardener.stop();
                return ToolResult.success("ambient_garden: stopped", "Ambient gardening stopped.");

            case "reports": {
                int limit = params.path("limit").asInt(5);
                List<AmbientMemoryGardener.GardenReport> reports = gardener.getReports(limit);
                if (reports.isEmpty()) {
                    return ToolResult.success("ambient_garden: reports", "No garden reports yet. Run 'run' action first.");
                }
                StringBuilder sb = new StringBuilder();
                for (AmbientMemoryGardener.GardenReport r : reports) {
                    sb.append(r).append("\n");
                }
                return ToolResult.success("ambient_garden: reports", sb.toString());
            }

            default:
                return ToolResult.error("Unknown action: " + action);
        }
    }
}
