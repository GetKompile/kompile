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
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.enforcer.deflection;

import ai.kompile.cli.main.chat.enforcer.EnforcerDecision;
import ai.kompile.cli.main.chat.enforcer.EnforcerDiagnostics;
import ai.kompile.cli.main.chat.enforcer.JudgementLog;
import ai.kompile.cli.main.chat.enforcer.JudgementRecord;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The deflection lane orchestrator (the {@code DirectionJudge} analogue): owns per-turn
 * correction counters, the cross-turn deflection streak, and judgement logging, and maps
 * {@link DeflectionVerdict}s onto the existing {@link EnforcerDecision} currency the chat
 * loop already understands.
 *
 * <p>Deterministic-only: no LLM judge, no backend, always available when enabled.
 * Fail-open by construction: any detector failure yields a no-op verdict, never a
 * correction. Repeated identical deflection escalates per the design's freeze pattern.</p>
 */
public final class DeflectionMonitor {

    /** Immutable tuning from {@code EnforcerConfig}. */
    public record Options(String mode, boolean reportOnly, double confidenceThreshold,
                          int maxCorrections, int crossTurnLimit, boolean requireReceipts) {

        public static final int HARD_MAX_CORRECTIONS = 4;
        public static final int HARD_MAX_CROSS_TURN_LIMIT = 20;

        public Options {
            mode = mode == null || mode.isBlank() ? "pattern" : mode.toLowerCase(Locale.ROOT);
            confidenceThreshold = Double.isFinite(confidenceThreshold)
                    ? Math.max(0.0, Math.min(1.0, confidenceThreshold)) : 0.7;
            maxCorrections = Math.max(0, Math.min(HARD_MAX_CORRECTIONS, maxCorrections));
            crossTurnLimit = Math.max(0, Math.min(HARD_MAX_CROSS_TURN_LIMIT, crossTurnLimit));
        }

        public boolean semantic() {
            return "semantic".equalsIgnoreCase(mode);
        }

        public boolean enabled() {
            return !"off".equalsIgnoreCase(mode);
        }
    }

    /** Result of one monitor check for the chat loop to act on. */
    public record Outcome(DeflectionVerdict verdict, EnforcerDecision decision,
                          boolean reportOnlyOverride, String correctionPrompt) {
        public boolean shouldCorrect() {
            return !reportOnlyOverride && verdict.isActionable()
                    && verdict.action() == DeflectionVerdict.Action.CORRECT;
        }

        public boolean shouldStop() {
            return !reportOnlyOverride && verdict.action() == DeflectionVerdict.Action.STOP;
        }

        public boolean shouldLog() {
            return verdict.action() == DeflectionVerdict.Action.LOG_ONLY
                    || (reportOnlyOverride && verdict.isActionable());
        }
    }

    private final DeflectionDetector detector;
    private final Options options;
    private final JudgementLog judgementLog;

    // Per-turn state (reset in beginTurn).
    private final AtomicInteger correctionsThisTurn = new AtomicInteger();
    private volatile String lastDeflectionFingerprint = "";

    // Cross-turn state (reset only on a clean turn).
    private final AtomicInteger consecutiveDeflectingTurns = new AtomicInteger();
    private volatile boolean currentTurnDeflected = false;

    public DeflectionMonitor(DeflectionDetector detector, Options options, JudgementLog judgementLog) {
        this.detector = detector;
        this.options = options;
        this.judgementLog = judgementLog;
    }

    public Options getOptions() {
        return options;
    }

    // ── Turn lifecycle ───────────────────────────────────────────────────────

    /** Call once per user turn, before the first model iteration. */
    public void beginTurn() {
        correctionsThisTurn.set(0);
        lastDeflectionFingerprint = "";
        currentTurnDeflected = false;
    }

