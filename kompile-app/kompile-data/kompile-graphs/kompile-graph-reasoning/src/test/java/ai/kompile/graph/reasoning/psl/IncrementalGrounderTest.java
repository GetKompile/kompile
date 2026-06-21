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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IncrementalGrounder} (GAP 4).
 *
 * <p>The key invariant being verified: after applying atom/rule deltas incrementally,
 * the maintained ground-rule set equals what a full {@link PslProgram#ground()} on the
 * same final program state would produce.</p>
 */
class IncrementalGrounderTest {

    // ─── Helper ──────────────────────────────────────────────────────────────────

    private static PslProgram smallProgram() {
        PslProgram p = new PslProgram();
        p.addRule("2.0: State(X) & Link(X, Y) -> State(Y) ^2");
        p.observe("State", 1.0, "alice");
        p.observe("Link", 0.8, "alice", "bob");
        p.target("State", "bob");
        return p;
    }

    // ─── Initial grounding ───────────────────────────────────────────────────────

    @Nested
    class InitialGrounding {

        @Test
        void initialGroundingMatchesFullGround() {
            PslProgram p = smallProgram();
            List<GroundRule> fullGround = p.ground();
            IncrementalGrounder ig = new IncrementalGrounder(p);
            // Display sets should match (order may differ).
            Set<String> full = fullGround.stream().map(GroundRule::display).collect(Collectors.toSet());
            Set<String> incr = ig.groundRules().stream().map(GroundRule::display).collect(Collectors.toSet());
            assertEquals(full, incr, "Initial incremental grounding should equal full grounding");
        }

        @Test
        void groundRuleCountIsPositive() {
            PslProgram p = smallProgram();
            IncrementalGrounder ig = new IncrementalGrounder(p);
            assertTrue(ig.groundRuleCount() > 0, "Should have at least one ground rule");
        }

        @Test
        void emptyProgramProducesNoGroundRules() {
            PslProgram p = new PslProgram();
            p.addRule("1.0: State(X) -> State(X) ^2");
            IncrementalGrounder ig = new IncrementalGrounder(p);
            assertEquals(0, ig.groundRuleCount());
        }
    }

    // ─── Add atom delta ──────────────────────────────────────────────────────────

    @Nested
    class AddAtom {

        @Test
        void addingNewAtomIncreasesGroundRuleCount() {
            PslProgram p = smallProgram();
            IncrementalGrounder ig = new IncrementalGrounder(p);
            int before = ig.groundRuleCount();

            // Add a new entity "charlie" and a link alice→charlie
            ig.addAtom(PslAtom.ground("Link", "alice", "charlie"), 0.6);
            ig.addTargetAtom(PslAtom.ground("State", "charlie"));

            assertTrue(ig.groundRuleCount() > before,
                    "Adding atoms that enable new rule instantiations should increase ground rule count");
        }

        @Test
        void afterAddAtomIncrementalEqualsFullReground() {
            // Build two programs: one that has the atoms upfront (full ground),
            // and one that starts smaller and adds atoms via the grounder.
            PslProgram full = new PslProgram();
            full.addRule("2.0: State(X) & Link(X, Y) -> State(Y) ^2");
            full.observe("State", 1.0, "alice");
            full.observe("Link", 0.8, "alice", "bob");
            full.observe("Link", 0.6, "alice", "charlie");
            full.target("State", "bob");
            full.target("State", "charlie");
            List<GroundRule> fullGround = full.ground();

            PslProgram partial = new PslProgram();
            partial.addRule("2.0: State(X) & Link(X, Y) -> State(Y) ^2");
            partial.observe("State", 1.0, "alice");
            partial.observe("Link", 0.8, "alice", "bob");
            partial.target("State", "bob");
            IncrementalGrounder ig = new IncrementalGrounder(partial);
            ig.addAtom(PslAtom.ground("Link", "alice", "charlie"), 0.6);
            ig.addTargetAtom(PslAtom.ground("State", "charlie"));

            Set<String> fullSet = fullGround.stream().map(GroundRule::display).collect(Collectors.toSet());
            Set<String> incrSet = ig.groundRules().stream().map(GroundRule::display).collect(Collectors.toSet());
            assertEquals(fullSet, incrSet,
                    "Incremental grounding after addAtom should equal full re-ground on same atoms");
        }

        @Test
        void addingNonGroundAtomThrows() {
            PslProgram p = smallProgram();
            IncrementalGrounder ig = new IncrementalGrounder(p);
            PslAtom nonGround = PslAtom.of("State", false, Term.var("X"));
            assertThrows(IllegalArgumentException.class, () -> ig.addAtom(nonGround, 1.0));
        }
    }

    // ─── Remove atom ─────────────────────────────────────────────────────────────

    @Nested
    class RemoveAtom {

        @Test
        void removingAtomPrunesReferencingGroundRules() {
            PslProgram p = smallProgram();
            IncrementalGrounder ig = new IncrementalGrounder(p);
            int before = ig.groundRuleCount();
            ig.removeAtom("Link(alice, bob)");
            assertTrue(ig.groundRuleCount() < before,
                    "Removing the Link atom should prune ground rules that reference it");
        }

        @Test
        void removingNonExistentAtomDoesNotThrow() {
            PslProgram p = smallProgram();
            IncrementalGrounder ig = new IncrementalGrounder(p);
            assertDoesNotThrow(() -> ig.removeAtom("State(nonexistent)"));
        }
    }

    // ─── Add rule ────────────────────────────────────────────────────────────────

    @Nested
    class AddRule {

        @Test
        void addingNewRuleGroundsItAgainstExistingAtoms() {
            PslProgram p = smallProgram();
            IncrementalGrounder ig = new IncrementalGrounder(p);
            int before = ig.groundRuleCount();

            // Add a new abduction rule
            ig.addRule(PslRule.parse("1.0: State(Y) & Link(X, Y) -> State(X) ^2"));
            assertTrue(ig.groundRuleCount() > before,
                    "Adding a rule that can ground against existing atoms should increase count");
        }

        @Test
        void programExposesAddedRule() {
            PslProgram p = smallProgram();
            IncrementalGrounder ig = new IncrementalGrounder(p);
            int rulesBefore = ig.program().rules().size();
            ig.addRule(PslRule.parse("1.0: State(Y) -> Prior(Y) ^2"));
            assertEquals(rulesBefore + 1, ig.program().rules().size());
        }
    }

    // ─── Invariant: incremental == full re-ground ─────────────────────────────────

    @Nested
    class InvariantEquivalence {

        @Test
        void multipleAddsThenIncrementalEqualsFullGround() {
            // Construct the final atom set directly in a "reference" program.
            PslProgram reference = new PslProgram();
            reference.addRule("2.0: State(X) & Link(X, Y) -> State(Y) ^2");
            reference.addRule("1.0: State(Y) & Link(X, Y) -> State(X) ^2");
            for (String n : List.of("alice", "bob", "carol")) {
                reference.observe("State", n.equals("alice") ? 1.0 : 0.0, n);
            }
            reference.observe("Link", 0.8, "alice", "bob");
            reference.observe("Link", 0.7, "bob", "carol");
            List<GroundRule> refGround = reference.ground();

            // Build incrementally.
            PslProgram incremental = new PslProgram();
            incremental.addRule("2.0: State(X) & Link(X, Y) -> State(Y) ^2");
            incremental.observe("State", 1.0, "alice");
            IncrementalGrounder ig = new IncrementalGrounder(incremental);

            ig.addRule(PslRule.parse("1.0: State(Y) & Link(X, Y) -> State(X) ^2"));
            ig.addAtom(PslAtom.ground("State", "bob"), 0.0);
            ig.addAtom(PslAtom.ground("State", "carol"), 0.0);
            ig.addAtom(PslAtom.ground("Link", "alice", "bob"), 0.8);
            ig.addAtom(PslAtom.ground("Link", "bob", "carol"), 0.7);

            Set<String> refSet = refGround.stream().map(GroundRule::display).collect(Collectors.toSet());
            Set<String> igSet = ig.groundRules().stream().map(GroundRule::display).collect(Collectors.toSet());
            assertEquals(refSet, igSet,
                    "Incremental grounding after multiple deltas must match full re-ground on same state");
        }
    }

    // ─── Ground rule contents ──────────────────────────────────────────────────────

    @Nested
    class GroundRuleContents {

        @Test
        void groundRulesAreUnmodifiable() {
            PslProgram p = smallProgram();
            IncrementalGrounder ig = new IncrementalGrounder(p);
            List<GroundRule> rules = ig.groundRules();
            assertThrows(UnsupportedOperationException.class, () -> rules.add(null));
        }

        @Test
        void groundRulesHaveCorrectWeightAndHardness() {
            PslProgram p = new PslProgram();
            p.addRule("2.0: State(X) & Link(X, Y) -> State(Y) ^2");
            p.observe("State", 1.0, "a");
            p.observe("Link", 0.9, "a", "b");
            p.target("State", "b");
            IncrementalGrounder ig = new IncrementalGrounder(p);
            assertFalse(ig.groundRules().isEmpty());
            for (GroundRule gr : ig.groundRules()) {
                assertEquals(2.0, gr.weight(), 1e-9);
                assertFalse(gr.hard());
                assertTrue(gr.squared());
            }
        }
    }

    // ─── Null safety ──────────────────────────────────────────────────────────────

    @Nested
    class NullSafety {

        @Test
        void nullProgramThrows() {
            assertThrows(IllegalArgumentException.class, () -> new IncrementalGrounder(null));
        }
    }
}
