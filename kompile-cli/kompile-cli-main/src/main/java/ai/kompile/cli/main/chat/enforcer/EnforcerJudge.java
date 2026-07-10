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
import ai.kompile.cli.main.chat.harness.ResilientJudgeBackend;
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
    private JudgementLog judgementLog;

    public EnforcerJudge(HarnessConfig config, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = JudgeBackendFactory.create(config, objectMapper);
        warmUpAsync();
    }

    public EnforcerJudge(JudgeBackend backend, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = backend;
        warmUpAsync();
    }

    /** The background warm-up thread, kept so {@link #awaitWarm(long)} can join it. */
    private volatile Thread warmupThread;

    /**
     * Warm the backend on a background daemon thread. A synchronous warm-up here used to
     * spawn (and wait up to 30s on) a persistent judge agent process INSIDE the caller's
     * startup path — for the MCP stdio server that stalled the initialize handshake of
     * every agent session with the enforcer environment set. The first {@code generate()}
     * call still ensures the process itself, so correctness does not depend on this.
     */
    private void warmUpAsync() {
        Thread warmup = new Thread(() -> {
            try {
                backend.warmUp(SYSTEM_PROMPT);
            } catch (Throwable t) {
                // Best-effort: first evaluate() will retry via the backend's own ensure path.
            }
        }, "enforcer-judge-warmup");
        warmup.setDaemon(true);
        this.warmupThread = warmup;
        warmup.start();
    }

    /**
     * Block until the background warm-up finishes (or the timeout elapses). Optional —
     * evaluation works without it — for callers that want first-judgement latency to
     * exclude backend boot (e.g. latency-sensitive monitors, timing tests).
     *
     * @return true if the warm-up completed within the timeout
     */
    public boolean awaitWarm(long timeoutMs) {
        Thread warmup = warmupThread;
        if (warmup == null) {
            return true;
        }
        try {
            warmup.join(timeoutMs);
            return !warmup.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                     EnforcerPolicy policy, int attempt) throws Exception {
        return evaluate(userPrompt, agentOutput, policy, attempt, EnforcerConversationContext.empty());
    }

    // synchronized: a single judge backend (esp. a persistent-subprocess pipe) is single-flight.
    // The realtime JSONL tap (poll thread) and the turn-gate (main thread) share this judge, so all
    // three evaluators serialize on the judge instance to avoid concurrent backend calls / interleaved
    // judgement-log writes. Uncontended today (turn-gate was the only caller) — no behavior change.
    @Override
    public synchronized EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                     EnforcerPolicy policy, int attempt,
                                     EnforcerConversationContext context) throws Exception {
        if (!isAvailable()) {
            return EnforcerDecision.stop(
                    java.util.List.of("No enforcer judge backend is available"),
                    "Configure the harness judge provider/model or a local judge backend.");
        }

        long startNanos = System.nanoTime();
        String response = backend.generate(buildJudgePrompt(userPrompt, agentOutput, policy, attempt, context),
                SYSTEM_PROMPT);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        EnforcerDecision decision = EnforcerDecision.parse(objectMapper, response);
        logJudgement("JUDGE_TURN", attempt, decision, response, latencyMs, userPrompt, agentOutput, null);
        return decision;
    }

    public EnforcerDecision evaluatePartialOutput(String userPrompt, String partialOutput,
                                                  EnforcerPolicy policy) throws Exception {
        return evaluatePartialOutput(userPrompt, partialOutput, policy, EnforcerConversationContext.empty());
    }

    public synchronized EnforcerDecision evaluatePartialOutput(String userPrompt, String partialOutput,
                                                  EnforcerPolicy policy,
                                                  EnforcerConversationContext context) throws Exception {
        if (!isAvailable()) {
            return EnforcerDecision.stop(
                    java.util.List.of("No enforcer judge backend is available"),
                    "Configure the harness judge provider/model or a local judge backend.");
        }

        long startNanos = System.nanoTime();
        String response = backend.generate(buildPartialJudgePrompt(userPrompt, partialOutput, policy, context),
                SYSTEM_PROMPT);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        EnforcerDecision decision = EnforcerDecision.parse(objectMapper, response);
        logJudgement("JUDGE_PARTIAL", 0, decision, response, latencyMs, userPrompt, partialOutput, null);
        return decision;
    }

    public EnforcerToolCallDecision evaluateToolCall(String toolName, String toolInput,
                                                     EnforcerPolicy policy) throws Exception {
        return evaluateToolCall(toolName, toolInput, policy, EnforcerConversationContext.empty());
    }

    public synchronized EnforcerToolCallDecision evaluateToolCall(String toolName, String toolInput,
                                                     EnforcerPolicy policy,
                                                     EnforcerConversationContext context) throws Exception {
        if (!isAvailable()) {
            return EnforcerToolCallDecision.block("No enforcer judge backend is available");
        }

        long startNanos = System.nanoTime();
        String response = backend.generate(buildToolCallPrompt(toolName, toolInput, policy, context),
                SYSTEM_PROMPT);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        EnforcerToolCallDecision decision = EnforcerToolCallDecision.parse(objectMapper, response);
        logToolJudgement(toolName, toolInput, decision, response, latencyMs);
        return decision;
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

    /** The judgement log this judge records to, or {@code null} if none is attached. */
    public JudgementLog getJudgementLog() {
        return judgementLog;
    }

    /** Attach a judgement log so every judge call (raw response + latency) is recorded. */
    public void setJudgementLog(JudgementLog judgementLog) {
        this.judgementLog = judgementLog;
        // If the backend swaps judge models on failure, record those swaps too.
        if (backend instanceof ResilientJudgeBackend resilient) {
            resilient.setSwapSink((from, to, reason) -> {
                JudgementLog log = this.judgementLog;
                if (log != null) {
                    log.record(JudgementRecord.builder()
                            .phase("SWAP")
                            .judgeMode("llm")
                            .backend(to)
                            .reasoning("judge swap: " + from + " → " + to + " (" + reason + ")")
                            .build());
                }
            });
        }
    }

    @Override
    public boolean recordsJudgements() {
        return judgementLog != null;
    }

    private void logJudgement(String phase, int attempt, EnforcerDecision decision, String rawResponse,
                              long latencyMs, String userPrompt, String agentOutput, String toolName) {
        if (judgementLog == null) {
            return;
        }
        judgementLog.record(JudgementRecord.builder()
                .phase(phase)
                .attempt(attempt)
                .judgeMode("llm")
                .backend(describe())
                .latencyMs(latencyMs)
                .compliant(decision.isCompliant())
                .stop(decision.isStop())
                .severity(decision.getSeverity())
                .violations(decision.getViolations())
                .correctionPrompt(decision.getCorrectionPrompt())
                .reasoning(decision.getReasoning())
                .userPromptExcerpt(userPrompt)
                .agentOutputExcerpt(agentOutput)
                .toolName(toolName)
                .judgeRawResponse(rawResponse)
                .build());
    }

    private void logToolJudgement(String toolName, String toolInput, EnforcerToolCallDecision decision,
                                  String rawResponse, long latencyMs) {
        if (judgementLog == null) {
            return;
        }
        judgementLog.record(JudgementRecord.builder()
                .phase("JUDGE_TOOL")
                .judgeMode("llm")
                .backend(describe())
                .latencyMs(latencyMs)
                .compliant(decision.isAllowed())
                .stop(!decision.isAllowed())
                .severity(decision.isAllowed() ? "info" : "error")
                .violations(decision.getViolations())
                .correctionPrompt(decision.getCorrectionPrompt())
                .reasoning(decision.getReason())
                .toolName(toolName)
                .agentOutputExcerpt(toolInput)
                .judgeRawResponse(rawResponse)
                .build());
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
}
