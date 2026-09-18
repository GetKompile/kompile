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
import java.util.Locale;
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
 *
 * <p>Loop guard: activation is idempotent and reported honestly. Repeated
 * activation of an already-active group returns a success answer that says the
 * tools are already loaded, never a fresh "Activated N tools" progress report
 * and never an error — and after several attempts the guard escalates to an
 * explicit instruction to stop activating and continue the task with the
 * already-visible tools.
 */
public class ActivateToolsTool implements CliTool {

    /** Identical repeat attempts before the reply escalates to an explicit stop instruction. */
    private static final int MAX_IDENTICAL_ACTIVATIONS = 3;
    /** Total activation attempts before the reply escalates to an explicit stop instruction. */
    private static final int MAX_TOTAL_ACTIVATIONS = 6;
    /**
     * Attempts beyond the budget before the harness hard-blocks activations for the rest of
     * the session. The escalation reply is always sent first, so a block here is never a
     * surprise — it enforces the instruction the model already received.
     */
    private static final int BLOCK_AFTER_IDENTICAL = 5;
    private static final int BLOCK_AFTER_TOTAL = 10;

    /** Historical group names seen in the wild; mapped instead of erroring. */
    private static final Map<String, String> GROUP_ALIASES = Map.of(
            "search", "code",
            "network", "web",
            "todo", "workflow",
            "tools", "all");

    private final DynamicToolManager toolManager;

    public ActivateToolsTool(DynamicToolManager toolManager) {
        this.toolManager = toolManager;
    }

    @Override
    public String id() { return "activate_tools"; }

