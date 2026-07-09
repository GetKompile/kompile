/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.tms.atms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Atms}, {@link Environment}, {@link Label}, and {@link NogoodStore}.
 *
 * <p>Covers the textbook ATMS scenarios from de Kleer (1986) §4–6 and
 * Reiter &amp; de Kleer (AAAI 1987).</p>
 */
@DisplayName("ATMS core engine")
class AtmsTest {

    // ─── Environment ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Environment")
    class EnvironmentTests {

        @Test
        @DisplayName("singleton contains only that assumption")
        void singleton() {
            Environment e = Environment.singleton("H1");
            assertTrue(e.contains("H1"));
            assertFalse(e.contains("H2"));
            assertEquals(1, e.size());
        }

        @Test
        @DisplayName("union of two environments is their set union")
        void union() {
            Environment e1 = new Environment(List.of("H1", "H2"));
            Environment e2 = new Environment(List.of("H2", "H3"));
            Environment u = e1.union(e2);
            assertEquals(Set.of("H1", "H2", "H3"), u.assumptions());
        }

        @Test
        @DisplayName("subsumes: subset relation")
        void subsumes() {
            Environment small = new Environment(List.of("H1"));
            Environment large = new Environment(List.of("H1", "H2"));
            assertTrue(small.subsumes(large));   // H1 ⊆ {H1,H2}
            assertFalse(large.subsumes(small));  // {H1,H2} ⊄ {H1}
            assertTrue(small.subsumes(small));   // reflexive
        }

        @Test
        @DisplayName("EMPTY subsumes everything")
        void emptySubsumesAll() {
            assertTrue(Environment.EMPTY.subsumes(new Environment(List.of("H1", "H2"))));
        }

        @Test
        @DisplayName("isSupported: environment ⊆ believed set")
        void isSupported() {
            Environment e = new Environment(List.of("H1", "H2"));
            assertTrue(e.isSupported(Set.of("H1", "H2", "H3")));
            assertFalse(e.isSupported(Set.of("H1")));
        }

        @Test
        @DisplayName("structural equality: same assumptions → equal regardless of construction order")
        void equality() {
            Environment a = new Environment(List.of("H2", "H1"));
            Environment b = new Environment(List.of("H1", "H2"));
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }
    }

    // ─── Label ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Label antichain")
    class LabelTests {

        @Test
        @DisplayName("add: subsumed environment is rejected")
        void addSubsumedIsRejected() {
            // {H1} is in label; adding {H1,H2} (a superset) should be rejected
            Label l = Label.singleton(Environment.singleton("H1"));
            Label l2 = l.add(new Environment(List.of("H1", "H2")));
            assertEquals(1, l2.size());
            assertEquals(l, l2); // no change
        }

        @Test
        @DisplayName("add: smaller environment supersedes existing larger one")
        void addRemovesSupersets() {
            // {H1,H2} is in label; adding {H1} (subset) should remove {H1,H2}
            Label l = Label.singleton(new Environment(List.of("H1", "H2")));
            Label l2 = l.add(Environment.singleton("H1"));
            assertEquals(1, l2.size());
            assertTrue(l2.environments().contains(Environment.singleton("H1")));
            assertFalse(l2.environments().contains(new Environment(List.of("H1", "H2"))));
        }

        @Test
        @DisplayName("add: disjoint environment added normally")
        void addDisjoint() {
            Label l = Label.singleton(Environment.singleton("H1"));
            Label l2 = l.add(Environment.singleton("H2"));
            assertEquals(2, l2.size());
        }

        @Test
        @DisplayName("merge: union of two labels, minimal antichain")
        void merge() {
            Label l1 = Label.singleton(Environment.singleton("H1"));
            Label l2 = Label.singleton(Environment.singleton("H2"));
            Label merged = l1.merge(l2);
            assertEquals(2, merged.size());
        }

        @Test
        @DisplayName("merge: subsumed environments eliminated")
        void mergeEliminatesSubsumed() {
            Label l1 = Label.singleton(new Environment(List.of("H1", "H2")));
            Label l2 = Label.singleton(Environment.singleton("H1"));
            // After merge: {H1} subsumes {H1,H2} → only {H1} remains
            Label merged = l1.merge(l2);
            assertEquals(1, merged.size());
            assertTrue(merged.environments().contains(Environment.singleton("H1")));
        }

