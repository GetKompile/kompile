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

package ai.kompile.crawl.graph;

import java.util.Map;

/**
 * Rich learning metrics captured during the WEIGHT_LEARNING sub-stage of the
 * ENRICHMENT crawl step (inside {@link GraphHydrationOrchestrator#run}).
 *
 * <p>An instance is constructed once per {@code run()} invocation from the
 * {@link ai.kompile.knowledgegraph.reasoning.RegroundResult} and the
 * {@link ai.kompile.knowledgegraph.reasoning.FactPromotionTracker} aggregate queries.
 * It is stored on {@link HydrationResult#learningMetrics()} and also emitted as a
 * structured message via the progress callback under the stage key
 * {@link GraphHydrationOrchestrator#STAGE_LEARNING_METRICS}.</p>
 *
 * <h3>Field catalogue</h3>
 * <table>
 *   <tr><th>Field</th><th>Source</th><th>Available now?</th></tr>
 *   <tr><td>{@code factVersionsWritten}</td>
 *       <td>{@link ai.kompile.knowledgegraph.reasoning.RegroundResult#versionsWritten()}</td>
 *       <td>Yes</td></tr>
 *   <tr><td>{@code retractedAtomCount}</td>
 *       <td>{@link ai.kompile.knowledgegraph.reasoning.RegroundResult#retractedAtomKeys()}</td>
 *       <td>Yes</td></tr>
 *   <tr><td>{@code promotedAtomCount}</td>
 *       <td>{@link ai.kompile.knowledgegraph.reasoning.FactPromotionTracker#promotedAtomCount(long)}</td>
 *       <td>Yes (in-memory, may be 0 on first cascade)</td></tr>
 *   <tr><td>{@code totalCorroboration}</td>
 *       <td>{@link ai.kompile.knowledgegraph.reasoning.FactPromotionTracker#totalCorroboration(long)}</td>
 *       <td>Yes (in-memory)</td></tr>
 *   <tr><td>{@code bandCounts}</td>
 *       <td>{@link ai.kompile.knowledgegraph.reasoning.FactPromotionTracker#bandCounts(long)}</td>
 *       <td>Yes — all 5 StrengthBand values present</td></tr>
 *   <tr><td>{@code pslRulesCount}</td>
 *       <td>Number of PSL rules in the program (known to the orchestrator at call time
 *           when the tracker is present; -1 if FactPromotionTracker absent)</td>
 *       <td>Partial — program not exposed by IncrementalReasoningOrchestrator API yet</td></tr>
 *   <tr><td>{@code ruleWeightsUpdated}</td>
 *       <td>Count of PSL rules whose weight changed by more than epsilon during mini-batch
 *           training — requires before/after program comparison inside
 *           {@code IncrementalReasoningOrchestrator.doReground()}; NOT yet returned to caller</td>
 *       <td>NOT AVAILABLE — deferred (see {@link #WEIGHT_DELTA_UNAVAILABLE})</td></tr>
 *   <tr><td>{@code meanWeightDelta} / {@code maxWeightDelta}</td>
 *       <td>Mean/max |w_after - w_before| across updated rules — same gap as above</td>
 *       <td>NOT AVAILABLE — deferred</td></tr>
 *   <tr><td>{@code contradictionsDetected}</td>
 *       <td>Count of contradicting fact pairs found by {@code ContradictionDetector} inside
 *           the re-ground — not returned in {@code RegroundResult}</td>
 *       <td>NOT AVAILABLE — deferred</td></tr>
 *   <tr><td>{@code mebnLearningRan}</td>
 *       <td>Whether the MEBN gradient-descent step ran (throttled every N cascades) —
 *           internal decision, not surfaced by {@code IncrementalReasoningOrchestrator}</td>
 *       <td>NOT AVAILABLE — deferred</td></tr>
 * </table>
 *
 * <h3>KbConfig tunables needed (for the lead to add)</h3>
 * <ul>
 *   <li>{@code kompile.kb.learning.metricsEnabled} — master on/off for metric collection
 *       (to allow zero-overhead no-op in hot paths)</li>
 * </ul>
 *
 * @param factVersionsWritten   inferred-fact versions written during MAP derivation;
 *                              0 if derivation was skipped or returned empty
 * @param retractedAtomCount    implicit retraction count — atom keys that were in the
 *                              inferred store before this run but absent from the new MAP output
 * @param promotedAtomCount     atoms whose StrengthBand advanced (higher tier) at least once
 *                              since the promotion tracker was last initialised for this fact sheet
 * @param totalCorroboration    sum of per-atom corroboration event counts across the fact sheet
 * @param bandCounts            distribution of atoms across the 5 StrengthBands (ESTABLISHED/HIGH/
 *                              PROBABLE/SPECULATIVE/SUPPRESSED); all bands present, zero if no data
 * @param ruleWeightsUpdated    count of PSL rules whose weights changed; -1 = NOT YET AVAILABLE
 *                              (requires {@link #WEIGHT_DELTA_UNAVAILABLE} follow-up)
 * @param meanWeightDelta       mean |Δw| across updated rules; {@link Double#NaN} when not available
 * @param maxWeightDelta        max |Δw| across updated rules; {@link Double#NaN} when not available
 * @param derivationSkipped     true when derivation was skipped (no reasoning orchestrator,
 *                              or config disabled); remaining fields are all zero/empty
 */
