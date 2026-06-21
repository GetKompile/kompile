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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AllenRelation#compute(TemporalInterval, TemporalInterval)} covering
 * all 13 Allen interval relations plus open-ended (null) bound handling and
 * {@link AllenRelation#isPrecedence()}.
 */
class AllenRelationTest {

    // Shared instants on a simple timeline: T0 < T1 < T2 < T3 < T4 < T5
    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2025-02-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2025-03-01T00:00:00Z");
    private static final Instant T3 = Instant.parse("2025-04-01T00:00:00Z");
    private static final Instant T4 = Instant.parse("2025-05-01T00:00:00Z");
    private static final Instant T5 = Instant.parse("2025-06-01T00:00:00Z");

    // ─── Helper ───────────────────────────────────────────────────────────────────

    private static TemporalInterval iv(Instant s, Instant e) {
        return TemporalInterval.of(s, e);
    }

    // ─── BEFORE ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BEFORE: a.end < b.start — no shared boundary")
    void before() {
        // A=[T0,T1), B=[T2,T3): A ends before B starts
        assertEquals(AllenRelation.BEFORE, AllenRelation.compute(iv(T0, T1), iv(T2, T3)));
    }

    @Test
    @DisplayName("AFTER: b.end < a.start (inverse of BEFORE)")
    void after() {
        // A=[T3,T4), B=[T0,T1)
        assertEquals(AllenRelation.AFTER, AllenRelation.compute(iv(T3, T4), iv(T0, T1)));
    }

    // ─── MEETS / MET_BY ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("MEETS: a.end == b.start — adjacent, no gap")
    void meets() {
        // A=[T0,T2), B=[T2,T4): A's end is exactly B's start
        assertEquals(AllenRelation.MEETS, AllenRelation.compute(iv(T0, T2), iv(T2, T4)));
    }

    @Test
    @DisplayName("MET_BY: b.end == a.start (inverse of MEETS)")
    void metBy() {
        assertEquals(AllenRelation.MET_BY, AllenRelation.compute(iv(T2, T4), iv(T0, T2)));
    }

    // ─── OVERLAPS / OVERLAPPED_BY ────────────────────────────────────────────────

    @Test
    @DisplayName("OVERLAPS: a starts before b, they share a period, a ends before b")
    void overlaps() {
        // A=[T0,T2), B=[T1,T3): a.start<b.start, b.start<a.end, a.end<b.end
        assertEquals(AllenRelation.OVERLAPS, AllenRelation.compute(iv(T0, T2), iv(T1, T3)));
    }

    @Test
    @DisplayName("OVERLAPPED_BY: b starts before a, they share a period, b ends before a")
    void overlappedBy() {
        assertEquals(AllenRelation.OVERLAPPED_BY, AllenRelation.compute(iv(T1, T3), iv(T0, T2)));
    }

    // ─── DURING / CONTAINS ───────────────────────────────────────────────────────

    @Test
    @DisplayName("DURING: A is strictly inside B (both bounds)")
    void during() {
        // A=[T1,T2), B=[T0,T3): b.start < a.start, a.end < b.end
        assertEquals(AllenRelation.DURING, AllenRelation.compute(iv(T1, T2), iv(T0, T3)));
    }

    @Test
    @DisplayName("CONTAINS: A strictly contains B (inverse of DURING)")
    void contains() {
        assertEquals(AllenRelation.CONTAINS, AllenRelation.compute(iv(T0, T3), iv(T1, T2)));
    }

    // ─── STARTS / STARTED_BY ─────────────────────────────────────────────────────

    @Test
    @DisplayName("STARTS: same start, A ends before B")
    void starts() {
        // A=[T0,T1), B=[T0,T3)
        assertEquals(AllenRelation.STARTS, AllenRelation.compute(iv(T0, T1), iv(T0, T3)));
    }

    @Test
    @DisplayName("STARTED_BY: same start, B ends before A (inverse of STARTS)")
    void startedBy() {
        assertEquals(AllenRelation.STARTED_BY, AllenRelation.compute(iv(T0, T3), iv(T0, T1)));
    }

    // ─── FINISHES / FINISHED_BY ──────────────────────────────────────────────────

    @Test
    @DisplayName("FINISHES: same end, B starts before A")
    void finishes() {
        // A=[T2,T3), B=[T0,T3): b.start < a.start, a.end == b.end
        assertEquals(AllenRelation.FINISHES, AllenRelation.compute(iv(T2, T3), iv(T0, T3)));
    }

    @Test
    @DisplayName("FINISHED_BY: same end, A starts before B (inverse of FINISHES)")
    void finishedBy() {
        assertEquals(AllenRelation.FINISHED_BY, AllenRelation.compute(iv(T0, T3), iv(T2, T3)));
    }

    // ─── EQUALS ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EQUALS: identical start and end")
    void equals_relation() {
        // A=[T1,T3), B=[T1,T3)
        assertEquals(AllenRelation.EQUALS, AllenRelation.compute(iv(T1, T3), iv(T1, T3)));
    }

    // ─── Open-ended (null) bounds ─────────────────────────────────────────────────

    @Test
    @DisplayName("Open-right (null end) interval cannot PRECEDE a bounded interval")
    void openRightCannotPrecede() {
        // A=[T0, null) — ongoing; B=[T3,T5)
        // A is still valid at T3 and beyond, so A cannot precede B
        TemporalInterval a = TemporalInterval.since(T0);    // [T0, +∞)
        TemporalInterval b = iv(T3, T5);

        AllenRelation rel = AllenRelation.compute(a, b);
        assertFalse(rel.isPrecedence(),
                "An open-right (ongoing) interval cannot precede anything; got: " + rel);
    }

    @Test
    @DisplayName("BEFORE still holds when b has open right bound and a ends before b starts")
    void beforeWithOpenRightB() {
        // A=[T0,T1), B=[T2, null): A ends before B starts → BEFORE
        TemporalInterval a = iv(T0, T1);
        TemporalInterval b = TemporalInterval.since(T2);
        assertEquals(AllenRelation.BEFORE, AllenRelation.compute(a, b));
        assertTrue(AllenRelation.compute(a, b).isPrecedence());
    }

    @Test
    @DisplayName("Fully open interval (both null bounds) contains everything — CONTAINS closed interval")
    void fullyOpenContainsAnything() {
        // A=[null, null): represents all time; B=[T1,T2)
        TemporalInterval a = TemporalInterval.of(null, null);
        TemporalInterval b = iv(T1, T2);
        assertEquals(AllenRelation.CONTAINS, AllenRelation.compute(a, b));
    }

    @Test
    @DisplayName("DURING with open left bound: A has no declared start, ends before b end")
    void duringOpenLeftBound() {
        // A=[null,T2) — started at -∞; B=[null,T3) — also started at -∞ but ends later
        // Both have the same start (-∞); A ends first → STARTS
        TemporalInterval a = TemporalInterval.of(null, T2);
        TemporalInterval b = TemporalInterval.of(null, T3);
        assertEquals(AllenRelation.STARTS, AllenRelation.compute(a, b));
    }

    // ─── isPrecedence ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isPrecedence is true only for BEFORE and MEETS")
    void isPrecedenceExactlyBeforeAndMeets() {
        assertTrue(AllenRelation.BEFORE.isPrecedence());
        assertTrue(AllenRelation.MEETS.isPrecedence());

        assertFalse(AllenRelation.AFTER.isPrecedence());
        assertFalse(AllenRelation.MET_BY.isPrecedence());
        assertFalse(AllenRelation.OVERLAPS.isPrecedence());
        assertFalse(AllenRelation.OVERLAPPED_BY.isPrecedence());
        assertFalse(AllenRelation.DURING.isPrecedence());
        assertFalse(AllenRelation.CONTAINS.isPrecedence());
        assertFalse(AllenRelation.STARTS.isPrecedence());
        assertFalse(AllenRelation.STARTED_BY.isPrecedence());
        assertFalse(AllenRelation.FINISHES.isPrecedence());
        assertFalse(AllenRelation.FINISHED_BY.isPrecedence());
        assertFalse(AllenRelation.EQUALS.isPrecedence());
    }

    // ─── Symmetry / inverse consistency ──────────────────────────────────────────

    @Test
    @DisplayName("compute(a,b) == BEFORE implies compute(b,a) == AFTER")
    void inverseConsistency() {
        TemporalInterval a = iv(T0, T1);
        TemporalInterval b = iv(T2, T3);
        assertEquals(AllenRelation.BEFORE, AllenRelation.compute(a, b));
        assertEquals(AllenRelation.AFTER, AllenRelation.compute(b, a));
    }

    @Test
    @DisplayName("compute(a,a) == EQUALS for identical interval objects")
    void selfEquality() {
        TemporalInterval a = iv(T1, T4);
        assertEquals(AllenRelation.EQUALS, AllenRelation.compute(a, a));
    }
}
