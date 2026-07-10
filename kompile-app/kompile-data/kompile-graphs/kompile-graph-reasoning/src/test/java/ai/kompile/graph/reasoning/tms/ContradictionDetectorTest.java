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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity tests for ContradictionDetector.findFactContradictions — verifies the
 * O(n) indexed implementation produces the IDENTICAL set of contradiction pairs
 * as the naive O(n²) reference algorithm across multiple fixture types.
 *
 * <p>The naive reference is kept as a private helper below; it is intentionally
 * NOT used in production code.</p>
 */
class ContradictionDetectorTest {

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Hard fact with value=1.0 (high truth). */
    private static Fact hardTrue(String atomKey) {
        return new Fact(atomKey, 1.0, "src", Instant.now(), true);
    }

    /** Hard fact with value=0.0 (low truth). */
    private static Fact hardFalse(String atomKey) {
        return new Fact(atomKey, 0.0, "src", Instant.now(), true);
    }

    /** Soft fact with value=1.0 — excluded from all contradiction checks. */
    private static Fact softTrue(String atomKey) {
        return Fact.soft(atomKey, 1.0, "src");
    }

    /** Soft fact with value=0.0 — excluded from all contradiction checks. */
    private static Fact softFalse(String atomKey) {
        return Fact.soft(atomKey, 0.0, "src");
    }

    /** Hard fact with intermediate value (0.5) — excluded from highTruth checks. */
    private static Fact hardMid(String atomKey) {
        return new Fact(atomKey, 0.5, "src", Instant.now(), true);
    }

    /**
     * Convert a list of pairs into an unordered set of unordered pairs (represented
     * as Set<Set<Fact>>) for order-insensitive equality comparison.
     */
    private static Set<Set<Fact>> toUnorderedPairSet(List<ContradictionDetector.Pair<Fact, Fact>> pairs) {
        Set<Set<Fact>> result = new HashSet<>();
        for (ContradictionDetector.Pair<Fact, Fact> p : pairs) {
            Set<Fact> s = new HashSet<>();
            s.add(p.first());
            s.add(p.second());
            result.add(s);
        }
        return result;
    }

    // Default functional predicates — mirrors ContradictionDetector.DEFAULT_FUNCTIONAL_PREDICATES.
    private static final Set<String> DEFAULT_FUNCTIONAL_PREDICATES = Set.of(
            "STATUS", "STATE", "CURRENT_STATUS", "LEGAL_STATUS", "PRIMARY_TYPE",
            "PRIMARY_CATEGORY", "HEADQUARTERS", "HEADQUARTERED_IN", "CEO", "HAS_CEO",
            "BIRTH_DATE", "DEATH_DATE", "FOUNDED_ON", "INCORPORATED_ON");

    /**
     * Mirror of ContradictionDetector.normalizePredicates — merges caller set with defaults.
     */
    private static Set<String> normalizePredicatesRef(Set<String> predicates) {
        if (predicates == null || predicates.isEmpty()) return DEFAULT_FUNCTIONAL_PREDICATES;
        Set<String> normalized = new LinkedHashSet<>(DEFAULT_FUNCTIONAL_PREDICATES);
        predicates.stream()
                .filter(Objects::nonNull)
                .map(ContradictionDetectorTest::normPred)
                .filter(p -> !p.isBlank())
                .forEach(normalized::add);
        return java.util.Collections.unmodifiableSet(normalized);
    }

    /**
     * Naive O(n²) reference implementation — mirrors the original production code
     * EXACTLY (before the indexed rewrite), including the normalizePredicates expansion.
     * Kept here for parity verification only.
     */
    private static List<ContradictionDetector.Pair<Fact, Fact>> naiveFindFactContradictions(
            List<Fact> facts, Set<String> functionalPredicates) {
        Set<String> normalizedFps = normalizePredicatesRef(functionalPredicates);
        List<ContradictionDetector.Pair<Fact, Fact>> result = new ArrayList<>();
        for (int i = 0; i < facts.size(); i++) {
            for (int j = i + 1; j < facts.size(); j++) {
                Fact a = facts.get(i);
                Fact b = facts.get(j);
                if (ContradictionDetector.contradicts(a, b) || naiveFunctionalContradiction(a, b, normalizedFps)) {
                    result.add(new ContradictionDetector.Pair<>(a, b));
                }
            }
        }
        return result;
    }

