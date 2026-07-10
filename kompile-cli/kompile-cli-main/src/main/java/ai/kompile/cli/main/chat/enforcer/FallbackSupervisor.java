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

import java.util.List;
import java.util.Locale;

/**
 * The agent-fallback decision policy (design WP13): watches per-turn judge scores for one agent
 * session and decides when performance has degraded enough to switch to the next agent — and to
 * which. Promoted from the proven test-local design in {@code TuiJudgeAndInterruptTest}.
 *
 * <p>Only the DECISION lives here (pure, deterministic, unit-testable). It is decoupled from both
 * the scoring source (the caller feeds a score from the real {@code EnforcerJudge}) and the
 * ACTUATION (the host performs the interrupt + agent switch + context-preserving resume) — the same
 * separation used by {@code TurnIdleDetector} / {@code FrameSettleGate}. Three triggers, checked in
 * priority order:</p>
 * <ol>
 *   <li><b>catastrophic</b> — a single turn scores at/below {@code catastrophicThreshold};</li>
 *   <li><b>consecutive</b> — {@code consecutiveFailureLimit} failing turns in a row;</li>
 *   <li><b>cumulative</b> — the running total drops to/below {@code cumulativeScoreFloor}.</li>
 * </ol>
 *
 * <p>On a fallback the supervisor advances to the next distinct agent in {@code agentPriority} and
 * resets its running scores (fresh start for the new agent). When no other agent remains it becomes
 * {@link #isExhausted() exhausted} and stops triggering.</p>
 */
public final class FallbackSupervisor {

    /** When to trigger a fallback, and the agent-switch order. */
    public record Config(int cumulativeScoreFloor,
                         int consecutiveFailureLimit,
                         int catastrophicThreshold,
                         List<String> agentPriority) {

        private static final List<String> DEFAULT_PRIORITY =
                List.of("claude", "codex", "gemini", "qwen", "opencode");

        public static Config defaults() {
            return new Config(-10, 3, -6, DEFAULT_PRIORITY);
        }

        public static Config strict() {
            return new Config(-5, 2, -4, DEFAULT_PRIORITY);
        }

        public static Config lenient() {
            return new Config(-20, 5, -10, DEFAULT_PRIORITY);
        }
    }

    /** The outcome of recording one turn's score. */
    public record Decision(boolean fallback,
                           String fromAgent,
                           String toAgent,
                           String reason,
                           int cumulativeScore,
                           int consecutiveFailures,
                           int triggeringTurnScore,
                           boolean exhausted) {

        static Decision cont(String agent, int cumulative, int consecutive, boolean exhausted) {
            return new Decision(false, agent, agent, "", cumulative, consecutive, 0, exhausted);
        }
    }

    private final Config config;
    private final boolean autoAdvance;
    private String currentAgent;
    private int agentIndex;
    private int cumulativeScore;
    private int consecutiveFailures;
    private boolean exhausted;

    /** Actuation-style supervisor: on a fallback it advances its own agent + resets (auto-advance). */
    public FallbackSupervisor(Config config, String initialAgent) {
        this(config, initialAgent, true);
    }

    /**
     * @param autoAdvance when true (actuation), a fallback advances {@link #currentAgent()} to the
     *     target and resets the running scores. When false (observe-only advisory), a fallback only
     *     RECOMMENDS a target (via the {@link Decision}) and resets the scores to warn-once, but keeps
     *     {@code currentAgent} unchanged — so the tracked agent never diverges from the un-switched
     *     reality when the host merely surfaces a suggestion.
     */
    public FallbackSupervisor(Config config, String initialAgent, boolean autoAdvance) {
        this.config = config;
        this.autoAdvance = autoAdvance;
        this.currentAgent = initialAgent;
        this.agentIndex = Math.max(0, config.agentPriority().indexOf(initialAgent));
    }

    /**
     * Map one enforcer turn outcome to a fallback score (pure): an accepted turn is positive; a
     * rejected turn is negative by the judge's severity, with {@code critical} reaching the
     * catastrophic band. Used by hosts that feed enforcer results to the supervisor.
     */
    public static int scoreForOutcome(boolean accepted, String severity) {
        if (accepted) {
            return 1;
        }
        String sev = severity == null ? "" : severity.toLowerCase(Locale.ROOT);
        return switch (sev) {
            case "critical" -> -6;   // reaches the default catastrophic threshold
            case "error" -> -3;
            case "warning" -> -1;
            default -> -2;           // unknown / info-level rejection
        };
    }

    /**
     * Record one turn's judge score. {@code failed} marks a rule-failing turn (feeds the consecutive
     * counter — a neutral 0-score turn need not be a failure). Returns whether a fallback should fire
     * and, if so, the target agent; on a fallback the supervisor's own agent/scores are advanced.
     */
    public Decision recordTurnScore(int score, boolean failed) {
        if (exhausted) {
            return Decision.cont(currentAgent, cumulativeScore, consecutiveFailures, true);
        }
        cumulativeScore += score;
        consecutiveFailures = failed ? consecutiveFailures + 1 : 0;

        String reason = triggerReason(score);
        if (reason == null) {
            return Decision.cont(currentAgent, cumulativeScore, consecutiveFailures, false);
        }

        String fromAgent = currentAgent;
        String toAgent = selectNextAgent();
        if (toAgent.equals(fromAgent)) {
            // No other agent to switch to — mark exhausted, do not fall back.
            exhausted = true;
            return new Decision(false, fromAgent, fromAgent, reason,
                    cumulativeScore, consecutiveFailures, score, true);
        }

        Decision decision = new Decision(true, fromAgent, toAgent, reason,
                cumulativeScore, consecutiveFailures, score, false);
        if (autoAdvance) {
            // Actuation: advance to the new agent (fresh start).
            currentAgent = toAgent;
            agentIndex = Math.max(0, config.agentPriority().indexOf(toAgent));
        }
        // Reset the running scores either way: after actuation it's a fresh agent; in advisory mode
        // it prevents re-warning every subsequent turn on the same still-breached counters.
        cumulativeScore = 0;
        consecutiveFailures = 0;
        return decision;
    }

    private String triggerReason(int latestScore) {
        if (latestScore <= config.catastrophicThreshold()) {
            return "CATASTROPHIC: single turn scored " + latestScore
                    + " (threshold: " + config.catastrophicThreshold() + ")";
        }
        if (consecutiveFailures >= config.consecutiveFailureLimit()) {
            return "CONSECUTIVE: " + consecutiveFailures + " consecutive failures (limit: "
                    + config.consecutiveFailureLimit() + ")";
        }
        if (cumulativeScore <= config.cumulativeScoreFloor()) {
            return "CUMULATIVE: total score " + cumulativeScore
                    + " below floor " + config.cumulativeScoreFloor();
        }
        return null;
    }

    private String selectNextAgent() {
        List<String> priority = config.agentPriority();
        for (int i = 1; i < priority.size(); i++) {
            String candidate = priority.get((agentIndex + i) % priority.size());
            if (!candidate.equals(currentAgent)) {
                return candidate;
            }
        }
        return currentAgent; // no other agent available
    }

    public String currentAgent() {
        return currentAgent;
    }

    public int cumulativeScore() {
        return cumulativeScore;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public boolean isExhausted() {
        return exhausted;
    }
}
