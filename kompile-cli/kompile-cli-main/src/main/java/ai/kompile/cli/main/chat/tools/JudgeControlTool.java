package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.enforcer.JudgeControl;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Operator-facing session controls shared by native chat and the MCP transports. */
public final class JudgeControlTool implements CliTool {
    private static final Set<String> ACTIONS = Set.of("status", "feedback", "clear_feedback",
            "enable", "disable", "approve", "cancel_approval", "override", "cancel_override");
    private static final Set<String> FIELDS = Set.of("action", "text", "command", "pattern", "confirmed");

    @Override public String id() { return "judge_control"; }
    @Override public String permissionKey() { return "read"; }
    @Override public String description() {
        return "Inspect or change the calling session's judge controls: status, feedback, clear_feedback, "
                + "enable, disable, approve, cancel_approval, override, cancel_override. "
                + "Mutations require confirmed=true (explicit user authorization) and judge_control permission. "
                + "Delegated children cannot mutate supervision. No cross-session selector. "
                + "Approvals use exact bash commands, or pattern=true for token globs (* within an argument, final ** for trailing arguments). "
                + "One-shot controls apply to the next chat turn, or next non-control tool call in standalone MCP. "
                + "Feedback persists; other switches expire with the host session. Does not execute commands, "
                + "create a judge backend, enable a global-disabled judge, or bypass workflow, gateway, permissions or hard tool protections.";
    }
    @Override public JsonNode parameterSchema() {
        var schema = JsonUtils.standardMapper().createObjectNode();
        schema.put("type", "object").put("additionalProperties", false);
        var props = schema.putObject("properties");
        var actions = props.putObject("action").put("type", "string").putArray("enum");
        ACTIONS.stream().sorted().forEach(actions::add);
        props.putObject("text").put("type", "string").put("description", "Replacement feedback for future verdicts");
        props.putObject("command").put("type", "string").put("description", "Exact command or token pattern to approve");
        props.putObject("pattern").put("type", "boolean").put("description", "Opt into token globs; default false");
        props.putObject("confirmed").put("type", "boolean").put("description", "Required true for mutations; only with explicit user authorization");
        schema.putArray("required").add("action");
        return schema;
    }
    @Override public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        if (params == null || !params.isObject()) return ToolResult.error("Expected an object");
        var fields = params.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!FIELDS.contains(field)) return ToolResult.error("Unknown field: " + field + "; controls are calling-session only");
        }
        String action = params.path("action").asText("");
        if (!ACTIONS.contains(action)) return ToolResult.error("Unknown judge control action: " + action);
        JudgeControl control = context.getJudgeControl();
        if (control == null) return ToolResult.error("No judge control is bound to this host session");
        if (!control.getSessionId().equals(context.getSessionId())) return ToolResult.error("Judge session mismatch");
        if (!"status".equals(action)) {
            if (context.isSupervisedChild()) return ToolResult.error("Delegated children cannot modify judge controls");
            if (!params.path("confirmed").isBoolean() || !params.path("confirmed").booleanValue()) {
                return ToolResult.error("Explicit user authorization and confirmed=true are required");
            }
            if ("feedback".equals(action) && (!params.path("text").isTextual() || params.path("text").asText().isBlank())) {
                return ToolResult.error("feedback requires nonblank text; use clear_feedback to remove it");
            }
            if ("approve".equals(action) && (!params.path("command").isTextual() || params.path("command").asText().isBlank())) {
                return ToolResult.error("approve requires a nonblank command");
            }
            if (params.has("pattern") && !params.path("pattern").isBoolean()) return ToolResult.error("pattern must be boolean");
            context.checkPermission("judge_control", "Change session judge controls: " + params);
            if ("enable".equals(action) && !HarnessConfig.load(JsonUtils.standardMapper()).isJudgeGlobalEnabled()) {
                return ToolResult.error("Judge is globally disabled; session enable cannot override it");
            }
            try {
                switch (action) {
                    case "feedback" -> control.setGuidance(params.path("text").asText());
                    case "clear_feedback" -> control.clearGuidance();
                    case "enable" -> control.setEnabled(true);
                    case "disable" -> control.setEnabled(false);
                    case "approve" -> {
                        if (params.path("pattern").asBoolean(false)) control.approvePatternNext(params.path("command").asText());
                        else control.approveCommandNext(params.path("command").asText());
                    }
                    case "cancel_approval" -> control.approveCommandNext("");
                    case "override" -> control.setOverrideNext(true);
                    case "cancel_override" -> control.clearOverrideNext();
                    default -> throw new IllegalStateException(action);
                }
            } catch (IllegalArgumentException invalid) {
                return ToolResult.error(invalid.getMessage());
            }
            context.emitOutput("[judge control] " + action + " for session " + context.getSessionId());
        }
        Map<String, Object> state = new LinkedHashMap<>();
        synchronized (control) {
            state.put("sessionId", control.getSessionId());
            state.put("enabled", control.isEnabled());
            state.put("guidance", control.getGuidance());
            state.put("overrideNext", control.isOverrideNextSet());
            state.put("approvedCommand", control.getApprovedCommandNext());
            state.put("pattern", control.isApprovalPatternNext());
        }
        state.put("oneShotScope", context.isJudgeToolCallScoped() ? "next_non_control_tool_call" : "next_chat_turn");
        state.put("note", "Session settings only; enabling does not create a backend or override global policy. No command was executed.");
        return ToolResult.success("Judge controls: " + action, JsonUtils.standardMapper().valueToTree(state).toPrettyString(), state);
    }
}
