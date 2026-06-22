/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.pruning;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.StrengthBand;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, parameterized, infrastructure-free pruner that decides PRUNE/KEEP for graph
 * elements based on their Subjective-Logic {@link Opinion} rather than a raw scalar confidence.
 *
 * <p>This class has no Spring annotations, no store access, and no side effects.
 * The calling module (e.g. a knowledge-graph service) retains full responsibility for
 * applying the deletions. Every decision comes with a human-readable reason string to
 * aid debugging and audit logging.</p>
 *
 * <h3>Decision rules</h3>
 * <p>Applied in order; first matching rule wins:</p>
 * <ol>
 *   <li>SUPPRESSED band (if {@link PrunePolicy#pruneSuppressedBand()}) — band is computed from
 *       {@link Opinion#projectBand()} which jointly considers expectation AND uncertainty, so a
 *       SUPPRESSED verdict already encodes both dimensions.</li>
 *   <li>belief &lt; {@link PrunePolicy#minBelief()} — direct evidence against the fact</li>
 *   <li>uncertainty &gt; {@link PrunePolicy#maxUncertainty()} — near-vacuous, no evidence base</li>
 *   <li>expectation &lt; {@link PrunePolicy#minExpectation()} — projected probability too low</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * OpinionPruner pruner = new OpinionPruner(PrunePolicy.defaults());
 *
 * // Single opinion
 * OpinionPruner.Decision d = pruner.decide("edge-42", opinion);
 *
 * // Batch (id → Opinion map)
 * Map<String, OpinionPruner.Decision> decisions = pruner.decideBatch(opinionMap);
 * }</pre>
 */
public final class OpinionPruner {

    /** The policy driving all prune/keep decisions. */
    private final PrunePolicy policy;

    /**
     * Construct an {@code OpinionPruner} with the given policy.
     *
     * @param policy the prune policy; must not be {@code null}
     */
    public OpinionPruner(PrunePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Single-opinion API
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Decide whether a single graph element identified by {@code elementId} should be pruned
     * based on its {@code opinion}.
     *
     * @param elementId a non-null identifier (edge id, node id, atom key…)
     * @param opinion   the element's Subjective-Logic opinion; must not be {@code null}
     * @return a {@link Decision} indicating PRUNE or KEEP and the reason
     */
    public Decision decide(String elementId, Opinion opinion) {
        Objects.requireNonNull(elementId, "elementId must not be null");
        Objects.requireNonNull(opinion, "opinion must not be null");

        // Rule 1: SUPPRESSED band (encodes both expectation and uncertainty jointly)
        if (policy.pruneSuppressedBand()) {
            StrengthBand band = opinion.projectBand();
            if (band == StrengthBand.SUPPRESSED) {
                return Decision.prune(elementId, opinion,
                        "band=SUPPRESSED (expectation=" + fmt(opinion.expectation())
                        + ", uncertainty=" + fmt(opinion.uncertainty()) + ")");
            }
        }

        // Rule 2: belief below floor
        if (opinion.belief() < policy.minBelief()) {
            return Decision.prune(elementId, opinion,
                    "belief=" + fmt(opinion.belief()) + " < minBelief=" + fmt(policy.minBelief()));
        }

        // Rule 3: uncertainty above ceiling
        if (opinion.uncertainty() > policy.maxUncertainty()) {
            return Decision.prune(elementId, opinion,
                    "uncertainty=" + fmt(opinion.uncertainty())
                    + " > maxUncertainty=" + fmt(policy.maxUncertainty()));
        }

        // Rule 4: projected expectation below floor
        double e = opinion.expectation();
        if (e < policy.minExpectation()) {
            return Decision.prune(elementId, opinion,
                    "expectation=" + fmt(e) + " < minExpectation=" + fmt(policy.minExpectation()));
        }

        // Passes all rules → KEEP
        StrengthBand band = opinion.projectBand();
        return Decision.keep(elementId, opinion,
                "band=" + band + " (expectation=" + fmt(e)
                + ", belief=" + fmt(opinion.belief())
                + ", uncertainty=" + fmt(opinion.uncertainty()) + ")");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Batch API
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Evaluate an {@link Opinion} for every entry in {@code opinionMap} and return a
     * decision per id.
     *
     * @param opinionMap a map from element ids to their opinions; must not be {@code null};
     *                   entries with {@code null} opinions are skipped with a KEEP/vacuous decision
     * @return an unmodifiable map from element id to {@link Decision}, in iteration order
     */
    public Map<String, Decision> decideBatch(Map<String, Opinion> opinionMap) {
        Objects.requireNonNull(opinionMap, "opinionMap must not be null");
        Map<String, Decision> result = new LinkedHashMap<>(opinionMap.size() * 2);
        for (Map.Entry<String, Opinion> entry : opinionMap.entrySet()) {
            String id = entry.getKey();
            Opinion opinion = entry.getValue();
            if (opinion == null) {
                // No opinion recorded — treat as vacuous, apply the standard rules
                result.put(id, decide(id, Opinion.vacuous()));
            } else {
                result.put(id, decide(id, opinion));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Convenience overload that evaluates all elements in {@code candidates}, where each entry
     * is an {@link OpinionEntry} pairing an id with its opinion.
     *
     * @param candidates elements to evaluate; must not be {@code null}
     * @return an unmodifiable map from element id to {@link Decision}
     */
    public Map<String, Decision> decideBatch(Collection<OpinionEntry> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Map<String, Opinion> map = new LinkedHashMap<>(candidates.size() * 2);
        for (OpinionEntry e : candidates) {
            map.put(e.id(), e.opinion());
        }
        return decideBatch(map);
    }

    /** The active policy. */
    public PrunePolicy policy() {
        return policy;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Decision record
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Immutable outcome for a single element.
     *
     * <p>Consumers can branch on {@link #shouldPrune()} and inspect {@link #reason()} for logging
     * or audit trails. The original {@link #opinion()} is also preserved for downstream scoring.</p>
     */
    public record Decision(
            /** The element id this decision pertains to. */
            String elementId,
            /** The opinion that drove the decision. */
            Opinion opinion,
            /** Whether the element should be pruned. */
            boolean shouldPrune,
            /** Human-readable explanation of the decision. */
            String reason
    ) {
        static Decision prune(String id, Opinion opinion, String reason) {
            return new Decision(id, opinion, true, "PRUNE: " + reason);
        }

        static Decision keep(String id, Opinion opinion, String reason) {
            return new Decision(id, opinion, false, "KEEP: " + reason);
        }

        /** Convenience negation of {@link #shouldPrune()}. */
        public boolean shouldKeep() {
            return !shouldPrune;
        }

        @Override
        public String toString() {
            return "Decision{id=" + elementId + ", prune=" + shouldPrune + ", reason=" + reason + "}";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // OpinionEntry helper
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Lightweight pair of element id and its opinion, for use with the batch API.
     */
    public record OpinionEntry(String id, Opinion opinion) {
        public OpinionEntry {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(opinion, "opinion must not be null");
        }

        public static OpinionEntry of(String id, Opinion opinion) {
            return new OpinionEntry(id, opinion);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }
}
