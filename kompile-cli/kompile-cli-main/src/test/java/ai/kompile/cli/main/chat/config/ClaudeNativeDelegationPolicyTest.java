/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeNativeDelegationPolicyTest {
    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final ClaudeNativeDelegationPolicy policy = new ClaudeNativeDelegationPolicy(mapper);

    private JsonNode event(String tool) throws Exception {
        return mapper.readTree("""
                {"hook_event_name":"PreToolUse","tool_name":"%s","tool_use_id":"toolu-1",
                 "tool_input":{"prompt":"search the code","subagent_type":"explore-quick"}}
                """.formatted(tool));
    }

    @Test
    void taskAndAgentAreDeniedWithARoutingReason() throws Exception {
        for (String tool : new String[]{"Task", "Agent"}) {
            JsonNode decision = policy.deny(event(tool)).path("hookSpecificOutput");
            assertEquals("PreToolUse", decision.path("hookEventName").asText());
            assertEquals("deny", decision.path("permissionDecision").asText());
            String reason = decision.path("permissionDecisionReason").asText();
            assertTrue(reason.contains("'" + tool + "'"), reason);
            assertTrue(reason.contains("mcp__kompile__task"), reason);
            assertTrue(reason.startsWith("BLOCKED:"), reason);
            assertFalse(decision.has("updatedToolOutput"), "a deny decides; it does not rewrite output");
        }
    }

    @Test
    void kompileTaskItselfAndOtherToolsPassThrough() throws Exception {
        for (String tool : new String[]{"mcp__kompile__task", "Read", "Bash", "Grep", "WebFetch"}) {
            JsonNode out = policy.deny(event(tool));
            assertFalse(out.has("hookSpecificOutput"), tool + " must not be denied");
        }
    }

    @Test
    void nonPreToolUseEventsAndUnknownShapesPassThrough() throws Exception {
        JsonNode post = mapper.readTree(
                "{\"hook_event_name\":\"PostToolUse\",\"tool_name\":\"Task\",\"tool_response\":{}}");
        assertFalse(policy.deny(post).has("hookSpecificOutput"));
        JsonNode unshaped = mapper.readTree("{\"unexpected\":true}");
        assertFalse(policy.deny(unshaped).has("hookSpecificOutput"));
    }

    @Test
    void aSubagentSessionIsDeniedWithoutBeingRoutedToKompileTask() throws Exception {
        ClaudeNativeDelegationPolicy child = new ClaudeNativeDelegationPolicy(mapper, true);
        for (String tool : new String[]{"Task", "Agent"}) {
            JsonNode decision = child.deny(event(tool)).path("hookSpecificOutput");
            assertEquals("deny", decision.path("permissionDecision").asText());
            String reason = decision.path("permissionDecisionReason").asText();
            assertTrue(reason.startsWith("BLOCKED: '" + tool + "'"), reason);
            assertTrue(reason.contains("Kompile subagent"), reason);
            assertFalse(reason.contains("mcp__kompile__task"),
                    "a subagent must not be told to delegate through Kompile instead: " + reason);
        }
    }
}
