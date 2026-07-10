/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A PSL program: a set of weighted {@link PslRule}s (logical and arithmetic) plus the
 * ground atoms they reason over, partitioned into <b>observed</b> atoms (evidence, fixed
 * during inference) and <b>target</b> atoms (the soft-truth random variables to infer).
 *
 * <p>This is the hand-rolled, dependency-free analogue of PSL's {@code DataStore} +
 * {@code Model}: ground atoms are declared up front (observed evidence and inference
 * targets), then {@link #ground()} instantiates every rule against them via a
 * backtracking conjunctive join, producing the {@link GroundRule}s that
 * {@link HlMrfMapInference} optimizes.  {@link #groundArithmetic()} returns the
 * {@link ArithmeticGroundRule}s produced from any {@link ArithmeticRule}s added via
 * {@link #addArithmeticRule}.</p>
 *
 * <h3>Step 3 — predicate declarations and CWA</h3>
 * <p>Call {@link #declareClosed(String, int)} to register a predicate as closed (CWA).
 * During grounding, if a body literal references a closed predicate and no matching atom
 * is found, the atom is auto-registered with value {@code 0.0} (observed) instead of
 * pruning the rule branch.</p>
 *
 * <h3>Step 4 — external-function predicates</h3>
 * <p>Register a function via {@link #registerFunction(String, ExternalFunction)}.
 * During grounding, body literals whose predicate is registered as a function have their
 * value computed on the fly and stored as temporary observed atoms.</p>
 */
public class PslProgram implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Safety cap on the number of ground rules to avoid pathological grounding blow-ups. */
    public static final int MAX_GROUND_RULES = 500_000;

    private static final Logger log = LoggerFactory.getLogger(PslProgram.class);
    /** One-time guard so a mis-ranged {@link #observe} (WP1a) warns once, not once per atom. */
    private static final AtomicBoolean OUT_OF_RANGE_WARNED = new AtomicBoolean(false);

    private final List<PslRule> rules = new ArrayList<>();
    private final List<ArithmeticRule> arithmeticRules = new ArrayList<>();
    private final Map<String, PslAtom> atomsByKey = new LinkedHashMap<>();
    private final Map<String, Double> values = new LinkedHashMap<>();
    private final Set<String> observed = new LinkedHashSet<>();

    /** WP17f — set by {@link #ground()} when the {@link #MAX_GROUND_RULES} cap truncated grounding. */
    private boolean groundingTruncated;

    /** Rebuilt by {@link #ground()}; lets {@code instantiate} stamp {@link GroundRule#templateIndex()}. */
    private transient Map<PslRule, Integer> templateIndexLookup;

    // Step 3 — predicate-level open/closed declarations
    /** Predicate name → arity for declared closed (CWA) predicates. */
    private final Map<String, Integer> closedPredicates = new LinkedHashMap<>();
    /** Predicate name → arity for declared open predicates. */
    private final Map<String, Integer> openPredicates = new LinkedHashMap<>();

    // Step 4 — external function predicates (lambdas — not serializable; re-register after load)
    private transient Map<String, ExternalFunction> functions = new LinkedHashMap<>();

    // E-8 — cached predicate index (invalidated whenever atoms change)
    /** Lazily-built predicate → atoms index, shared across ground() / groundArithmetic() calls. */
    private transient Map<String, List<PslAtom>> cachedPredicateIndex = null;
    /** Whether the cache needs rebuilding. */
    private boolean predicateIndexDirty = true;

    /**
     * Reinitialize transient fields after Java deserialization: the external-function registry holds
     * lambdas (not serializable) and the predicate index is a lazy cache. The rule / atom / weight /
     * observed-value structure is fully restored; re-register any {@link ExternalFunction}s after
     * loading.
     */
    private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
        in.defaultReadObject();
        this.functions = new LinkedHashMap<>();
        this.cachedPredicateIndex = null;
        this.predicateIndexDirty = true;
    }

    // ─── Rules ───────────────────────────────────────────────────────────────

    public PslProgram addRule(PslRule rule) {
        rules.add(rule);
        return this;
    }

    public PslProgram addRule(String ruleText) {
        return addRule(PslRule.parse(ruleText));
    }

    public List<PslRule> rules() {
        return rules;
    }

    /**
     * Return a copy of this program with its weighted {@link PslRule}s replaced by {@code newRules},
     * preserving all atom declarations (observed values + targets) and arithmetic rules. Lets a caller
     * re-run inference with learned weights without re-grounding the atoms (used by weight learning).
     */
    public PslProgram withRules(List<PslRule> newRules) {
        PslProgram fresh = new PslProgram();
        for (PslRule rule : newRules) {
            fresh.addRule(rule);
        }
        for (ArithmeticRule arithmetic : arithmeticRules) {
            fresh.addArithmeticRule(arithmetic);
        }
        for (String key : atomKeys()) {
            String predicate = predicateOf(key);
            String[] args = argsOf(key);
            if (isObserved(key)) {
                fresh.observe(predicate, value(key), args);
            } else {
                fresh.target(predicate, args);
            }
        }
        return fresh;
    }

    private static String predicateOf(String atomKey) {
        int lp = atomKey.indexOf('(');
        return lp < 0 ? atomKey : atomKey.substring(0, lp).trim();
    }

    private static String[] argsOf(String atomKey) {
        int lp = atomKey.indexOf('(');
        int rp = atomKey.lastIndexOf(')');
        if (lp < 0 || rp <= lp) {
            return new String[0];
        }
        String inner = atomKey.substring(lp + 1, rp).trim();
        if (inner.isEmpty()) {
            return new String[0];
        }
        String[] parts = inner.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    /** Add an arithmetic rule (functional, mutual-exclusion, symmetry, etc.). */
    public PslProgram addArithmeticRule(ArithmeticRule rule) {
        arithmeticRules.add(rule);
        return this;
    }

    /** Parse and add an arithmetic rule from text. */
    public PslProgram addArithmeticRule(String ruleText) {
        return addArithmeticRule(ArithmeticRule.parse(ruleText));
    }

    public List<ArithmeticRule> arithmeticRules() {
        return arithmeticRules;
    }

    // ─── Predicate declarations (Step 3) ─────────────────────────────────────

    /**
     * Declare a predicate as <b>closed</b> (CWA): unobserved groundings default to 0.0
     * instead of pruning the rule branch during grounding.
     */
    public PslProgram declareClosed(String name, int arity) {
        closedPredicates.put(name, arity);
        return this;
    }

    /**
     * Declare a predicate as <b>open</b>: unobserved groundings become target atoms.
     * This is the default behaviour; calling this explicitly documents intent.
     */
    public PslProgram declareOpen(String name, int arity) {
        openPredicates.put(name, arity);
        return this;
    }

    public boolean isClosed(String predicateName) {
        return closedPredicates.containsKey(predicateName);
    }

    /**
     * E-8 — Return whether the predicate-to-atoms index is currently cached (i.e. still
     * valid from the last rebuild, no mutations since).  Exposed for testing only.
     */
    boolean isPredicateIndexCached() {
        return !predicateIndexDirty && cachedPredicateIndex != null;
    }

    // ─── External functions (Step 4) ─────────────────────────────────────────

    /**
     * Register an external function predicate.  During grounding, body literals whose
     * predicate name matches are evaluated on the fly rather than looked up in the atom store.
     */
    public PslProgram registerFunction(String name, ExternalFunction fn) {
        functions.put(name, fn);
        return this;
    }

    public boolean isFunction(String predicateName) {
        return functions.containsKey(predicateName);
    }

    // ─── Atoms ───────────────────────────────────────────────────────────────

    private String register(PslAtom groundAtom) {
        if (!groundAtom.isGround()) {
            throw new IllegalArgumentException("Atom is not ground: " + groundAtom);
        }
        // Arity validation against declarations
        String pred = groundAtom.predicate();
        int arity = groundAtom.args().size();
        Integer declaredArity = closedPredicates.containsKey(pred) ? closedPredicates.get(pred)
                : openPredicates.getOrDefault(pred, null);
        if (declaredArity != null && declaredArity != arity) {
            throw new IllegalArgumentException("Arity mismatch for predicate '" + pred
                    + "': declared " + declaredArity + " but got " + arity);
        }
        String key = groundAtom.key();
        if (!atomsByKey.containsKey(key)) {
            atomsByKey.put(key, groundAtom);
            values.put(key, 0.0);
            predicateIndexDirty = true; // E-8: invalidate cached index
        }
        return key;
    }

    /** Declare (or update) an observed atom fixed at {@code value} during inference. */
    public PslProgram observe(PslAtom groundAtom, double value) {
        String key = register(groundAtom);
        if ((value < 0.0 || value > 1.0) && OUT_OF_RANGE_WARNED.compareAndSet(false, true)) {
            // Solver-level guard (WP1a): the value is still clamped below, but a caller passing a
            // soft-truth outside [0,1] (e.g. raw cosine ∈ [-1,1]) is a bug worth surfacing once.
            log.warn("PslProgram.observe: soft-truth value {} (atom '{}') is outside [0,1]; clamping. "
                    + "Callers must pass values in [0,1] (e.g. clamp raw cosine with max(0,cos)). "
                    + "This warns once per JVM.", value, key);
        }
        values.put(key, clamp01(value));
        if (observed.add(key)) {
            predicateIndexDirty = true; // E-8: marking observed may change atom-set composition
        }
        return this;
    }

    public PslProgram observe(String predicate, double value, String... args) {
        return observe(PslAtom.ground(predicate, args), value);
    }

    /** Declare a target atom whose soft truth is to be inferred. */
    public PslProgram target(PslAtom groundAtom) {
        register(groundAtom);
        return this;
    }

    public PslProgram target(String predicate, String... args) {
        return target(PslAtom.ground(predicate, args));
    }

    /**
     * Remove a single atom from the observed set, converting it back to a target atom.
     *
     * <p>This is used by belief revision ({@link ai.kompile.graph.reasoning.tms.BeliefReviser#retractReviseAndPurge})
     * to clear a retracted fact's observed registration so the MAP solver treats it as an
     * unknown (target) rather than a fixed evidence value.  The atom's truth value in the
     * {@link #values} map is reset to 0.0 (the soft-truth default) so the solver can
     * re-optimise it freely.</p>
     *
     * <p>If the atom key is not currently observed, this method is a no-op.</p>
     *
     * @param atomKey the canonical atom key (e.g. {@code "State(alice)"}) to un-observe
     */
    public void clearObserved(String atomKey) {
        if (observed.remove(atomKey)) {
            // Reset the soft-truth value so the solver starts fresh.
            values.put(atomKey, 0.0);
            predicateIndexDirty = true;
        }
    }

    /**
     * Internally register an atom as an observed CWA atom (value 0.0) without exposing it
     * as a target.  Used by the grounding engine when a closed-predicate body literal has
     * no matching registered atom.
     */
    private String registerCwa(PslAtom groundAtom) {
        String key = groundAtom.key();
        if (!atomsByKey.containsKey(key)) {
            atomsByKey.put(key, groundAtom);
            values.put(key, 0.0);
            predicateIndexDirty = true; // E-8: new atom added
        }
        observed.add(key); // CWA: treat as observed-false
        return key;
    }

    public boolean contains(String atomKey) {
        return atomsByKey.containsKey(atomKey);
    }

    public boolean isObserved(String atomKey) {
        return observed.contains(atomKey);
    }

    public double value(String atomKey) {
        return values.getOrDefault(atomKey, 0.0);
    }

    public Set<String> atomKeys() {
        return atomsByKey.keySet();
    }

    public Set<String> observedKeys() {
        return observed;
    }

    /** Keys of all target (non-observed) atoms. */
    public List<String> targetKeys() {
        List<String> targets = new ArrayList<>();
        for (String key : atomsByKey.keySet()) {
            if (!observed.contains(key)) targets.add(key);
        }
        return targets;
    }

    /** A mutable snapshot of the current truth assignment (observed + targets). */
    public Map<String, Double> valueSnapshot() {
        return new LinkedHashMap<>(values);
    }

    public int atomCount() {
        return atomsByKey.size();
    }

    // ─── Grounding ───────────────────────────────────────────────────────────

    /**
     * Instantiate every logical rule against the declared ground atoms via a backtracking join.
     * A rule grounds only when every one of its literals matches a declared ground atom
     * with a consistent variable binding (and all {@code A != B} guards hold).
     *
     * <p><b>CWA (Step 3)</b>: body literals of closed predicates auto-register as 0.0 when absent.</p>
     * <p><b>External functions (Step 4)</b>: body literals of function predicates are evaluated
     * on the fly and registered as temporary observed atoms.</p>
     */
    public List<GroundRule> ground() {
        Map<String, List<PslAtom>> byPredicate = buildPredicateIndex();
        List<GroundRule> out = new ArrayList<>();
        // Identity lookup so instantiate() can stamp each grounding with its template-rule
        // index — signature matching downstream cannot disambiguate equal-weight rules.
        templateIndexLookup = new IdentityHashMap<>();
        for (int i = 0; i < rules.size(); i++) {
            templateIndexLookup.put(rules.get(i), i);
        }
        for (PslRule rule : rules) {
            // E-8: optimise join order for body atoms (most-selective first), then head atoms
            List<PslAtom> optimizedBody = optimizeJoinOrder(rule.body(), byPredicate);
            List<PslAtom> allAtoms = new ArrayList<>(optimizedBody.size() + rule.head().size());
            allAtoms.addAll(optimizedBody);
            allAtoms.addAll(rule.head());
            groundInto(rule, allAtoms, byPredicate, 0, new LinkedHashMap<>(), out);
            if (out.size() >= MAX_GROUND_RULES) break;
        }
        // WP17f — a hit on the grounding cap silently truncates inference; make it loud + observable.
        groundingTruncated = out.size() >= MAX_GROUND_RULES;
        if (groundingTruncated) {
            log.warn("PSL grounding hit the MAX_GROUND_RULES cap ({}) — inference is INCOMPLETE "
                    + "(grounding truncated). Reduce graph/rule fan-out or raise the cap.", MAX_GROUND_RULES);
        }
        return out;
    }

    /** WP17f — true iff the most recent {@link #ground()} was truncated at {@link #MAX_GROUND_RULES}. */
    public boolean isGroundingTruncated() {
        return groundingTruncated;
    }

    /** Ground all {@link ArithmeticRule}s into {@link ArithmeticGroundRule}s. */
    public List<ArithmeticGroundRule> groundArithmetic() {
        Map<String, List<PslAtom>> byPredicate = buildPredicateIndex();
        List<ArithmeticGroundRule> out = new ArrayList<>();
        for (ArithmeticRule rule : arithmeticRules) {
            groundArithmeticRule(rule, byPredicate, out);
        }
        return out;
    }

    /**
     * Build (or return the cached) predicate-to-atoms index.
     *
     * <h3>E-8 — Predicate index caching</h3>
     * <p>The index is rebuilt at most once per mutation cycle: {@link #observe},
     * {@link #target}, and the CWA auto-registration path each set
     * {@link #predicateIndexDirty} = {@code true} so the next {@link #ground} call rebuilds.
     * Subsequent calls within the same mutation cycle reuse the cached index, avoiding
     * O(atoms) overhead on every grounding attempt.</p>
     */
    private Map<String, List<PslAtom>> buildPredicateIndex() {
        if (!predicateIndexDirty && cachedPredicateIndex != null) {
            return cachedPredicateIndex;
        }
        Map<String, List<PslAtom>> byPredicate = new LinkedHashMap<>();
        for (PslAtom atom : atomsByKey.values()) {
            byPredicate.computeIfAbsent(atom.predicate(), k -> new ArrayList<>()).add(atom);
        }
        cachedPredicateIndex = byPredicate;
        predicateIndexDirty = false;
        return byPredicate;
    }

    /**
     * E-8 — Join-order optimisation: reorder body atoms by ascending candidate count.
     *
     * <p>Atoms whose predicate has fewer matching ground atoms are placed first in the
     * backtracking join. This is the classic "most-selective-first" heuristic: binding
     * the fewest-candidate variable earliest prunes the search tree most aggressively,
     * reducing the size of intermediate result sets before the more-permissive predicates
     * are joined in.</p>
     *
     * <p>Head atoms (which always come after the body in {@link PslRule#allAtoms()}) are
     * left in their original order; the optimisation applies to body atoms only.</p>
     *
     * <p>Function predicates and closed-predicate atoms (which may not yet have candidates
     * in the index) are placed last in the body, because their cost model differs from
     * ordinary stored-atom predicates.</p>
     *
     * @param bodyAtoms the body atom list from a {@link PslRule}
     * @param byPredicate the current predicate index (used to estimate selectivity)
     * @return a new list with body atoms sorted most-selective first
     */
    List<PslAtom> optimizeJoinOrder(List<PslAtom> bodyAtoms,
                                    Map<String, List<PslAtom>> byPredicate) {
        if (bodyAtoms.size() <= 1) return bodyAtoms;
        List<PslAtom> sorted = new ArrayList<>(bodyAtoms);
        sorted.sort(Comparator.comparingInt(atom -> {
            String pred = atom.predicate();
            // Function predicates: expensive to enumerate → push to end
            if (functions.containsKey(pred)) return Integer.MAX_VALUE;
            // Closed predicates with no atoms yet: they expand at grounding time → push late
            if (closedPredicates.containsKey(pred)
                    && !byPredicate.containsKey(pred)) return Integer.MAX_VALUE - 1;
            List<PslAtom> cands = byPredicate.get(pred);
            return cands == null ? Integer.MAX_VALUE - 2 : cands.size();
        }));
        return sorted;
    }

    // ─── Logical-rule grounding ───────────────────────────────────────────────

    private void groundInto(PslRule rule, List<PslAtom> atoms, Map<String, List<PslAtom>> byPredicate,
                            int index, Map<String, String> binding, List<GroundRule> out) {
        if (out.size() >= MAX_GROUND_RULES) return;
        if (index == atoms.size()) {
            if (satisfiesDistinct(rule, binding)) {
                out.add(instantiate(rule, binding));
            }
            return;
        }
        PslAtom template = atoms.get(index);
        String pred = template.predicate();

        // Step 4 — external function predicate
        if (functions.containsKey(pred)) {
            groundWithFunction(rule, atoms, byPredicate, index, binding, out, template);
            return;
        }

        List<PslAtom> candidates = byPredicate.get(pred);

        // Step 3 — closed-world assumption: absent atoms of closed predicates → value 0.0
        if (candidates == null && closedPredicates.containsKey(pred)) {
            // Only unify positions that are already bound; unbound variables cannot be constrained
            // from the closed-predicate alone — generate all candidates by brute-force from
            // the constant universe visible so far.
            Set<String> constants = collectBoundConstants(binding, byPredicate);
            candidates = generateCwaCandidates(template, constants);
            for (PslAtom candidate : candidates) {
                // Auto-register as observed 0.0 (CWA)
                if (!atomsByKey.containsKey(candidate.key())) {
                    registerCwa(candidate);
                    byPredicate.computeIfAbsent(pred, k -> new ArrayList<>()).add(candidate);
                }
                Map<String, String> extended = unify(template, candidate, binding);
                if (extended != null) {
                    groundInto(rule, atoms, byPredicate, index + 1, extended, out);
                }
            }
            return;
        }

        if (candidates == null) return; // no atoms for this predicate ⇒ rule cannot ground
        for (PslAtom candidate : candidates) {
            Map<String, String> extended = unify(template, candidate, binding);
            if (extended != null) {
                groundInto(rule, atoms, byPredicate, index + 1, extended, out);
            }
        }
    }

    /** Collect all constant values currently bound (from partial binding + all known atoms). */
    private Set<String> collectBoundConstants(Map<String, String> binding,
                                               Map<String, List<PslAtom>> byPredicate) {
        Set<String> constants = new LinkedHashSet<>(binding.values());
        for (List<PslAtom> atoms : byPredicate.values()) {
            for (PslAtom a : atoms) {
                for (Term t : a.args()) constants.add(t.name());
            }
        }
        return constants;
    }

    /**
     * Generate candidate ground atoms for a CWA predicate by substituting all known
     * constants for unbound variable positions (Cartesian product).
     */
    private List<PslAtom> generateCwaCandidates(PslAtom template, Set<String> constants) {
        // Start with a list containing one binding: the current fixed positions
        List<Map<String, String>> partials = new ArrayList<>();
        partials.add(new LinkedHashMap<>());
        for (Term t : template.args()) {
            if (!t.variable()) continue;  // constant position — no expansion needed
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> partial : partials) {
                for (String c : constants) {
                    Map<String, String> copy = new LinkedHashMap<>(partial);
                    copy.put(t.name(), c);
                    next.add(copy);
                }
            }
            if (!next.isEmpty()) partials = next;
        }
        List<PslAtom> result = new ArrayList<>();
        for (Map<String, String> binding : partials) {
            PslAtom candidate = template.ground(binding);
            if (candidate.isGround()) result.add(candidate);
        }
        return result;
    }

    /** Handle an external-function body literal: evaluate fn, register temp atom, continue. */
    private void groundWithFunction(PslRule rule, List<PslAtom> atoms,
                                    Map<String, List<PslAtom>> byPredicate,
                                    int index, Map<String, String> binding,
                                    List<GroundRule> out, PslAtom template) {
        ExternalFunction fn = functions.get(template.predicate());
        // Collect all candidate tuples of constants for the unbound variables
        Set<String> constants = collectBoundConstants(binding, byPredicate);
        List<PslAtom> candidates = generateCwaCandidates(template, constants);
        for (PslAtom candidate : candidates) {
            Map<String, String> extended = unify(template, candidate, binding);
            if (extended == null) continue;
            // Evaluate the function with the bound constant values
            String[] argValues = new String[candidate.args().size()];
            for (int i = 0; i < candidate.args().size(); i++) {
                argValues[i] = candidate.args().get(i).name();
            }
            double fnValue = fn.evaluate(argValues);
            if (fnValue <= 0.0) continue; // skip zero-valued function atoms (open-world default)
            // Register as a temporary observed atom
            String key = candidate.key();
            if (!atomsByKey.containsKey(key)) {
                atomsByKey.put(key, candidate);
                values.put(key, clamp01(fnValue));
                observed.add(key);
                byPredicate.computeIfAbsent(template.predicate(), k -> new ArrayList<>()).add(candidate);
            }
            groundInto(rule, atoms, byPredicate, index + 1, extended, out);
        }
    }

    // ─── Arithmetic-rule grounding ────────────────────────────────────────────

    /**
     * Ground one arithmetic rule.
     *
     * <p>The strategy:
     * <ol>
     *   <li>Identify all <em>non-summation variables</em> (i.e., variables that appear in
     *       non-summation argument positions of predicate terms).  Build a template atom for
     *       each such predicate by replacing summation-variable arguments with a fresh wildcard,
     *       so the outer join only binds the non-summation variables.</li>
     *   <li>For each outer binding, look up all atoms for any summation-variable term and sum
     *       their contributions into the linear combination, emitting one
     *       {@link ArithmeticGroundRule} per outer binding.</li>
     * </ol>
     *
     * <p>Example: {@code HasType(X, +C) = 1.}
     * <ul>
     *   <li>Non-summation variable: {@code X} (position 0 of HasType).</li>
     *   <li>Outer join template: {@code HasType(X, _)} where {@code _} is an unbound wildcard
     *       — matching all distinct {@code X} bindings from the atom store.</li>
     *   <li>For each outer binding {@code X=e1}: collect all atoms {@code HasType(e1, *)},
     *       sum with coefficient 1.0.</li>
     * </ul>
     */
    private void groundArithmeticRule(ArithmeticRule rule,
                                      Map<String, List<PslAtom>> byPredicate,
                                      List<ArithmeticGroundRule> out) {
        // Build outer-join templates.
        // For each predicate term: non-summation args drive the outer join;
        // summation args are replaced by a unique fresh variable (so they don't constrain the join).
        List<PslAtom> outerAtoms = new ArrayList<>();
        for (ArithmeticRule.ArithmeticTerm term : rule.lhs()) {
            addOuterAtom(term, outerAtoms);
        }
        for (ArithmeticRule.ArithmeticTerm term : rule.rhs()) {
            addOuterAtom(term, outerAtoms);
        }
        // Deduplicate by predicate+non-summation signature
        List<PslAtom> dedupedOuter = deduplicateOuterAtoms(outerAtoms);

        List<Map<String, String>> outerBindings = new ArrayList<>();
        if (dedupedOuter.isEmpty()) {
            outerBindings.add(new LinkedHashMap<>());
        } else {
            enumerateBindings(dedupedOuter, byPredicate, 0, new LinkedHashMap<>(), outerBindings);
        }

        // Deduplicate on the non-summation variable bindings only.
        // The outer join may produce multiple bindings that only differ in the value of
        // the __SUM_* wildcard variables (one per candidate in the summation domain).
        // We collapse these to unique non-summation bindings.
        Set<String> summationWildcards = collectSummationWildcardNames(rule);
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, String> outerBinding : outerBindings) {
            // Build a signature from non-wildcard entries only
            Map<String, String> reducedBinding = new LinkedHashMap<>(outerBinding);
            summationWildcards.forEach(reducedBinding::remove);
            String sig = reducedBinding.toString();
            if (seen.add(sig)) {
                emitArithmeticGroundRule(rule, reducedBinding, byPredicate, out);
                if (out.size() >= MAX_GROUND_RULES) return;
            }
        }
    }

    /**
     * For a given arithmetic term, build a "reduced" template for the outer join:
     * non-summation variable positions are kept; summation-variable positions are replaced
     * by a fresh unique variable name so they don't constrain the join (will match anything).
     */
    private void addOuterAtom(ArithmeticRule.ArithmeticTerm term, List<PslAtom> outerAtoms) {
        if (term.isConstant()) return;
        if (term.args().isEmpty()) {
            outerAtoms.add(new PslAtom(term.predicate(), List.of(), false));
            return;
        }
        // Check if ANY argument is a summation variable
        boolean hasSummationArg = false;
        for (Term arg : term.args()) {
            if (arg.variable()) {
                // Summation variables are those prefixed with '+' in the source;
                // at parse time, ArithmeticRule marks the entire term as summationVariable
                // if any argument was prefixed '+'.  We need to track which arg positions
                // are summation vars — currently stored at term level, not arg level.
                // As a heuristic: if the term is marked summationVariable, treat the LAST
                // variable argument as the summation variable (the most common PSL pattern
                // is Pred(GroupVar, +SummationVar)).
                hasSummationArg = term.summationVariable();
                break;
            }
        }
        if (!hasSummationArg) {
            // Pure non-summation term: use as-is in the outer join
            outerAtoms.add(new PslAtom(term.predicate(), term.args(), false));
            return;
        }
        // Mixed term: identify summation vs non-summation arg positions.
        // PSL convention: the +var is the last variable arg in the term by convention
        // (e.g. HasType(X, +C) — X is outer, +C is summation).
        // We approximate: the last variable position is the summation var.
        int lastVarIdx = -1;
        for (int i = term.args().size() - 1; i >= 0; i--) {
            if (term.args().get(i).variable()) { lastVarIdx = i; break; }
        }
        List<Term> reducedArgs = new ArrayList<>(term.args());
        if (lastVarIdx >= 0) {
            // Replace with a unique fresh variable that won't conflict with real variables
            reducedArgs.set(lastVarIdx, Term.var("__SUM_" + term.predicate() + "_" + lastVarIdx));
        }
        // Only add to outer join if there are non-wildcard variable positions
        boolean hasNonWildcard = false;
        for (int i = 0; i < reducedArgs.size(); i++) {
            if (i != lastVarIdx && reducedArgs.get(i).variable()) { hasNonWildcard = true; break; }
        }
        if (hasNonWildcard) {
            outerAtoms.add(new PslAtom(term.predicate(), reducedArgs, false));
        }
    }

    /** Remove atoms with identical predicate+args from the outer template list. */
    private List<PslAtom> deduplicateOuterAtoms(List<PslAtom> atoms) {
        List<PslAtom> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PslAtom a : atoms) {
            if (seen.add(a.predicate() + a.args().toString())) result.add(a);
        }
        return result;
    }

    /** Collect the names of all synthetic summation-wildcard variables (__SUM_...) in outer templates. */
    private Set<String> collectSummationWildcardNames(ArithmeticRule rule) {
        Set<String> wildcards = new LinkedHashSet<>();
        for (ArithmeticRule.ArithmeticTerm term : rule.lhs()) addSummationWildcards(term, wildcards);
        for (ArithmeticRule.ArithmeticTerm term : rule.rhs()) addSummationWildcards(term, wildcards);
        return wildcards;
    }

    private void addSummationWildcards(ArithmeticRule.ArithmeticTerm term, Set<String> wildcards) {
        if (term.isConstant() || !term.summationVariable()) return;
        // Identify the last variable argument position (the summation position)
        for (int i = term.args().size() - 1; i >= 0; i--) {
            if (term.args().get(i).variable()) {
                wildcards.add("__SUM_" + term.predicate() + "_" + i);
                break;
            }
        }
    }

    /** Recursively enumerate all consistent variable bindings for a list of template atoms. */
    private void enumerateBindings(List<PslAtom> atoms,
                                   Map<String, List<PslAtom>> byPredicate,
                                   int index,
                                   Map<String, String> binding,
                                   List<Map<String, String>> out) {
        if (index == atoms.size()) {
            out.add(new LinkedHashMap<>(binding));
            return;
        }
        PslAtom template = atoms.get(index);
        List<PslAtom> candidates = byPredicate.get(template.predicate());
        if (candidates == null) return;
        for (PslAtom candidate : candidates) {
            Map<String, String> extended = unify(template, candidate, binding);
            if (extended != null) {
                enumerateBindings(atoms, byPredicate, index + 1, extended, out);
            }
        }
    }

    /**
     * For a given outer binding, enumerate all summation-variable bindings, accumulate the
     * linear sum, and emit one {@link ArithmeticGroundRule}.
     */
    private void emitArithmeticGroundRule(ArithmeticRule rule,
                                          Map<String, String> outerBinding,
                                          Map<String, List<PslAtom>> byPredicate,
                                          List<ArithmeticGroundRule> out) {
        // Accumulate: key → net coefficient
        Map<String, Double> netCoef = new LinkedHashMap<>();
        double rhsConstant = 0.0;

        // Process LHS
        for (ArithmeticRule.ArithmeticTerm term : rule.lhs()) {
            if (term.isConstant()) {
                rhsConstant -= term.coefficient(); // move constant to RHS
                continue;
            }
            rhsConstant += accumulateTerm(term, outerBinding, byPredicate, netCoef, term.coefficient());
        }
        // Process RHS — move to LHS with negated coefficient
        for (ArithmeticRule.ArithmeticTerm term : rule.rhs()) {
            if (term.isConstant()) {
                rhsConstant += term.coefficient();
                continue;
            }
            rhsConstant += accumulateTerm(term, outerBinding, byPredicate, netCoef, -term.coefficient());
        }

        if (netCoef.isEmpty()) return; // nothing to constrain
        String[] keys = netCoef.keySet().toArray(new String[0]);
        double[] coefs = new double[keys.length];
        for (int i = 0; i < keys.length; i++) coefs[i] = netCoef.get(keys[i]);

        out.add(new ArithmeticGroundRule(rule.weight(), rule.hard(), rule.squared(),
                coefs, keys, rhsConstant, rule.op()));
    }

    /**
     * Accumulate one arithmetic term into the coefficient map.
     *
     * @return any constant offset that should be moved to the RHS constant
     */
    private double accumulateTerm(ArithmeticRule.ArithmeticTerm term,
                                  Map<String, String> outerBinding,
                                  Map<String, List<PslAtom>> byPredicate,
                                  Map<String, Double> netCoef,
                                  double signedCoef) {
        if (!term.summationVariable()) {
            // Single atom — ground it directly from the outer binding
            String key = term.groundKey(outerBinding);
            // Ensure the atom is registered (may be a CWA predicate)
            if (!atomsByKey.containsKey(key) && closedPredicates.containsKey(term.predicate())) {
                List<Term> groundArgs = new ArrayList<>();
                for (Term t : term.args()) {
                    String val = t.variable() ? outerBinding.getOrDefault(t.name(), t.name()) : t.name();
                    groundArgs.add(Term.con(val));
                }
                registerCwa(new PslAtom(term.predicate(), groundArgs, false));
            }
            netCoef.merge(key, signedCoef, Double::sum);
            return 0.0;
        }

        // Summation variable: enumerate all candidates and sum
        // Identify which argument position is the summation variable
        List<PslAtom> candidates = byPredicate.get(term.predicate());
        if (candidates == null) return 0.0;

        // Build a template atom for the summation
        PslAtom template = new PslAtom(term.predicate(), term.args(), false);
        for (PslAtom candidate : candidates) {
            Map<String, String> extended = unify(template, candidate, outerBinding);
            if (extended != null) {
                String key = candidate.key();
                netCoef.merge(key, signedCoef, Double::sum);
            }
        }
        return 0.0;
    }

    /** Try to match a (possibly non-ground) template against a ground candidate atom. */
    private Map<String, String> unify(PslAtom template, PslAtom candidate, Map<String, String> binding) {
        if (template.args().size() != candidate.args().size()) return null;
        Map<String, String> extended = null;
        for (int i = 0; i < template.args().size(); i++) {
            Term t = template.args().get(i);
            String c = candidate.args().get(i).name();
            if (t.variable()) {
                String bound = binding.get(t.name());
                if (bound != null) {
                    if (!bound.equals(c)) return null;
                } else {
                    if (extended == null) extended = new LinkedHashMap<>(binding);
                    extended.put(t.name(), c);
                    binding = extended; // so later positions in the same atom see the binding
                }
            } else if (!t.name().equals(c)) {
                return null;
            }
        }
        return extended != null ? extended : new LinkedHashMap<>(binding);
    }

    private boolean satisfiesDistinct(PslRule rule, Map<String, String> binding) {
        for (String[] pair : rule.distinct()) {
            String a = binding.getOrDefault(pair[0], pair[0]);
            String b = binding.getOrDefault(pair[1], pair[1]);
            if (a.equals(b)) return false;
        }
        return true;
    }

    private GroundRule instantiate(PslRule rule, Map<String, String> binding) {
        List<GroundRule.Lit> body = new ArrayList<>(rule.body().size());
        List<GroundRule.Lit> head = new ArrayList<>(rule.head().size());
        for (PslAtom atom : rule.body()) {
            body.add(new GroundRule.Lit(atom.ground(binding).key(), atom.negated()));
        }
        for (PslAtom atom : rule.head()) {
            head.add(new GroundRule.Lit(atom.ground(binding).key(), atom.negated()));
        }
        int templateIndex = templateIndexLookup == null ? -1
                : templateIndexLookup.getOrDefault(rule, -1);
        return new GroundRule(rule.weight(), rule.hard(), rule.squared(), body, head,
                renderGround(rule, binding), templateIndex);
    }

    private String renderGround(PslRule rule, Map<String, String> binding) {
        StringBuilder sb = new StringBuilder();
        if (!rule.hard()) sb.append(trim(rule.weight())).append(": ");
        sb.append(renderLiterals(rule.body(), binding, " & "));
        if (!rule.body().isEmpty()) sb.append(" -> ");
        sb.append(renderLiterals(rule.head(), binding, " | "));
        if (rule.hard()) sb.append(" .");
        return sb.toString();
    }

    private String renderLiterals(List<PslAtom> atoms, Map<String, String> binding, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < atoms.size(); i++) {
            if (i > 0) sb.append(sep);
            PslAtom atom = atoms.get(i);
            sb.append(atom.negated() ? "~" : "").append(atom.ground(binding).key());
        }
        return sb.toString();
    }

    private static String trim(double w) {
        if (w == Math.rint(w) && !Double.isInfinite(w)) return Long.toString((long) w);
        return Double.toString(w);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
