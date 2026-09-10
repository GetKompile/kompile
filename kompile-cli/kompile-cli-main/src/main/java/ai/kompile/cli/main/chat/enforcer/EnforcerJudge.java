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
import ai.kompile.cli.main.chat.ChatSessionContext;
import ai.kompile.utils.StringUtils;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import ai.kompile.cli.main.chat.harness.JudgeBackendFactory;
import ai.kompile.cli.main.chat.harness.ResilientJudgeBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LLM-backed enforcer judge. It evaluates a subordinate LLM turn against
 * user-authored rules plus active reminder constraints and returns a machine-readable
 * intervention decision.
 */
public class EnforcerJudge implements EnforcerEvaluator {

    private static final int MAX_PROMPT_CHARS = 4_000;
    private static final int MAX_OUTPUT_CHARS = 8_000;
    private static final int MAX_CONTEXT_CHARS = 8_000;
    private static final int MAX_INVALID_VERDICT_CHARS = 2_000;
    private static final int MAX_JUDGE_CHAT_MESSAGES = 20;
    private static final long DEFAULT_READY_TIMEOUT_MS = 30_000L;
    private static final String FORMAT_REPAIR_INSTRUCTION = """

            [FORMAT REPAIR]
            Your previous response was not a parseable JSON object. Evaluate the same input again
            and return exactly one JSON object using the system prompt's verdict contract. Do not
            add prose, markdown, code fences, comments, or placeholders.
            [END FORMAT REPAIR]
            """;

    /**
     * Unified system prompt for all evaluation modes (full, partial, tool-call).
     * The evaluation mode is specified in the user prompt, not the system prompt,
     * so a persistent judge subprocess can handle all modes in one session.
     */
    static final String SYSTEM_PROMPT = """
            You are Kompile Enforcer, an automated intervention judge. You evaluate whether a subordinate LLM's output follows the user's enforcer rules and active reminder constraints.

            CRITICAL: You must respond with ONLY a single JSON object. No prose, no markdown, no explanation, no code fences. Just the raw JSON.

            If compliant: {"compliant":true,"stop":false,"severity":"info","violations":[],"correction_prompt":"","reasoning":"brief reason"}
            If non-compliant: {"compliant":false,"stop":false,"severity":"error","violations":["specific violation"],"correction_prompt":"tell the subordinate exactly what to fix","reasoning":"brief reason"}
            If must stop: {"compliant":false,"stop":true,"severity":"critical","violations":["specific violation"],"correction_prompt":"","reasoning":"brief reason"}

            Rules:
            - Treat the user's enforcer rules and active reminder constraints as authoritative
            - Active reminders are enforceable user instructions, not optional advice
            - If an active reminder conflicts with an explicit enforcer rule, the explicit enforcer rule wins
            - Intervention is a high-confidence exception, not the default. When meaning, applicability, or context is ambiguous, mark compliant=true and stop=false
            - Apply each instruction according to its complete plain-language meaning, including qualifiers such as "especially", "unless", and "before"; never widen a narrow ban into a broader prohibition
            - Do not invent requirements, infer a missing task, replace the user's explicit request, or police mere relevance, efficiency, style preference, or incomplete progress unless a stated rule directly requires it
            - Memory, transcript excerpts, and recent-chat context explain the request; they are not themselves a new request and must not override an explicit current user prompt
            - Be specific in violations — quote what was wrong
            - correction_prompt must be actionable for the subordinate LLM
            - Your entire response must be parseable as JSON
            - Reserve stop=true for a concrete critical violation that must halt immediately; use a normal correction for a repairable direct violation
            - For partial/streaming output, only stop when the output has ALREADY violated rules in a way later text cannot repair
            - For MCP tool calls, use action ALLOW/BLOCK/REWRITE format when evaluating proposed tool calls
            - Routine session bookkeeping (for example task-list reads/updates) is allowed unless an explicit rule or active reminder conflicts with that tool or command
            - Distinguish command effects: git log/show/status/diff inspect; git commit/revert mutate; reset --hard, clean and force-push can discard work. Never treat all git commands as one risk category
            - Filesystem administration such as rm/rmdir, mv, mkdir or chmod is not categorically banned. Evaluate exact targets, flags, user authorization and applicable rules; do not claim edit/write can remove directories or change file modes
            - A current explicit user approval can narrow or supersede their earlier judge guidance for that exact action; it does not authorize unrelated commands or bypass hard tool protections
            - Never block a tool merely because it seems unnecessary, mundane, or could be done another way; identify a concrete rule conflict
            """;