    @Override
    public String description() {
        List<String> groups = toolManager.availableGroupNames();
        if (!toolManager.isDynamicMode()) {
            // MCP stdio: the full catalog is always listed, so activation cannot add
            // anything. Saying so up front stops agents from "unlocking" tools that
            // are already in their toolset.
            return "Tool-group discovery helper. All tools are already listed in your toolset — "
                    + "activation is a no-op. Use action='list' to see group names, or 'describe' "
                    + "to preview a group's tools. Never needed to unlock a tool you can call.";
        }
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
        String action = params.path("action").asText("list").trim().toLowerCase(Locale.ROOT);
        String group = normalizeGroup(params.path("group").asText("").trim().toLowerCase(Locale.ROOT));

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

            case "enable" -> ToolResult.error("Unknown action: enable. "
                    + "The activation action is 'activate' (action='activate', group='" + group + "').");

            case "activate" -> {
                if (group.isEmpty()) {
                    yield ToolResult.error("group is required for activate action");
                }
                yield activate(context, group);
            }

            default -> ToolResult.error("Unknown action: " + action + ". Use list, describe, or activate.");
        };
    }

    /**
     * Activation with loop protection. Every attempt is recorded per session:
     * <ol>
     *   <li>Unknown groups fail once with the valid names — as before.</li>
     *   <li>Already-active or no-tool groups succeed with an honest "nothing new to load"
     *       answer instead of a repeated progress report, so the model is never rewarded
     *       with fresh-looking "Activated N tools" output for a no-op.</li>
     *   <li>Once a session exceeds the repeat or total-activation budget, the reply stops
     *       the loop explicitly: no more group changes are possible, continue the task.</li>
     * </ol>
     */
    private ToolResult activate(ToolContext context, String group) {
        String sessionId = context == null ? "(anonymous)" : context.getSessionId();
        int groupAttempts = toolManager.activationCount(sessionId, group);
        int sessionTotal = toolManager.totalActivations(sessionId);

        // Hard block: the session was already escalated for this exact repeat and kept going.
        // The block makes the loop physically impossible instead of relying on the model
        // obeying the stop instruction.
        if (toolManager.isActivationFrozen(sessionId)
                || groupAttempts >= BLOCK_AFTER_IDENTICAL || sessionTotal >= BLOCK_AFTER_TOTAL) {
            toolManager.freezeActivations(sessionId);
            return ToolResult.error(
                    "Blocked: this session has attempted tool activation "
                            + Math.max(groupAttempts, sessionTotal)
                            + " times. The harness has frozen activation for the rest of the "
                            + "session; every group it can load is already loaded. Do not retry. "
                            + "Continue the task with the current tools, or finish and report "
                            + "what you are missing.");
        }

        DynamicToolManager.ActivationResult result = "all".equals(group)
                ? toolManager.activateAllWithStatus()
                : toolManager.activateGroupWithStatus(group);
        sessionTotal = toolManager.recordActivation(sessionId, group);
        groupAttempts = toolManager.activationCount(sessionId, group);
        String stopInstruction = " STOP activating tool groups: this is a repeated no-op. "
                + "No further activation can change your toolset. Continue the task with the "
                + "tools already available, or finish and report what you are missing.";

        switch (result.status()) {
            case UNKNOWN -> {
                String names = String.join("|", toolManager.availableGroupNames());
                return ToolResult.error("Unknown group: " + group
                        + ". Valid groups: " + names
                        + ". Use action='list' to see descriptions.");
            }
            case ACTIVATED -> {
                String base = "Activated " + result.added().size() + " tools: "
                        + String.join(", ", result.added())
                        + "\nThese tools are available on the next model step.";
                // Still escalating when a session keeps re-activating (even between groups):
                // the budget is spent, so the next attempt cannot produce anything new.
                if (sessionTotal > MAX_TOTAL_ACTIVATIONS) {
                    return ToolResult.success("already active: " + group, base + stopInstruction,
                            Map.of("group", group, "toolsAdded", result.added().size(),
                                    "activationAttempts", sessionTotal, "loopGuard", true));
                }
                return ToolResult.success("activated: " + group, base,
                        Map.of("group", group, "toolsAdded", result.added().size()));
            }
            case ALREADY_ACTIVE -> {
                String visible = describeActiveState(group);
                String base = "Group '" + group + "' is already active. " + visible
                        + " Re-activating changes nothing.";
                if (groupAttempts >= MAX_IDENTICAL_ACTIVATIONS
                        || sessionTotal > MAX_TOTAL_ACTIVATIONS) {
                    return ToolResult.success("already active: " + group, base + stopInstruction,
                            Map.of("group", group, "toolsAdded", 0,
                                    "activationAttempts", sessionTotal, "loopGuard", true));
                }
                return ToolResult.success("already active: " + group, base,
                        Map.of("group", group, "toolsAdded", 0));
            }
            case NO_TOOLS_IN_SESSION -> {
                String base = "Group '" + group + "' is known but registers no tools in this "
                        + "session (it is valid — it would activate nothing here). "
                        + describeActiveState(group);
                if (groupAttempts >= MAX_IDENTICAL_ACTIVATIONS
                        || sessionTotal > MAX_TOTAL_ACTIVATIONS) {
                    return ToolResult.success("no-op: " + group, base + stopInstruction,
                            Map.of("group", group, "toolsAdded", 0,
                                    "activationAttempts", sessionTotal, "loopGuard", true));
                }
                return ToolResult.success("no-op: " + group, base,
                        Map.of("group", group, "toolsAdded", 0));
            }
        }
        return ToolResult.error("Unhandled activation status: " + result.status());
    }

    /**
     * Plain statement of what the session currently exposes, so "already active" answers
     * carry evidence instead of asking the model to call list again (which feeds loops).
     */
    private String describeActiveState(String group) {
        int count = toolManager.getActiveToolIds().size();
        String remaining = toolManager.availableGroupNames().stream()
                .filter(name -> !toolManager.isGroupActive(name))
                .filter(name -> !"all".equals(name))
                .toList().toString();
        return "Your toolset now exposes " + count + " tools; groups not yet activated: "
                + remaining + ".";
    }

    /**
     * Map historical group vocabulary onto current names so an old prompt or an
     * agent trained on earlier group names gets a working activation instead of
     * an error it will retry.
     */
    private static String normalizeGroup(String group) {
        if (group == null || group.isBlank()) return "";
        return GROUP_ALIASES.getOrDefault(group, group);
    }
}