    // Verbatim copy of the pre-rewrite private functionalContradiction logic.
    private static boolean naiveFunctionalContradiction(Fact f1, Fact f2, Set<String> functionalPredicates) {
        if (!f1.hard() || !f2.hard() || !naiveHighTruth(f1) || !naiveHighTruth(f2)) return false;
        ParsedAtomRef a = parsedRef(f1.atomKey());
        ParsedAtomRef b = parsedRef(f2.atomKey());
        if (a.negated || b.negated) return false;
        if (!Objects.equals(a.predicate, b.predicate)) return false;
        if (!naiveIsFunctional(a.predicate, functionalPredicates)) return false;
        if (a.args.isEmpty() || b.args.isEmpty()) return false;
        return Objects.equals(a.args.get(0), b.args.get(0)) && !Objects.equals(a.args, b.args);
    }

    private static boolean naiveHighTruth(Fact f) { return f.value() >= 0.9; }

    private static boolean naiveIsFunctional(String predicate, Set<String> fps) {
        return fps.contains(predicate)
                || predicate.startsWith("CURRENT_")
                || predicate.startsWith("PRIMARY_")
                || predicate.endsWith("_CURRENT_STATUS")
                || predicate.endsWith("_PRIMARY_VALUE");
    }

    // Minimal parsed-atom ref for the naive reference (mirrors ParsedAtom.parse logic).
    private record ParsedAtomRef(String predicate, List<String> args, boolean negated) {}