        @Test
        @DisplayName("holdsIn: at least one environment ⊆ context")
        void holdsIn() {
            Label l = Label.singleton(new Environment(List.of("H1", "H2")));
            assertTrue(l.holdsIn(Set.of("H1", "H2")));
            assertTrue(l.holdsIn(Set.of("H1", "H2", "H3")));
            assertFalse(l.holdsIn(Set.of("H1")));
        }

        @Test
        @DisplayName("survivesRetraction: at least one env excludes the assumption")
        void survivesRetraction() {
            // Label has two environments: {H1} and {H2}
            Label l = Label.EMPTY.add(Environment.singleton("H1")).add(Environment.singleton("H2"));
            assertTrue(l.survivesRetraction("H1")); // {H2} survives
            assertTrue(l.survivesRetraction("H2")); // {H1} survives
        }

        @Test
        @DisplayName("survivesRetraction: false when all envs contain the assumption")
        void doesNotSurviveRetraction() {
            Label l = Label.singleton(new Environment(List.of("H1", "H2")));
            // Only env is {H1,H2} — retracting H1 kills it
            assertFalse(l.survivesRetraction("H1"));
        }

        @Test
        @DisplayName("filterNogoods: removes inconsistent environments")
        void filterNogoods() {
            NogoodStore ns = new NogoodStore();
            ns.addNogood(new Environment(List.of("H2")));

            Label l = Label.EMPTY
                    .add(Environment.singleton("H1"))
                    .add(new Environment(List.of("H2", "H3")));
            Label filtered = l.filterNogoods(ns);
            // {H2,H3} is a superset of the nogood {H2} → removed
            assertEquals(1, filtered.size());
            assertTrue(filtered.environments().contains(Environment.singleton("H1")));
        }
    }

    // ─── NogoodStore ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NogoodStore antichain")
    class NogoodStoreTests {

        @Test
        @DisplayName("isNogood: superset of stored nogood is inconsistent")
        void isNogoodSuperset() {
            NogoodStore ns = new NogoodStore();
            ns.addNogood(new Environment(List.of("H1", "H2")));
            // {H1,H2,H3} is a superset → inconsistent
            assertTrue(ns.isNogood(new Environment(List.of("H1", "H2", "H3"))));
            // {H1,H2} exact match → also inconsistent (superset of itself)
            assertTrue(ns.isNogood(new Environment(List.of("H1", "H2"))));
            // {H1} is a strict subset → NOT inconsistent
            assertFalse(ns.isNogood(Environment.singleton("H1")));
        }

        @Test
        @DisplayName("addNogood: maintains antichain — redundant superset rejected")
        void addNogoodRedundantRejected() {
            NogoodStore ns = new NogoodStore();
            ns.addNogood(Environment.singleton("H1")); // {H1} is minimal
            ns.addNogood(new Environment(List.of("H1", "H2"))); // {H1,H2} ⊇ {H1} → ignored
            assertEquals(1, ns.size());
        }

        @Test
        @DisplayName("addNogood: smaller nogood removes existing supersets")
        void addNogoodSmallRemovesLarge() {
            NogoodStore ns = new NogoodStore();
            ns.addNogood(new Environment(List.of("H1", "H2")));
            ns.addNogood(Environment.singleton("H1")); // {H1} ⊂ {H1,H2} → {H1,H2} removed
            assertEquals(1, ns.size());
            assertTrue(ns.isNogood(Environment.singleton("H1")));
            assertTrue(ns.isNogood(new Environment(List.of("H1", "H2")))); // still covered
        }
    }

    // ─── Atms textbook scenarios ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Textbook ATMS scenarios")
    class TextbookScenarios {

        /**
         * De Kleer (1986) §4 example:
         * Assumptions: H1, H2, H3.
         * Justifications: H1 → B; H2 ∧ H3 → B.
         * Expected label(B) = {{H1}, {H2,H3}}.
         */
        @Test
        @DisplayName("textbook: H1→B and H2∧H3→B ⇒ label(B)={{H1},{H2,H3}}")
        void textbookBasicLabel() {
            Atms atms = new Atms();
            atms.addAssumption("H1");
            atms.addAssumption("H2");
            atms.addAssumption("H3");
            atms.addJustification("B", List.of("H1"), "H1->B");
            atms.addJustification("B", List.of("H2", "H3"), "H2^H3->B");

            Set<Environment> lb = atms.label("B");
            assertEquals(2, lb.size());
            assertTrue(lb.contains(Environment.singleton("H1")));
            assertTrue(lb.contains(new Environment(List.of("H2", "H3"))));
        }