    /** Supplies the user's current judge guidance, or {@code null} when absent. */
    @FunctionalInterface
    public interface GuidanceSupplier {
        String get();
    }

    private final ChatSessionContext sessionContext = ChatSessionContext.current();
    private final ObjectMapper objectMapper;
    private final JudgeBackend backend;
    private JudgementLog judgementLog;
    private volatile GuidanceSupplier guidanceSupplier = () -> null;
    private volatile GuidanceSupplier reminderSupplier = () -> null;
    private volatile boolean closed;
    private final List<EnforcerConversationContext.Message> judgeChatHistory = new ArrayList<>();

    private record GeneratedVerdict(String response, String rawForLog, long latencyMs) { }

    public EnforcerJudge(HarnessConfig config, ObjectMapper objectMapper) {
        this(config, objectMapper, null);
    }

    public EnforcerJudge(HarnessConfig config, ObjectMapper objectMapper, Path workingDirectory) {
        this.objectMapper = objectMapper;
        this.backend = JudgeBackendFactory.create(config, objectMapper, workingDirectory);
        warmUpAsync();
    }

    public EnforcerJudge(JudgeBackend backend, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = backend;
        warmUpAsync();
    }

    public EnforcerJudge(JudgeBackend backend, ObjectMapper objectMapper,
                         GuidanceSupplier guidanceSupplier) {
        this.objectMapper = objectMapper;
        this.backend = backend;
        if (guidanceSupplier != null) {
            this.guidanceSupplier = guidanceSupplier;
        }
        warmUpAsync();
    }

    /** Bind a live supplier of the user's durable judge guidance. */
    public void setGuidanceSupplier(GuidanceSupplier supplier) {
        this.guidanceSupplier = supplier == null ? () -> null : supplier;
    }

    /** Bind the live project/session reminders that this judge must enforce. */
    public void setReminderSupplier(GuidanceSupplier supplier) {
        this.reminderSupplier = supplier == null ? () -> null : supplier;
    }

    /** Frozen child inputs; a reload may close the backend, but never changes these constraints. */
    private record Constraints(String guidance, String reminders) {
        public Constraints {
            guidance = guidance == null ? "" : guidance;
            reminders = reminders == null ? "" : reminders;
        }
    }

    public CapturedEvaluator captureForChild() {
        // Do not swallow supplier failures: the child capture must fail closed.
        return new CapturedEvaluator(new Constraints(guidanceSupplier.get(), reminderSupplier.get()));
    }

    public final class CapturedEvaluator implements EnforcerEvaluator {
        private final Constraints constraints;
        private CapturedEvaluator(Constraints constraints) { this.constraints = constraints; }
        public boolean isAvailable() { return EnforcerJudge.this.isAvailable(); }
        public String describe() { return "Captured child judge"; }
        public EnforcerDecision evaluate(String user, String output, EnforcerPolicy policy, int attempt)
                throws Exception {
            return evaluate(user, output, policy, attempt, EnforcerConversationContext.empty());
        }
        public EnforcerDecision evaluate(String user, String output, EnforcerPolicy policy, int attempt,
                                          EnforcerConversationContext context) throws Exception {
            return EnforcerJudge.this.evaluate(user, output, policy, attempt, context, constraints);
        }
        public EnforcerToolCallDecision evaluateToolCall(String name, String input, EnforcerPolicy policy)
                throws Exception {
            return evaluateToolCall(name, input, policy, EnforcerConversationContext.empty());
        }
        public EnforcerToolCallDecision evaluateToolCall(String name, String input, EnforcerPolicy policy,
                                                         EnforcerConversationContext context) throws Exception {
            return EnforcerJudge.this.evaluateToolCall(name, input, policy, context, constraints);
        }
    }

