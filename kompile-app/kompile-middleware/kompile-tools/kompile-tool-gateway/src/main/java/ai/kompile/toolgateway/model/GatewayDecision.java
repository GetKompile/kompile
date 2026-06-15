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

package ai.kompile.toolgateway.model;

import java.util.Map;

/**
 * The result of evaluating a tool call through the gateway.
 *
 * @param action   whether to ALLOW, REWRITE, or BLOCK the call
 * @param reason   human-readable explanation of why this decision was made
 * @param rewrittenArgs  if action is REWRITE, the replacement arguments; null otherwise
 * @param matchedRuleId  the ID of the rule that triggered this decision, or null if no rule matched (default ALLOW)
 */
public record GatewayDecision(
        GatewayAction action,
        String reason,
        Map<String, Object> rewrittenArgs,
        String matchedRuleId
) {

    /** Convenience factory for a pass-through ALLOW with no matched rule. */
    public static GatewayDecision allow() {
        return new GatewayDecision(GatewayAction.ALLOW, "No rules matched; default allow", null, null);
    }

    /** Convenience factory for an ALLOW triggered by a specific rule. */
    public static GatewayDecision allow(String reason, String ruleId) {
        return new GatewayDecision(GatewayAction.ALLOW, reason, null, ruleId);
    }

    /** Convenience factory for a BLOCK decision. */
    public static GatewayDecision block(String reason, String ruleId) {
        return new GatewayDecision(GatewayAction.BLOCK, reason, null, ruleId);
    }

    /** Convenience factory for a REWRITE decision. */
    public static GatewayDecision rewrite(String reason, String ruleId, Map<String, Object> rewrittenArgs) {
        return new GatewayDecision(GatewayAction.REWRITE, reason, rewrittenArgs, ruleId);
    }
}
