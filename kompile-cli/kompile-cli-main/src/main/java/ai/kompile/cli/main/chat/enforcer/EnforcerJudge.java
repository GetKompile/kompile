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

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.utils.StringUtils;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import ai.kompile.cli.main.chat.harness.JudgeBackendFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LLM-backed enforcer judge. It evaluates a subordinate LLM turn against
 * user-authored rules and returns a machine-readable intervention decision.
 */
public class EnforcerJudge implements EnforcerEvaluator {

    private static final int MAX_PROMPT_CHARS = 4_000;
    private static final int MAX_OUTPUT_CHARS = 8_000;
    private static final int MAX_CONTEXT_CHARS = 8_000;

    /**
     * Unified system prompt for all evaluation modes (full, partial, tool-call).
     * The evaluation mode is specified in the user prompt, not the system prompt,
     * so a persistent judge subprocess can handle all modes in one session.
     */
    static final String SYSTEM_PROMPT = """
            You are Kompile Enforcer, an automated intervention judge. You evaluate whether a subordinate LLM's output follows the user's enforcer rules.

            CRITICAL: You must respond with ONLY a single JSON object. No prose, no markdown, no explanation, no code fences. Just the raw JSON.

            If compliant: {"compliant":true,"stop":false,"severity":"info","violations":[],"correction_prompt":"","reasoning":"brief reason"}
            If non-compliant: {"compliant":false,"stop":false,"severity":"error","violations":["specific violation"],"correction_prompt":"tell the subordinate exactly what to fix","reasoning":"brief reason"}
            If must stop: {"compliant":false,"stop":true,"severity":"critical","violations":["specific violation"],"correction_prompt":"","reasoning":"brief reason"}

            Rules:
            - Treat the user's enforcer rules as the sole authority
            - Be specific in violations — quote what was wrong
            - correction_prompt must be actionable for the subordinate LLM
            - Your entire response must be parseable as JSON
            - For partial/streaming output, only stop when the output has ALREADY violated rules in a way later text cannot repair
            - For MCP tool calls, use action ALLOW/BLOCK/REWRITE format when evaluating proposed tool calls
            """;

    private final ObjectMapper objectMapper;
    private final JudgeBackend backend;

    public EnforcerJudge(HarnessConfig config, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = JudgeBackendFactory.create(config, objectMapper);
        this.backend.warmUp(SYSTEM_PROMPT);
    }

    public EnforcerJudge(JudgeBackend backend, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = backend;
        this.backend.warmUp(SYSTEM_PROMPT);
    }

