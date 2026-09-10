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

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.main.chat.tools.BashTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic policy for low-risk tools that a probabilistic judge must not
 * reinterpret as mutations.
 *
 * <p>These calls are low-risk, session-local bookkeeping and should not pay for, or be
 * second-guessed by, a probabilistic LLM review. Explicit tool/command bans still win,
 * so a user can deliberately prohibit one of these tools without relying on the model.</p>
 */
public final class JudgeToolPolicy {

    private static final Set<String> ROUTINE_SESSION_TOOLS = Set.of("todoread", "todowrite");
    private static final Set<String> EXPLICIT_TOOL_RULE_PREFIXES = Set.of(
            "BAN_TOOL:", "STOP_TOOL:", "BAN_CMD:", "BAN_CMD_REGEX:", "STOP_CMD:");

    private JudgeToolPolicy() {}

    public static boolean isRoutineSessionTool(String toolName) {
        return ROUTINE_SESSION_TOOLS.contains(canonicalToolName(toolName));
    }

    /**
     * Return a deterministic decision for a routine tool, or {@code null} when the tool
     * should continue through normal judge evaluation.
     */
    public static EnforcerToolCallDecision evaluateRoutineTool(
            String toolName, String toolInput, EnforcerPolicy policy, ObjectMapper objectMapper) {
        if (!isRoutineSessionTool(toolName)) {
            return null;
        }

        EnforcerToolCallDecision explicitDecision = evaluateExplicitRules(
                toolName, toolInput, policy, objectMapper);
        if (explicitDecision != null) return explicitDecision;

        return EnforcerToolCallDecision.allow(
                "Routine session bookkeeping is allowed because no explicit tool or command ban matched");
    }

    /**
     * Return a deterministic decision for a recognized read-only Git inspection.
     * General natural-language cautions about Git mutations must not turn {@code git diff},
     * {@code git status}, or equivalent queries into writes. Structured tool/command bans
     * still win, and unknown or mutating Git forms continue through normal judge review.
     */
    public static EnforcerToolCallDecision evaluateReadOnlyGitTool(
            String toolName, String toolInput, EnforcerPolicy policy, ObjectMapper objectMapper) {
        if (!ShellMandatePolicy.isShellTool(toolName)) return null;
        String command = ShellMandatePolicy.extractCommandFromJson(toolInput);
        if (!BashTool.isReadOnlyGitCommand(command)) return null;

        EnforcerToolCallDecision mandate = ShellMandatePolicy.evaluateCommand(toolName, command);
        if (mandate != null) return mandate;
        EnforcerToolCallDecision explicitDecision = evaluateExplicitRules(
                toolName, toolInput, policy, objectMapper);
        if (explicitDecision != null) return explicitDecision;
        return EnforcerToolCallDecision.allow(
                "Read-only Git inspection is non-mutating and no explicit tool or command ban matched");
    }

    static String canonicalToolName(String toolName) {
        if (toolName == null) {
            return "";
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        int namespaced = normalized.lastIndexOf("__");
        if (namespaced >= 0 && namespaced + 2 < normalized.length()) {
            normalized = normalized.substring(namespaced + 2);
        }
        return normalized;
    }

    private static EnforcerToolCallDecision evaluateExplicitRules(
            String toolName, String toolInput, EnforcerPolicy policy, ObjectMapper objectMapper) {
        String explicitRules = explicitToolRules(policy);
        if (explicitRules.isBlank()) return null;
        EnforcerPolicy deterministicPolicy = new EnforcerPolicy(
                explicitRules,
                policy != null ? policy.getMaxCorrections() : EnforcerPolicy.DEFAULT_MAX_CORRECTIONS,
                false);
        KeywordEnforcerEvaluator evaluator = KeywordEnforcerEvaluator.fromPolicy(
                deterministicPolicy, objectMapper);
        EnforcerToolCallDecision decision = evaluator.evaluateToolCall(
                canonicalToolName(toolName), toolInput, deterministicPolicy);
        return decision.isAllowed() ? null : decision;
    }

    private static String explicitToolRules(EnforcerPolicy policy) {
        if (policy == null || !policy.hasRules()) {
            return "";
        }
        String rules = policy.getRules().trim();
        if (rules.startsWith("[") || rules.startsWith("{")) {
            // JSON rules carry an explicit scope field; preserve them for deterministic parsing.
            return rules;
        }
        return rules.lines()
                .map(String::trim)
                .filter(line -> {
                    String upper = line.toUpperCase(Locale.ROOT);
                    return EXPLICIT_TOOL_RULE_PREFIXES.stream().anyMatch(upper::startsWith);
                })
                .collect(Collectors.joining("\n"));
    }
}
