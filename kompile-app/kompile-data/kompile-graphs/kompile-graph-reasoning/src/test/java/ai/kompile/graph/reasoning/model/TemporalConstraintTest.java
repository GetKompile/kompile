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
package ai.kompile.graph.reasoning.model;

import ai.kompile.graph.reasoning.fol.FolRule;
import ai.kompile.graph.reasoning.fol.ReasoningGraphKnowledgeBase;
import ai.kompile.graph.reasoning.mebn.logic.Constraints;
import ai.kompile.graph.reasoning.mebn.logic.KnowledgeBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Phase-T2 temporal constraint predicates:
 * {@link Constraints#precedes}, {@link Constraints#withinWindow},
 * and {@link Constraints#allenRelation}.
 *
 * <p>The test graph contains four timestamped entities on a simple timeline:</p>
 * <pre>
 *   early  : timestamp = T0 = 2025-01-01T00:00
 *   mid    : timestamp = T2 = 2025-03-01T00:00
 *   late   : timestamp = T4 = 2025-05-01T00:00
 *   notime : no timestamp, no valid-time
 *
 *   intervalA : validTime = [T0, T2)  (covers Jan–Feb)
 *   intervalB : validTime = [T1, T3)  (covers Feb–Mar; overlaps A)
 *   intervalC : validTime = [T3, T5)  (covers Apr–May; after A)
 * </pre>
 * Entities are connected by a CAUSES edge early→mid and mid→late for the FOL rule test.
 */
class TemporalConstraintTest {

    // Timeline instants
    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2025-02-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2025-03-01T00:00:00Z");
    private static final Instant T3 = Instant.parse("2025-04-01T00:00:00Z");
    private static final Instant T4 = Instant.parse("2025-05-01T00:00:00Z");
    private static final Instant T5 = Instant.parse("2025-06-01T00:00:00Z");

    MutableReasoningGraph graph;
    KnowledgeBase kb;

    @BeforeEach
    void buildGraph() {
        graph = new MutableReasoningGraph();

        // Point-timestamped entities
        graph.addEntity(GraphEntity.builder("early").type("Event").label("Early").weight(1.0)
                .timestamp(T0).build());
        graph.addEntity(GraphEntity.builder("mid").type("Event").label("Mid").weight(1.0)
                .timestamp(T2).build());
        graph.addEntity(GraphEntity.builder("late").type("Event").label("Late").weight(1.0)
                .timestamp(T4).build());

        // Entity with no temporal information
        graph.addEntity(GraphEntity.builder("notime").type("Event").label("NoTime").weight(1.0)
                .build());

        // Interval-bearing entities (validFrom/validUntil stored in attributes map, as per GraphEntity.validTime() default)
        graph.addEntity(GraphEntity.builder("ivA").type("Interval").label("IntervalA").weight(1.0)
                .attribute("validFrom",  T0.toString())
                .attribute("validUntil", T2.toString())
                .build());
        graph.addEntity(GraphEntity.builder("ivB").type("Interval").label("IntervalB").weight(1.0)
                .attribute("validFrom",  T1.toString())
                .attribute("validUntil", T3.toString())
                .build());
        graph.addEntity(GraphEntity.builder("ivC").type("Interval").label("IntervalC").weight(1.0)
                .attribute("validFrom",  T3.toString())
                .attribute("validUntil", T5.toString())
                .build());

        // Causal edges for FOL rule test
        graph.addRelation("r1", "early", "mid",  "CAUSES", 0.9);
        graph.addRelation("r2", "mid",   "late", "CAUSES", 0.8);
        // Reverse: late→early (violates temporal order)
        graph.addRelation("r3", "late",  "early", "CAUSES", 0.3);

        kb = new ReasoningGraphKnowledgeBase(graph);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Constraints.precedes
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Constraints.precedes")
    class PrecedesTests {

        @Test
        @DisplayName("early precedes mid (T0 < T2) → true")
        void precedes_earlyBeforeMid() {
            assertTrue(Constraints.precedes("X", "Y").evaluate(kb, Map.of("X", "early", "Y", "mid")));
        }

        @Test
        @DisplayName("early precedes late (T0 < T4) → true")
        void precedes_earlyBeforeLate() {
            assertTrue(Constraints.precedes("X", "Y").evaluate(kb, Map.of("X", "early", "Y", "late")));
        }

        @Test
        @DisplayName("mid precedes late (T2 < T4) → true")
        void precedes_midBeforeLate() {
            assertTrue(Constraints.precedes("X", "Y").evaluate(kb, Map.of("X", "mid", "Y", "late")));
        }

        @Test
        @DisplayName("late does NOT precede early (T4 > T0) → false")
        void precedes_lateNotBeforeEarly() {
            assertFalse(Constraints.precedes("X", "Y").evaluate(kb, Map.of("X", "late", "Y", "early")));
        }

        @Test
        @DisplayName("same entity does NOT precede itself (equal timestamps, not strictly before) → false")
        void precedes_selfIsNotBefore() {
            assertFalse(Constraints.precedes("X", "Y").evaluate(kb, Map.of("X", "early", "Y", "early")));
        }

        @Test
        @DisplayName("entity with no timestamp → false (cannot establish precedence)")
        void precedes_noTimestamp() {
            assertFalse(Constraints.precedes("X", "Y").evaluate(kb, Map.of("X", "notime", "Y", "early")));
            assertFalse(Constraints.precedes("X", "Y").evaluate(kb, Map.of("X", "early", "Y", "notime")));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Constraints.withinWindow
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Constraints.withinWindow")
    class WithinWindowTests {

        /** T0 to T2 = ~59 days (Jan→Mar). */
        private static final Duration SIXTY_DAYS = Duration.ofDays(60);
        private static final Duration THIRTY_DAYS = Duration.ofDays(30);

        @Test
        @DisplayName("early and mid within 60 days (T0 to T2 ≈ 59d) → true")
        void withinWindow_earlyMid_60days() {
            assertTrue(Constraints.withinWindow("X", "Y", SIXTY_DAYS)
                    .evaluate(kb, Map.of("X", "early", "Y", "mid")));
        }

        @Test
        @DisplayName("early and mid NOT within 30 days (T0 to T2 ≈ 59d) → false")
        void withinWindow_earlyMid_30days() {
            assertFalse(Constraints.withinWindow("X", "Y", THIRTY_DAYS)
                    .evaluate(kb, Map.of("X", "early", "Y", "mid")));
        }

        @Test
        @DisplayName("window is symmetric: (X,Y) same as (Y,X)")
        void withinWindow_symmetric() {
            boolean xy = Constraints.withinWindow("X", "Y", SIXTY_DAYS)
                    .evaluate(kb, Map.of("X", "early", "Y", "mid"));
            boolean yx = Constraints.withinWindow("X", "Y", SIXTY_DAYS)
                    .evaluate(kb, Map.of("X", "mid", "Y", "early"));
            assertEquals(xy, yx);
        }

        @Test
        @DisplayName("early and late (T0 to T4 ≈ 120d) NOT within 60 days → false")
        void withinWindow_earlyLate_60days() {
            assertFalse(Constraints.withinWindow("X", "Y", SIXTY_DAYS)
                    .evaluate(kb, Map.of("X", "early", "Y", "late")));
        }

        @Test
        @DisplayName("entity with no timestamp → false")
        void withinWindow_noTimestamp() {
            assertFalse(Constraints.withinWindow("X", "Y", SIXTY_DAYS)
                    .evaluate(kb, Map.of("X", "notime", "Y", "mid")));
        }

        @Test
        @DisplayName("zero-duration window: entity must have same timestamp as itself")
        void withinWindow_zeroDuration() {
            // Same entity: duration = 0 ≤ 0 → true
            assertTrue(Constraints.withinWindow("X", "Y", Duration.ZERO)
                    .evaluate(kb, Map.of("X", "early", "Y", "early")));
            // Different entities: duration > 0 → false
            assertFalse(Constraints.withinWindow("X", "Y", Duration.ZERO)
                    .evaluate(kb, Map.of("X", "early", "Y", "mid")));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Constraints.allenRelation
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Constraints.allenRelation")
    class AllenRelationConstraintTests {

        @Test
        @DisplayName("ivA OVERLAPS ivB (A=[T0,T2), B=[T1,T3)) → true for OVERLAPS")
        void allenRelation_ivA_OVERLAPS_ivB() {
            assertTrue(Constraints.allenRelation("X", "Y", AllenRelation.OVERLAPS)
                    .evaluate(kb, Map.of("X", "ivA", "Y", "ivB")));
        }

        @Test
        @DisplayName("ivA does NOT satisfy BEFORE relative to ivB (they overlap) → false")
        void allenRelation_ivA_NOT_BEFORE_ivB() {
            assertFalse(Constraints.allenRelation("X", "Y", AllenRelation.BEFORE)
                    .evaluate(kb, Map.of("X", "ivA", "Y", "ivB")));
        }

        @Test
        @DisplayName("ivA BEFORE ivC (A=[T0,T2), C=[T3,T5)) → true for BEFORE")
        void allenRelation_ivA_BEFORE_ivC() {
            assertTrue(Constraints.allenRelation("X", "Y", AllenRelation.BEFORE)
                    .evaluate(kb, Map.of("X", "ivA", "Y", "ivC")));
        }

        @Test
        @DisplayName("ivA BEFORE ivC → isPrecedence true")
        void allenRelation_ivA_BEFORE_ivC_isPrecedence() {
            AllenRelation rel = AllenRelation.compute(
                    TemporalInterval.of(T0, T2), TemporalInterval.of(T3, T5));
            assertTrue(rel.isPrecedence());
        }

        @Test
        @DisplayName("ivA EQUALS ivA (same interval) → true for EQUALS")
        void allenRelation_ivA_EQUALS_itself() {
            assertTrue(Constraints.allenRelation("X", "Y", AllenRelation.EQUALS)
                    .evaluate(kb, Map.of("X", "ivA", "Y", "ivA")));
        }

        @Test
        @DisplayName("entity with no interval but a timestamp: falls back to point interval")
        void allenRelation_fallback_to_pointInterval() {
            // early (T0) vs mid (T2): point(T0) BEFORE point(T2)
            // point() wraps t as [t, t+1ns) so BEFORE holds
            assertTrue(Constraints.allenRelation("X", "Y", AllenRelation.BEFORE)
                    .evaluate(kb, Map.of("X", "early", "Y", "mid")));
        }

        @Test
        @DisplayName("entity with no temporal info → false regardless of relation")
        void allenRelation_noTemporalInfo() {
            assertFalse(Constraints.allenRelation("X", "Y", AllenRelation.BEFORE)
                    .evaluate(kb, Map.of("X", "notime", "Y", "early")));
            assertFalse(Constraints.allenRelation("X", "Y", AllenRelation.EQUALS)
                    .evaluate(kb, Map.of("X", "notime", "Y", "notime")));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // FOL rule with precedes constraint
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FOL rule with precedes temporal constraint")
    class FolRuleWithPrecedesTests {

        /**
         * Rule: "A CAUSES B only when A temporally precedes B."
         * Antecedent: edgeOfType(X, Y, "CAUSES") AND precedes(X, Y)
         * Consequent: entityExists(Y)
         */
        FolRule temporalCausalityRule;

        @BeforeEach
        void buildRule() {
            temporalCausalityRule = FolRule.of(
                    "temporal-causality",
                    3.0,
                    Constraints.and(
                            Constraints.edgeOfType("X", "Y", "CAUSES"),
                            Constraints.precedes("X", "Y")),
                    Constraints.entityExists("Y"));
        }

        @Test
        @DisplayName("early→mid (CAUSES, early < mid): antecedent fires, consequent holds → satisfied")
        void rule_firesForTemporallyOrderedPair() {
            // Antecedent: edgeExists(early,mid) AND precedes(early,mid) → both true
            boolean ante = Constraints.and(
                    Constraints.edgeOfType("X", "Y", "CAUSES"),
                    Constraints.precedes("X", "Y"))
                    .evaluate(kb, Map.of("X", "early", "Y", "mid"));
            assertTrue(ante, "antecedent must fire for temporally-ordered early→mid pair");
            assertTrue(temporalCausalityRule.isSatisfied(kb, Map.of("X", "early", "Y", "mid")));
        }

        @Test
        @DisplayName("mid→late (CAUSES, mid < late): antecedent fires → satisfied")
        void rule_firesForMidLate() {
            boolean ante = Constraints.and(
                    Constraints.edgeOfType("X", "Y", "CAUSES"),
                    Constraints.precedes("X", "Y"))
                    .evaluate(kb, Map.of("X", "mid", "Y", "late"));
            assertTrue(ante);
            assertTrue(temporalCausalityRule.isSatisfied(kb, Map.of("X", "mid", "Y", "late")));
        }

        @Test
        @DisplayName("late→early (CAUSES edge exists but late > early): antecedent does NOT fire → vacuously satisfied")
        void rule_doesNotFireForReversedPair() {
            // Edge r3: late→early exists, but precedes(late, early) is false (T4 > T0)
            boolean ante = Constraints.and(
                    Constraints.edgeOfType("X", "Y", "CAUSES"),
                    Constraints.precedes("X", "Y"))
                    .evaluate(kb, Map.of("X", "late", "Y", "early"));
            assertFalse(ante, "antecedent must NOT fire when temporal order is violated (late→early)");
            // Rule is vacuously satisfied (antecedent false)
            assertTrue(temporalCausalityRule.isSatisfied(kb, Map.of("X", "late", "Y", "early")),
                    "rule is vacuously true when antecedent does not hold");
        }

        @Test
        @DisplayName("pair with no edge: antecedent false → rule vacuously satisfied")
        void rule_noEdge_vacuouslySatisfied() {
            assertFalse(Constraints.edgeOfType("X", "Y", "CAUSES")
                    .evaluate(kb, Map.of("X", "early", "Y", "late")));
            assertTrue(temporalCausalityRule.isSatisfied(kb, Map.of("X", "early", "Y", "late")));
        }

        @Test
        @DisplayName("precedes constraint alone: only pairs where X.time < Y.time → true")
        void precedes_onlyFiringForCorrectOrder() {
            var prec = Constraints.precedes("X", "Y");

            assertTrue(prec.evaluate(kb, Map.of("X", "early", "Y", "mid")),  "early < mid");
            assertTrue(prec.evaluate(kb, Map.of("X", "early", "Y", "late")), "early < late");
            assertTrue(prec.evaluate(kb, Map.of("X", "mid",   "Y", "late")), "mid < late");

            assertFalse(prec.evaluate(kb, Map.of("X", "mid",   "Y", "early")), "mid !< early");
            assertFalse(prec.evaluate(kb, Map.of("X", "late",  "Y", "early")), "late !< early");
            assertFalse(prec.evaluate(kb, Map.of("X", "late",  "Y", "mid")),   "late !< mid");
            assertFalse(prec.evaluate(kb, Map.of("X", "early", "Y", "early")), "same ts: not strictly before");
        }

        @Test
        @DisplayName("precedes composes with 'not': reversed pair satisfies NOT(precedes)")
        void precedes_composesWithNot() {
            var notPrecedes = Constraints.not(Constraints.precedes("X", "Y"));
            // late→early: not(false) = true
            assertTrue(notPrecedes.evaluate(kb, Map.of("X", "late", "Y", "early")));
            // early→mid: not(true) = false
            assertFalse(notPrecedes.evaluate(kb, Map.of("X", "early", "Y", "mid")));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // KnowledgeBase temporal accessor delegation
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ReasoningGraphKnowledgeBase temporal accessors")
    class KbTemporalAccessorTests {

        @Test
        @DisplayName("getTimestamp returns present for timestamped entity")
        void getTimestamp_present() {
            assertTrue(kb.getTimestamp("early").isPresent());
            assertEquals(T0, kb.getTimestamp("early").get());
        }

        @Test
        @DisplayName("getTimestamp returns empty for entity without timestamp")
        void getTimestamp_absent() {
            assertTrue(kb.getTimestamp("notime").isEmpty());
        }

        @Test
        @DisplayName("getTimestamp returns empty for null / unknown entity")
        void getTimestamp_nullOrMissing() {
            assertTrue(kb.getTimestamp(null).isEmpty());
            assertTrue(kb.getTimestamp("ghost").isEmpty());
        }

        @Test
        @DisplayName("getValidTime returns present for interval entity (attributes map)")
        void getValidTime_present() {
            assertTrue(kb.getValidTime("ivA").isPresent());
            TemporalInterval iv = kb.getValidTime("ivA").get();
            assertEquals(T0, iv.start());
            assertEquals(T2, iv.end());
        }

        @Test
        @DisplayName("getValidTime returns empty for entity without validFrom/validUntil attributes")
        void getValidTime_absent() {
            assertTrue(kb.getValidTime("early").isEmpty());
            assertTrue(kb.getValidTime("notime").isEmpty());
        }
    }
}