public record LearningMetrics(
        int     factVersionsWritten,
        int     retractedAtomCount,
        int     promotedAtomCount,
        int     totalCorroboration,
        Map<String, Integer> bandCounts,
        int     ruleWeightsUpdated,
        double  meanWeightDelta,
        double  maxWeightDelta,
        boolean derivationSkipped) {

    /**
     * Sentinel value for {@code ruleWeightsUpdated}: the count is not yet available because
     * {@code IncrementalReasoningOrchestrator.doReground()} does not return the before/after
     * PSL program to its caller. To fix: return a richer {@code RegroundResult} that includes
     * a {@code Map<String,Double>} of per-rule weight deltas from the mini-batch training step,
     * or add a dedicated {@code PslWeightEvent} published during the cascade.
     */
    public static final int WEIGHT_DELTA_UNAVAILABLE = -1;

    /** Zero-filled metrics for when derivation was skipped entirely. */
    public static LearningMetrics skipped() {
        return new LearningMetrics(
                0, 0, 0, 0,
                java.util.Map.of(),
                WEIGHT_DELTA_UNAVAILABLE,
                Double.NaN, Double.NaN,
                true);
    }

    /**
     * Render a concise human-readable summary suitable for a progress callback message
     * or log line.  Example:
     * <pre>
     * LearningMetrics[versions=7 retracted=2 promoted=3 corroboration=18
     *   bands={ESTABLISHED=1, HIGH=3, PROBABLE=5, SPECULATIVE=8, SUPPRESSED=1}
     *   ruleWeightsUpdated=N/A meanDelta=N/A maxDelta=N/A]
     * </pre>
     */
    public String summary() {
        StringBuilder sb = new StringBuilder("LearningMetrics[");
        if (derivationSkipped) {
            sb.append("SKIPPED]");
            return sb.toString();
        }
        sb.append("versions=").append(factVersionsWritten)
          .append(" retracted=").append(retractedAtomCount)
          .append(" promoted=").append(promotedAtomCount)
          .append(" corroboration=").append(totalCorroboration);
        if (!bandCounts.isEmpty()) {
            sb.append(" bands={");
            boolean first = true;
            // Emit in canonical StrengthBand ordinal order for reproducible output
            String[] orderedBands = {"ESTABLISHED", "HIGH", "PROBABLE", "SPECULATIVE", "SUPPRESSED"};
            for (String band : orderedBands) {
                if (!first) sb.append(", ");
                sb.append(band).append('=').append(bandCounts.getOrDefault(band, 0));
                first = false;
            }
            sb.append('}');
        }
        sb.append(" ruleWeightsUpdated=");
        if (ruleWeightsUpdated == WEIGHT_DELTA_UNAVAILABLE) {
            sb.append("N/A");
        } else {
            sb.append(ruleWeightsUpdated);
        }
        sb.append(" meanDelta=");
        sb.append(Double.isNaN(meanWeightDelta) ? "N/A" : String.format("%.4f", meanWeightDelta));
        sb.append(" maxDelta=");
        sb.append(Double.isNaN(maxWeightDelta) ? "N/A" : String.format("%.4f", maxWeightDelta));
        sb.append(']');
        return sb.toString();
    }
}
