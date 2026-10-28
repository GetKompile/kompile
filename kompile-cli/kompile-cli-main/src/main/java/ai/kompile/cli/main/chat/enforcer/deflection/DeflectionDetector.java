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

import java.util.List;

/**
 * Pure deterministic deflection scorer: combines the Tier 0–2 signals with the
 * receipts check ({@code verdict = signal AND NOT evidence}) and applies the
 * false-positive guards. No I/O — trivially unit-testable.
 *
 * <p>Escalation actions (LOG_ONLY / CORRECT / STOP) are decided against the
 * caller-supplied correction budget; the detector itself is stateless.</p>
 */
public final class DeflectionDetector {

    /** Immutable tuning for one detector. */
    public record Options(double confidenceThreshold, boolean requireReceipts) {
        public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.7;

        public Options {
            if (!Double.isFinite(confidenceThreshold)) {
                confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;
            }
            confidenceThreshold = Math.max(0.0, Math.min(1.0, confidenceThreshold));
        }

        public static Options defaults() {
            return new Options(DEFAULT_CONFIDENCE_THRESHOLD, true);
        }
    }

    private final DeflectionPatternLibrary library;
    private final SemanticScorer semanticScorer;
    private final Options options;

    /** Optional Tier 1/2 scorer hook; null means pattern-only. */
    @FunctionalInterface
    public interface SemanticScorer {
        /**
         * Score the best deflection-paraphrase match in the output.
         * Returns null when no match clears the semantic threshold.
         */
        DeflectionSignal score(String output);
    }

    public DeflectionDetector(DeflectionPatternLibrary library, Options options) {
        this(library, null, options);
    }

    public DeflectionDetector(DeflectionPatternLibrary library, SemanticScorer semanticScorer,
                              Options options) {
        this.library = library == null ? DeflectionPatternLibrary.defaults() : library;
        this.semanticScorer = semanticScorer;
        this.options = options == null ? Options.defaults() : options;
    }

    /**
     * Evaluate one turn. {@code output} is the agent's text; {@code toolTraceText} is the
     * turn's executed-tool summary (tool names + arguments, possibly empty).
     *
     * @param userPrompt     the user's original prompt, for the echoed-language guard:
     *                       a deflection phrase the USER used is exempt — the agent
     *                       discussing the user's phrase is not deflecting
     * @param correctionsUsed how many deflection corrections were already spent this turn
     * @param maxCorrections  the configured per-turn correction budget
     */
    public DeflectionVerdict evaluate(String userPrompt, String output, String toolTraceText,
                                      int correctionsUsed, int maxCorrections) {
        if (output == null || output.isBlank()) {
            return DeflectionVerdict.none();
        }

        List<DeflectionSignal> signals = library.findSignals(output);
        if (semanticScorer != null) {
            DeflectionSignal semantic = semanticScorer.score(output);
            if (semantic != null) {
                signals.add(semantic);
            }
        }
        if (signals.isEmpty()) {
            return DeflectionVerdict.none();
        }

        // Guard: echoed user language — drop signals whose canonical phrase the user used.
        if (userPrompt != null && !userPrompt.isBlank()) {
            String lowerPrompt = userPrompt.toLowerCase(java.util.Locale.ROOT);
            signals.removeIf(s -> lowerPrompt.contains(s.phrase().toLowerCase(java.util.Locale.ROOT)));
            if (signals.isEmpty()) {
                return DeflectionVerdict.none();
            }
        }

        // Guard: ownership acknowledgment suppresses the verdict outright. Scan text
        // OUTSIDE the signal spans — "my error" inside "not my error" is not ownership.
        String outsideSignals = output;
        for (DeflectionSignal s : signals) {
            if (!s.matched().isEmpty()) {
                outsideSignals = outsideSignals.replace(s.matched(), " ");
            }
        }
        if (library.hasOwnershipLanguage(outsideSignals)) {
            return DeflectionVerdict.passNoted(signals, DeflectionEvidence.empty(),
                    "ownership language present — not deflection");
        }

        // (Quote/echo guard is applied at collection time: quoted matches never
        // become signals, so anything reaching here is an unquoted claim.)

        // Receipts half of the contract.
        DeflectionEvidence evidence = options.requireReceipts()
                ? DeflectionEvidence.scan(output, toolTraceText)
                : DeflectionEvidence.empty();
        if (!evidence.isEmpty()) {
            return DeflectionVerdict.passNoted(signals, evidence,
                    "deflection phrasing WITH receipts (" + evidence.summarize() + ") — allowed");
        }

        // Signal without receipts: pick the strongest signal and route by threshold/budget.
        DeflectionSignal strongest = signals.get(0);
        for (DeflectionSignal s : signals) {
            if (s.score() > strongest.score()) {
                strongest = s;
            }
        }

        if (strongest.score() < options.confidenceThreshold()) {
            return DeflectionVerdict.logOnly(signals, evidence,
                    "sub-threshold deflection signal ("
                            + strongest.describe() + ") — logged, not corrected");
        }

        if (maxCorrections > 0 && correctionsUsed >= maxCorrections) {
            return DeflectionVerdict.stop(signals, evidence,
                    "deflection persisted through the full correction budget");
        }
        return DeflectionVerdict.correct(signals, evidence,
                "non-ownership claim without receipts: " + strongest.describe());
    }
}
