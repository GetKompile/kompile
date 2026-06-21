/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TemporalInterval}: contains, overlaps, isValidAt, open-ended bounds,
 * factory methods, and JSON round-trip.
 */
class TemporalIntervalTest {

    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2025-06-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2025-12-01T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-01-01T00:00:00Z");

    // ─── contains ────────────────────────────────────────────────────────────────

    @Test
    void contains_withinBounds_returnsTrue() {
        TemporalInterval interval = TemporalInterval.of(T0, T2);
        assertTrue(interval.contains(T1), "T1 should be inside [T0, T2)");
    }

    @Test
    void contains_atStart_returnsTrue() {
        TemporalInterval interval = TemporalInterval.of(T0, T2);
        assertTrue(interval.contains(T0), "Inclusive left bound T0 should be contained");
    }

    @Test
    void contains_atEnd_returnsFalse() {
        TemporalInterval interval = TemporalInterval.of(T0, T2);
        assertFalse(interval.contains(T2), "Exclusive right bound T2 should NOT be contained");
    }

    @Test
    void contains_beforeStart_returnsFalse() {
        TemporalInterval interval = TemporalInterval.of(T1, T2);
        assertFalse(interval.contains(T0), "T0 before [T1, T2) should NOT be contained");
    }

    @Test
    void contains_openLeft_includesEarlyInstants() {
        TemporalInterval interval = TemporalInterval.until(T2); // [null, T2)
        assertTrue(interval.contains(T0), "Open-left interval should contain T0");
        assertFalse(interval.contains(T2), "T2 should still be excluded (exclusive right)");
    }

    @Test
    void contains_openRight_includesLateInstants() {
        TemporalInterval interval = TemporalInterval.since(T1); // [T1, null)
        assertTrue(interval.contains(T2), "Open-right interval should contain T2");
        assertFalse(interval.contains(T0), "T0 before T1 should NOT be contained");
    }

    @Test
    void contains_fullyOpen_alwaysTrue() {
        TemporalInterval interval = TemporalInterval.of(null, null);
        assertTrue(interval.contains(T0));
        assertTrue(interval.contains(T3));
    }

    // ─── isValidAt (alias for contains) ─────────────────────────────────────────

    @Test
    void isValidAt_delegatesToContains() {
        TemporalInterval interval = TemporalInterval.of(T0, T2);
        assertEquals(interval.contains(T1), interval.isValidAt(T1));
        assertEquals(interval.contains(T2), interval.isValidAt(T2));
    }

    // ─── overlaps ────────────────────────────────────────────────────────────────

    @Test
    void overlaps_adjacentNonOverlapping_returnsFalse() {
        TemporalInterval a = TemporalInterval.of(T0, T1);
        TemporalInterval b = TemporalInterval.of(T1, T2);
        // [T0,T1) and [T1,T2) — they share only the boundary T1, but half-open semantics
        // means T1 is NOT in [T0,T1). Whether they overlap: T1 < T1 is false, so no overlap.
        assertFalse(a.overlaps(b), "[T0,T1) and [T1,T2) should NOT overlap (half-open)");
    }

    @Test
    void overlaps_overlapping_returnsTrue() {
        TemporalInterval a = TemporalInterval.of(T0, T2);
        TemporalInterval b = TemporalInterval.of(T1, T3);
        assertTrue(a.overlaps(b), "[T0,T2) and [T1,T3) should overlap at [T1,T2)");
    }

    @Test
    void overlaps_containedInterval_returnsTrue() {
        TemporalInterval outer = TemporalInterval.of(T0, T3);
        TemporalInterval inner = TemporalInterval.of(T1, T2);
        assertTrue(outer.overlaps(inner));
        assertTrue(inner.overlaps(outer));
    }

    @Test
    void overlaps_openRightWithLaterInterval_returnsTrue() {
        TemporalInterval a = TemporalInterval.since(T1); // [T1, null)
        TemporalInterval b = TemporalInterval.of(T2, T3);
        assertTrue(a.overlaps(b), "[T1,+∞) should overlap [T2,T3)");
    }

    @Test
    void overlaps_disjointIntervals_returnsFalse() {
        TemporalInterval a = TemporalInterval.of(T0, T1);
        TemporalInterval b = TemporalInterval.of(T2, T3);
        assertFalse(a.overlaps(b), "[T0,T1) and [T2,T3) are disjoint");
    }

    // ─── Factory methods ─────────────────────────────────────────────────────────

    @Test
    void point_containsOnlyThatInstant() {
        TemporalInterval p = TemporalInterval.point(T1);
        assertTrue(p.contains(T1), "point(T1) must contain T1");
        assertFalse(p.contains(T0), "point(T1) must NOT contain T0");
        assertFalse(p.contains(T2), "point(T1) must NOT contain T2");
    }

    @Test
    void since_openRight() {
        TemporalInterval s = TemporalInterval.since(T1);
        assertNull(s.end(), "since() should have null end");
        assertEquals(T1, s.start());
        assertTrue(s.contains(T2));
        assertFalse(s.contains(T0));
    }

    @Test
    void until_openLeft() {
        TemporalInterval u = TemporalInterval.until(T2);
        assertNull(u.start(), "until() should have null start");
        assertEquals(T2, u.end());
        assertTrue(u.contains(T1));
        assertFalse(u.contains(T2)); // exclusive
    }

    @Test
    void of_closedInterval() {
        TemporalInterval iv = TemporalInterval.of(T0, T3);
        assertEquals(T0, iv.start());
        assertEquals(T3, iv.end());
    }

    @Test
    void constructor_startAfterEnd_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> TemporalInterval.of(T2, T0),
                "start > end should throw");
    }

    // ─── JSON round-trip ─────────────────────────────────────────────────────────

    @Test
    void jsonRoundTrip_closedInterval() {
        TemporalInterval original = TemporalInterval.of(T0, T2);
        String json = original.toJson();
        TemporalInterval parsed  = TemporalInterval.fromJson(json);
        assertEquals(original.start(), parsed.start());
        assertEquals(original.end(),   parsed.end());
    }

    @Test
    void jsonRoundTrip_nullStart() {
        TemporalInterval original = TemporalInterval.until(T2);
        String json = original.toJson();
        TemporalInterval parsed  = TemporalInterval.fromJson(json);
        assertNull(parsed.start(), "null start should survive JSON round-trip");
        assertEquals(original.end(), parsed.end());
    }

    @Test
    void jsonRoundTrip_nullEnd() {
        TemporalInterval original = TemporalInterval.since(T1);
        String json = original.toJson();
        TemporalInterval parsed  = TemporalInterval.fromJson(json);
        assertEquals(original.start(), parsed.start());
        assertNull(parsed.end(), "null end should survive JSON round-trip");
    }

    @Test
    void jsonRoundTrip_bothNull() {
        TemporalInterval original = TemporalInterval.of(null, null);
        String json = original.toJson();
        TemporalInterval parsed  = TemporalInterval.fromJson(json);
        assertNull(parsed.start());
        assertNull(parsed.end());
    }

    @Test
    void toJson_containsValidFromAndValidUntilKeys() {
        TemporalInterval iv = TemporalInterval.of(T0, T2);
        String json = iv.toJson();
        assertTrue(json.contains("validFrom"),  "JSON must contain 'validFrom' key");
        assertTrue(json.contains("validUntil"), "JSON must contain 'validUntil' key");
        assertTrue(json.contains(T0.toString()), "JSON must contain T0 ISO string");
        assertTrue(json.contains(T2.toString()), "JSON must contain T2 ISO string");
    }
}