    /**
     * Call once per turn end. A turn in which the agent produced receipts (or no signal
     * fired) resets the streak; a deflecting turn increments it.
     */
    public void completeTurn(boolean countTurn) {
        if (!countTurn) {
            return;
        }
        if (currentTurnDeflected) {
            consecutiveDeflectingTurns.incrementAndGet();
        } else {
            consecutiveDeflectingTurns.set(0);
        }
    }

    public int getConsecutiveDeflectingTurns() {
        return consecutiveDeflectingTurns.get();
    }

    public int getCorrectionsThisTurn() {
        return correctionsThisTurn.get();
    }

    /**
     * A child-scoped view for subagent supervision: same detector and options, but its own
     * correction counter and fingerprint, reset per child completion attempt. Verdicts are
     * translated to plain feedback text (null = accepted), matching the
     * {@code DirectSubagentSupervision.DeflectionReview} contract. Cross-turn streak stays
     * with the parent monitor — the parent turn is the session's drift unit.
     */
    public ChildReview childReview() {
        return new ChildReview();
    }

    /** Stateful one-child review session created by {@link #childReview()}. */
    public final class ChildReview {
        private final AtomicInteger childCorrections = new AtomicInteger();
        private volatile String childFingerprint = "";

        private ChildReview() {
        }

        /**
         * Review one child completion attempt.
         *
         * @return correction feedback for the child, or null when accepted
         * @throws IllegalStateException escalation requires blocking the child outright
         */
        public String review(String childPrompt, String output, String toolTraceText) {
            if (!options.enabled()) return null;
            DeflectionVerdict verdict;
            try {
                verdict = detector.evaluate(childPrompt, output, toolTraceText,
                        childCorrections.get(), options.maxCorrections());
            } catch (Exception failure) {
                EnforcerDiagnostics.alert(
                        "[deflection] child review failed open: " + failure.getMessage());
                return null;
            }
            if (options.reportOnly()) {
                record(verdict, toDecision(verdict, childPrompt, false), verdict.isActionable());
                return null;
            }
            if (!verdict.isActionable()) {
                return null;
            }
            String fingerprint = strongestPhrase(verdict);
            boolean repeated = !fingerprint.isEmpty()
                    && fingerprint.equals(childFingerprint);
            childFingerprint = fingerprint;
            childCorrections.incrementAndGet();

            if (verdict.action() == DeflectionVerdict.Action.STOP
                    || childCorrections.get() > options.maxCorrections()) {
                throw new IllegalStateException(DeflectionMandate.stopReason(
                        signalSummary(verdict), childCorrections.get(), options.maxCorrections())
                        + " " + verdict.reasoning());
            }
            String mandate = repeated
                    ? DeflectionMandate.evidenceFirstMandate(childPrompt, signalSummary(verdict))
                    : DeflectionMandate.ownershipMandate(childPrompt, signalSummary(verdict));
            return "Deflection without receipts: " + signalSummary(verdict) + "\n"
                    + mandate;
        }

        public int getCorrections() {
            return childCorrections.get();
        }
    }

    // ── Evaluation ───────────────────────────────────────────────────────────

