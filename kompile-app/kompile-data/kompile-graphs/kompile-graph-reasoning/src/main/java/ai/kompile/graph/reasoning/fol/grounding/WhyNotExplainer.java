/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.grounding;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * ONE-STEP NEAR-MISS explanation for {@link VerifyResult.Status#UNKNOWN} verdicts.
 *
 * <p>Implements the why-not / provenance-gap technique described in:
 * <ul>
 *   <li>Herschel &amp; Hernández, "Explaining the Missing Answers to Top-k Queries",
 *       PVLDB 2010 (why-not provenance).</li>
 *   <li>Lee et al., "PUG: A Framework and Practical Implementation of Why and Why-Not
 *       Provenance for Relational Queries", VLDB 2017.</li>
 * </ul>
 *
 * <p><strong>Algorithm (one-step near-miss approximation):</strong> Given a claim atom
 * {@code head(c₁, c₂, …)}, for each rule whose head predicate case-insensitively matches
 * the claim predicate:
 * <ol>
 *   <li>Bind head variables to the claim's constant arguments.</li>
 *   <li>For each body atom, substitute the binding and check whether the resulting ground
 *       atom is present in the {@link InferredFactStore} or {@link FactStore} with
 *       {@code confidence ≥ 0.5} (the truth threshold).</li>
 *   <li>When a body atom contains unbound variables (those not yet fixed by the head
 *       binding), enumerate up to {@link #maxBindingsPerRule} candidate bindings from the
 *       stores' predicate index (fact key scan), keeping the one that satisfies the most
 *       body atoms.</li>
 *   <li>Emit a {@link NearMiss} with the partition of satisfied/missing body atoms,
 *       a "completing fact" when exactly one atom is missing and fully ground, and a
 *       closeness score.</li>
 * </ol>
 *
 * <p>This is a <em>one-hop</em> approximation: chained rule derivations are not explored
 * (the complexity is bounded). For practical KBs this catches the most actionable gaps
 * (exactly one missing premise) while keeping latency negligible.</p>
 *
 * <p>Infra-free: no Spring, no JPA, no external dependencies.</p>
 */
public final class WhyNotExplainer {

    // ── Knobs ────────────────────────────────────────────────────────────────────

    /** Default cap on candidate bindings explored per rule. */
    public static final int DEFAULT_MAX_BINDINGS_PER_RULE = 500;

    /** Default cap on the number of rules evaluated (lowest-binding-count first). */
    public static final int DEFAULT_MAX_RULES = 8;

    /** Minimum confidence for a body atom to count as "satisfied". */
    public static final double SATISFIED_THRESHOLD = 0.5;

    // ── Public result types ──────────────────────────────────────────────────────

    /**
     * A near-miss derivation: the rule came close to proving the claim but some body
     * atoms were missing from the KB.
     *
     * @param ruleDisplay       human-readable rule description (head-predicate :- body)
     * @param satisfiedAtoms    ground body atoms that ARE present in the KB
     * @param missingAtoms      ground body atoms that are ABSENT from the KB
     * @param completingFact    the single missing ground atom when {@code missingAtoms.size() == 1}
     *                          and it is fully ground (no variables); {@code null} otherwise
     * @param closeness         {@code satisfied / (satisfied + missing)} in [0, 1];
     *                          1.0 means the claim IS derivable but was not materialized
     *                          (stale-materialization signal)
     */
    public record NearMiss(
            String ruleDisplay,
            List<String> satisfiedAtoms,
            List<String> missingAtoms,
            String completingFact,
            double closeness
    ) {}

    /**
     * The full why-not report for one claim atom.
     *
     * @param claimAtom   the atom that was verified as UNKNOWN
     * @param nearMisses  near-miss derivations sorted by fewest missing atoms then highest
     *                    closeness; may be empty
     * @param suggestions deduped set of ground completing-facts (actions that would make
     *                    the claim provable); may be empty
     */
    public record WhyNotReport(
            String claimAtom,
            List<NearMiss> nearMisses,
            List<String> suggestions
    ) {
        public WhyNotReport {
            Objects.requireNonNull(claimAtom, "claimAtom must not be null");
            nearMisses = (nearMisses == null) ? List.of() : List.copyOf(nearMisses);
            suggestions = (suggestions == null) ? List.of() : List.copyOf(suggestions);
        }

        /** Convenience: true when no near-miss was found (no rules match the predicate). */
        public boolean isEmpty() { return nearMisses.isEmpty(); }
    }

    // ── Rule normal form ─────────────────────────────────────────────────────────

    /**
     * Internal normal form for a rule, shared by the Datalog and PSL adapters.
     *
     * @param display  human-readable string (e.g. "basedIn :- worksAt, locatedIn")
     * @param headPredicate lowercase head predicate name
     * @param headArgs argument names for the head (variables start with "?"; constants are literals)
     * @param body     body atom patterns
     */
    public record RuleNf(String display, String headPredicate, List<String> headArgs, List<BodyAtomNf> body) {}

    /**
     * A body atom in the normal form.
     *
     * @param predicate lowercase predicate name
     * @param args      argument names (variable "?" prefix or constant)
     */
    public record BodyAtomNf(String predicate, List<String> args) {
        /** Ground this pattern by substituting variables from {@code binding}. */
        List<String> ground(Map<String, String> binding) {
            List<String> grounded = new ArrayList<>(args.size());
            for (String a : args) {
                grounded.add(isVar(a) ? binding.getOrDefault(a, a) : a);
            }
            return grounded;
        }

        boolean fullyGround(Map<String, String> binding) {
            for (String a : args) {
                if (isVar(a) && !binding.containsKey(a)) return false;
            }
            return true;
        }

        LinkedHashSet<String> freeVars(Map<String, String> binding) {
            LinkedHashSet<String> free = new LinkedHashSet<>();
            for (String a : args) {
                if (isVar(a) && !binding.containsKey(a)) free.add(a);
            }
            return free;
        }
    }

    private static boolean isVar(String s) {
        return s != null && s.startsWith("?");
    }

    private static String atomKey(String predicate, List<String> args) {
        return predicate + "(" + String.join(", ", args) + ")";
    }

    // ── Adapters: DatalogRule → RuleNf ───────────────────────────────────────────

    /**
     * Convert a {@link RecursiveQueryEngine.DatalogRule} to the internal normal form.
     * Variables are already "?"-prefixed in DatalogRule convention.
     *
     * <p>The {@link RuleNf#headPredicate()} is lowercased for case-insensitive matching.
     * {@link BodyAtomNf#predicate()} preserves the original case so that generated missing-atom
     * keys display the predicate in its original form (e.g. "locatedIn" not "locatedin").
     * All comparisons at lookup time use {@code equalsIgnoreCase} or canonical normalization.</p>
     */
    public static RuleNf fromDatalogRule(RecursiveQueryEngine.DatalogRule rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        String head = rule.headPredicate().toLowerCase(Locale.ROOT);
        List<String> headArgs = rule.headArgs();

        List<BodyAtomNf> body = new ArrayList<>();
        for (RecursiveQueryEngine.RuleAtom atom : rule.body()) {
            if (!atom.negated()) {  // skip negated atoms for near-miss (they don't produce facts)
                // Preserve original predicate casing in BodyAtomNf so that atom-key strings
                // display the predicate as supplied (e.g. "locatedIn(acme, london)" not "locatedin(...)")
                body.add(new BodyAtomNf(atom.predicate(), atom.args()));
            }
        }

        // Build display string: headPredicate(headArgs) :- body[0], body[1], …
        String display = head + "(" + String.join(", ", headArgs) + ")" + " :- "
                + formatBodyNf(body);
        return new RuleNf(display, head, headArgs, body);
    }

    /**
     * Convert a PSL rule into the internal normal form.
     *
     * <p>A PSL rule carries body ({@code body}) and head ({@code head}) as lists of
     * {@link ai.kompile.graph.reasoning.psl.PslAtom}. The "head" in PSL is the consequent
     * atom (there is typically one head atom; if multiple, we take the first non-negated one).
     * PSL variables use UPPERCASE_WITH_UNDERSCORE convention; we normalise to "?"-prefix here.</p>
     */
    public static RuleNf fromPslRule(ai.kompile.graph.reasoning.psl.PslRule rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        // Find the head atom (first non-negated atom in rule.head())
        ai.kompile.graph.reasoning.psl.PslAtom headAtom = null;
        for (ai.kompile.graph.reasoning.psl.PslAtom a : rule.head()) {
            if (!a.negated()) { headAtom = a; break; }
        }
        if (headAtom == null) return null; // no usable head

        String headPred = headAtom.predicate().toLowerCase(Locale.ROOT);
        List<String> headArgs = normalizePslArgs(headAtom.args());

        List<BodyAtomNf> body = new ArrayList<>();
        for (ai.kompile.graph.reasoning.psl.PslAtom ba : rule.body()) {
            if (!ba.negated()) {
                // Preserve original predicate casing for display (same rationale as fromDatalogRule)
                body.add(new BodyAtomNf(ba.predicate(), normalizePslArgs(ba.args())));
            }
        }

        String display = headPred + "(" + String.join(", ", headArgs) + ")" + " :- "
                + formatBodyNf(body);
        return new RuleNf(display, headPred, headArgs, body);
    }

    /** Convert PSL Term list to strings, normalising variables to "?"-prefix. */
    private static List<String> normalizePslArgs(List<ai.kompile.graph.reasoning.psl.Term> terms) {
        List<String> result = new ArrayList<>(terms.size());
        for (ai.kompile.graph.reasoning.psl.Term t : terms) {
            if (t.variable()) {
                // Variable: use the "?" prefix convention used throughout the reasoning lib.
                // Lowercase the variable name so predicate-index lookups are case-consistent.
                result.add("?" + t.name().toLowerCase(Locale.ROOT));
            } else {
                result.add(t.name());
            }
        }
        return result;
    }

    private static String formatBodyNf(List<BodyAtomNf> body) {
        if (body.isEmpty()) return "⊤";
        List<String> parts = new ArrayList<>();
        for (BodyAtomNf a : body) {
            parts.add(a.predicate() + "(" + String.join(", ", a.args()) + ")");
        }
        return String.join(" & ", parts);
    }

    // ── Core explainer ────────────────────────────────────────────────────────────

    private final List<RuleNf> rules;
    private final InferredFactStore inferredStore;
    private final FactStore factStore;
    private final int maxBindingsPerRule;
    private final int maxRules;

    /**
     * Canonical atom key: lowercase predicate, trimmed args, joined with ", ".
     *
     * <p>Used to normalize keys before store lookups so that "worksAt(alice, acme)"
     * and "worksat(alice, acme)" resolve to the same canonical key "worksat(alice, acme)".</p>
     */
    private static String canonicalKey(String atomKey) {
        ParsedAtom pa = ParsedAtom.parse(atomKey);
        if (pa == null) return atomKey.toLowerCase(Locale.ROOT);
        return pa.predicate.toLowerCase(Locale.ROOT) + "(" + String.join(", ", pa.args) + ")";
    }

    /**
     * Per-explain() canonical indexes: canonical-key → value (fact value or inferred confidence).
     * Rebuilt at the start of each {@link #explain(String)} call to pick up store mutations and
     * to normalize predicate casing (e.g. "worksAt" → "worksat").
     *
     * <p>originalKeyIndex maps canonical-key → the original atom key as stored (preserving
     * predicate casing), used when displaying satisfied atoms so that "worksAt(alice, acme)"
     * is reported instead of the lowercased "worksat(alice, acme)".</p>
     */
    private Map<String, Double> canonicalFactIndex     = new HashMap<>();
    private Map<String, Double> canonicalInferredIndex = new HashMap<>();
    /** canonical-key → original atom key (preserves predicate casing for display). */
    private Map<String, String> originalKeyIndex       = new HashMap<>();

    private void rebuildLookupIndexes() {
        canonicalFactIndex = new HashMap<>();
        canonicalInferredIndex = new HashMap<>();
        originalKeyIndex = new HashMap<>();
        for (Fact f : factStore.allFacts()) {
            String ck = canonicalKey(f.atomKey());
            canonicalFactIndex.put(ck, f.value());
            originalKeyIndex.putIfAbsent(ck, f.atomKey());
        }
        for (InferredFact f : inferredStore.allLatest()) {
            String ck = canonicalKey(f.atomKey());
            canonicalInferredIndex.put(ck, f.confidence());
            originalKeyIndex.putIfAbsent(ck, f.atomKey());
        }
    }

    /**
     * Look up the original-case atom key for display.
     * If the key was found in the store (via canonical index), return the store's original key;
     * otherwise return the input key as-is.
     */
    private String displayKey(String atomKey) {
        String ck = canonicalKey(atomKey);
        String original = originalKeyIndex.get(ck);
        return (original != null) ? original : atomKey;
    }

    /**
     * Construct a WhyNotExplainer with default knobs.
     *
     * @param rules         the rule normal forms to evaluate (use {@link #fromDatalogRule} /
     *                      {@link #fromPslRule} to convert)
     * @param inferredStore the materialized inferred-fact store
     * @param factStore     the observed-fact store
     */
    public WhyNotExplainer(List<RuleNf> rules, InferredFactStore inferredStore, FactStore factStore) {
        this(rules, inferredStore, factStore, DEFAULT_MAX_BINDINGS_PER_RULE, DEFAULT_MAX_RULES);
    }

    /**
     * Construct a WhyNotExplainer with explicit knobs.
     *
     * @param rules             the rule normal forms to evaluate
     * @param inferredStore     the materialized inferred-fact store
     * @param factStore         the observed-fact store
     * @param maxBindingsPerRule cap on candidate bindings enumerated per rule
     * @param maxRules           cap on rules evaluated per claim
     */
    public WhyNotExplainer(List<RuleNf> rules, InferredFactStore inferredStore, FactStore factStore,
                            int maxBindingsPerRule, int maxRules) {
        Objects.requireNonNull(inferredStore, "inferredStore must not be null");
        Objects.requireNonNull(factStore, "factStore must not be null");
        this.rules = (rules == null) ? List.of() : List.copyOf(rules);
        this.inferredStore = inferredStore;
        this.factStore = factStore;
        this.maxBindingsPerRule = maxBindingsPerRule > 0 ? maxBindingsPerRule : DEFAULT_MAX_BINDINGS_PER_RULE;
        this.maxRules = maxRules > 0 ? maxRules : DEFAULT_MAX_RULES;
    }

    /**
     * Build the why-not report for the given claim atom key.
     *
     * @param claimAtomKey e.g. {@code "basedIn(alice, london)"}
     * @return the report (may have empty nearMisses if no matching rules)
     */
    public WhyNotReport explain(String claimAtomKey) {
        Objects.requireNonNull(claimAtomKey, "claimAtomKey must not be null");
        String claim = claimAtomKey.trim();

        // Parse the claim
        ParsedAtom parsed = ParsedAtom.parse(claim);
        if (parsed == null) {
            return new WhyNotReport(claim, List.of(), List.of());
        }
        String claimPredLc = parsed.predicate.toLowerCase(Locale.ROOT);

        // Build canonical lookup indexes (lowercase-predicate key → value) once per explain() call.
        // This lets isSatisfied work even when the store has mixed-case predicate keys
        // (e.g. "worksAt(alice, acme)") and the explainer generates lowercase keys
        // (e.g. "worksat(alice, acme)").
        // We rebuild these on every call (explains are rare, stores can change between calls).
        rebuildLookupIndexes();

        // Select matching rules (head predicate matches, case-insensitive), cap to maxRules
        List<RuleNf> matching = new ArrayList<>();
        for (RuleNf rule : rules) {
            if (rule.headPredicate().equalsIgnoreCase(claimPredLc)) {
                matching.add(rule);
                if (matching.size() >= maxRules) break;
            }
        }

        if (matching.isEmpty()) {
            return new WhyNotReport(claim, List.of(), List.of());
        }

        // Evaluate each matching rule
        List<NearMiss> misses = new ArrayList<>();
        for (RuleNf rule : matching) {
            NearMiss miss = evaluateRule(rule, parsed);
            if (miss != null) {
                misses.add(miss);
            }
        }

        // Sort: fewest missing atoms first, then highest closeness
        misses.sort(Comparator
                .comparingInt((NearMiss m) -> m.missingAtoms().size())
                .thenComparing(Comparator.comparingDouble(NearMiss::closeness).reversed()));

        // Collect deduped completing facts as suggestions
        List<String> suggestions = new ArrayList<>();
        for (NearMiss m : misses) {
            if (m.completingFact() != null && !suggestions.contains(m.completingFact())) {
                suggestions.add(m.completingFact());
            }
        }

        return new WhyNotReport(claim, misses, suggestions);
    }

    // ── Rule evaluation ──────────────────────────────────────────────────────────

    private NearMiss evaluateRule(RuleNf rule, ParsedAtom claim) {
        // Build initial binding by matching head args to claim constants
        Map<String, String> headBinding = bindHead(rule.headPredicate(), rule.headArgs(), claim);
        if (headBinding == null) {
            return null; // arity mismatch or head constants conflict
        }

        // Find the best binding (maximising satisfied body atoms) over candidate enumerations
        return bestBinding(rule, headBinding);
    }

    /**
     * Bind head variables to claim constants. Returns null if arity doesn't match or a head
     * constant disagrees with the claim constant.
     */
    private static Map<String, String> bindHead(String headPred, List<String> headArgs,
                                                  ParsedAtom claim) {
        if (headArgs.size() != claim.args.size()) return null;

        Map<String, String> binding = new HashMap<>();
        for (int i = 0; i < headArgs.size(); i++) {
            String headArg = headArgs.get(i);
            String claimArg = claim.args.get(i);
            if (isVar(headArg)) {
                // Variable: bind to the claim constant
                String existing = binding.put(headArg, claimArg);
                if (existing != null && !existing.equalsIgnoreCase(claimArg)) {
                    return null; // conflicting binding
                }
            } else {
                // Constant in head: must match the claim constant
                if (!headArg.equalsIgnoreCase(claimArg)) return null;
            }
        }
        return binding;
    }

    /**
     * Find the best partial-binding that maximises satisfied body atoms. We:
     * <ol>
     *   <li>Start with the head binding.</li>
     *   <li>For each body atom that is fully ground under the current binding, check
     *       satisfaction immediately.</li>
     *   <li>For body atoms with free variables, enumerate candidate constant values from
     *       the predicate index (fact key scan) and pick the assignment that satisfies the
     *       most additional atoms (greedy left-to-right, within the binding cap).</li>
     * </ol>
     */
    private NearMiss bestBinding(RuleNf rule, Map<String, String> headBinding) {
        // Expand the binding iteratively: evaluate body atoms left-to-right,
        // enumerating free variables on the first unsatisfied atom.
        NearMiss best = null;
        int exploredCount = 0;

        // Use a queue of partial bindings; start with just the head binding
        List<Map<String, String>> frontier = new ArrayList<>();
        frontier.add(new HashMap<>(headBinding));

        while (!frontier.isEmpty() && exploredCount < maxBindingsPerRule) {
            List<Map<String, String>> nextFrontier = new ArrayList<>();

            for (Map<String, String> binding : frontier) {
                if (exploredCount >= maxBindingsPerRule) break;

                // Evaluate all body atoms under this binding
                List<String> satisfied = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                boolean hasUnbound = false;

                for (BodyAtomNf bodyAtom : rule.body()) {
                    if (bodyAtom.fullyGround(binding)) {
                        List<String> groundArgs = bodyAtom.ground(binding);
                        String key = atomKey(bodyAtom.predicate(), groundArgs);
                        if (isSatisfied(key)) {
                            // Use the original-case key for display (e.g. "worksAt" not "worksat")
                            satisfied.add(displayKey(key));
                        } else {
                            missing.add(key);
                        }
                    } else {
                        // Has free variables: we'll enumerate below
                        hasUnbound = true;
                        break;
                    }
                }

                if (!hasUnbound) {
                    // Fully ground binding — record as a candidate NearMiss
                    exploredCount++;
                    NearMiss candidate = toNearMiss(rule.display(), satisfied, missing);
                    if (best == null || isBetter(candidate, best)) {
                        best = candidate;
                    }
                } else {
                    // Enumerate free vars from the first unbound body atom that actually HAS
                    // candidate facts. This lets a variable shared across atoms — e.g. ?h in
                    // officeOf(?y,?h) & cityOf(?h,?z) — bind from the satisfiable side (cityOf),
                    // so the unsatisfiable atom becomes a GROUND miss (officeOf(acme,hq1)) rather
                    // than a non-ground dead end. Only when NO unbound atom has any candidate do
                    // we record the partial near-miss.
                    boolean enumerated = false;
                    for (BodyAtomNf bodyAtom : rule.body()) {
                        if (!bodyAtom.fullyGround(binding)) {
                            LinkedHashSet<String> freeVars = bodyAtom.freeVars(binding);
                            List<Map<String, String>> candidates =
                                    enumerateBindings(bodyAtom, freeVars, binding,
                                            maxBindingsPerRule - exploredCount);
                            if (!candidates.isEmpty()) {
                                nextFrontier.addAll(candidates);
                                enumerated = true;
                                break; // extend one unbound atom per iteration
                            }
                        }
                    }
                    if (!enumerated) {
                        // No unbound body atom has any candidate binding — record the partial
                        // near-miss with whatever we have bound so far.
                        exploredCount++;
                        NearMiss candidate = toNearMissPartial(rule, binding);
                        if (best == null || isBetter(candidate, best)) {
                            best = candidate;
                        }
                    }
                }
            }

            frontier = nextFrontier;
        }

        return best;
    }

    /**
     * Enumerate at most {@code cap} candidate bindings for {@code freeVars} by scanning
     * the fact stores for facts matching {@code bodyAtom.predicate}. For each matching
     * ground fact, if the bound args agree with the current binding, emit an extended binding.
     */
    private List<Map<String, String>> enumerateBindings(BodyAtomNf bodyAtom,
                                                         LinkedHashSet<String> freeVars,
                                                         Map<String, String> currentBinding,
                                                         int cap) {
        List<Map<String, String>> results = new ArrayList<>();
        int count = 0;

        // Scan FactStore (observed facts) for matching predicate
        for (Fact fact : factStore.allFacts()) {
            if (count >= cap) break;
            ParsedAtom pa = ParsedAtom.parse(fact.atomKey());
            if (pa == null) continue;
            if (!pa.predicate.equalsIgnoreCase(bodyAtom.predicate())) continue;
            if (pa.args.size() != bodyAtom.args().size()) continue;

            Map<String, String> extended = tryExtend(bodyAtom, pa.args, currentBinding);
            if (extended != null) {
                results.add(extended);
                count++;
            }
        }

        // Also scan InferredFactStore (materialized inferred facts)
        for (InferredFact fact : inferredStore.allLatest()) {
            if (count >= cap) break;
            if (fact.confidence() < SATISFIED_THRESHOLD) continue;
            ParsedAtom pa = ParsedAtom.parse(fact.atomKey());
            if (pa == null) continue;
            if (!pa.predicate.equalsIgnoreCase(bodyAtom.predicate())) continue;
            if (pa.args.size() != bodyAtom.args().size()) continue;

            Map<String, String> extended = tryExtend(bodyAtom, pa.args, currentBinding);
            if (extended != null && !results.contains(extended)) {
                results.add(extended);
                count++;
            }
        }

        return results;
    }

    /**
     * Try to extend {@code currentBinding} by unifying {@code bodyAtom.args()} with
     * {@code groundArgs} from a candidate fact. Returns the extended binding if consistent,
     * or null if there's a conflict.
     */
    private static Map<String, String> tryExtend(BodyAtomNf bodyAtom,
                                                   List<String> groundArgs,
                                                   Map<String, String> currentBinding) {
        if (bodyAtom.args().size() != groundArgs.size()) return null;
        Map<String, String> extended = new HashMap<>(currentBinding);
        for (int i = 0; i < bodyAtom.args().size(); i++) {
            String pattern = bodyAtom.args().get(i);
            String ground = groundArgs.get(i);
            if (isVar(pattern)) {
                String existing = extended.get(pattern);
                if (existing != null && !existing.equalsIgnoreCase(ground)) {
                    return null; // conflict
                }
                extended.put(pattern, ground);
            } else {
                // constant in body must match
                if (!pattern.equalsIgnoreCase(ground)) return null;
            }
        }
        return extended;
    }

    /** Evaluate all body atoms under a partial binding (some may still have free variables). */
    private NearMiss toNearMissPartial(RuleNf rule, Map<String, String> binding) {
        List<String> satisfied = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (BodyAtomNf bodyAtom : rule.body()) {
            if (bodyAtom.fullyGround(binding)) {
                List<String> groundArgs = bodyAtom.ground(binding);
                String key = atomKey(bodyAtom.predicate(), groundArgs);
                if (isSatisfied(key)) {
                    satisfied.add(displayKey(key));
                } else {
                    missing.add(key);
                }
            } else {
                // Partially unbound → represent as a pattern
                List<String> partialArgs = new ArrayList<>();
                for (String a : bodyAtom.args()) {
                    partialArgs.add(isVar(a) ? binding.getOrDefault(a, a) : a);
                }
                missing.add(bodyAtom.predicate() + "(" + String.join(", ", partialArgs) + ")");
            }
        }
        return toNearMiss(rule.display(), satisfied, missing);
    }

    private static NearMiss toNearMiss(String display, List<String> satisfied, List<String> missing) {
        int total = satisfied.size() + missing.size();
        double closeness = (total == 0) ? 1.0 : (double) satisfied.size() / total;

        String completingFact = null;
        if (missing.size() == 1) {
            String m = missing.get(0);
            // Only set completingFact if fully ground (no "?" variables remaining)
            if (!m.contains("?")) {
                completingFact = m;
            }
        }

        return new NearMiss(display, List.copyOf(satisfied), List.copyOf(missing),
                completingFact, closeness);
    }

    private static boolean isBetter(NearMiss candidate, NearMiss current) {
        int cm = candidate.missingAtoms().size();
        int bm = current.missingAtoms().size();
        if (cm != bm) return cm < bm;
        return candidate.closeness() > current.closeness();
    }

    // ── Satisfaction check ───────────────────────────────────────────────────────

    /**
     * Check if the given ground atom key is satisfied in either store with confidence ≥ 0.5.
     *
     * <p>Uses the canonical-key indexes built at the start of each {@link #explain(String)} call,
     * so that predicate casing differences (e.g. "worksAt" vs "worksat") are normalized before
     * comparison. The InferredFactStore is checked first (materialized MAP results take
     * precedence).</p>
     */
    private boolean isSatisfied(String atomKey) {
        String ck = canonicalKey(atomKey);
        // Check InferredFactStore first (materialized MAP results)
        Double inferredConf = canonicalInferredIndex.get(ck);
        if (inferredConf != null && inferredConf >= SATISFIED_THRESHOLD) {
            return true;
        }
        // Check FactStore (hard or soft observed facts with sufficient truth value)
        Double factValue = canonicalFactIndex.get(ck);
        if (factValue != null && factValue >= SATISFIED_THRESHOLD) {
            return true;
        }
        return false;
    }

    // ── Atom key parser ──────────────────────────────────────────────────────────

    /**
     * Minimal parse of an atom key into predicate + args list.
     * Handles {@code pred(a1, a2, …)} format.
     */
    static final class ParsedAtom {
        final String predicate;
        final List<String> args;

        ParsedAtom(String predicate, List<String> args) {
            this.predicate = predicate;
            this.args = args;
        }

        static ParsedAtom parse(String atomKey) {
            if (atomKey == null || atomKey.isBlank()) return null;
            int lp = atomKey.indexOf('(');
            if (lp < 0) return null;
            int rp = atomKey.lastIndexOf(')');
            if (rp <= lp) return null;
            String pred = atomKey.substring(0, lp).trim();
            if (pred.isBlank()) return null;
            String inside = atomKey.substring(lp + 1, rp).trim();
            List<String> args = new ArrayList<>();
            if (!inside.isBlank()) {
                for (String part : inside.split(",")) {
                    args.add(part.trim());
                }
            }
            return new ParsedAtom(pred, args);
        }
    }

}
