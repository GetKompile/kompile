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
package ai.kompile.graph.reasoning.attribution.shapley;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.Derivation;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.EdbProvider;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.FixpointResult;
import ai.kompile.graph.reasoning.fol.materialization.FolDatalogAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * High-level convenience façade for Monte-Carlo Shapley source attribution.
 *
 * <p>Given a Datalog claim atom, a rule set, and a {@link FactStore}, this class:</p>
 * <ol>
 *   <li>Runs the engine with provenance to identify the <em>witness-restricted player set</em>:
 *       only base facts appearing in at least one recorded derivation of the claim are players.
 *       Facts outside every recorded proof have Shapley value 0 — they cannot change the
 *       outcome given the provenance structure of the claim.
 *       <em>Caveat:</em> the derivation cap ({@link RecursiveQueryEngine#DEFAULT_MAX_DERIVATIONS_PER_ATOM})
 *       means that some derivations may be dropped; if a fact appears only in dropped derivations it
 *       will not be included as a player.  This is conservative: such facts may have non-zero
 *       Shapley value if uncapped, but are excluded here to keep the player set tractable.
 *       When no derivation is recorded (the claim is trivially true or the rule set is empty),
 *       all facts from the store are admitted as players (capped at {@link #DEFAULT_MAX_PLAYERS})
 *       with a warning.</li>
 *   <li>Caps the player set at {@link #DEFAULT_MAX_PLAYERS} (default: 24) with an explicit
 *       truncation flag — the cap is never applied silently.  The first {@code maxPlayers}
 *       players (in derivation-walk order) are used.  Remaining players are recorded in
 *       {@link AttributionResult#truncatedPlayers()}.</li>
 *   <li>Runs {@link ShapleyAttribution#assess} with the restricted player set.</li>
 *   <li>Aggregates player Shapley values by {@link Fact#sourceId()} — the
 *       source-level attribution sums the member Shapley values for each source.</li>
 * </ol>
 *
 * <h2>Why witness restriction is sound</h2>
 * <p>A base fact not appearing in any recorded derivation of the claim cannot be a
 * "cause" of the claim according to the Datalog proof tree.  Under the closed-world
 * assumption, adding or removing such a fact cannot change whether the claim is derived
 * (its marginal contribution in every permutation is 0).  Therefore its Shapley value
 * is provably 0, and it is safe to exclude it from the player set (see Livshits et al.,
 * ICDT 2020, Proposition 4.3).</p>
 *
 * <h2>Infra-free</h2>
 * <p>No Spring, no JPA, no external libraries.  The orchestrator builds after all
 * parallel agents complete; this class is purely additive.</p>
 *
 * @see ShapleyAttribution
 * @see DatalogClaimEvaluator
 */
public final class ClaimShapley {

    private static final Logger log = LoggerFactory.getLogger(ClaimShapley.class);

    /**
     * Default maximum number of players (distinct endogenous facts) admitted to the
     * Shapley computation.  When the witness-restricted player set exceeds this size,
     * the first {@code maxPlayers} players (BFS walk order of the derivation tree) are
     * used and the rest are reported as truncated.
     *
     * <p>Rationale: the Shapley sampler makes {@code samples × players} evaluations
     * (without memoization savings).  At 24 players and 2000 samples that is 48,000
     * evaluations — tractable within seconds.  At 64 players it reaches 128,000 —
     * still feasible but noticeably slower.  The default keeps latency predictable.</p>
     */
    public static final int DEFAULT_MAX_PLAYERS = 24;

    private ClaimShapley() {}

    /**
     * Singleton for convenient access.
     */
    public static final ClaimShapley INSTANCE = new ClaimShapley();

    // ─── Main entry point ────────────────────────────────────────────────────────

    /**
     * Run Shapley attribution for a claim over a {@link FactStore} using default player cap.
     *
     * <p>Players are restricted to the witness set (base facts appearing in any recorded
     * derivation of {@code claimAtom}).  Players are grouped by {@link Fact#sourceId}
     * for the source-level attribution in {@link ShapleyReport#bySource()}.</p>
     *
     * @param claimAtom the claim atom key to attribute (e.g. {@code "conclusion(alice)"})
     * @param rules     the Datalog rule set
     * @param facts     the full knowledge base; binarization threshold 0.5 applied
     * @param samples   Monte-Carlo sample count; must be ≥ 1
     * @param seed      random seed for reproducibility
     * @return an {@link AttributionResult} wrapping the {@link ShapleyReport} plus
     *         provenance metadata (player set, truncation flag, source map)
     */
    public AttributionResult attribute(String claimAtom,
                                       List<DatalogRule> rules,
                                       FactStore facts,
                                       int samples,
                                       long seed) {
        return attribute(claimAtom, rules, facts, samples, seed, DEFAULT_MAX_PLAYERS);
    }

    /**
     * Run Shapley attribution with an explicit player cap.
     *
     * @param claimAtom  the claim atom key to attribute
     * @param rules      the Datalog rule set
     * @param facts      the full knowledge base
     * @param samples    Monte-Carlo sample count; must be ≥ 1
     * @param seed       random seed for reproducibility
     * @param maxPlayers maximum number of players; must be ≥ 1
     * @return the attribution result
     */
    public AttributionResult attribute(String claimAtom,
                                       List<DatalogRule> rules,
                                       FactStore facts,
                                       int samples,
                                       long seed,
                                       int maxPlayers) {
        Objects.requireNonNull(claimAtom, "claimAtom");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(facts, "facts");
        if (samples < 1) throw new IllegalArgumentException("samples must be ≥ 1");
        if (maxPlayers < 1) throw new IllegalArgumentException("maxPlayers must be ≥ 1");

        // Step 1: Build sourceId map for aggregation: atomKey → sourceId
        Map<String, String> sourceMap = buildSourceMap(facts);

        // Step 2: Run engine with provenance to identify witness-restricted players
        EdbProvider edb = FolDatalogAdapter.factStoreEdb(facts, 0.0); // include all facts for provenance
        FixpointResult provResult = RecursiveQueryEngine.evaluate(
                rules, edb,
                RecursiveQueryEngine.DEFAULT_MAX_ROUNDS,
                RecursiveQueryEngine.DEFAULT_MAX_DERIVED_FACTS,
                // Use a higher derivation cap so the provenance walk captures more leaves
                Math.max(RecursiveQueryEngine.DEFAULT_MAX_DERIVATIONS_PER_ATOM, 16));

        // Step 3: Walk derivations of the claim atom to find EDB leaf facts
        Set<String> witnessPlayers = extractWitnessPlayers(claimAtom, provResult, facts);

        // Fallback: if no provenance found (claim trivially true or empty rules),
        // use all facts from the store
        boolean provenanceAvailable = !witnessPlayers.isEmpty();
        if (!provenanceAvailable) {
            log.warn("ClaimShapley: no recorded derivations for claim '{}' — "
                    + "falling back to all facts as players (cap: {})", claimAtom, maxPlayers);
            // extractWitnessPlayers may return an immutable empty set — build a fresh one.
            witnessPlayers = new LinkedHashSet<>();
            for (Fact f : facts.allFacts()) {
                witnessPlayers.add(f.atomKey());
            }
        }

        // Step 4: Apply player cap with explicit truncation reporting
        List<String> allPlayers = new ArrayList<>(witnessPlayers);
        boolean truncated = allPlayers.size() > maxPlayers;
        List<String> truncatedPlayers = Collections.emptyList();
        if (truncated) {
            log.warn("ClaimShapley: witness player set size {} exceeds maxPlayers {}. "
                    + "Using first {} players (BFS walk order). "
                    + "Truncated players have Shapley value 0 within this run.",
                    allPlayers.size(), maxPlayers, maxPlayers);
            truncatedPlayers = Collections.unmodifiableList(
                    new ArrayList<>(allPlayers.subList(maxPlayers, allPlayers.size())));
            allPlayers = new ArrayList<>(allPlayers.subList(0, maxPlayers));
        }

        // Step 5: Build the DatalogClaimEvaluator (no exogenous facts — all are players)
        Map<String, Double> factValues = buildFactValues(facts);
        DatalogClaimEvaluator evaluator = new DatalogClaimEvaluator(
                claimAtom, rules, factValues, Set.of());

        // Step 6: Run Shapley estimation
        ShapleyReport baseReport = ShapleyAttribution.INSTANCE.assess(evaluator, allPlayers, samples, seed);

        // Step 7: Aggregate by sourceId
        Map<String, Double> bySource = aggregateBySource(baseReport.shapley(), sourceMap);

        // Step 8: Rebuild report with bySource populated
        ShapleyReport report = new ShapleyReport(
                baseReport.shapley(), baseReport.stdError(),
                samples, seed, baseReport.targetDelta(), bySource);

        return new AttributionResult(report, allPlayers, truncated, truncatedPlayers,
                provenanceAvailable, evaluator);
    }

    // ─── Witness player extraction ────────────────────────────────────────────────

    /**
     * Walk the derivation index transitively from {@code claimAtom} down to EDB leaves.
     *
     * <p>BFS over the derivation graph: start from the claim atom's derivations, recurse
     * into parent atom keys that are themselves IDB atoms (appear in the derivation index),
     * and collect atom keys that are EDB atoms (not in the derivation index) as players.
     * Also collect IDB atoms that ARE in the facts store (same-predicate base + derived case).</p>
     *
     * <p>The result is a set of EDB atom keys that appear in at least one proof of the claim.
     * BFS order is preserved for deterministic player ordering (important for the player cap).</p>
     */
    private static Set<String> extractWitnessPlayers(String claimAtom,
                                                      FixpointResult provResult,
                                                      FactStore facts) {
        Map<String, List<Derivation>> derivIdx = provResult.derivationIndex();
        Set<String> allFactKeys = new HashSet<>();
        for (Fact f : facts.allFacts()) allFactKeys.add(f.atomKey());

        // If the claim itself has no derivations, return empty (caller handles fallback)
        List<Derivation> claimDerivs = derivIdx.get(claimAtom);
        if (claimDerivs == null || claimDerivs.isEmpty()) {
            // The claim might be a base fact that is directly present
            if (allFactKeys.contains(claimAtom)) {
                // Claim holds trivially as a base fact — no derivation to walk
                return Set.of();
            }
            return Set.of();
        }

        // BFS
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(claimAtom);
        visited.add(claimAtom);

        // Maintain insertion-ordered result for deterministic cap behavior
        Set<String> players = new LinkedHashSet<>();

        while (!queue.isEmpty()) {
            String atomKey = queue.poll();
            List<Derivation> derivs = derivIdx.get(atomKey);
            if (derivs == null || derivs.isEmpty()) {
                // EDB leaf — this is a player if it's a base fact in the store
                if (allFactKeys.contains(atomKey)) {
                    players.add(atomKey);
                }
                // If not in the store, it might be a constant the engine generated —
                // skip (not a claim-able base fact)
                continue;
            }
            // IDB atom: recurse into all recorded derivations' parents
            for (Derivation d : derivs) {
                for (String parentKey : d.parentAtomKeys()) {
                    if (visited.add(parentKey)) {
                        queue.add(parentKey);
                    }
                }
            }
            // An IDB atom that is ALSO in the facts store is a base fact player
            // (e.g., same-predicate EDB+IDB where the base facts seed the IDB)
            if (allFactKeys.contains(atomKey) && !atomKey.equals(claimAtom)) {
                players.add(atomKey);
            }
        }

        return players;
    }

    // ─── Source aggregation ───────────────────────────────────────────────────────

    private static Map<String, Double> aggregateBySource(Map<String, Double> shapleyValues,
                                                          Map<String, String> sourceMap) {
        Map<String, Double> bySource = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : shapleyValues.entrySet()) {
            String sourceId = sourceMap.getOrDefault(entry.getKey(), "<unknown>");
            bySource.merge(sourceId, entry.getValue(), Double::sum);
        }
        return bySource;
    }

    private static Map<String, String> buildSourceMap(FactStore facts) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Fact f : facts.allFacts()) {
            map.put(f.atomKey(), f.sourceId());
        }
        return map;
    }

    private static Map<String, Double> buildFactValues(FactStore facts) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (Fact f : facts.allFacts()) {
            map.put(f.atomKey(), f.value());
        }
        return map;
    }

    // ─── Result type ──────────────────────────────────────────────────────────────

    /**
     * Complete attribution result: the {@link ShapleyReport} plus provenance metadata.
     *
     * @param report               the Shapley report (values, std-errors, bySource)
     * @param players              the final (possibly capped) player list used
     * @param truncated            {@code true} if the witness set exceeded {@code maxPlayers}
     * @param truncatedPlayers     atom keys excluded due to the player cap (empty if not truncated)
     * @param provenanceAvailable  {@code true} if provenance was recorded for the claim;
     *                             {@code false} means the engine found no derivations and all
     *                             facts were used as a fallback
     * @param evaluator            the {@link DatalogClaimEvaluator} used — exposed for
     *                             diagnostics (e.g. {@link DatalogClaimEvaluator#callCount()})
     */
    public record AttributionResult(
            ShapleyReport report,
            List<String> players,
            boolean truncated,
            List<String> truncatedPlayers,
            boolean provenanceAvailable,
            DatalogClaimEvaluator evaluator) {

        public AttributionResult {
            Objects.requireNonNull(report, "report");
            Objects.requireNonNull(players, "players");
            Objects.requireNonNull(truncatedPlayers, "truncatedPlayers");
            Objects.requireNonNull(evaluator, "evaluator");
            players = List.copyOf(players);
            truncatedPlayers = List.copyOf(truncatedPlayers);
        }
    }
}
