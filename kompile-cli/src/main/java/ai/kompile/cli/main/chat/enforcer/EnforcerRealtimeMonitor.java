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

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-time monitor that can interrupt a subprocess agent before a turn
 * completes. It is intentionally conservative for text chunks to avoid stopping
 * incomplete answers that may become compliant, and strict for tool calls
 * because MCP calls are about to create side effects.
 * <p>
 * Text evaluation is <b>asynchronous</b>: the judge call runs in a background
 * thread so the output-reader thread is never blocked. The interrupt decision
 * is picked up on the next {@link #onTextChunk} call. Tool-call evaluation
 * remains synchronous because it gates side effects.
 */
public class EnforcerRealtimeMonitor implements SubprocessAgentRunner.RealtimeMonitor, AutoCloseable {

    private static final int MIN_PARTIAL_CHARS = 1_200;
    private static final int EVALUATION_INTERVAL_CHARS = 900;
    private static final long EVALUATION_INTERVAL_MS = 2_000;

    private final EnforcerJudge judge;
    private final EnforcerPolicy policy;
    private final String userPrompt;
    private final EnforcerConversationWindow conversationWindow;
    private volatile int lastEvaluatedLength;
    private volatile long lastEvaluationMs;

    // Async evaluation — background thread deposits here, next onTextChunk picks it up
    private final AtomicReference<SubprocessAgentRunner.MonitorDecision> asyncDecision =
            new AtomicReference<>(null);
    private volatile boolean asyncEvaluationInFlight = false;
    private final ExecutorService evaluationExecutor;

    public EnforcerRealtimeMonitor(EnforcerJudge judge, EnforcerPolicy policy, String userPrompt) {
        this(judge, policy, userPrompt, null);
    }

    public EnforcerRealtimeMonitor(EnforcerJudge judge, EnforcerPolicy policy, String userPrompt,
                                   EnforcerConversationWindow conversationWindow) {
        this.judge = judge;
        this.policy = policy;
        this.userPrompt = userPrompt;
        this.conversationWindow = conversationWindow;
        this.evaluationExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "enforcer-realtime-eval");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public SubprocessAgentRunner.MonitorDecision onTextChunk(String chunk, String fullText) {
        if (conversationWindow != null) {
            conversationWindow.updateAssistantMessage(fullText);
        }

        // Check if a background evaluation completed with an interrupt
        SubprocessAgentRunner.MonitorDecision pending = asyncDecision.getAndSet(null);
        if (pending != null && pending.interrupt()) {
            asyncEvaluationInFlight = false;
            return pending;
        }

        if (judge == null || policy == null || !policy.hasRules()
                || fullText == null || fullText.length() < MIN_PARTIAL_CHARS) {
            return SubprocessAgentRunner.MonitorDecision.continueRun();
        }

        long now = System.currentTimeMillis();
        int charsSinceLast = fullText.length() - lastEvaluatedLength;
        if (charsSinceLast < EVALUATION_INTERVAL_CHARS
                && now - lastEvaluationMs < EVALUATION_INTERVAL_MS) {
            return SubprocessAgentRunner.MonitorDecision.continueRun();
        }

        // Submit evaluation to background thread — does not block output streaming
        if (!asyncEvaluationInFlight) {
            lastEvaluatedLength = fullText.length();
            lastEvaluationMs = now;
            asyncEvaluationInFlight = true;
            final String textSnapshot = fullText;
            evaluationExecutor.submit(() -> {
                try {
                    EnforcerConversationContext context = conversationWindow != null
                            ? conversationWindow.snapshot() : EnforcerConversationContext.empty();
                    EnforcerDecision decision = judge.evaluatePartialOutput(
                            userPrompt, textSnapshot, policy, context);
                    if (decision.isStop()) {
                        asyncDecision.set(SubprocessAgentRunner.MonitorDecision.interrupt(
                                summarize(decision), decision.getCorrectionPrompt()));
                    }
                } catch (Exception ignored) {
                    // Final turn-level enforcement still runs after the subprocess exits.
                } finally {
                    asyncEvaluationInFlight = false;
                }
            });
        }

        return SubprocessAgentRunner.MonitorDecision.continueRun();
    }

    @Override
    public SubprocessAgentRunner.MonitorDecision onToolUse(String toolName, String input) {
        if (conversationWindow != null) {
            conversationWindow.addToolCall(toolName, input);
        }
        if (judge == null || policy == null || !policy.hasRules()) {
            return SubprocessAgentRunner.MonitorDecision.continueRun();
        }
        try {
            EnforcerConversationContext context = conversationWindow != null
                    ? conversationWindow.snapshot() : EnforcerConversationContext.empty();
            EnforcerToolCallDecision decision = judge.evaluateToolCall(toolName, input, policy, context);
            if (!decision.isAllowed() || decision.isRewrite()) {
                return SubprocessAgentRunner.MonitorDecision.interrupt(
                        decision.blockMessage(), decision.getCorrectionPrompt());
            }
        } catch (Exception e) {
            return SubprocessAgentRunner.MonitorDecision.interrupt(
                    "Enforcer tool-use evaluation failed: " + e.getMessage(), "");
        }
        return SubprocessAgentRunner.MonitorDecision.continueRun();
    }

    @Override
    public void close() {
        evaluationExecutor.shutdownNow();
        try {
            evaluationExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String summarize(EnforcerDecision decision) {
        if (decision == null) {
            return "rule violation";
        }
        if (!decision.getViolations().isEmpty()) {
            return String.join("; ", decision.getViolations());
        }
        if (!decision.getReasoning().isBlank()) {
            return decision.getReasoning();
        }
        return decision.getSeverity();
    }
}