    @Override
    public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                     EnforcerPolicy policy, int attempt) throws Exception {
        return evaluate(userPrompt, agentOutput, policy, attempt, EnforcerConversationContext.empty());
    }

    @Override
    public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                     EnforcerPolicy policy, int attempt,
                                     EnforcerConversationContext context) throws Exception {
        if (!isAvailable()) {
            return EnforcerDecision.stop(
                    java.util.List.of("No enforcer judge backend is available"),
                    "Configure the harness judge provider/model or a local judge backend.");
        }

        String response = backend.generate(buildJudgePrompt(userPrompt, agentOutput, policy, attempt, context),
                SYSTEM_PROMPT);
        return EnforcerDecision.parse(objectMapper, response);
    }

    public EnforcerDecision evaluatePartialOutput(String userPrompt, String partialOutput,
                                                  EnforcerPolicy policy) throws Exception {
        return evaluatePartialOutput(userPrompt, partialOutput, policy, EnforcerConversationContext.empty());
    }

    public EnforcerDecision evaluatePartialOutput(String userPrompt, String partialOutput,
                                                  EnforcerPolicy policy,
                                                  EnforcerConversationContext context) throws Exception {
        if (!isAvailable()) {
            return EnforcerDecision.stop(
                    java.util.List.of("No enforcer judge backend is available"),
                    "Configure the harness judge provider/model or a local judge backend.");
        }

        String response = backend.generate(buildPartialJudgePrompt(userPrompt, partialOutput, policy, context),
                SYSTEM_PROMPT);
        return EnforcerDecision.parse(objectMapper, response);
    }

    public EnforcerToolCallDecision evaluateToolCall(String toolName, String toolInput,
                                                     EnforcerPolicy policy) throws Exception {
        return evaluateToolCall(toolName, toolInput, policy, EnforcerConversationContext.empty());
    }

    public EnforcerToolCallDecision evaluateToolCall(String toolName, String toolInput,
                                                     EnforcerPolicy policy,
                                                     EnforcerConversationContext context) throws Exception {
        if (!isAvailable()) {
            return EnforcerToolCallDecision.block("No enforcer judge backend is available");
        }

        String response = backend.generate(buildToolCallPrompt(toolName, toolInput, policy, context),
                SYSTEM_PROMPT);
        return EnforcerToolCallDecision.parse(objectMapper, response);
    }

    @Override
    public boolean isAvailable() {
        return backend != null && backend.isAvailable();
    }

    @Override
    public String describe() {
        return backend != null ? backend.describe() : "none";
    }

    public void close() {
        if (backend != null) {
            backend.close();
        }
    }

    private String buildJudgePrompt(String userPrompt, String agentOutput,
                                    EnforcerPolicy policy, int attempt,
                                    EnforcerConversationContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("[ENFORCER RULES]\n")
                .append(policy.getRules())
                .append("\n[END ENFORCER RULES]\n\n");

        prompt.append("[USER PROMPT]\n")
                .append(StringUtils.truncateWithSize(userPrompt, MAX_PROMPT_CHARS))
                .append("\n[END USER PROMPT]\n\n");

        appendRecentContext(prompt, context);

        prompt.append("[SUBORDINATE LLM RESPONSE, ATTEMPT ")
                .append(attempt)
                .append("]\n")
                .append(StringUtils.truncateWithSize(agentOutput, MAX_OUTPUT_CHARS))
                .append("\n[END SUBORDINATE LLM RESPONSE]\n\n");

        prompt.append("Evaluate only compliance with the enforcer rules. ")
                .append("When non-compliant, make correction_prompt specific enough ")
                .append("for the subordinate LLM to rewrite the answer without asking follow-up questions.");
        return prompt.toString();
    }

    private String buildPartialJudgePrompt(String userPrompt, String partialOutput,
                                           EnforcerPolicy policy,
                                           EnforcerConversationContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("[ENFORCER RULES]\n")
                .append(policy.getRules())
                .append("\n[END ENFORCER RULES]\n\n");

        prompt.append("[USER PROMPT]\n")
                .append(StringUtils.truncateWithSize(userPrompt, MAX_PROMPT_CHARS))
                .append("\n[END USER PROMPT]\n\n");

        appendRecentContext(prompt, context);

        prompt.append("[PARTIAL SUBORDINATE OUTPUT]\n")
                .append(StringUtils.truncateWithSize(partialOutput, MAX_OUTPUT_CHARS))
                .append("\n[END PARTIAL SUBORDINATE OUTPUT]\n\n");

        prompt.append("This is streamed output that may still be incomplete. ")
                .append("Only stop the chat when the partial output has already violated ")
                .append("an enforcer rule in a way that cannot be repaired by later text. ")
                .append("When uncertain, mark compliant=true and stop=false.");
        return prompt.toString();
    }

    private String buildToolCallPrompt(String toolName, String toolInput,
                                       EnforcerPolicy policy,
                                       EnforcerConversationContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("[ENFORCER RULES]\n")
                .append(policy.getRules())
                .append("\n[END ENFORCER RULES]\n\n");

        appendRecentContext(prompt, context);

        prompt.append("[PROPOSED MCP TOOL CALL]\n")
                .append("Tool: ").append(toolName == null ? "" : toolName).append('\n')
                .append("Arguments: ").append(StringUtils.truncateWithSize(toolInput, MAX_OUTPUT_CHARS))
                .append("\n[END PROPOSED MCP TOOL CALL]\n\n");

        prompt.append("Decide before execution whether this MCP call may run under the rules. ")
                .append("Block destructive, out-of-scope, privacy-violating, network, process, ")
                .append("delegation, or file operations when the user rules prohibit them. ")
                .append("Return REWRITE only when a safe argument rewrite is obvious.");
        return prompt.toString();
    }

    private void appendRecentContext(StringBuilder prompt, EnforcerConversationContext context) {
        if (context == null || context.isEmpty()) {
            return;
        }
        String formatted = context.formatForPrompt(MAX_CONTEXT_CHARS);
        if (formatted.isBlank()) {
            return;
        }
        prompt.append("[RECENT CHAT MESSAGES]\n")
                .append(formatted)
                .append("\n[END RECENT CHAT MESSAGES]\n\n");
    }

    static final String PARTIAL_SYSTEM_PROMPT = """
            You are Kompile Enforcer, a real-time stream interruption judge evaluating partial (incomplete) output.

            CRITICAL: Respond with ONLY a single JSON object. No prose, no markdown, no code fences.

            Only interrupt when the partial output has already violated the rules in a way later text cannot repair.
            When uncertain, mark compliant=true.

            {"compliant":true|false,"stop":true|false,"severity":"info|warning|error|critical","violations":["..."],"correction_prompt":"...","reasoning":"..."}
            """;

    static final String TOOL_CALL_SYSTEM_PROMPT = """
            You are Kompile Enforcer, a pre-execution MCP tool-call gate. Decide if this tool call may execute.

            CRITICAL: Respond with ONLY a single JSON object. No prose, no markdown, no code fences.

            {"action":"ALLOW|BLOCK|REWRITE","reason":"...","violations":["..."],"correction_prompt":"...","rewrittenArgs":null}

            BLOCK when execution would violate the rules or when a safe rewrite is not obvious.
            """;
}