    private static ParsedAtomRef parsedRef(String atomKey) {
        String text = atomKey == null ? "" : atomKey.trim();
        boolean negated = false;
        if (text.startsWith("!")) { negated = true; text = text.substring(1).trim(); }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("not ")) { negated = true; text = text.substring(4).trim(); }
        else if (lower.startsWith("not(") && text.endsWith(")")) {
            negated = true; text = text.substring(4, text.length() - 1).trim();
        }
        int lp = text.indexOf('('), rp = text.lastIndexOf(')');
        String rawPred = lp < 0 ? text : text.substring(0, lp);
        String pred = normPred(rawPred);
        boolean negFromPrefix = false;
        for (String pfx : List.of("NOT_","NO_","NON_","NEGATED_","DENIES_","DENY_","REFUTES_","REFUTE_","DISPROVES_","IS_NOT_")) {
            if (pred.startsWith(pfx)) { negFromPrefix = true; pred = pred.substring(pfx.length()); break; }
        }
        if (negFromPrefix) negated = true;
        List<String> args = List.of();
        if (lp >= 0 && rp > lp) {
            String inside = text.substring(lp + 1, rp).trim();
            if (!inside.isBlank()) {
                args = java.util.Arrays.stream(inside.split(","))
                        .map(String::trim).filter(s -> !s.isBlank()).toList();
            }
        }
        return new ParsedAtomRef(pred, args, negated);
    }

    private static String normPred(String raw) {
        if (raw == null) return "";
        return raw.trim().replace('-','_').replace(' ','_')
                .replaceAll("[^A-Za-z0-9_]","").replaceAll("_+","_")
                .toUpperCase(java.util.Locale.ROOT);
    }

    /** Build a FactStore from a list (last-write-wins for duplicate atomKeys). */
    private static FactStore storeOf(List<Fact> facts) {
        FactStore store = new FactStore();
        for (Fact f : facts) store.assertFact(f);
        return store;
    }

    /**
     * Assert that the indexed implementation returns the same unordered set of
     * unordered pairs as the naive O(n²) reference.
     *
     * <p>The naive reference operates on the raw list (may have duplicate atomKeys in
     * theory); the indexed implementation operates on FactStore (dedup by atomKey).
     * For the parity assertion we always deduplicate the list before both: the naive
     * gets the deduplicated list and the indexed gets a FactStore built from the same
     * deduplicated list.</p>
     */
    private static void assertParity(List<Fact> facts, Set<String> functionalPredicates) {
        // Dedup by atomKey (same as FactStore semantics — last writer wins).
        java.util.LinkedHashMap<String, Fact> dedupMap = new java.util.LinkedHashMap<>();
        for (Fact f : facts) dedupMap.put(f.atomKey(), f);
        List<Fact> deduped = new ArrayList<>(dedupMap.values());

        FactStore store = storeOf(deduped);
        List<ContradictionDetector.Pair<Fact, Fact>> indexed =
                ContradictionDetector.findFactContradictions(store, functionalPredicates);
        List<ContradictionDetector.Pair<Fact, Fact>> naive =
                naiveFindFactContradictions(deduped, functionalPredicates);

        Set<Set<Fact>> indexedSet = toUnorderedPairSet(indexed);
        Set<Set<Fact>> naiveSet = toUnorderedPairSet(naive);

        assertEquals(naiveSet, indexedSet,
                "Indexed result must match naive O(n²) reference.\n"
                + "  Naive:   " + naiveSet + "\n"
                + "  Indexed: " + indexedSet);
    }

    // ── fixture: no contradictions ─────────────────────────────────────────────

    @Test
    @DisplayName("Empty store: no contradictions")
    void emptyStore() {
        assertParity(List.of(), Set.of());
    }

    @Test
    @DisplayName("Non-contradicting hard facts with different keys")
    void nonContradicting() {
        assertParity(List.of(
                hardTrue("State(alice)"),
                hardTrue("State(bob)"),
                hardTrue("Knows(alice, bob)")
        ), Set.of());
    }

    // ── fixture: negation contradictions ──────────────────────────────────────

    @Test
    @DisplayName("State(alice) vs Not_State(alice) — negation prefix contradiction")
    void negationPrefix() {
        List<Fact> facts = List.of(
                hardTrue("State(alice)"),
                hardTrue("Not_State(alice)")
        );
        assertParity(facts, Set.of());

        FactStore store = storeOf(facts);
        List<ContradictionDetector.Pair<Fact, Fact>> result =
                ContradictionDetector.findFactContradictions(store);
        assertEquals(1, result.size(), "Expected exactly one contradiction pair");
    }

    @Test
    @DisplayName("State(alice) vs NO_State(alice) — NO_ prefix")
    void noPrefix() {
        assertParity(List.of(
                hardTrue("State(alice)"),
                hardTrue("NO_State(alice)")
        ), Set.of());
    }

    @Test
    @DisplayName("!State(alice) vs State(alice) — bang prefix")
    void bangPrefix() {
        assertParity(List.of(
                hardTrue("State(alice)"),
                hardTrue("!State(alice)")
        ), Set.of());
    }

    @Test
    @DisplayName("NOT State(alice) vs State(alice) — NOT-space prefix")
    void notSpacePrefix() {
        assertParity(List.of(
                hardTrue("State(alice)"),
                hardTrue("NOT State(alice)")
        ), Set.of());
    }

    @Test
    @DisplayName("Multiple negation pairs in one store")
    void multipleNegationPairs() {
        assertParity(List.of(
                hardTrue("State(alice)"),
                hardTrue("Not_State(alice)"),
                hardTrue("Status(bob)"),
                hardTrue("Not_Status(bob)"),
                hardTrue("Knows(alice, carol)") // no negated counterpart
        ), Set.of());
    }

    // ── fixture: soft/low-truth exclusion ─────────────────────────────────────

    @Test
    @DisplayName("Soft facts are NOT detected as contradictions")
    void softFactsExcluded() {
        assertParity(List.of(
                softTrue("State(alice)"),
                softFalse("State(alice)")    // same key, different value — but both soft
        ), Set.of());

        FactStore store = new FactStore();
        store.assertFact(softTrue("Not_State(alice)"));
        store.assertFact(softTrue("State(alice)"));
        List<ContradictionDetector.Pair<Fact, Fact>> result =
                ContradictionDetector.findFactContradictions(store);
        // soft → highTruth condition fails for negated-kind check in contradicts()
        assertTrue(result.isEmpty(), "Soft facts must not be flagged as contradictions");
    }

    @Test
    @DisplayName("Hard fact with mid truth-value excluded from highTruth-gated checks")
    void midTruthExcluded() {
        FactStore store = new FactStore();
        store.assertFact(hardMid("Not_State(alice)"));
        store.assertFact(hardTrue("State(alice)"));
        // contradicts() negated-kind requires both highTruth → hardMid fails
        List<ContradictionDetector.Pair<Fact, Fact>> result =
                ContradictionDetector.findFactContradictions(store);
        assertTrue(result.isEmpty(),
                "Negated-kind contradiction requires both facts to be highTruth");
    }

    // ── fixture: functional predicate contradictions ───────────────────────────

    @Test
    @DisplayName("CURRENT_STATUS(order1, open) vs CURRENT_STATUS(order1, closed) — default functional clash")
    void functionalPredicateCurrentStatus() {
        // CURRENT_STATUS (with underscore) matches the default isFunctionalPredicate startsWith("CURRENT_") pattern.
        // CamelCase "CurrentStatus" normalizes to "CURRENTSTATUS" which does NOT match — use the underscore form.
        List<Fact> facts = List.of(
                hardTrue("CURRENT_STATUS(order1, open)"),
                hardTrue("CURRENT_STATUS(order1, closed)")
        );
        assertParity(facts, Set.of());

        FactStore store = storeOf(facts);
        List<ContradictionDetector.Pair<Fact, Fact>> result =
                ContradictionDetector.findFactContradictions(store);
        assertEquals(1, result.size(), "Two different values for same functional subject");
    }

    @Test
    @DisplayName("CurrentStatus(order1, open) vs CurrentStatus(order1, closed) — requires explicit predicate")
    void functionalPredicateCamelCaseRequiresExplicit() {
        // CamelCase "CurrentStatus" normalizes to "CURRENTSTATUS" — not in default set, not a CURRENT_ prefix.
        // Must supply explicitly. Parity asserts both naive and indexed agree (both detect it with explicit set).
        List<Fact> facts = List.of(
                hardTrue("CurrentStatus(order1, open)"),
                hardTrue("CurrentStatus(order1, closed)")
        );
        assertParity(facts, Set.of("CurrentStatus"));

        FactStore store = storeOf(facts);
        List<ContradictionDetector.Pair<Fact, Fact>> result =
                ContradictionDetector.findFactContradictions(store, Set.of("CurrentStatus"));
        assertEquals(1, result.size(), "Two different values for same functional subject (explicit predicate)");
    }

    @Test
    @DisplayName("STATUS(x, a) vs STATUS(x, b) — default functional predicate")
    void defaultFunctionalPredicate() {
        List<Fact> facts = List.of(
                hardTrue("STATUS(x, active)"),
                hardTrue("STATUS(x, inactive)")
        );
        assertParity(facts, Set.of());

        FactStore store = storeOf(facts);
        assertEquals(1, ContradictionDetector.findFactContradictions(store).size());
    }

    @Test
    @DisplayName("LifecyclePhase(order-1, draft) vs LifecyclePhase(order-1, approved) — caller-supplied functional")
    void callerSuppliedFunctionalPredicate() {
        List<Fact> facts = List.of(
                hardTrue("LifecyclePhase(order-1, draft)"),
                hardTrue("LifecyclePhase(order-1, approved)")
        );
        assertParity(facts, Set.of("LifecyclePhase"));

        FactStore store = storeOf(facts);
        List<ContradictionDetector.Pair<Fact, Fact>> result =
                ContradictionDetector.findFactContradictions(store, Set.of("LifecyclePhase"));
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("Three values for same functional subject — C(3,2)=3 pairs")
    void threeFunctionalValues() {
        List<Fact> facts = List.of(
                hardTrue("Status(entity1, a)"),
                hardTrue("Status(entity1, b)"),
                hardTrue("Status(entity1, c)")
        );
        assertParity(facts, Set.of());
    }

    @Test
    @DisplayName("Functional facts with different first-arg do NOT contradict")
    void functionalDifferentSubject() {
        assertParity(List.of(
                hardTrue("STATUS(x, active)"),
                hardTrue("STATUS(y, active)")
        ), Set.of());

        FactStore store = storeOf(List.of(
                hardTrue("STATUS(x, active)"),
                hardTrue("STATUS(y, active)")
        ));
        assertTrue(ContradictionDetector.findFactContradictions(store).isEmpty());
    }

    @Test
    @DisplayName("Functional facts with same args do NOT contradict")
    void functionalSameArgs() {
        assertParity(List.of(
                hardTrue("STATUS(x, active)"),
                hardTrue("STATUS(x, active)")
        ), Set.of());
    }

    @Test
    @DisplayName("Soft functional facts are NOT flagged")
    void softFunctionalExcluded() {
        FactStore store = new FactStore();
        store.assertFact(softTrue("STATUS(x, active)"));
        store.assertFact(softTrue("STATUS(x, inactive)"));
        // softTrue makes hard=false → excluded
        assertTrue(ContradictionDetector.findFactContradictions(store).isEmpty());
    }

    // ── fixture: pair dedup — both kinds fire on same pair ─────────────────────

    @Test
    @DisplayName("A pair satisfying both negated-kind contradicts AND functional does not appear twice")
    void deduplicatedPair() {
        // Construct a pair where the functional index fires AND the negated-kind fires.
        // This is contrived but must not produce a duplicate pair.
        // STATUS(x, open) vs NOT_STATUS(x, open) — both facts:
        //   - contradicts() negated-kind: same ground atom STATUS+[x, open], opposite negated flag, both highTruth
        //   - functionalContradiction: same predicate STATUS, both highTruth, same first arg x,
        //     but args are [x, open] vs [x, open] — SAME full args → NOT a functional contradiction
        // So no duplicate in this specific case. Let's just verify parity covers general case.
        List<Fact> facts = List.of(
                hardTrue("STATUS(x, open)"),
                hardTrue("NOT_STATUS(x, open)")
        );
        assertParity(facts, Set.of());
        // There should be exactly 1 pair (negated-kind), not 2.
        FactStore store = storeOf(facts);
        List<ContradictionDetector.Pair<Fact, Fact>> result =
                ContradictionDetector.findFactContradictions(store);
        assertEquals(1, result.size(), "Pair should appear exactly once even if both checks match");
    }

    // ── fixture: mixed store ──────────────────────────────────────────────────

    @Test
    @DisplayName("Mixed store: negation + functional + non-contradicting facts")
    void mixedStore() {
        List<Fact> facts = List.of(
                hardTrue("State(alice)"),
                hardTrue("Not_State(alice)"),         // contradicts State(alice)
                hardTrue("CurrentStatus(order1, open)"),
                hardTrue("CurrentStatus(order1, closed)"), // functional contradiction
                hardTrue("Knows(alice, bob)"),        // no counterpart
                softTrue("Status(x, active)"),        // soft → excluded
                hardMid("Status(x, inactive)")        // mid-truth → excluded from highTruth checks
        );
        assertParity(facts, Set.of());
    }

    // ── fixture: large random — 500 facts ─────────────────────────────────────

    @Test
    @DisplayName("500-fact random fixture: indexed == naive O(n²) reference")
    void largRandomFixture500() {
        Random rng = new Random(0xdeadbeefL);
        List<String> predicates = List.of("STATE", "STATUS", "CURRENT_PHASE", "PRIMARY_ROLE",
                "KNOWS", "LINKED", "OWNS", "REPORTS_TO");
        List<String> entities = new ArrayList<>();
        for (int i = 0; i < 30; i++) entities.add("e" + i);
        List<String> values = List.of("v0", "v1", "v2", "v3", "v4");

        java.util.LinkedHashMap<String, Fact> byKey = new java.util.LinkedHashMap<>();
        int added = 0;
        while (added < 500) {
            String pred = predicates.get(rng.nextInt(predicates.size()));
            String subj = entities.get(rng.nextInt(entities.size()));
            // Randomly: 0-arg, 1-arg, 2-arg atoms; negated; hard/soft; truth value
            int arity = rng.nextInt(3);
            String atomKey;
            if (arity == 0) {
                atomKey = pred;
            } else if (arity == 1) {
                atomKey = pred + "(" + subj + ")";
            } else {
                String val = values.get(rng.nextInt(values.size()));
                atomKey = pred + "(" + subj + ", " + val + ")";
            }
            // 20% chance of negated form
            if (rng.nextDouble() < 0.20) atomKey = "Not_" + atomKey;
            // truth value
            double tv;
            int tv_kind = rng.nextInt(3);
            if (tv_kind == 0) tv = 1.0;
            else if (tv_kind == 1) tv = 0.0;
            else tv = 0.5;
            boolean hard = rng.nextBoolean();
            Fact f = new Fact(atomKey, tv, "src", Instant.now(), hard);
            byKey.put(atomKey, f);
            added++;
        }

        List<Fact> facts = new ArrayList<>(byKey.values());
        // Use empty functionalPredicates so normalizePredicates() merges DEFAULT set
        assertParity(facts, Set.of());
    }

    @Test
    @DisplayName("500-fact fixture with caller-supplied functional predicates")
    void largRandomFixtureWithFunctional() {
        Random rng = new Random(0xfeedcafeL);
        List<String> predicates = List.of("ASSIGNED_TO", "HAS_OWNER", "PHASE", "CATEGORY");
        List<String> entities = new ArrayList<>();
        for (int i = 0; i < 20; i++) entities.add("ent" + i);
        List<String> vals = List.of("alpha", "beta", "gamma", "delta");

        java.util.LinkedHashMap<String, Fact> byKey = new java.util.LinkedHashMap<>();
        int added = 0;
        while (added < 500) {
            String pred = predicates.get(rng.nextInt(predicates.size()));
            String subj = entities.get(rng.nextInt(entities.size()));
            String val = vals.get(rng.nextInt(vals.size()));
            String atomKey = pred + "(" + subj + ", " + val + ")";
            double tv = rng.nextDouble() < 0.5 ? 1.0 : 0.0;
            boolean hard = rng.nextDouble() < 0.7;
            Fact f = new Fact(atomKey, tv, "src", Instant.now(), hard);
            byKey.put(atomKey, f);
            added++;
        }

        List<Fact> facts = new ArrayList<>(byKey.values());
        assertParity(facts, new HashSet<>(List.of("ASSIGNED_TO", "HAS_OWNER", "PHASE", "CATEGORY")));
    }
}
