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

import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.psl.PslAtom;
import ai.kompile.graph.reasoning.psl.Term;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent-facing primitive for conjunctive pattern queries against the knowledge base.
 *
 * <p>This is the <em>P0-2 query primitive</em> from the agent-grounding infrastructure
 * design. An LLM agent can ask the KB:
 * <pre>
 *   "Find all ?Person who worksFor ?Company AND hasSkill 'AI'"
 * </pre>
 * by constructing two {@link AtomPattern}s and calling {@link #query(List, InferredFactStore, int)}.
 *
 * <h3>Algorithm</h3>
 * <p>The join kernel is the same backtracking conjunctive join used in
 * {@code PslProgram.groundInto}: for each conjunct in order, iterate all atoms with the
 * matching predicate in the {@link InferredFactStore} and attempt to unify their arguments
 * against the current partial variable binding. When all conjuncts are satisfied, emit a
 * {@link QueryBinding} whose confidence is the minimum soft-truth across matched atoms
 * (Łukasiewicz T-norm — consistent with PSL semantics).</p>
 *
 * <p>Note: the unification helper ({@code unify}) implements the same algorithm as
 * {@code PslProgram}'s private {@code unify} method. Rather than adding a public hook to
 * {@code PslProgram} for a 12-line pure function, it is reproduced here with the same
 * semantics. Both methods produce identical results for identical inputs.</p>
 *
 * <h3>Variable convention</h3>
 * <p>A query argument is treated as a <em>variable</em> if it starts with {@code "?"} or
 * starts with an upper-case ASCII letter (following PSL/Prolog convention). All other
 * tokens are treated as ground constants.</p>
 */
public final class ConjunctiveQueryEngine {

    /** Default maximum number of result rows returned before truncation. */
    public static final int DEFAULT_MAX_RESULTS = 50;

    private ConjunctiveQueryEngine() {}

    // ─── Atom-pattern API ────────────────────────────────────────────────────────

    /**
     * A single conjunct in a conjunctive query pattern.
     *
     * <p>Arguments may be variables (starting with {@code "?"} or upper-case) or ground
     * constants. Example:
     * <pre>
     *   new AtomPattern("worksFor", List.of("?X", "Acme"))
     *   new AtomPattern("hasSkill", List.of("?X", "AI"))
     * </pre>
     *
     * @param predicate  the predicate name to match (must not be null)
     * @param args       the argument patterns (variables or constants)
     */
    public record AtomPattern(String predicate, List<String> args) {
        public AtomPattern {
            Objects.requireNonNull(predicate, "predicate must not be null");
            args = (args == null) ? List.of() : List.copyOf(args);
        }

        /** Build the canonical atom key for a given binding. Used for lookup. */
        String groundKey(Map<String, String> binding) {
            StringBuilder sb = new StringBuilder(predicate).append('(');
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(", ");
                String a = args.get(i);
                String canonical = canonicalVarName(a);
                String bound = binding.get(canonical);
                sb.append(bound != null ? bound : a);
            }
            return sb.append(')').toString();
        }

        /** Convert to a {@link PslAtom} template for predicate-index lookup. */
        PslAtom toTemplate() {
            List<Term> terms = new ArrayList<>(args.size());
            for (String a : args) {
                if (isVariable(a)) {
                    terms.add(Term.var(canonicalVarName(a)));
                } else {
                    terms.add(Term.con(a));
                }
            }
            return new PslAtom(predicate, terms, false);
        }
    }

    // ─── Query execution ─────────────────────────────────────────────────────────

    /**
     * Execute a conjunctive query against the materialized {@link InferredFactStore}.
     *
     * <p>Only atoms that are present in the {@code InferredFactStore} contribute to
     * query results. The fast-path lookup is O(atoms × patterns) with no MAP
     * re-inference.</p>
     *
     * @param conjuncts  the list of atom patterns forming the conjunctive query
     *                   (must not be null or empty)
     * @param store      the materialized inferred fact store to query
     * @param maxResults maximum number of result rows (truncation guard)
     * @return list of variable bindings, each with a per-row minimum confidence
     */
    public static List<QueryBinding> query(List<AtomPattern> conjuncts,
                                           InferredFactStore store,
                                           int maxResults) {
        Objects.requireNonNull(conjuncts, "conjuncts must not be null");
        Objects.requireNonNull(store, "store must not be null");
        if (conjuncts.isEmpty()) return List.of();
        int limit = maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;

        // Build a predicate index over the InferredFactStore for fast candidate lookup
        Map<String, List<InferredFact>> byPredicate = buildPredicateIndex(store);

        List<QueryBinding> results = new ArrayList<>();
        backtrack(conjuncts, byPredicate, 0, new LinkedHashMap<>(), 1.0, results, limit);
        return Collections.unmodifiableList(results);
    }

    /**
     * Convenience overload using the default max-results limit.
     *
     * @param conjuncts the list of atom patterns
     * @param store     the inferred fact store
     * @return list of variable bindings
     */
    public static List<QueryBinding> query(List<AtomPattern> conjuncts, InferredFactStore store) {
        return query(conjuncts, store, DEFAULT_MAX_RESULTS);
    }

    // ─── Backtracking join ───────────────────────────────────────────────────────

    /**
     * Recursive backtracking join — the same algorithm as {@code PslProgram.groundInto}
     * but returning variable bindings instead of grounded rules.
     *
     * <p>Delegates to the shared {@link JoinKernel#backtrack} so that
     * {@link RecursiveQueryEngine} can reuse the same join logic without duplication.</p>
     *
     * @param conjuncts    ordered conjunct list
     * @param byPredicate  predicate-index over the InferredFactStore
     * @param idx          current conjunct index
     * @param binding      current partial variable binding
     * @param minConf      running minimum confidence (Łukasiewicz T-norm)
     * @param out          output accumulator
     * @param limit        maximum results before early termination
     */
    private static void backtrack(List<AtomPattern> conjuncts,
                                  Map<String, List<InferredFact>> byPredicate,
                                  int idx,
                                  Map<String, String> binding,
                                  double minConf,
                                  List<QueryBinding> out,
                                  int limit) {
        JoinKernel.backtrack(conjuncts, byPredicate, idx, binding, minConf, out, limit);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Build a predicate → fact list index from the InferredFactStore.
     *
     * <p>The predicate name is extracted from the atom key's format:
     * {@code predicate(arg1, arg2, ...)}.</p>
     */
    private static Map<String, List<InferredFact>> buildPredicateIndex(InferredFactStore store) {
        Map<String, List<InferredFact>> index = new LinkedHashMap<>();
        for (InferredFact fact : store.allLatest()) {
            String predicate = extractPredicate(fact.atomKey());
            if (predicate != null) {
                index.computeIfAbsent(predicate, k -> new ArrayList<>()).add(fact);
            }
        }
        return index;
    }

    /**
     * Try to unify a (possibly non-ground) template atom against a ground candidate atom.
     *
     * <p>This implements the same unification as {@code PslProgram}'s private {@code unify}
     * method: variables in the template are bound to the corresponding constants in the
     * candidate; a clash (variable already bound to a different constant) causes failure.</p>
     *
     * <p>Delegates to {@link JoinKernel#unify} so both query engines share a single
     * implementation.</p>
     *
     * @param template  the pattern atom (may contain variables)
     * @param candidate the ground atom from the fact store
     * @param binding   the current partial binding
     * @return the extended binding on success, or {@code null} on failure
     */
    static Map<String, String> unify(PslAtom template, PslAtom candidate, Map<String, String> binding) {
        return JoinKernel.unify(template, candidate, binding);
    }

    /**
     * Parse an atom key string of the form {@code predicate(arg1, arg2, ...)} into a
     * ground {@link PslAtom}. Returns {@code null} if the key cannot be parsed.
     */
    static PslAtom parseAtomKey(String atomKey) {
        if (atomKey == null) return null;
        int lp = atomKey.indexOf('(');
        if (lp < 0) {
            // 0-arity predicate
            return new PslAtom(atomKey.trim(), List.of(), false);
        }
        int rp = atomKey.lastIndexOf(')');
        if (rp <= lp) return null;
        String predicate = atomKey.substring(0, lp).trim();
        String inside = atomKey.substring(lp + 1, rp).trim();
        List<Term> args = new ArrayList<>();
        if (!inside.isEmpty()) {
            for (String tok : inside.split(",")) {
                args.add(Term.con(tok.trim()));
            }
        }
        return new PslAtom(predicate, args, false);
    }

    /**
     * Extract the predicate name from an atom key ({@code "pred(a,b)"} → {@code "pred"}).
     * Returns {@code null} if the key is blank.
     */
    static String extractPredicate(String atomKey) {
        if (atomKey == null || atomKey.isBlank()) return null;
        int lp = atomKey.indexOf('(');
        return lp < 0 ? atomKey.trim() : atomKey.substring(0, lp).trim();
    }

    /**
     * Whether a query argument string is a variable.
     *
     * <p>In the agent-facing query API, variables are identified exclusively by the
     * {@code "?"} prefix (e.g. {@code "?X"}, {@code "?Company"}). This differs from
     * the internal PSL {@link Term#parse} convention (uppercase = variable) because
     * KB entity constants routinely start with uppercase letters (e.g. {@code "Alice"},
     * {@code "AcmeNYC"}, {@code "AI"}). Using only the {@code "?"} prefix avoids
     * ambiguity between entity names and query variables.</p>
     *
     * @param arg the query argument string
     * @return {@code true} if the argument is a query variable
     */
    static boolean isVariable(String arg) {
        return arg != null && arg.startsWith("?");
    }

    /**
     * Return the canonical variable name: strip leading {@code "?"} if present.
     * This normalizes {@code "?X"} and {@code "X"} to the same key in bindings.
     */
    static String canonicalVarName(String arg) {
        return (arg != null && arg.startsWith("?")) ? arg.substring(1) : arg;
    }
}