        /**
         * After recording nogood {H2}: the environment {H2,H3} is inconsistent.
         * Expected label(B) = {{H1}}.
         */
        @Test
        @DisplayName("textbook: nogood {H2} removes {H2,H3} from label(B)")
        void textbookNogoodFiltersLabel() {
            Atms atms = new Atms();
            atms.addAssumption("H1");
            atms.addAssumption("H2");
            atms.addAssumption("H3");
            atms.addJustification("B", List.of("H1"), "H1->B");
            atms.addJustification("B", List.of("H2", "H3"), "H2^H3->B");

            // Record that {H2} alone is inconsistent
            atms.addNogood(List.of("H2"));

            Set<Environment> lb = atms.label("B");
            assertEquals(1, lb.size());
            assertTrue(lb.contains(Environment.singleton("H1")));
        }

        /**
         * After the nogood reduces label(B) to {{H1}}, retractionImpact(H1) must contain B.
         */
        @Test
        @DisplayName("textbook: after nogood, retractionImpact(H1) contains B")
        void textbookRetractionImpactAfterNogood() {
            Atms atms = new Atms();
            atms.addAssumption("H1");
            atms.addAssumption("H2");
            atms.addAssumption("H3");
            atms.addJustification("B", List.of("H1"), "H1->B");
            atms.addJustification("B", List.of("H2", "H3"), "H2^H3->B");
            atms.addNogood(List.of("H2")); // {H2,H3} becomes inconsistent

            Set<String> impact = atms.retractionImpact("H1");
            assertTrue(impact.contains("B"),
                    "B's only surviving environment {H1} is lost when H1 is retracted");
        }
    }

    // ─── Minimality ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Minimality invariant")
    class MinimalityTests {

        @Test
        @DisplayName("larger environment not added when smaller already present")
        void largerNotAdded() {
            Atms atms = new Atms();
            atms.addAssumption("H1");
            atms.addAssumption("H2");
            // Add justification using {H1} alone
            atms.addJustification("B", List.of("H1"), "H1->B");
            // Add justification using {H1,H2}: should not enlarge label(B)
            atms.addJustification("B", List.of("H1", "H2"), "H1^H2->B");

            Set<Environment> lb = atms.label("B");
            assertEquals(1, lb.size(), "Label must remain {{H1}} — {{H1,H2}} is subsumed");
            assertTrue(lb.contains(Environment.singleton("H1")));
        }

        @Test
        @DisplayName("adding smaller environment removes existing larger superset")
        void smallerRemovesLarger() {
            Atms atms = new Atms();
            atms.addAssumption("H1");
            atms.addAssumption("H2");
            // First register via {H1,H2}
            atms.addJustification("B", List.of("H1", "H2"), "H1^H2->B");
            // Now register via {H1} alone — should evict {H1,H2}
            atms.addJustification("B", List.of("H1"), "H1->B");

            Set<Environment> lb = atms.label("B");
            assertEquals(1, lb.size(), "{{H1}} should replace {{H1,H2}}");
            assertTrue(lb.contains(Environment.singleton("H1")));
        }
    }

    // ─── Chained propagation ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Chained propagation")
    class ChainedPropagation {

        /**
         * A → B → C:
         * Justifications: H_ab → B (via intermediate); H_bc → C.
         * But to test pure chaining: assumption A, A→B, B→C.
         * After propagation label(C) should be {{A}}.
         */
        @Test
        @DisplayName("A→B, B→C: label(C) = {{A}} (base assumptions only, not intermediates)")
        void chainPropagates() {
            Atms atms = new Atms();
            atms.addAssumption("A");
            atms.addJustification("B", List.of("A"), "A->B");
            atms.addJustification("C", List.of("B"), "B->C");

            Set<Environment> lc = atms.label("C");
            assertEquals(1, lc.size());
            // The environment should be {A} — not {B}, because B is derived, not an assumption.
            // But: B's label is {{A}}, so the cross-product for C via B is {{A}}.
            assertTrue(lc.contains(Environment.singleton("A")),
                    "label(C) must be {{A}}, not {{B}} (B is derived)");
        }