    /** One-shot conversational message to the judge backend (the /judge chat lane). */
    public synchronized String chatWithJudge(String message) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException(judgeStatus());
        }
        String prompt = buildJudgeChatPrompt(message);
        long startNanos = System.nanoTime();
        String response = backend.generate(prompt, CHAT_SYSTEM_PROMPT);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (ai.kompile.cli.main.chat.harness.ResilientJudgeBackend.isErrorResponse(response)) {
            throw new IllegalStateException(
                    "Judge backend returned an error response: "
                            + StringUtils.truncate(response, 200));
        }
        rememberJudgeChat(message, response);
        if (judgementLog != null) {
            judgementLog.record(JudgementRecord.builder()
                    .phase("JUDGE_CHAT")
                    .judgeMode("llm")
                    .backend(describe())
                    .latencyMs(latencyMs)
                    .compliant(true)
                    .stop(false)
                    .severity("info")
                    .reasoning("user judge-chat exchange")
                    .userPromptExcerpt(message)
                    .agentOutputExcerpt(response)
                    .judgeRawResponse(response)
                    .build());
        }
        return response;
    }

    /**
     * System prompt for the conversational /judge lane. Deliberately different from the
     * strict JSON verdict contract: this lane exists to discuss feedback with the user.
     */
    public static final String CHAT_SYSTEM_PROMPT = """
            You are Kompile Enforcer, the chat judge. The user is talking to you directly — not
            asking for a compliance verdict. They may give you feedback about your previous
            judgements, explain project context you were missing, or ask what rules you are
            applying.

            Respond conversationally in plain prose. Acknowledge corrections explicitly and
            restate how you will apply them in future judgements. If the user's feedback should
            apply to future evaluations, remind them that persistent instructions should be
            saved with '/judge feedback <text>' — your memory of this conversation alone does
            not change future verdicts. Active reminder constraints are already persistent
            instructions; do not ask the user to duplicate them as judge feedback. Judge chat
            never enables intervention or sends feedback to the main agent; the user may keep
            intervention disabled while discussing or correcting your decisions.
            For a command-specific override, explain '/judge approve <exact bash command>': it
            approves only that command during the next turn without disabling other judge checks.
            For argument variations use '/judge approve --pattern <pattern>', for example
            'git log **' or 'rm -rf target/cache-*'. '*' matches within one argument and never
            crosses '/', and final '**' permits all remaining arguments (including options).
            Patterns reject shell chaining, redirects, expansions and '..' path components.
            '/judge approve off' cancels either mode. It does not run the command or bypass permissions,
            workflow gates, or dedicated-tool/managed-memory protections. Never claim you have
            applied an approval merely by acknowledging it in conversation.
            """;

    /** The background warm-up thread, kept so {@link #awaitWarm(long)} can join it. */
    private volatile Thread warmupThread;
    private volatile boolean warmupComplete;
    private volatile String readinessFailure;
    private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();

    /**
     * Warm the backend on a background daemon thread. A synchronous warm-up here used to
     * spawn (and wait up to 30s on) a persistent judge agent process INSIDE the caller's
     * startup path — for the MCP stdio server that stalled the initialize handshake of
     * every agent session with the enforcer environment set. The first {@code generate()}
     * call still ensures the process itself, so correctness does not depend on this.
     */
    private void warmUpAsync() {
        Thread warmup = new Thread(sessionContext.wrap(() -> {
            try {
                synchronized (this) {
                    backend.warmUp(SYSTEM_PROMPT);
                }
            } catch (Throwable t) {
                readinessFailure = t.getMessage() == null || t.getMessage().isBlank()
                        ? t.getClass().getSimpleName() : t.getMessage();
            } finally {
                warmupComplete = true;
                if (!backend.isAvailable() && (readinessFailure == null || readinessFailure.isBlank())) {
                    readinessFailure = backend.failureReason();
                }
                fireStateChange();
            }
        }), "enforcer-judge-warmup");
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

    /**
     * Wait for the judge's actual backend health check. A binary merely existing on
     * PATH is not sufficient: this blocks the first subordinate turn until the
     * selected agent has authenticated and accepted a request.
     */
    @Override
    public boolean awaitReady(long timeoutMs) {
        long bounded = timeoutMs > 0 ? timeoutMs : DEFAULT_READY_TIMEOUT_MS;
        if (!awaitWarm(bounded)) {
            readinessFailure = "Judge warm-up timed out after " + bounded + "ms";
            try {
                backend.close();
            } catch (RuntimeException ignored) {
                // Best effort; the watcher is still failed below.
            }
            fireStateChange();
            return false;
        }
        return isAvailable();
    }

    /** Register a callback for readiness failures and restart/modify transitions. */
    public void addStateListener(Runnable listener) {
        if (listener != null) {
            stateListeners.add(ChatSessionContext.current().wrap(listener));
        }
    }

    private void fireStateChange() {
        for (Runnable listener : stateListeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // UI/process bookkeeping must not break judge execution.
            }
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
        return evaluate(userPrompt, agentOutput, policy, attempt, context, null);
    }

    private synchronized EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                     EnforcerPolicy policy, int attempt,
                                     EnforcerConversationContext context, Constraints constraints) throws Exception {
        if (constraints != null && closed) throw new IllegalStateException("Captured child judge backend was closed by reload");
        if (!isAvailable()) {
            return EnforcerDecision.pass("No enforcer judge backend is available; failing open");
        }

        GeneratedVerdict generated;
        try {
            generated = generateVerdict(
                    buildJudgePrompt(userPrompt, agentOutput, policy, attempt, context, constraints),
                    interventionVerdictSchema());
        } catch (Exception failure) {
            EnforcerDiagnostics.alert("[enforcer] judge failure: " + failure.getMessage());
            fireStateChange();
            throw failure;
        }
        String response = generated.response();
        EnforcerDecision decision;
        if (ResilientJudgeBackend.isErrorResponse(response)) {
            decision = EnforcerDecision.pass(backendFailureReason(response));
            fireStateChange();
        } else {
            decision = EnforcerDecision.parse(objectMapper, response);
        }
        logJudgement("JUDGE_TURN", attempt, decision, generated.rawForLog(),
                generated.latencyMs(), userPrompt, agentOutput, null);
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
            return EnforcerDecision.pass("No enforcer judge backend is available; failing open");
        }

        GeneratedVerdict generated;
        try {
            generated = generateVerdict(
                    buildPartialJudgePrompt(userPrompt, partialOutput, policy, context),
                    interventionVerdictSchema());
        } catch (Exception failure) {
            EnforcerDiagnostics.alert("[enforcer] judge partial-evaluation failure: " + failure.getMessage());
            fireStateChange();
            throw failure;
        }
        String response = generated.response();
        EnforcerDecision decision;
        if (ResilientJudgeBackend.isErrorResponse(response)) {
            decision = EnforcerDecision.pass(backendFailureReason(response));
            fireStateChange();
        } else {
            decision = EnforcerDecision.parse(objectMapper, response);
        }
        logJudgement("JUDGE_PARTIAL", 0, decision, generated.rawForLog(),
                generated.latencyMs(), userPrompt, partialOutput, null);
        return decision;
    }

    public EnforcerToolCallDecision evaluateToolCall(String toolName, String toolInput,
                                                     EnforcerPolicy policy) throws Exception {
        return evaluateToolCall(toolName, toolInput, policy, EnforcerConversationContext.empty());
    }

    public synchronized EnforcerToolCallDecision evaluateToolCall(String toolName, String toolInput,
                                                     EnforcerPolicy policy,
                                                     EnforcerConversationContext context) throws Exception {
        return evaluateToolCall(toolName, toolInput, policy, context, null);
    }

    private synchronized EnforcerToolCallDecision evaluateToolCall(String toolName, String toolInput,
                                                     EnforcerPolicy policy,
                                                     EnforcerConversationContext context, Constraints constraints) throws Exception {
        if (constraints != null && closed) throw new IllegalStateException("Captured child judge backend was closed by reload");
        // Deterministic shell-mandate layer: hard block regardless of judge availability so
        // a failing or disabled judge never re-opens the bash sed/grep escape hatch.
        EnforcerToolCallDecision mandate = ShellMandatePolicy.evaluateFromSerializedArgs(toolName, toolInput);
        if (mandate != null) {
            logToolJudgement(toolName, toolInput, mandate, "[deterministic shell-mandate policy]", 0L);
            return mandate;
        }

        EnforcerToolCallDecision readOnlyGit = JudgeToolPolicy.evaluateReadOnlyGitTool(
                toolName, toolInput, policy, objectMapper);
        if (readOnlyGit != null) {
            logToolJudgement(toolName, toolInput, readOnlyGit,
                    "[deterministic read-only Git policy]", 0L);
            return readOnlyGit;
        }

        if ((constraints == null ? currentReminderConstraints() : constraints.reminders()).isBlank()) {
            EnforcerToolCallDecision routine = JudgeToolPolicy.evaluateRoutineTool(
                    toolName, toolInput, policy, objectMapper);
            if (routine != null) {
                logToolJudgement(toolName, toolInput, routine,
                        "[deterministic routine-tool policy]", 0L);
                return routine;
            }
        }
        if (!isAvailable()) {
            return EnforcerToolCallDecision.allow(
                    "No enforcer judge backend is available; failing open");
        }

        GeneratedVerdict generated;
        try {
            generated = generateVerdict(
                    buildToolCallPrompt(toolName, toolInput, policy, context, constraints),
                    toolVerdictSchema());
        } catch (Exception failure) {
            EnforcerDiagnostics.alert("[enforcer] judge tool-evaluation failure: " + failure.getMessage());
            fireStateChange();
            throw failure;
        }
        String response = generated.response();
        EnforcerToolCallDecision decision;
        if (ResilientJudgeBackend.isErrorResponse(response)) {
            decision = EnforcerToolCallDecision.allow(backendFailureReason(response));
            fireStateChange();
        } else {
            decision = EnforcerToolCallDecision.parse(objectMapper, response);
        }
        logToolJudgement(toolName, toolInput, decision,
                generated.rawForLog(), generated.latencyMs());
        return decision;
    }

    @Override
    public boolean isAvailable() {
        return !closed && backend != null && (readinessFailure == null || readinessFailure.isBlank())
                && backend.isAvailable();
    }

    @Override
    public String describe() {
        return backend != null ? backend.describe() : "none";
    }

    /** Human-readable state for CLI/REST controls. */
    public synchronized String judgeStatus() {
        if (backend == null) return "failed · no judge backend";
        String state = !warmupComplete ? "starting" : isAvailable() ? "ready" : "failed";
        String reason = readinessFailure;
        if (reason == null || reason.isBlank()) {
            reason = backend.failureReason();
        }
        return state + " · " + backend.describe()
                + (reason == null || reason.isBlank() ? "" : " · " + reason);
    }

    /** Stop the current judge process and retry using the selected agent. */
    public synchronized String restartJudge() {
        if (backend == null) return "Judge restart failed: no judge backend";
        try {
            readinessFailure = null;
            warmupComplete = false;
            backend.restart();
            backend.warmUp(SYSTEM_PROMPT);
            warmupComplete = true;
            if (!backend.isAvailable()) {
                readinessFailure = backend.failureReason();
            }
            fireStateChange();
            return "Judge restarted: " + judgeStatus();
        } catch (Exception failure) {
            readinessFailure = failure.getMessage();
            warmupComplete = true;
            fireStateChange();
            return "Judge restart failed: " + judgeStatus();
        }
    }

    /** Switch the judge to a named local agent and reset its process/session. */
    public synchronized String modifyJudge(String selection) {
        if (backend == null) return "Judge modification failed: no judge backend";
        try {
            readinessFailure = null;
            warmupComplete = false;
            if (!backend.modify(selection)) {
                warmupComplete = true;
                readinessFailure = backend.failureReason();
                fireStateChange();
                return "Judge agent '" + selection + "' is unavailable: " + judgeStatus();
            }
            backend.warmUp(SYSTEM_PROMPT);
            warmupComplete = true;
            if (!backend.isAvailable()) {
                readinessFailure = backend.failureReason();
            }
            fireStateChange();
            return "Judge modified: " + judgeStatus();
        } catch (Exception failure) {
            readinessFailure = failure.getMessage();
            warmupComplete = true;
            fireStateChange();
            return "Judge modification failed: " + judgeStatus();
        }
    }

    public synchronized void close() {
        closed = true;
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

    private GeneratedVerdict generateVerdict(
            String prompt, JudgeBackend.JsonSchema outputSchema) throws Exception {
        long startNanos = System.nanoTime();
        String initial = backend.generateJson(prompt, SYSTEM_PROMPT, outputSchema);
        if (ResilientJudgeBackend.isErrorResponse(initial) || hasParseableJsonObject(initial)) {
            return new GeneratedVerdict(
                    initial, initial, (System.nanoTime() - startNanos) / 1_000_000L);
        }

        String repaired = backend.generateJson(
                prompt + FORMAT_REPAIR_INSTRUCTION, SYSTEM_PROMPT, outputSchema);
        String rawForLog = "[INITIAL MALFORMED JUDGE RESPONSE]\n"
                + StringUtils.truncate(initial, MAX_INVALID_VERDICT_CHARS)
                + "\n[FORMAT REPAIR RESPONSE]\n"
                + StringUtils.truncate(repaired, MAX_INVALID_VERDICT_CHARS);
        return new GeneratedVerdict(
                repaired, rawForLog, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private boolean hasParseableJsonObject(String response) {
        String json = EnforcerDecision.extractJson(response);
        if (json == null) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
            return root != null && root.isObject();
        } catch (Exception ignored) {
            return false;
        }
    }

    private JudgeBackend.JsonSchema interventionVerdictSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("compliant").put("type", "boolean");
        properties.putObject("stop").put("type", "boolean");
        properties.putObject("severity").put("type", "string")
                .putArray("enum").add("info").add("error").add("critical");
        properties.putObject("violations").put("type", "array")
                .putObject("items").put("type", "string");
        properties.putObject("correction_prompt").put("type", "string");
        properties.putObject("reasoning").put("type", "string");
        ArrayNode required = schema.putArray("required");
        required.add("compliant").add("stop").add("severity").add("violations")
                .add("correction_prompt").add("reasoning");
        return new JudgeBackend.JsonSchema("kompile_intervention_judge", schema, true);
    }

    private JudgeBackend.JsonSchema toolVerdictSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("action").put("type", "string")
                .putArray("enum").add("ALLOW").add("BLOCK").add("REWRITE");
        properties.putObject("reason").put("type", "string");
        properties.putObject("violations").put("type", "array")
                .putObject("items").put("type", "string");
        properties.putObject("correction_prompt").put("type", "string");
        properties.putObject("rewrittenArgs").putArray("type").add("object").add("null");
        ArrayNode required = schema.putArray("required");
        required.add("action").add("reason").add("violations")
                .add("correction_prompt").add("rewrittenArgs");
        return new JudgeBackend.JsonSchema("kompile_tool_judge", schema, false);
    }

    private static String backendFailureReason(String response) {
        return "Enforcer judge backend unavailable; failing open: "
                + StringUtils.truncate(response == null ? "empty response" : response, 240);
    }

    private String buildJudgePrompt(String userPrompt, String agentOutput,
                                    EnforcerPolicy policy, int attempt,
                                    EnforcerConversationContext context, Constraints constraints) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("[ENFORCER RULES]\n")
                .append(policy.getRules())
                .append("\n[END ENFORCER RULES]\n\n");

        appendReminderConstraints(prompt, constraints);
        appendUserGuidance(prompt, constraints);

        prompt.append("[USER PROMPT]\n")
                .append(StringUtils.truncateWithSize(userPrompt, MAX_PROMPT_CHARS))
                .append("\n[END USER PROMPT]\n\n");

        appendRecentContext(prompt, context);

        prompt.append("[SUBORDINATE LLM RESPONSE, ATTEMPT ")
                .append(attempt)
                .append("]\n")
                .append(StringUtils.truncateWithSize(agentOutput, MAX_OUTPUT_CHARS))
                .append("\n[END SUBORDINATE LLM RESPONSE]\n\n");

        prompt.append("Evaluate only concrete, material compliance with the enforcer rules and applicable active reminder constraints. ")
                .append("Do not turn inferred preferences, mere incompleteness, or debatable relevance into violations. ")
                .append("When uncertain, mark compliant=true and stop=false. ")
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

        appendReminderConstraints(prompt);
        appendUserGuidance(prompt);

        prompt.append("[USER PROMPT]\n")
                .append(StringUtils.truncateWithSize(userPrompt, MAX_PROMPT_CHARS))
                .append("\n[END USER PROMPT]\n\n");

        appendRecentContext(prompt, context);

        prompt.append("[PARTIAL SUBORDINATE OUTPUT]\n")
                .append(StringUtils.truncateWithSize(partialOutput, MAX_OUTPUT_CHARS))
                .append("\n[END PARTIAL SUBORDINATE OUTPUT]\n\n");

        prompt.append("This is streamed output that may still be incomplete. ")
                .append("Only stop the chat when the partial output has already violated ")
                .append("an enforcer rule or active reminder constraint in a way that cannot be repaired by later text. ")
                .append("When uncertain, mark compliant=true and stop=false.");
        return prompt.toString();
    }

    private String buildToolCallPrompt(String toolName, String toolInput,
                                       EnforcerPolicy policy,
                                       EnforcerConversationContext context, Constraints constraints) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("[ENFORCER RULES]\n")
                .append(policy.getRules())
                .append("\n[END ENFORCER RULES]\n\n");

        appendReminderConstraints(prompt, constraints);
        appendUserGuidance(prompt, constraints);

        appendRecentContext(prompt, context);

        prompt.append("[PROPOSED MCP TOOL CALL]\n")
                .append("Tool: ").append(toolName == null ? "" : toolName).append('\n')
                .append("Arguments: ").append(StringUtils.truncateWithSize(toolInput, MAX_OUTPUT_CHARS))
                .append("\n[END PROPOSED MCP TOOL CALL]\n\n");

        prompt.append("Decide before execution whether this MCP call may run under the rules. ")
                .append("Treat every applicable active reminder as an enforceable constraint too. ")
                .append("Block destructive, out-of-scope, privacy-violating, network, process, ")
                .append("delegation, or file operations when the user rules prohibit them. ")
                .append("Allow ordinary bookkeeping and read-only workflow updates unless an explicit rule or active reminder prohibits them. ")
                .append("Do not block from generalized caution, preference, or a debatable scope judgment: cite the exact concrete conflicting rule. ")
                .append("Interpret qualified instructions narrowly and in full; when uncertain, ALLOW. ")
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

    /** Add active project/session reminders as a distinct enforceable policy source. */
    private void appendReminderConstraints(StringBuilder prompt) {
        appendReminderConstraints(prompt, null);
    }

    private void appendReminderConstraints(StringBuilder prompt, Constraints constraints) {
        String reminders = constraints == null ? currentReminderConstraints() : constraints.reminders();
        if (reminders.isBlank()) {
            return;
        }
        prompt.append("[ACTIVE REMINDER CONSTRAINTS]\n")
                .append("These are user-configured constraints. Evaluate every applicable reminder; ")
                .append("do not treat them as suggestions. Explicit enforcer rules take precedence ")
                .append("if they conflict.\n")
                .append(StringUtils.truncateWithSize(reminders, MAX_CONTEXT_CHARS))
                .append("\n[END ACTIVE REMINDER CONSTRAINTS]\n\n");
    }

    private String currentReminderConstraints() {
        try {
            String reminders = reminderSupplier.get();
            return reminders == null ? "" : reminders.strip();
        } catch (RuntimeException ignored) {
            // Storage failures cannot manufacture a policy block; judge infrastructure remains fail-open.
            return "";
        }
    }

    /**
     * Inject the user's durable judge guidance. This is how a human corrects the judge:
     * it appears in every turn, partial-output, tool-call, and judge-chat prompt as a
     * high-priority instruction block.
     */
    private void appendUserGuidance(StringBuilder prompt) {
        appendUserGuidance(prompt, null);
    }

    private void appendUserGuidance(StringBuilder prompt, Constraints constraints) {
        String guidance = constraints == null ? guidanceSupplier.get() : constraints.guidance();
        if (guidance == null || guidance.isBlank()) {
            return;
        }
        prompt.append("[USER GUIDANCE TO THE JUDGE]\n")
                .append("The user has given you the following feedback and instructions. ")
                .append("Treat it as high-priority context that supplements — but never "
                        + "contradicts the user's enforcer rules themselves — your evaluation:\n")
                .append(StringUtils.truncateWithSize(guidance, MAX_CONTEXT_CHARS))
                .append("\n[END USER GUIDANCE TO THE JUDGE]\n\n");
    }

    private String buildJudgeChatPrompt(String message) {
        StringBuilder prompt = new StringBuilder();
        appendReminderConstraints(prompt);
        appendUserGuidance(prompt);
        String history = EnforcerConversationContext.of(judgeChatHistory)
                .formatForPrompt(MAX_CONTEXT_CHARS);
        if (!history.isBlank()) {
            prompt.append("[JUDGE CHAT HISTORY]\n")
                    .append(history)
                    .append("\n[END JUDGE CHAT HISTORY]\n\n");
        }
        prompt.append("[USER MESSAGE TO THE JUDGE]\n")
                .append(StringUtils.truncateWithSize(message, MAX_PROMPT_CHARS))
                .append("\n[END USER MESSAGE TO THE JUDGE]\n\n")
                .append("Reply conversationally. This is not a compliance evaluation — ")
                .append("no JSON verdict is expected.");
        return prompt.toString();
    }

    private void rememberJudgeChat(String message, String response) {
        judgeChatHistory.add(new EnforcerConversationContext.Message("user", message));
        judgeChatHistory.add(new EnforcerConversationContext.Message("judge", response));
        while (judgeChatHistory.size() > MAX_JUDGE_CHAT_MESSAGES) {
            judgeChatHistory.remove(0);
        }
    }
}
