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
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.EdbProvider;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.FixpointResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ClaimEvaluator} backed by {@link RecursiveQueryEngine}.
 *
 * <p>Determines whether a claim atom is derivable — or present as a base fact —
 * when the knowledge base consists of the <em>exogenous</em> facts (always present)
 * plus the <em>endogenous</em> subset supplied in {@link #holds(Set)}.</p>
 *
 * <h2>Memoization</h2>
 * <p>Results are cached in a {@code Map<Set<String>, Boolean>}.  The Monte-Carlo
 * permutation sampler makes up to {@code samples × players} prefix evaluations, but
 * in practice the same prefixes appear across multiple permutations.  Memo growth is
 * bounded by {@code samples × players} distinct subsets in the worst case (each prefix
 * unique), and is typically much smaller due to repeated prefixes.  The memo is
 * intentionally kept in memory for the lifetime of the evaluator: it is created fresh
 * per {@link ShapleyAttribution#assess} call and discarded thereafter.</p>
 *
 * <h2>Invocation counting</h2>
 * <p>A call counter is exposed for testing and diagnostics.  Each call to
 * {@link #holds(Set)} increments {@link #callCount()}.  A memo hit does <em>not</em>
 * count as an additional engine invocation — the counter records only the number of
 * times the Datalog engine was actually run.</p>
 *
 * <h2>Claim checking</h2>
 * <p>A coalition {@code S} satisfies the claim when:
 * <ol>
 *   <li>The claim atom key is a base fact in {@code exogenous ∪ S}, or</li>
 *   <li>The claim atom key appears in the derived facts of
 *       {@link RecursiveQueryEngine#evaluate} run over {@code exogenous ∪ S}.</li>
 * </ol>
 * </p>
 *
 * <h2>Binarization</h2>
 * <p>All facts (exogenous and coalition members) are treated as present with value 1.0
 * (hard/crisp).  The atom key is the canonical form {@code "Pred(arg1, arg2)"}.  Facts
 * with value strictly below 0.5 that were present in the original store are treated as
 * absent from the EDB (consistent with {@link ai.kompile.graph.reasoning.fol.materialization.FolDatalogAdapter}
 * binarization).</p>
 *
 * @see ClaimEvaluator
 * @see ShapleyAttribution
 */
public final class DatalogClaimEvaluator implements ClaimEvaluator {

    /** Default binarization threshold (matching FolDatalogAdapter). */
    public static final double DEFAULT_BINARIZATION_THRESHOLD = 0.5;

    private final String claimAtomKey;
    private final List<DatalogRule> rules;
    private final Set<String> exogenousKeys;

    /**
     * Indexed EDB tuples for exogenous facts: predicate → list of ground tuples.
     * Built once at construction time from the exogenous key set.
     */
    private final Map<String, List<List<String>>> exogenousIndex;

    /**
     * Memoization cache: subset of endogenous keys → claim holds?
     * Keyed by an unmodifiable copy of the Set to guarantee structural equality.
     */
    private final Map<Set<String>, Boolean> memo = new HashMap<>();

    /**
     * All fact atom-keys and their parsed tuples available in the knowledge base
     * (exogenous ∪ all endogenous), used to build EDB providers for subsets.
     * Map: atomKey → parsed tuple (predicate, args).
     */
    private final Map<String, ParsedAtom> allParsed;

    /** Number of times the Datalog engine was actually invoked (memo misses). */
    private final AtomicInteger callCounter = new AtomicInteger(0);

    /**
     * Construct a {@code DatalogClaimEvaluator} from a {@link FactStore}.
     *
     * <p>All facts in {@code store} whose atom key is in {@code exogenousKeySet} are
     * treated as always-present.  All other facts above the binarization threshold are
     * treated as endogenous (players).</p>
     *
     * @param claimAtomKey   the canonical atom key of the claim to evaluate
     *                       (e.g. {@code "conclusion(alice)"})
     * @param rules          the Datalog rules to run during evaluation
     * @param store          the full knowledge base; binarization threshold
     *                       {@value #DEFAULT_BINARIZATION_THRESHOLD} is applied
     * @param exogenousKeySet atom keys of facts that are always present (never players)
     */
    public DatalogClaimEvaluator(String claimAtomKey,
                                  List<DatalogRule> rules,
                                  FactStore store,
                                  Set<String> exogenousKeySet) {
        this(claimAtomKey, rules, buildAtomMap(store, DEFAULT_BINARIZATION_THRESHOLD),
                exogenousKeySet);
    }

    /**
     * Construct a {@code DatalogClaimEvaluator} from an explicit fact-value map.
     *
     * <p>This constructor is useful in tests and when facts come from outside a
     * {@link FactStore}.  Keys in {@code allFactValues} whose value is strictly
     * below {@value #DEFAULT_BINARIZATION_THRESHOLD} are excluded from the EDB.</p>
     *
     * @param claimAtomKey   the canonical atom key of the claim to evaluate
     * @param rules          the Datalog rules to run during evaluation
     * @param allFactValues  map from atom key → truth value in [0,1]; facts with
     *                       value ≥ 0.5 are admitted to the EDB
     * @param exogenousKeySet atom keys of facts that are always present (never players)
     */
    public DatalogClaimEvaluator(String claimAtomKey,
                                  List<DatalogRule> rules,
                                  Map<String, Double> allFactValues,
                                  Set<String> exogenousKeySet) {
        Objects.requireNonNull(claimAtomKey, "claimAtomKey");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(allFactValues, "allFactValues");
        Objects.requireNonNull(exogenousKeySet, "exogenousKeySet");

        this.claimAtomKey = claimAtomKey;
        this.rules = List.copyOf(rules);
        this.exogenousKeys = Collections.unmodifiableSet(new HashSet<>(exogenousKeySet));

        // Parse all atoms once
        Map<String, ParsedAtom> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : allFactValues.entrySet()) {
            if (entry.getValue() >= DEFAULT_BINARIZATION_THRESHOLD) {
                ParsedAtom pa = ParsedAtom.parse(entry.getKey());
                if (pa != null) parsed.put(entry.getKey(), pa);
            }
        }
        // Also ensure the claim atom key itself is parseable (it may be a base fact key)
        if (!parsed.containsKey(claimAtomKey)) {
            ParsedAtom pa = ParsedAtom.parse(claimAtomKey);
            if (pa != null) {
                // Don't add it to allParsed unless it was in allFactValues —
                // the claim is a derived/query atom, not necessarily a base fact
            }
        }
        this.allParsed = Collections.unmodifiableMap(parsed);

        // Build exogenous index: predicate → tuples
        Map<String, List<List<String>>> exoIdx = new LinkedHashMap<>();
        for (String key : exogenousKeySet) {
            ParsedAtom pa = parsed.get(key);
            if (pa != null) {
                exoIdx.computeIfAbsent(pa.predicate(), k -> new ArrayList<>())
                      .add(pa.args());
            }
        }
        this.exogenousIndex = Collections.unmodifiableMap(exoIdx);
    }

    /**
     * Determine whether the claim holds for the coalition {@code presentFactKeys}.
     *
     * <p>Results are memoized: the Datalog engine is invoked at most once per distinct
     * subset value.  The call counter ({@link #callCount()}) records only engine
     * invocations (memo misses).</p>
     *
     * @param presentFactKeys the endogenous coalition; must not be null; copied defensively
     * @return {@code true} if the claim holds given {@code exogenous ∪ presentFactKeys}
     */
    @Override
    public boolean holds(Set<String> presentFactKeys) {
        Objects.requireNonNull(presentFactKeys, "presentFactKeys");
        // Use an immutable copy as the memo key so structural Set equality works
        Set<String> key = Set.copyOf(presentFactKeys);
        Boolean cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = evaluate(presentFactKeys);
        callCounter.incrementAndGet();
        memo.put(key, result);
        return result;
    }

    /**
     * Number of times the Datalog engine was actually invoked (memo misses only).
     *
     * <p>In tests, this value must be strictly less than {@code samples × players}
     * when at least some prefixes repeat across permutations.</p>
     *
     * @return engine invocation count (≥ 0)
     */
    public int callCount() {
        return callCounter.get();
    }

    /**
     * Return the memoization cache size (number of distinct subsets evaluated so far).
     *
     * @return memo size (≥ 0)
     */
    public int memoSize() {
        return memo.size();
    }

    // ─── Evaluation ──────────────────────────────────────────────────────────────

    private boolean evaluate(Set<String> presentFactKeys) {
        // Check whether the claim is already a base fact in exogenous ∪ coalition
        if (exogenousKeys.contains(claimAtomKey)) return true;
        if (presentFactKeys.contains(claimAtomKey)) return true;

        // Build EDB provider: exogenous ∪ coalition
        EdbProvider edb = buildEdb(presentFactKeys);

        // If no rules, claim can only hold as a base fact (already checked above)
        if (rules.isEmpty()) return false;

        // Run fixpoint
        FixpointResult result = RecursiveQueryEngine.evaluate(rules, edb);

        // Check if the claim atom was derived
        ParsedAtom claimParsed = ParsedAtom.parse(claimAtomKey);
        if (claimParsed == null) return false;

        Set<List<String>> derived = result.derivedFacts().get(claimParsed.predicate());
        if (derived == null) return false;
        return derived.contains(claimParsed.args());
    }

    private EdbProvider buildEdb(Set<String> presentFactKeys) {
        // Build coalition index: predicate → tuples (from coalition only)
        Map<String, List<List<String>>> coalitionIdx = new LinkedHashMap<>();
        for (String key : presentFactKeys) {
            ParsedAtom pa = allParsed.get(key);
            if (pa != null) {
                coalitionIdx.computeIfAbsent(pa.predicate(), k -> new ArrayList<>())
                            .add(pa.args());
            }
        }

        // Merge: exogenous ∪ coalition
        return predicate -> {
            List<List<String>> exo = exogenousIndex.getOrDefault(predicate, List.of());
            List<List<String>> coalition = coalitionIdx.getOrDefault(predicate, List.of());
            if (exo.isEmpty()) return coalition;
            if (coalition.isEmpty()) return exo;
            List<List<String>> merged = new ArrayList<>(exo.size() + coalition.size());
            merged.addAll(exo);
            for (List<String> t : coalition) {
                if (!exo.contains(t)) merged.add(t);
            }
            return merged;
        };
    }

    // ─── Utilities ───────────────────────────────────────────────────────────────

    private static Map<String, Double> buildAtomMap(FactStore store, double threshold) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (Fact fact : store.allFacts()) {
            if (fact.value() >= threshold) {
                map.put(fact.atomKey(), fact.value());
            }
        }
        return map;
    }

    /**
     * Parse a canonical atom key into predicate name + args list.
     *
     * <p>Format: {@code "Pred(a, b, c)"} or {@code "Pred"} (zero-arity).</p>
     */
    record ParsedAtom(String predicate, List<String> args) {

        static ParsedAtom parse(String atomKey) {
            if (atomKey == null || atomKey.isBlank()) return null;
            int lp = atomKey.indexOf('(');
            if (lp < 0) {
                // Zero-arity
                return new ParsedAtom(atomKey.trim(), List.of());
            }
            int rp = atomKey.lastIndexOf(')');
            String pred = atomKey.substring(0, lp).trim();
            String inside = (rp > lp) ? atomKey.substring(lp + 1, rp).trim() : "";
            if (inside.isEmpty()) {
                return new ParsedAtom(pred, List.of());
            }
            String[] parts = inside.split(",");
            List<String> args = new ArrayList<>(parts.length);
            for (String p : parts) {
                String t = p.trim();
                if (!t.isEmpty()) args.add(t);
            }
            return new ParsedAtom(pred, List.copyOf(args));
        }
    }
}