        @Test
        @DisplayName("A→B, B→C: label(B)={{A}}, not {{B}} (derived nodes don't self-assume)")
        void intermediateUsesBaseAssumption() {
            Atms atms = new Atms();
            atms.addAssumption("A");
            atms.addJustification("B", List.of("A"), "A->B");
            atms.addJustification("C", List.of("B"), "B->C");

            // B is derived, label(B) = {{A}}
            Set<Environment> lb = atms.label("B");
            assertEquals(1, lb.size());
            assertTrue(lb.contains(Environment.singleton("A")));
        }
    }

    // ─── Cycle safety ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Cycle safety")
    class CycleSafety {

        @Test
        @DisplayName("A→B, B→A: labels stabilise without infinite loop")
        void mutualCycleTerminates() {
            Atms atms = new Atms();
            atms.addAssumption("A");
            // A is an assumption; B is derived from A, and A is also "derived" from B.
            // Since A is an assumption its label is fixed to {{A}} and won't grow from B.
            atms.addJustification("B", List.of("A"), "A->B");
            atms.addJustification("A_derived", List.of("B"), "B->A_derived");
            // No infinite loop: labels are stable after one propagation round.

            // Verify B's label is {A}
            Set<Environment> lb = atms.label("B");
            assertFalse(lb.isEmpty(), "B must be derivable from assumption A");
            assertTrue(lb.contains(Environment.singleton("A")));
        }

        @Test
        @DisplayName("truly cyclic derived nodes: A→B, B→A (both derived) — labels remain empty")
        void trulyCircularDerivedNodes() {
            Atms atms = new Atms();
            // No assumptions registered for A or B — they are both purely derived.
            // Neither can be justified without the other, so both labels remain empty.
            atms.addJustification("B", List.of("A"), "A->B");
            atms.addJustification("A", List.of("B"), "B->A");

            // Neither A nor B is an assumption; no base facts → empty labels.
            assertTrue(atms.label("A").isEmpty(),
                    "A has no base support — label must be empty");
            assertTrue(atms.label("B").isEmpty(),
                    "B has no base support — label must be empty");
        }
    }

    // ─── survivesRetraction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("survivesRetraction")
    class SurvivesRetractionTests {

        /**
         * Diamond topology: two disjoint paths from base assumptions to conclusion C.
         * H1→C and H2→C. After adding both justifications:
         *   label(C) = {{H1}, {H2}}
         * Retracting H1 → C survives (H2 still holds).
         * Retracting H2 → C survives (H1 still holds).
         * But retracting both would kill C — not tested here (single retraction is the API).
         */
        @Test
        @DisplayName("diamond: survives single retraction of either path")
        void diamondSurvivesSingleRetraction() {
            Atms atms = new Atms();
            atms.addAssumption("H1");
            atms.addAssumption("H2");
            atms.addJustification("C", List.of("H1"), "H1->C");
            atms.addJustification("C", List.of("H2"), "H2->C");

            assertTrue(atms.survivesRetraction("C", "H1"),
                    "C survives retraction of H1 because {H2} still holds");
            assertTrue(atms.survivesRetraction("C", "H2"),
                    "C survives retraction of H2 because {H1} still holds");
        }

        @Test
        @DisplayName("single-environment conclusion does not survive retraction of its sole assumption")
        void singleEnvDoesNotSurvive() {
            Atms atms = new Atms();
            atms.addAssumption("H1");
            atms.addJustification("C", List.of("H1"), "H1->C");

            assertFalse(atms.survivesRetraction("C", "H1"),
                    "C's only environment is {H1}; it does not survive retracting H1");
        }
    }

    // ─── retractionImpact ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("retractionImpact")
    class RetractionImpactTests {

        @Test
        @DisplayName("retractionImpact: node with single-environment label is in impact set")
        void singleSupportInImpact() {
            Atms atms = new Atms();
            atms.addAssumption("H1");
            atms.addJustification("B", List.of("H1"), "H1->B");

            Set<String> impact = atms.retractionImpact("H1");
            assertTrue(impact.contains("B"));
            // H1 itself is an assumption: it IS in its own label {{H1}}, so retracting H1
            // also kills H1's label (which contains only {H1}).
            assertTrue(impact.contains("H1"),
                    "H1's own assumption label is {{H1}}, which doesn't survive H1's retraction");
        }

