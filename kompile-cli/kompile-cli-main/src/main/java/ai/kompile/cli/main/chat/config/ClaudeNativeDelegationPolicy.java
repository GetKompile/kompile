/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Denies Claude Code's native subagent tools (Task/Agent) before they run, so a
 * delegation is routed through Kompile's task tool instead. The native tools
 * launch a child the harness never sees: no panel row, no streamed progress, no
 * stop control, no usage or tool-call record, no delegation-depth guard.
 * Kompile's task tool provides all of that, so the deny reason tells the model
 * to re-issue the same delegation there.
 *
 * <p>Applied by Claude's PreToolUse hook; the deny reason is the text the model
 * reads when its call is refused. Depth 1+ launches already remove Task/Agent
 * with {@code --disallowedTools}; this hook closes the depth 0 chat session,
 * where the native tools are still in the model's context.</p>
 */
final class ClaudeNativeDelegationPolicy {
    static final String CALLBACK_ID = "kompile-native-delegation-v1";
    static final String MATCHER = "^(Task|Agent)$";
    private final ObjectMapper mapper;
    /** The session is itself a Kompile subagent: there is no delegation to redirect to. */
    private final boolean subagent;

    ClaudeNativeDelegationPolicy(ObjectMapper mapper) {
        this(mapper, false);
    }

    ClaudeNativeDelegationPolicy(ObjectMapper mapper, boolean subagent) {
        this.mapper = mapper;
        this.subagent = subagent;
    }

    /** Unknown shapes and other tools pass through untouched: no decision is issued. */
    ObjectNode deny(JsonNode event) {
        ObjectNode unchanged = mapper.createObjectNode();
        if (!"PreToolUse".equals(event.path("hook_event_name").asText())) return unchanged;
        String tool = event.path("tool_name").asText("");
        if (!"Task".equals(tool) && !"Agent".equals(tool)) return unchanged;
        ObjectNode specific = unchanged.putObject("hookSpecificOutput");
        specific.put("hookEventName", "PreToolUse");
        specific.put("permissionDecision", "deny");
        if (subagent) {
            specific.put("permissionDecisionReason", "BLOCKED: '" + tool + "' is unavailable inside a "
                    + "Kompile subagent. Subagents must not launch other agents, natively or through "
                    + "Kompile's task tools; do the work directly with your own tools and report the result.");
            return unchanged;
        }
        specific.put("permissionDecisionReason", "BLOCKED: '" + tool + "' is Claude Code's native subagent tool; "
                + "it launches a child outside Kompile's harness with no panel row, progress stream, stop "
                + "control, usage accounting, tool-call tracking, or delegation-depth guard. Delegate with "
                + "Kompile's 'task' tool instead (mcp__kompile__task): pass this same work as its prompt; "
                + "resolve_model can pick provider/model. Do not retry the native call.");
        return unchanged;
    }
}
