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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tool that the LLM calls to signal it has finished planning and is ready
 * to execute. When planning mode is active, this tool is injected into the
 * tool definitions. Calling it sets a flag that the agentic loop checks to
 * transition from planning to execution.
 */
public class ExitPlanModeTool implements CliTool {

    private final AtomicBoolean planApproved = new AtomicBoolean(false);

    @Override
    public String id() { return "exit_plan_mode"; }

    @Override
    public String description() {
        return "Signal that planning is complete and you are ready to execute. "
                + "Call this tool when you have finished analyzing the task and created a plan. "
                + "The user will be asked to approve the plan before execution begins. "
                + "Do NOT call this until your plan is fully formed and you have used todowrite to track all planned steps.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode summary = props.putObject("plan_summary");
        summary.put("type", "string");
        summary.put("description", "A concise summary of the plan (1-3 sentences)");

        schema.putArray("required").add("plan_summary");
        return schema;
    }

    @Override
    public String permissionKey() { return "exit_plan_mode"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String summary = params.path("plan_summary").asText("");
        planApproved.set(true);
        return ToolResult.success("Plan submitted for approval: " + summary);
    }

    public boolean isPlanApproved() {
        return planApproved.get();
    }

    public void reset() {
        planApproved.set(false);
    }
}
