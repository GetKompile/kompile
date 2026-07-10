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

package ai.kompile.process.discovery.mining.entail;

import ai.kompile.graph.reasoning.confidence.Opinion;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of {@link ProcessEntailment}: the precedence relation the HL-MRF engine settled on over a
 * discovered process, with per-pair provenance (supporting facts, activated rules), temporal
 * verdicts against the event log's valid time, and a fused subjective-logic opinion.
 *
 * @param precedences          every evaluated ordered pair, highest posterior first (includes the
 *                             deliberately-suppressed reverse directions — transparency over tidiness)
 * @param ruleTexts            the compiled PSL rules the entailment ran with (for lineage/persistence)
 * @param transitivityApplied  false when the activity count exceeded
 *                             {@link ProcessEntailment#MAX_TRANSITIVITY_ACTIVITIES} and the O(n³)
 *                             transitive rule was skipped — never silently
 * @param runId                the {@code EntailmentEngine} inference run id
 */
public record ProcessEntailmentResult(
        List<EntailedPrecedence> precedences,
        List<String> ruleTexts,
        boolean transitivityApplied,
        String runId) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ProcessEntailmentResult {
        precedences = (precedences == null) ? List.of() : List.copyOf(precedences);
        ruleTexts = (ruleTexts == null) ? List.of() : List.copyOf(ruleTexts);
    }

    public static ProcessEntailmentResult empty() {
        return new ProcessEntailmentResult(List.of(), List.of(), false, "");
    }

    public boolean isEmpty() {
        return precedences.isEmpty();
    }

    /** Pairs the MAP state accepted (posterior ≥ 0.5) — the precedence relation of the process. */
    public List<EntailedPrecedence> accepted() {
        return precedences.stream().filter(p -> p.posterior() >= 0.5).toList();
    }

    /** Accepted pairs derived only by rules (no directly-follows arc) — the genuinely new orderings. */
    public List<EntailedPrecedence> entailedOnly() {
        return accepted().stream().filter(p -> !p.observed()).toList();
    }

    /**
     * Non-refuted, non-concurrent pairs at or above the threshold — the KB-assertable /
     * materializable subset. Concurrent pairs (interval overlap outweighing both directions) are
     * excluded like refuted ones: asserting {@code precedes} over activities that demonstrably run
     * together would be a false ordering.
     */
    public List<EntailedPrecedence> assertable(double threshold) {
        return precedences.stream()
                .filter(p -> !p.temporallyRefuted() && !p.concurrent() && p.posterior() >= threshold)
                .toList();
    }

    /**
     * One fused opinion over the accepted precedence relation: per-pair posteriors become opinions
     * (they all share the same event log, so they are averaged rather than cumulatively fused — the
     * evidence is dependent), then the whole is discounted by the temporally-refuted fraction.
     */
    public Opinion fusedOpinion() {
        List<EntailedPrecedence> accepted = accepted();
        if (accepted.isEmpty()) {
            return Opinion.vacuous();
        }
        List<Opinion> perPair = new ArrayList<>(accepted.size());
        int refuted = 0;
        for (EntailedPrecedence p : accepted) {
            perPair.add(Opinion.fromBayesianPosterior(p.posterior(), 0.5));
            if (p.temporallyRefuted()) {
                refuted++;
            }
        }
        Opinion fused = Opinion.averageFuse(perPair.toArray(new Opinion[0]));
        double keep = 1.0 - ((double) refuted / accepted.size());
        return fused.discount(keep);
    }

    /**
     * One evaluated ordered activity pair.
     *
     * @param from               source activity display label
     * @param to                 target activity display label
     * @param posterior          HL-MRF MAP soft truth of {@code Precedes(from, to)} in [0,1]
     * @param observed           a directly-follows arc {@code from→to} exists in the DFG (vs derived
     *                           purely by rules — transitivity / declarative constraints)
     * @param temporallyRefuted  the event log's timestamps contradict this ordering (strict majority
     *                           of dated traces saw {@code to} first)
     * @param orderedEvidence    dated traces whose intervals put {@code from} wholly before {@code to}
     *                           (Allen BEFORE/MEETS)
     * @param reversedEvidence   dated traces whose intervals put {@code to} wholly before {@code from}
     * @param overlappedEvidence dated traces whose activity INTERVALS intersect (Allen
     *                           OVERLAPS/STARTS/DURING/FINISHES/EQUAL either way) — evidence of
     *                           concurrency, abstaining from direction
     * @param concurrent         the recency-weighted overlap outweighs both directions combined —
     *                           the pair runs together, so {@code precedes} must not be asserted
     * @param temporalOpinion    Beta-evidence opinion over the dated traces; null when no trace dates
     *                           both activities (abstention, not support)
     * @param supportingFactKeys observed atoms that supported the conclusion (activity labels inlined)
     * @param activatedRules     ground rules that fired for it (activity labels inlined)
     * @param atomKey            the raw engine atom key, e.g. {@code Precedes(n0, n3)}
     */
    public record EntailedPrecedence(
            String from,
            String to,
            double posterior,
            boolean observed,
            boolean temporallyRefuted,
            long orderedEvidence,
            long reversedEvidence,
            long overlappedEvidence,
            boolean concurrent,
            Opinion temporalOpinion,
            List<String> supportingFactKeys,
            List<String> activatedRules,
            String atomKey) implements Serializable {

        private static final long serialVersionUID = 1L;

        public EntailedPrecedence {
            supportingFactKeys = (supportingFactKeys == null) ? List.of() : List.copyOf(supportingFactKeys);
            activatedRules = (activatedRules == null) ? List.of() : List.copyOf(activatedRules);
        }

        /** Point-timestamp-era constructor (no interval evidence) — kept for existing callers/tests. */
        public EntailedPrecedence(String from, String to, double posterior, boolean observed,
                                  boolean temporallyRefuted, long orderedEvidence, long reversedEvidence,
                                  Opinion temporalOpinion, List<String> supportingFactKeys,
                                  List<String> activatedRules, String atomKey) {
            this(from, to, posterior, observed, temporallyRefuted, orderedEvidence, reversedEvidence,
                    0L, false, temporalOpinion, supportingFactKeys, activatedRules, atomKey);
        }

        /**
         * The canonical KB atom for this pair ({@link ProcessAtoms#precedesAtom}):
         * {@code precedes("A", "B")}. Uses display labels only — node ids and paths must never
         * appear in atom keys.
         */
        public String kbAtomKey() {
            return ProcessAtoms.precedesAtom(from, to);
        }
    }
}