    /**
     * Check one model iteration's output. Deterministic, cheap, and never throws.
     *
     * @param userPrompt    the user's original prompt (echo guard + mandate context)
     * @param output        the assistant output under review
     * @param toolTraceText executed-tools summary for the turn so far (may be null)
     */
    public Outcome check(String userPrompt, String output, String toolTraceText) {
        if (!options.enabled()) {
            return new Outcome(DeflectionVerdict.none(), EnforcerDecision.pass("Deflection monitoring is off"), false, null);
        }
        DeflectionVerdict verdict;
        try {
            verdict = detector.evaluate(userPrompt, output, toolTraceText,
                    correctionsThisTurn.get(), options.maxCorrections());
        } catch (Exception failure) {
            EnforcerDiagnostics.alert("[deflection] detector failed open: " + failure.getMessage());
            verdict = DeflectionVerdict.none();
        }

        // Cross-turn escalation: streak at/over the limit converts a correction into a stop.
        if (verdict.action() == DeflectionVerdict.Action.CORRECT
                && options.crossTurnLimit() > 0
                && consecutiveDeflectingTurns.get() + 1 >= options.crossTurnLimit()) {
            verdict = DeflectionVerdict.stop(verdict.signals(), verdict.evidence(),
                    "cross-turn deflection streak " + (consecutiveDeflectingTurns.get() + 1)
                            + "/" + options.crossTurnLimit() + ": " + verdict.reasoning());
        }

        currentTurnDeflected = currentTurnDeflected || verdict.isActionable();

        // Repeated identical deflection (same strongest phrase) wastes the budget on
        // correction 1 only — escalation mirrors the ActivateTools freeze pattern.
        String fingerprint = strongestPhrase(verdict);
        boolean repeated = !fingerprint.isEmpty() && fingerprint.equals(lastDeflectionFingerprint);
        lastDeflectionFingerprint = fingerprint;

        EnforcerDecision decision = toDecision(verdict, userPrompt, repeated);

        boolean override = options.reportOnly() && verdict.isActionable();
        if (verdict.action() == DeflectionVerdict.Action.CORRECT) {
            correctionsThisTurn.incrementAndGet();
        }

        String correctionPrompt = decision.getCorrectionPrompt();
        record(verdict, decision, override);
        return new Outcome(verdict, decision, override, correctionPrompt);
    }

    // ── Mapping to the existing decision currency ────────────────────────────

    private EnforcerDecision toDecision(DeflectionVerdict verdict, String userPrompt,
                                        boolean repeated) {
        List<String> violations = List.of(signalLine(verdict));
        switch (verdict.action()) {
            case NONE:
                return EnforcerDecision.pass("No deflection signal");
            case PASS_NOTED:
                return EnforcerDecision.pass(verdict.reasoning());
            case LOG_ONLY:
                return EnforcerDecision.pass(verdict.reasoning());
            case CORRECT:
                String mandate = repeated
                        ? DeflectionMandate.evidenceFirstMandate(userPrompt, signalSummary(verdict))
                        : DeflectionMandate.ownershipMandate(userPrompt, signalSummary(verdict));
                return EnforcerDecision.fail(violations, mandate, verdict.reasoning());
            case STOP:
                return EnforcerDecision.stop(violations,
                        DeflectionMandate.stopReason(signalSummary(verdict),
                                correctionsThisTurn.get(), options.maxCorrections())
                                + " " + verdict.reasoning());
            default:
                return EnforcerDecision.pass(verdict.reasoning());
        }
    }

    private static String signalLine(DeflectionVerdict verdict) {
        String summary = signalSummary(verdict);
        return "Deflection without receipts: " + summary;
    }

    private static String signalSummary(DeflectionVerdict verdict) {
        return verdict.signals().isEmpty() ? "unknown" : verdict.signalSummary();
    }

    private static String strongestPhrase(DeflectionVerdict verdict) {
        if (verdict.signals().isEmpty()) {
            return "";
        }
        DeflectionSignal strongest = verdict.signals().get(0);
        for (DeflectionSignal s : verdict.signals()) {
            if (s.score() > strongest.score()) {
                strongest = s;
            }
        }
        return strongest.phrase();
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    private void record(DeflectionVerdict verdict, EnforcerDecision decision, boolean override) {
        if (judgementLog == null) {
            return;
        }
        try {
            JudgementRecord record = JudgementRecord.builder()
                    .phase("DEFLECTION")
                    .judgeMode("policy")
                    .backend("deflection-monitor")
                    .compliant(decision.isCompliant())
                    .stop(decision.isStop())
                    .severity(decision.getSeverity())
                    .violations(decision.getViolations())
                    .correctionPrompt(decision.getCorrectionPrompt())
                    .reasoning(verdict.reasoning())
                    .status(override ? "REPORT_ONLY" : decision.isStop() ? "STOP" : "CHECKED")
                    .build();
            judgementLog.record(record);
        } catch (Exception ignored) {
            // Logging must never break enforcement.
        }
    }
}
