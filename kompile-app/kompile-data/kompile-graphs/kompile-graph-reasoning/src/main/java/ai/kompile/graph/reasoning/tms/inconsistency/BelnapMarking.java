/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.tms.inconsistency;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.tms.ContradictionDetector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Fast paraconsistent marking of a fact collection using Belnap's 4-valued logic.
 *
 * <h3>Algorithm</h3>
 * <p>Each ground atom (identified by its {@linkplain ParsedAtom#canonicalKey() canonical key} —
 * predicate key + args, without negation decoration) receives one of four marks:</p>
 * <ul>
 *   <li>{@link Mark#T} — supported: only strong positive evidence (value ≥ {@value #EVIDENCE_THRESHOLD}) found.</li>
 *   <li>{@link Mark#F} — refuted: only strong negated evidence (value ≥ threshold) found.</li>
 *   <li>{@link Mark#B} — both: strong evidence for the atom <em>and</em> for its negation — the
 *       paraconsistent case.</li>
 *   <li>{@link Mark#N} — neither: no strong evidence in either direction.</li>
 * </ul>
 *
 * <p>A "conflict pair" is recorded for each (positive-fact, negating-fact) pair that both exceed
 * the evidence threshold. Two mechanisms produce conflict pairs — exactly mirroring the two
 * contradiction kinds in {@link ContradictionDetector}:</p>
 * <ol>
 *   <li><b>Negated-pair conflict</b> — one fact has a canonical atom key {@code P(args)} with
 *       value ≥ threshold and another has the same ground atom but with a negation marker
 *       ({@code NOT_}, {@code NO_}, {@code NON_}, {@code NEGATED_}, {@code DENIES_},
 *       {@code DENY_}, {@code REFUTES_}, {@code REFUTE_}, {@code DISPROVES_}, {@code IS_NOT_},
 *       or the {@code !} / {@code ~} / {@code "not "} / {@code "not(...)"} syntactic forms).</li>
 *   <li><b>Functional-predicate conflict</b> — two non-negated facts share the same functional
 *       predicate (per {@link ContradictionDetector#isFunctionalPredicate(String)}) and the same
 *       first argument, but different full argument lists — the classic single-valued-relation
 *       violation (e.g. {@code CEO(acme,alice)} vs {@code CEO(acme,bob)}).</li>
 * </ol>
 *
 * <h3>Negation parsing is ContradictionDetector's</h3>
 * <p>Atoms are parsed by {@link ContradictionDetector.ParsedAtom#parse}, so this marking and the
 * contradiction checks agree on which facts negate which, and predicates are compared by
 * {@link ai.kompile.graph.reasoning.query.PredicateNames#key}: {@code headquarteredIn},
 * {@code HEADQUARTERED_IN}, and {@code HEADQUARTEREDIN} mark one atom.</p>
 *
 * <h3>Evidence threshold</h3>
 * <p>Only facts with {@code value ≥ 0.70} ({@value #EVIDENCE_THRESHOLD}) count as strong evidence.
 * This is intentionally lower than ContradictionDetector's hard-fact / {@code highTruth} threshold
 * of 0.9 to capture probabilistic near-certainties, but still filters out weak signals (e.g.,
 * 0.4 vs 0.9 does NOT mark B as required by the specification). Use
 * {@link #mark(Collection, double)} to supply a custom threshold.</p>
 *
 * <h3>References</h3>
 * <ul>
 *   <li>Belnap, "A useful four-valued logic", in <em>Modern Uses of Multiple-Valued Logic</em>,
 *       1977.</li>
 *   <li>Hunter &amp; Konieczny, "Measuring Inconsistency through Minimal Inconsistent Sets",
 *       KR 2008 / AIJ 2010.</li>
 *   <li>Thimm, <em>Inconsistency Measurement</em>, 2019
 *       (reference implementation: Tweety, tweetyproject.org).</li>
 * </ul>
 */
public final class BelnapMarking {

    /** Default evidence-strength threshold (inclusive). Facts below this are not "strong". */
    public static final double EVIDENCE_THRESHOLD = 0.70;

    private BelnapMarking() {}

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Public entry points
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Mark a collection of {@link Fact} objects using the default evidence threshold
     * ({@value #EVIDENCE_THRESHOLD}).
     *
     * @param facts the facts to analyse; nulls within the collection are silently skipped
     * @return the Belnap marking result
     */
    public static MarkingResult mark(Collection<Fact> facts) {
        return mark(facts, EVIDENCE_THRESHOLD);
    }

    /**
     * Mark a collection of {@link Fact} objects using a caller-supplied threshold.
     *
     * @param facts             the facts to analyse; nulls within the collection are silently skipped
     * @param evidenceThreshold minimum {@code value} for a fact to count as strong evidence (inclusive)
     * @return the Belnap marking result
     */
    public static MarkingResult mark(Collection<Fact> facts, double evidenceThreshold) {
        if (facts == null || facts.isEmpty()) {
            return new MarkingResult(Map.of(), 0);
        }
        List<Fact> safeList = new ArrayList<>(facts.size());
        for (Fact f : facts) {
            if (f != null) safeList.add(f);
        }
        return computeMarking(safeList, evidenceThreshold);
    }

    /**
     * Mark from a plain atom-key → value map (no Fact wrapper).
     *
     * <p>All entries are treated as soft (hard=false) for the purpose of this marking — the
     * {@code hard} flag is not used here; only the {@code value} threshold matters. This entry
     * point is convenient for quick ad-hoc marking when you already have a posterior map.</p>
     *
     * @param atomValues        map from atom key (e.g. {@code "CEO(acme,alice)"}) to truth value [0,1]
     * @param evidenceThreshold minimum value to count as strong evidence (inclusive)
     * @return the Belnap marking result
     */
    public static MarkingResult markMap(Map<String, Double> atomValues, double evidenceThreshold) {
        if (atomValues == null || atomValues.isEmpty()) {
            return new MarkingResult(Map.of(), 0);
        }
        List<Fact> facts = new ArrayList<>(atomValues.size());
        Instant now = Instant.now();
        for (Map.Entry<String, Double> e : atomValues.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                double v = Math.max(0.0, Math.min(1.0, e.getValue()));
                facts.add(new Fact(e.getKey(), v, "map", now, false));
            }
        }
        return computeMarking(facts, evidenceThreshold);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Core algorithm
    // ─────────────────────────────────────────────────────────────────────────────────────

    private static MarkingResult computeMarking(List<Fact> facts, double threshold) {
        int n = facts.size();
        ParsedAtom[] parsed = new ParsedAtom[n];
        for (int i = 0; i < n; i++) {
            parsed[i] = ParsedAtom.parse(facts.get(i).atomKey());
        }

        // Per canonical atom: accumulate whether we saw positive or negative strong evidence.
        // canonicalKey = predicate key + "|" + args (without negation decoration)
        Map<String, boolean[]> evidenceMap = new LinkedHashMap<>(); // [0]=positive, [1]=negative

        for (int i = 0; i < n; i++) {
            Fact f = facts.get(i);
            if (f.value() < threshold) continue; // below evidence threshold → skip
            ParsedAtom pa = parsed[i];
            String canonKey = pa.canonicalKey();
            boolean[] flags = evidenceMap.computeIfAbsent(canonKey, k -> new boolean[2]);
            if (pa.negated()) {
                flags[1] = true; // strong negative evidence
            } else {
                flags[0] = true; // strong positive evidence
            }
        }

        // ── Functional-predicate conflicts ────────────────────────────────────────────────
        // Group non-negated strong-evidence facts by (predicate key, firstArg).
        // Any bucket with 2+ DISTINCT canonical keys ↦ functional clash → all atoms in bucket B.
        // We use a LinkedHashSet per bucket to deduplicate (same fact asserted twice = same key).
        Map<String, LinkedHashSet<String>> byFuncSubject = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Fact f = facts.get(i);
            if (f.value() < threshold) continue;
            ParsedAtom pa = parsed[i];
            if (pa.negated()) continue;
            if (pa.args().isEmpty()) continue;
            if (!ContradictionDetector.isFunctionalPredicate(pa.predicate())) continue;
            String bucketKey = pa.predicateKey() + "|" + pa.args().get(0);
            byFuncSubject.computeIfAbsent(bucketKey, k -> new LinkedHashSet<>())
                    .add(pa.canonicalKey());
        }
        // Mark all atoms in a functional-clash bucket as having both-positive-evidence conflict.
        // We represent this by artificially setting both flags[0] and flags[1] for each
        // conflicting atom. Two distinct canonicalKeys with the same first arg and functional
        // predicate both become B, because each one "sees" the other as its contradiction.
        for (LinkedHashSet<String> group : byFuncSubject.values()) {
            if (group.size() < 2) continue; // only 1 distinct value → no clash
            for (String canonKey : group) {
                boolean[] flags = evidenceMap.computeIfAbsent(canonKey, k -> new boolean[2]);
                // Both are positive, but they conflict with each other: mark both sides B.
                // We signal this by setting the negative flag as well (synthetic conflict).
                flags[0] = true;
                flags[1] = true; // synthetic: conflicting functional value acts as negating evidence
            }
        }

        // Build the output map
        Map<String, Mark> byCanonicalAtom = new LinkedHashMap<>();
        int bCount = 0;
        for (Map.Entry<String, boolean[]> entry : evidenceMap.entrySet()) {
            boolean pos = entry.getValue()[0];
            boolean neg = entry.getValue()[1];
            Mark m;
            if (pos && neg) {
                m = Mark.B;
                bCount++;
            } else if (pos) {
                m = Mark.T;
            } else if (neg) {
                m = Mark.F;
            } else {
                m = Mark.N; // shouldn't happen (threshold gate above), but guard
            }
            byCanonicalAtom.put(entry.getKey(), m);
        }

        return new MarkingResult(byCanonicalAtom, bCount);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Atom parsing (ContradictionDetector's parser)
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * An atom key as {@link ContradictionDetector.ParsedAtom#parse} reads it, plus the
     * {@link #canonicalKey} this package marks atoms by.
     */
    public static final class ParsedAtom {
        private final ContradictionDetector.ParsedAtom atom;

        private ParsedAtom(ContradictionDetector.ParsedAtom atom) {
            this.atom = atom;
        }

        /** Canonical predicate name ({@code WORKS_FOR}), negation prefix stripped. */
        public String predicate() { return atom.predicate(); }

        /** Comparison form of the predicate ({@code WORKSFOR}), shared by all of its spellings. */
        public String predicateKey() { return atom.predicateKey(); }

        /** Argument list (trimmed strings, may be empty for 0-ary atoms). */
        public List<String> args() { return atom.args(); }

        /** True if this atom was negated in any supported form. */
        public boolean negated() { return atom.negated(); }

        /**
         * The canonical key used as the map key in {@link MarkingResult#byCanonicalAtom()}:
         * {@link #predicateKey()} + {@code "|"} + {@code args.toString()}, as
         * {@code HASCEO|[acme, alice]}. A positive atom, its negated form, and every spelling of
         * the predicate share it, so they merge into a single mark.
         */
        public String canonicalKey() {
            return atom.predicateKey() + "|" + atom.args();
        }

        /**
         * Parse an atom key string with {@link ContradictionDetector.ParsedAtom#parse}.
         *
         * @param atomKey the raw atom key string; may be null (returns empty atom)
         * @return the parsed atom, never null
         */
        public static ParsedAtom parse(String atomKey) {
            return new ParsedAtom(ContradictionDetector.ParsedAtom.parse(atomKey));
        }

        @Override
        public String toString() {
            return "ParsedAtom{predicate='" + atom.predicate() + "', args=" + atom.args()
                    + ", negated=" + atom.negated() + '}';
        }
    }
}
