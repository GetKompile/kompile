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

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-time scoring monitor that pipes conversation text to a background
 * judge LLM for continuous enforcement, scoring, and automatic reprompting.
 * <p>
 * Designed for the kompile managed TUI passthrough: the monitor is installed
 * on either a {@link SubprocessAgentRunner} or an
 * {@link ai.kompile.cli.main.chat.config.AgentSubprocessClient} and fires
 * {@link RealtimeInterruptEvent}s to registered listeners as the agent
 * streams output.
 * <p>
 * <b>Lifecycle:</b>
 * <ol>
 *   <li>Create with judge, policy, agent name</li>
 *   <li>Add listeners via {@link #addListener}</li>
 *   <li>Call {@link #resetFull} before each new user turn</li>
 *   <li>Install as the {@code RealtimeMonitor} on the subprocess runner/client</li>
 *   <li>After the turn completes, call {@link #scoreTurn} for final scoring</li>
 *   <li>Call {@link #close} when the session ends</li>
 * </ol>
 */
public class ScoringRealtimeMonitor implements SubprocessAgentRunner.RealtimeMonitor, AutoCloseable {

    private static final int MIN_PARTIAL_CHARS = 800;
    private static final int EVALUATION_INTERVAL_CHARS = 600;
    private static final long EVALUATION_INTERVAL_MS = 1_500;
    private static final long SCORE_REPORT_INTERVAL_MS = 5_000;

    private final EnforcerJudge judge;
    private final EnforcerPolicy policy;
    private final String agentName;
    private final List<RealtimeInterruptListener> listeners = new CopyOnWriteArrayList<>();
    private final EnforcerConversationWindow conversationWindow;

    // Scoring state
    private final AtomicReference<Double> currentScore = new AtomicReference<>(1.0);
    private final AtomicInteger correctionAttempts = new AtomicInteger(0);
    private final int maxAutoReprompts;

    // Enabled toggle — can be flipped at runtime via /enforce on|off
    private volatile boolean enabled = true;

    // Throttling
    private volatile int lastEvaluatedLength;
    private volatile long lastEvaluationMs;
    private volatile long lastScoreReportMs;
    private volatile String lastUserPrompt = "";

    // Background evaluation thread
    private final ExecutorService evaluationExecutor;

    // Async decision buffer — background evaluation deposits here,
    // next onTextChunk() picks it up and returns interrupt if needed
    private final AtomicReference<SubprocessAgentRunner.MonitorDecision> asyncDecision =
            new AtomicReference<>(null);

    // Guard to avoid submitting overlapping async evaluations
    private volatile boolean asyncEvaluationInFlight = false;

    public ScoringRealtimeMonitor(EnforcerJudge judge, EnforcerPolicy policy,
                                   String agentName, int maxAutoReprompts) {
        this(judge, policy, agentName, maxAutoReprompts, null);
    }

    public ScoringRealtimeMonitor(EnforcerJudge judge, EnforcerPolicy policy,
                                   String agentName, int maxAutoReprompts,
                                   EnforcerConversationWindow conversationWindow) {
        this.judge = judge;
        this.policy = policy;
        this.agentName = agentName != null ? agentName : "agent";
        this.maxAutoReprompts = maxAutoReprompts > 0 ? maxAutoReprompts : 2;
        this.conversationWindow = conversationWindow;
        this.evaluationExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "scoring-monitor-" + this.agentName);
            t.setDaemon(true);
            return t;
        });
    }

    // ── Listener management ──────────────────────────────────────────────

    public void addListener(RealtimeInterruptListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(RealtimeInterruptListener listener) {
        listeners.remove(listener);
    }

    // ── Enable / disable ──────────────────────────────────────────────────

    /**
     * Enable or disable the monitor at runtime. When disabled, all
     * evaluations are skipped and the monitor returns continueRun().
     * The monitor state (score, attempts) is preserved across toggles.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ── State accessors ──────────────────────────────────────────────────

    public void setUserPrompt(String prompt) {
        this.lastUserPrompt = prompt != null ? prompt : "";
    }

    public double getCurrentScore() {
        return currentScore.get();
    }

    public int getCorrectionAttempts() {
        return correctionAttempts.get();
    }

    public boolean canAutoReprompt() {
        return correctionAttempts.get() < maxAutoReprompts;
    }

    public int getMaxAutoReprompts() {
        return maxAutoReprompts;
    }

    public EnforcerPolicy getPolicy() {
        return policy;
    }

    /**
     * Reset state for a correction attempt (keeps correctionAttempts counter).
     */
    public void resetTurn(String userPrompt) {
        this.lastUserPrompt = userPrompt != null ? userPrompt : "";
        this.lastEvaluatedLength = 0;
        this.lastEvaluationMs = 0;
        this.lastScoreReportMs = 0;
        this.asyncDecision.set(null);
    }

    /**
     * Reset everything for a completely new user turn.
     */
    public void resetFull(String userPrompt) {
        resetTurn(userPrompt);
        correctionAttempts.set(0);
        currentScore.set(1.0);
    }

    // ── RealtimeMonitor implementation ───────────────────────────────────

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

        // Short-circuit when disabled or no rules
        if (!enabled || judge == null || policy == null || !policy.hasRules()
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
            final String promptSnapshot = lastUserPrompt;
            evaluationExecutor.submit(() -> {
                try {
                    EnforcerConversationContext context = conversationWindow != null
                            ? conversationWindow.snapshot() : EnforcerConversationContext.empty();
                    EnforcerDecision decision = judge.evaluatePartialOutput(
                            promptSnapshot, textSnapshot, policy, context);

                    if (decision.isStop()) {
                        double score = 0.0;
                        currentScore.set(score);
                        int attempt = correctionAttempts.incrementAndGet();

                        RealtimeInterruptEvent event;
                        if (canAutoReprompt() && !decision.getCorrectionPrompt().isBlank()) {
                            event = RealtimeInterruptEvent.textViolation(
                                    score, decision.getViolations(), summarize(decision),
                                    decision.getCorrectionPrompt(), agentName, textSnapshot, attempt);
                        } else {
                            event = RealtimeInterruptEvent.blocked(
                                    decision.getViolations(), summarize(decision), agentName);
                        }
                        fireEvent(event);

                        asyncDecision.set(SubprocessAgentRunner.MonitorDecision.interrupt(
                                summarize(decision), decision.getCorrectionPrompt()));
                    } else {
                        double score = decision.isCompliant() ? 1.0 : 0.4;
                        currentScore.set(score);
                        long evalNow = System.currentTimeMillis();
                        if (evalNow - lastScoreReportMs > SCORE_REPORT_INTERVAL_MS) {
                            lastScoreReportMs = evalNow;
                            fireEvent(RealtimeInterruptEvent.scoreUpdate(score, agentName, textSnapshot));
                        }
                    }
                } catch (Exception ignored) {
                    // Evaluation failures are non-fatal during streaming
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
        if (!enabled || judge == null || policy == null || !policy.hasRules()) {
            return SubprocessAgentRunner.MonitorDecision.continueRun();
        }

        try {
            EnforcerConversationContext context = conversationWindow != null
                    ? conversationWindow.snapshot() : EnforcerConversationContext.empty();
            EnforcerToolCallDecision decision = judge.evaluateToolCall(
                    toolName, input, policy, context);

            if (!decision.isAllowed() || decision.isRewrite()) {
                currentScore.set(0.0);
                fireEvent(RealtimeInterruptEvent.toolViolation(
                        toolName, decision.getViolations(),
                        decision.blockMessage(), decision.getCorrectionPrompt(), agentName));
                return SubprocessAgentRunner.MonitorDecision.interrupt(
                        decision.blockMessage(), decision.getCorrectionPrompt());
            }
        } catch (Exception e) {
            currentScore.set(0.0);
            fireEvent(RealtimeInterruptEvent.toolViolation(
                    toolName, List.of("Evaluation failed: " + e.getMessage()),
                    "Enforcer tool-use evaluation failed", "", agentName));
            return SubprocessAgentRunner.MonitorDecision.interrupt(
                    "Enforcer tool-use evaluation failed: " + e.getMessage(), "");
        }

        return SubprocessAgentRunner.MonitorDecision.continueRun();
    }

    // ── Status ────────────────────────────────────────────────────────────

    /**
     * Return a multi-line status string suitable for terminal display.
     */
    public String describeStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Enabled:      ").append(enabled ? "yes" : "no").append('\n');
        sb.append("Score:        ").append(String.format("%.0f%%", currentScore.get() * 100)).append('\n');
        sb.append("Corrections:  ").append(correctionAttempts.get())
                .append(" / ").append(maxAutoReprompts).append('\n');
        sb.append("Judge:        ").append(judge != null ? judge.describe() : "none").append('\n');
        sb.append("Listeners:    ").append(listeners.size());
        return sb.toString();
    }

    /**
     * Return the active enforcer rules text.
     */
    public String getRules() {
        return policy != null ? policy.getRules() : "";
    }

    // ── Turn-level scoring ───────────────────────────────────────────────

    /**
     * Perform a final turn-level scoring after the agent response completes.
     * Returns the final compliance score (0.0 to 1.0).
     */
    public double scoreTurn(String fullOutput) {
        if (judge == null || policy == null || !policy.hasRules()
                || fullOutput == null || fullOutput.isBlank()) {
            return 1.0;
        }

        try {
            EnforcerConversationContext context = conversationWindow != null
                    ? conversationWindow.snapshot() : EnforcerConversationContext.empty();
            EnforcerDecision decision = judge.evaluate(
                    lastUserPrompt, fullOutput, policy, 1, context);

            double score = decision.isCompliant() ? 1.0
                    : decision.isStop() ? 0.0
                    : 0.3;
            currentScore.set(score);
            fireEvent(RealtimeInterruptEvent.turnScored(score, agentName));
            return score;
        } catch (Exception e) {
            return currentScore.get();
        }
    }

    /**
     * Build a correction prompt to send back to the subordinate agent after
     * the monitor has interrupted the turn. Combines the enforcer rules,
     * the original user prompt, and the specific correction guidance.
     */
    public String buildCorrectionMessage(String correctionPrompt) {
        if (correctionPrompt == null || correctionPrompt.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Your previous response was interrupted by the enforcer judge.\n\n");
        sb.append("## Required Correction\n");
        sb.append(correctionPrompt).append("\n\n");
        sb.append("## Original User Prompt\n");
        sb.append(lastUserPrompt).append("\n\n");
        sb.append("Produce the corrected response now.");
        return sb.toString();
    }

    // ── Event dispatch ───────────────────────────────────────────────────

    private void fireEvent(RealtimeInterruptEvent event) {
        for (RealtimeInterruptListener listener : listeners) {
            try {
                listener.onInterruptEvent(event);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private String summarize(EnforcerDecision decision) {
        if (decision == null) return "rule violation";
        if (!decision.getViolations().isEmpty()) {
            return String.join("; ", decision.getViolations());
        }
        if (!decision.getReasoning().isBlank()) {
            return decision.getReasoning();
        }
        return decision.getSeverity();
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
}
