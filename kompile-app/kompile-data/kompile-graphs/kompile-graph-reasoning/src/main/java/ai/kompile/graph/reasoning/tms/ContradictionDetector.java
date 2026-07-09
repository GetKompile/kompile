/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.tms;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.psl.GroundRule;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Detects constraint violations and fact-level contradictions in PSL inference results.
 *
 * <h3>Constraint violations</h3>
 * After MAP inference, a ground rule is considered violated if its
 * {@link GroundRule#distanceToSatisfaction distanceToSatisfaction} exceeds a threshold.
 * Hard constraints must always be satisfied; soft rule violations are expected (they define
 * the objective) but extremely large violations may indicate data problems.
 *
 * <h3>Fact-level contradictions</h3>
 * Two hard facts with the same atom key but conflicting values (one asserts truth 1.0 and
 * another asserts truth 0.0) directly contradict each other.
 */
public final class ContradictionDetector {

    private static final double DEFAULT_THRESHOLD = 1e-6;
    private static final Set<String> DEFAULT_FUNCTIONAL_PREDICATES = Set.of(
            "STATUS",
            "STATE",
            "CURRENT_STATUS",
            "LEGAL_STATUS",
            "PRIMARY_TYPE",
            "PRIMARY_CATEGORY",
            "HEADQUARTERS",
            "HEADQUARTERED_IN",
            "CEO",
            "HAS_CEO",
            "BIRTH_DATE",
            "DEATH_DATE",
            "FOUNDED_ON",
            "INCORPORATED_ON");
    private static final List<String> NEGATION_PREFIXES = List.of(
            "NOT_", "NO_", "NON_", "NEGATED_", "DENIES_", "DENY_", "REFUTES_", "REFUTE_", "DISPROVES_");

    private ContradictionDetector() {}

    /**
     * Return the default set of functional predicates used by the detector.
     *
     * <p>These are the predicates that are treated as single-valued per subject (e.g.
     * {@code CEO}, {@code STATUS}, {@code BIRTH_DATE}). Callers — such as
     * {@link ai.kompile.graph.reasoning.fol.grounding.DefaultKbVerifier} — may pass this set to
     * {@link #findFactContradictions(FactStore, Set)} or use {@link #isFunctionalPredicate(String)}
     * to test individual predicates without instantiating the detector.</p>
     *
     * @return unmodifiable copy of the default functional predicates (upper-case normalised)
     */
    public static Set<String> defaultFunctionalPredicates() {
        return DEFAULT_FUNCTIONAL_PREDICATES;
    }

    /**
     * Test whether the given predicate name is treated as functionally single-valued by the
     * default rules (explicit membership OR {@code CURRENT_}/{@code PRIMARY_} prefix OR
     * {@code _CURRENT_STATUS}/{@code _PRIMARY_VALUE} suffix).
     *
     * <p>The predicate is normalised (upper-cased, dashes/spaces → underscores) before testing,
     * matching the normalisation applied inside {@link #findFactContradictions}.</p>
     *
     * @param rawPredicate the raw predicate name as it appears in an atom key
     * @return true if the predicate is treated as functional
     */
    public static boolean isFunctionalPredicate(String rawPredicate) {
        if (rawPredicate == null || rawPredicate.isBlank()) return false;
        String normalized = normalizePredicate(rawPredicate);
        return isFunctionalPredicate(normalized, DEFAULT_FUNCTIONAL_PREDICATES);
    }

    /**
     * Scan inference result for hard constraint violations.
     *
     * @param result the MAP inference result
     * @return list of contradictions for violated hard constraints
     */
    public static List<Contradiction> detectHard(HlMrfMapInference.Result result) {
        return detect(result, DEFAULT_THRESHOLD);
    }

    /**
     * Scan inference result for constraint violations above the given threshold.
     *
     * @param result    the MAP inference result
     * @param threshold distance threshold above which a constraint is considered violated
     * @return list of contradictions for violated constraints
     */
    public static List<Contradiction> detect(HlMrfMapInference.Result result, double threshold) {
        Objects.requireNonNull(result, "result must not be null");
        List<Contradiction> contradictions = new ArrayList<>();
        Map<String, Double> values = result.values();

        for (GroundRule gr : result.groundRules()) {
            if (!gr.hard()) continue; // only check hard constraints by default

            double dist = gr.distanceToSatisfaction(values);
            if (dist > threshold) {
                // Identify which atoms in body/head are violating
                List<String> violating = new ArrayList<>();
                // Body atoms that are contributing (high value) but head is not satisfied
                double bodyTruth = gr.bodyTruth(values);
                double headTruth = gr.headTruth(values);

                if (bodyTruth > headTruth) {
                    // Body is more true than head -- body literals with high values are "violating"
                    for (GroundRule.Lit lit : gr.body()) {
                        double litVal = lit.value(values);
                        if (litVal > 0.5) violating.add(lit.atomKey());
                    }
                    // Head literals with low values are also part of the violation
                    for (GroundRule.Lit lit : gr.head()) {
                        double litVal = lit.value(values);
                        if (litVal < 0.5) violating.add(lit.atomKey());
                    }
                }

                contradictions.add(new Contradiction(
                        gr,
                        new LinkedHashMap<>(values),
                        dist,
                        violating
                ));
            }
        }

        return contradictions;
    }

    /**
     * Check if two facts directly contradict each other.
     *
     * <p>Two facts contradict if they are both hard and either have the same atom key
     * with opposite values, or assert opposite polarities of the same normalized atom
     * (for example {@code State(alice)} and {@code Not_State(alice)}).</p>
     *
     * @param f1 the first fact
     * @param f2 the second fact
     * @return true if the facts contradict
     */
    public static boolean contradicts(Fact f1, Fact f2) {
        Objects.requireNonNull(f1, "f1 must not be null");
        Objects.requireNonNull(f2, "f2 must not be null");
        if (!f1.hard() || !f2.hard()) return false;
        if (f1.atomKey().equals(f2.atomKey())) {
            return oppositeTruthValues(f1, f2);
        }

        ParsedAtom a = ParsedAtom.parse(f1.atomKey());
        ParsedAtom b = ParsedAtom.parse(f2.atomKey());
        return a.sameGroundAtom(b)
                && a.negated() != b.negated()
                && highTruth(f1)
                && highTruth(f2);
    }

    /**
     * Find all pairs of contradicting facts in a FactStore.
     *
     * <p>Hard facts with the same atom key but conflicting values (one nearly 1.0, one nearly 0.0)
     * are returned as contradiction pairs.</p>
     *
     * @param factStore the store to check
     * @return list of contradicting fact pairs
     */
    public static List<Pair<Fact, Fact>> findFactContradictions(FactStore factStore) {
        return findFactContradictions(factStore, DEFAULT_FUNCTIONAL_PREDICATES);
    }

    /**
     * Find all pairs of contradicting facts in a FactStore with caller-supplied
     * functional predicates.
     *
     * <p>A functional predicate is treated as single-valued for a subject. Two hard true facts
     * such as {@code CurrentStatus(order1, open)} and {@code CurrentStatus(order1, closed)}
     * therefore contradict each other even though their atom keys differ.</p>
     *
     * @param factStore the store to check
     * @param functionalPredicates predicates that are single-valued by first argument
     * @return list of contradicting fact pairs
     */
    public static List<Pair<Fact, Fact>> findFactContradictions(FactStore factStore,
                                                                Set<String> functionalPredicates) {
        Objects.requireNonNull(factStore, "factStore must not be null");
        Set<String> normalizedFunctionalPredicates = normalizePredicates(functionalPredicates);

        // Parse every atom ONCE up front — O(n) total, never inside a pair loop.
        List<Fact> facts = new ArrayList<>(factStore.allFacts());
        int n = facts.size();
        ParsedAtom[] parsed = new ParsedAtom[n];
        for (int i = 0; i < n; i++) {
            parsed[i] = ParsedAtom.parse(facts.get(i).atomKey());
        }

        // Track already-emitted (i,j) pairs (i<j) to avoid double-adding when a pair
        // satisfies both contradiction kinds simultaneously.
        // We encode the pair as (long)(i << 32 | j) — valid as long as n < 2^31 (always true).
        Set<Long> emitted = new LinkedHashSet<>();
        List<Pair<Fact, Fact>> result = new ArrayList<>();

        // ── KIND A: contradicts() — same atomKey, oppositeTruthValues ────────────────
        // Index: atomKey → list of (index, Fact) for hard facts only.
        // Two facts with identical atomKey but different truth polarity (one ≥0.9, one ≤0.1)
        // form a contradiction.  Note: FactStore is keyed by atomKey so in practice this
        // group is at most size 1 per store, but the caller may pass a list with duplicates,
        // so we handle the general case.
        {
            Map<String, List<Integer>> byAtomKey = new HashMap<>();
            for (int i = 0; i < n; i++) {
                Fact f = facts.get(i);
                if (!f.hard()) continue;
                byAtomKey.computeIfAbsent(f.atomKey(), k -> new ArrayList<>()).add(i);
            }
            for (List<Integer> group : byAtomKey.values()) {
                int gs = group.size();
                if (gs < 2) continue;
                for (int gi = 0; gi < gs; gi++) {
                    for (int gj = gi + 1; gj < gs; gj++) {
                        int i = group.get(gi), j = group.get(gj);
                        Fact a = facts.get(i), b = facts.get(j);
                        if (oppositeTruthValues(a, b)) {
                            long key = ((long) i << 32) | j;
                            if (emitted.add(key)) result.add(new Pair<>(a, b));
                        }
                    }
                }
            }
        }

        // ── KIND B: contradicts() — sameGroundAtom with opposite negated flag,
        //   both hard + highTruth ──────────────────────────────────────────────────────
        // Index: (normalizedPredicate, args) → list of indices for hard+highTruth facts.
        // Within each bucket, a pair contradicts iff one is negated and the other is not.
        {
            Map<String, List<Integer>> byGroundAtom = new HashMap<>();
            for (int i = 0; i < n; i++) {
                Fact f = facts.get(i);
                if (!f.hard() || !highTruth(f)) continue;
                ParsedAtom pa = parsed[i];
                // Key: predicate + serialized args (args are already trimmed strings).
                String bucketKey = pa.predicate() + "|" + pa.args();
                byGroundAtom.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(i);
            }
            for (List<Integer> group : byGroundAtom.values()) {
                int gs = group.size();
                if (gs < 2) continue;
                for (int gi = 0; gi < gs; gi++) {
                    for (int gj = gi + 1; gj < gs; gj++) {
                        int i = group.get(gi), j = group.get(gj);
                        // Must have different atomKeys (same atomKey is handled by kind A)
                        // and opposite negation flags.
                        if (facts.get(i).atomKey().equals(facts.get(j).atomKey())) continue;
                        if (parsed[i].negated() == parsed[j].negated()) continue;
                        long key = ((long) i << 32) | j;
                        if (emitted.add(key)) result.add(new Pair<>(facts.get(i), facts.get(j)));
                    }
                }
            }
        }

        // ── FUNCTIONAL CONTRADICTIONS ────────────────────────────────────────────────
        // Conditions (from functionalContradiction): both hard + highTruth + non-negated,
        // same predicate, predicate is functional, non-empty args, same first arg, different
        // full args.
        // Index: (predicate, args.get(0)) → list of indices.
        // Buckets are tiny (typically 2–3 values for one subject), so inner loops are O(1).
        {
            Map<String, List<Integer>> byFuncSubject = new HashMap<>();
            for (int i = 0; i < n; i++) {
                Fact f = facts.get(i);
                if (!f.hard() || !highTruth(f)) continue;
                ParsedAtom pa = parsed[i];
                if (pa.negated()) continue;
                if (pa.args().isEmpty()) continue;
                if (!isFunctionalPredicate(pa.predicate(), normalizedFunctionalPredicates)) continue;
                String bucketKey = pa.predicate() + "|" + pa.args().get(0);
                byFuncSubject.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(i);
            }
            for (List<Integer> group : byFuncSubject.values()) {
                int gs = group.size();
                if (gs < 2) continue;
                for (int gi = 0; gi < gs; gi++) {
                    for (int gj = gi + 1; gj < gs; gj++) {
                        int i = group.get(gi), j = group.get(gj);
                        if (Objects.equals(parsed[i].args(), parsed[j].args())) continue;
                        long key = ((long) i << 32) | j;
                        if (emitted.add(key)) result.add(new Pair<>(facts.get(i), facts.get(j)));
                    }
                }
            }
        }

        return result;
    }

    private static boolean functionalContradiction(Fact f1, Fact f2, Set<String> functionalPredicates) {
        if (!f1.hard() || !f2.hard() || !highTruth(f1) || !highTruth(f2)) {
            return false;
        }
        ParsedAtom a = ParsedAtom.parse(f1.atomKey());
        ParsedAtom b = ParsedAtom.parse(f2.atomKey());
        if (a.negated() || b.negated()) return false;
        if (!Objects.equals(a.predicate(), b.predicate())) return false;
        if (!isFunctionalPredicate(a.predicate(), functionalPredicates)) return false;
        if (a.args().isEmpty() || b.args().isEmpty()) return false;
        return Objects.equals(a.args().get(0), b.args().get(0)) && !Objects.equals(a.args(), b.args());
    }

    private static boolean isFunctionalPredicate(String predicate, Set<String> functionalPredicates) {
        return functionalPredicates.contains(predicate)
                || predicate.startsWith("CURRENT_")
                || predicate.startsWith("PRIMARY_")
                || predicate.endsWith("_CURRENT_STATUS")
                || predicate.endsWith("_PRIMARY_VALUE");
    }

    private static boolean oppositeTruthValues(Fact f1, Fact f2) {
        return (f1.value() >= 0.9 && f2.value() <= 0.1) ||
               (f1.value() <= 0.1 && f2.value() >= 0.9);
    }

    private static boolean highTruth(Fact fact) {
        return fact.value() >= 0.9;
    }

    private static Set<String> normalizePredicates(Set<String> predicates) {
        if (predicates == null || predicates.isEmpty()) {
            return DEFAULT_FUNCTIONAL_PREDICATES;
        }
        Set<String> normalized = new LinkedHashSet<>(DEFAULT_FUNCTIONAL_PREDICATES);
        predicates.stream()
                .filter(Objects::nonNull)
                .map(ContradictionDetector::normalizePredicate)
                .filter(p -> !p.isBlank())
                .forEach(normalized::add);
        return Collections.unmodifiableSet(normalized);
    }

    private static String normalizePredicate(String raw) {
        if (raw == null) return "";
        return raw.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .replaceAll("[^A-Za-z0-9_]", "")
                .replaceAll("_+", "_")
                .toUpperCase(Locale.ROOT);
    }

    private static String stripNegationPrefix(String predicate) {
        String stripped = predicate;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : NEGATION_PREFIXES) {
                if (stripped.startsWith(prefix)) {
                    stripped = stripped.substring(prefix.length());
                    changed = true;
                }
            }
            if (stripped.startsWith("IS_NOT_")) {
                stripped = stripped.substring("IS_NOT_".length());
                changed = true;
            }
        }
        return stripped;
    }

    private record ParsedAtom(String predicate, List<String> args, boolean negated) {
        static ParsedAtom parse(String atomKey) {
            String text = atomKey == null ? "" : atomKey.trim();
            boolean negated = false;
            if (text.startsWith("!")) {
                negated = true;
                text = text.substring(1).trim();
            }
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.startsWith("not ")) {
                negated = true;
                text = text.substring(4).trim();
            } else if (lower.startsWith("not(") && text.endsWith(")")) {
                negated = true;
                text = text.substring(4, text.length() - 1).trim();
            }

            int lp = text.indexOf('(');
            int rp = text.lastIndexOf(')');
            String rawPredicate = lp < 0 ? text : text.substring(0, lp);
            String predicate = normalizePredicate(rawPredicate);
            if (startsWithNegation(predicate)) {
                negated = true;
                predicate = stripNegationPrefix(predicate);
            }
            List<String> args = List.of();
            if (lp >= 0 && rp > lp) {
                String inside = text.substring(lp + 1, rp).trim();
                if (!inside.isBlank()) {
                    args = Arrays.stream(inside.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isBlank())
                            .toList();
                }
            }
            return new ParsedAtom(predicate, args, negated);
        }

        boolean sameGroundAtom(ParsedAtom other) {
            return Objects.equals(predicate, other.predicate()) && Objects.equals(args, other.args());
        }

        private static boolean startsWithNegation(String predicate) {
            return NEGATION_PREFIXES.stream().anyMatch(predicate::startsWith)
                    || predicate.startsWith("IS_NOT_");
        }
    }

    /**
     * A simple immutable pair type for contradiction results.
     */
    public record Pair<A, B>(A first, B second) {}
}