        @Test
        @DisplayName("retractionImpact: node with alternative support is NOT in impact set")
        void alternativeSupportNotInImpact() {
            Atms atms = new Atms();
            atms.addAssumption("H1");
            atms.addAssumption("H2");
            atms.addJustification("B", List.of("H1"), "H1->B");
            atms.addJustification("B", List.of("H2"), "H2->B");

            // B has label {{H1},{H2}}; retracting H1 leaves {H2} intact → B NOT in impact
            Set<String> impact = atms.retractionImpact("H1");
            assertFalse(impact.contains("B"),
                    "B has alternative support via H2, so it is not in retractionImpact(H1)");
        }
    }

    // ─── Label cap (truncation) ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Label cap and truncatedLabels()")
    class LabelCapTests {

        @Test
        @DisplayName("more alternative proofs than cap → truncatedLabels() reports the node")
        void labelCapTruncated() {
            // Cap at 2 environments; add 4 distinct assumptions each separately deriving B.
            Atms atms = new Atms(2);
            atms.addAssumption("H1");
            atms.addAssumption("H2");
            atms.addAssumption("H3");
            atms.addAssumption("H4");
            atms.addJustification("B", List.of("H1"), "H1->B");
            atms.addJustification("B", List.of("H2"), "H2->B");
            atms.addJustification("B", List.of("H3"), "H3->B");
            atms.addJustification("B", List.of("H4"), "H4->B");

            // With cap=2, only 2 environments are kept
            assertEquals(2, atms.label("B").size(),
                    "label should be capped at 2 environments");
            assertTrue(atms.truncatedLabels().contains("B"),
                    "B must be reported in truncatedLabels()");
        }

        @Test
        @DisplayName("within cap → truncatedLabels() is empty")
        void withinCapNotTruncated() {
            Atms atms = new Atms(4);
            atms.addAssumption("H1");
            atms.addAssumption("H2");
            atms.addJustification("B", List.of("H1"), "H1->B");
            atms.addJustification("B", List.of("H2"), "H2->B");

            assertFalse(atms.truncatedLabels().contains("B"),
                    "label has only 2 environments, well within cap 4 — must not be truncated");
        }
    }

    // ─── holdsIn ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("holdsIn context query")
    class HoldsInTests {

        @Test
        @DisplayName("holdsIn: true when believed assumptions cover one environment")
        void holdsInPositive() {
            Atms atms = new Atms();
            atms.addAssumption("H1");
            atms.addAssumption("H2");
            atms.addJustification("B", List.of("H1"), "H1->B");
            atms.addJustification("B", List.of("H2"), "H2->B");

            assertTrue(atms.holdsIn("B", Set.of("H1")));
            assertTrue(atms.holdsIn("B", Set.of("H2")));
            assertTrue(atms.holdsIn("B", Set.of("H1", "H2")));
        }

        @Test
        @DisplayName("holdsIn: false when believed assumptions cover no environment")
        void holdsInNegative() {
            Atms atms = new Atms();
            atms.addAssumption("H1");
            atms.addJustification("B", List.of("H1"), "H1->B");

            assertFalse(atms.holdsIn("B", Set.of("H2", "H3")));
        }
    }

    // ─── addNogood return value ───────────────────────────────────────────────────

    @Nested
    @DisplayName("addNogood returns nodes that lost all support")
    class AddNogoodReturnValueTests {

        @Test
        @DisplayName("nogood that makes B lose all support → B in returned set")
        void addNogoodReturnIncludesLostSupport() {
            Atms atms = new Atms();
            atms.addAssumption("H1");
            atms.addJustification("B", List.of("H1"), "H1->B");

            // {H1} as a nogood: label(B)={{H1}} → filtered to empty
            Set<String> lost = atms.addNogood(List.of("H1"));
            assertTrue(lost.contains("B"));
        }

        @Test
        @DisplayName("nogood that leaves B with surviving support → B not in returned set")
        void addNogoodReturnExcludesSurvivors() {
            Atms atms = new Atms();
            atms.addAssumption("H1");
            atms.addAssumption("H2");
            atms.addJustification("B", List.of("H1"), "H1->B");
            atms.addJustification("B", List.of("H2"), "H2->B");

            // Nogood {H1}: removes {H1} env, but {H2} remains
            Set<String> lost = atms.addNogood(List.of("H1"));
            assertFalse(lost.contains("B"),
                    "B still has support via {H2} — must not be in lost-support set");
        }
    }
}
