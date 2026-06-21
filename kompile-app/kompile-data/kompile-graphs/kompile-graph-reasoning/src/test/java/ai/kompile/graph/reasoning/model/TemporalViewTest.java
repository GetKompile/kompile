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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TemporalView}: asOf filtering, between-window filtering, endpoint
 * validity enforcement on relations, and the validTime/isValidAt fallback to timestamp.
 */
class TemporalViewTest {

    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2025-06-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2025-12-01T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-03-01T00:00:00Z");

    /**
     * Helper: build a {@link SimpleGraphEntity} with "validFrom"/"validUntil" in its attributes
     * so that {@link GraphEntity#validTime()} returns the specified interval.
     */
    private static GraphEntity entityWithInterval(String id, Instant from, Instant until) {
        Map<String, Object> attrs = new java.util.LinkedHashMap<>();
        if (from  != null) attrs.put("validFrom",  from.toString());
        if (until != null) attrs.put("validUntil", until.toString());
        return GraphEntity.builder(id)
                .type("EVENT").label(id)
                .attributes(attrs)
                .build();
    }

    /**
     * Helper: build a {@link SimpleGraphEntity} with only a point timestamp (no interval
     * attributes) to test the fallback path.
     */
    private static GraphEntity entityWithTimestamp(String id, Instant ts) {
        return GraphEntity.builder(id)
                .type("EVENT").label(id)
                .timestamp(ts)
                .build();
    }

    /** Helper: build a timeless entity (no timestamp, no interval). */
    private static GraphEntity timelessEntity(String id) {
        return GraphEntity.builder(id).type("STATIC").label(id).build();
    }

    // ─── Graph used by most tests ─────────────────────────────────────────────────

    private MutableReasoningGraph graph;

    /** Three entities with non-overlapping valid-time intervals + one timeless entity. */
    @BeforeEach
    void setUp() {
        graph = new MutableReasoningGraph();
        // e1: valid [T0, T1)  — before T1
        graph.addEntity(entityWithInterval("e1", T0, T1));
        // e2: valid [T1, T2)  — around mid-year 2025
        graph.addEntity(entityWithInterval("e2", T1, T2));
        // e3: valid [T2, T3)  — late 2025 to early 2026
        graph.addEntity(entityWithInterval("e3", T2, T3));
        // e4: timeless (no temporal info)
        graph.addEntity(timelessEntity("e4"));

        // r12: e1 → e2, valid [T0, T2)
        Map<String, Object> relAttrs = Map.of("validFrom", T0.toString(), "validUntil", T2.toString());
        graph.addRelation(GraphRelation.builder("r12", "e1", "e2")
                .type("FOLLOWS").weight(1.0).confidence(1.0)
                .attributes(relAttrs).build());

        // r23: e2 → e3, valid [T1, T3)
        Map<String, Object> relAttrs2 = Map.of("validFrom", T1.toString(), "validUntil", T3.toString());
        graph.addRelation(GraphRelation.builder("r23", "e2", "e3")
                .type("FOLLOWS").weight(1.0).confidence(1.0)
                .attributes(relAttrs2).build());
    }

    // ─── asOf: entity filtering ───────────────────────────────────────────────────

    @Test
    void asOf_atT0_includesOnlyE1AndTimeless() {
        TemporalView view = TemporalView.asOf(graph, T0);
        Collection<GraphEntity> entities = view.entities();
        Set<String> ids = entityIds(entities);
        assertTrue(ids.contains("e1"), "e1 valid at T0");
        assertFalse(ids.contains("e2"), "e2 starts at T1, NOT valid at T0");
        assertFalse(ids.contains("e3"), "e3 starts at T2, NOT valid at T0");
        assertTrue(ids.contains("e4"), "timeless e4 always valid");
        assertEquals(2, ids.size(), "Exactly e1 and e4 expected at T0");
    }

    @Test
    void asOf_atT1_includesE2AndTimeless() {
        TemporalView view = TemporalView.asOf(graph, T1);
        Set<String> ids = entityIds(view.entities());
        // e1 is [T0, T1) — T1 is NOT in [T0, T1) (exclusive end)
        assertFalse(ids.contains("e1"), "e1 ends at T1 (exclusive), NOT valid at T1");
        assertTrue(ids.contains("e2"), "e2 starts at T1 (inclusive), valid at T1");
        assertFalse(ids.contains("e3"), "e3 not valid at T1");
        assertTrue(ids.contains("e4"), "timeless e4 always valid");
    }

    @Test
    void asOf_atT2_includesE3AndTimeless() {
        TemporalView view = TemporalView.asOf(graph, T2);
        Set<String> ids = entityIds(view.entities());
        assertFalse(ids.contains("e1"));
        assertFalse(ids.contains("e2"), "e2 is [T1,T2) — T2 is exclusive");
        assertTrue(ids.contains("e3"), "e3 starts at T2");
        assertTrue(ids.contains("e4"));
    }

    // ─── asOf: entity count sanity check ─────────────────────────────────────────

    @Test
    void asOf_beforeAnyInterval_countDropsToTimelessOnly() {
        Instant beforeAll = T0.minusSeconds(1);
        TemporalView view = TemporalView.asOf(graph, beforeAll);
        // e1..e3 all start at or after T0; e4 is timeless
        // Note: e1.isValidAt uses the fallback: no validFrom/validUntil in attributes?
        // Actually e1 has validFrom=T0, so at T0-1s it is NOT valid.
        Collection<GraphEntity> entities = view.entities();
        assertEquals(1, entities.size(), "Only timeless e4 should be valid before T0");
    }

    // ─── asOf: relation endpoint rule ────────────────────────────────────────────

    @Test
    void asOf_relationsDroppedWhenEndpointInvalid() {
        // At T0: only e1 and e4 are valid. r12 connects e1→e2, but e2 is not valid.
        TemporalView view = TemporalView.asOf(graph, T0);
        assertEquals(0, view.relations().size(),
                "r12 dropped: e2 not valid at T0; r23 dropped: e2 not valid");
    }

    @Test
    void asOf_relationsIncludedWhenBothEndpointsValid() {
        // At T1: e2 is valid, e4 is valid. r12 connects e1→e2 but e1 is expired.
        // r23 connects e2→e3 but e3 is not valid yet.
        // No relation should survive.
        TemporalView view = TemporalView.asOf(graph, T1);
        assertEquals(0, view.relations().size(),
                "r12: e1 expired at T1; r23: e3 not yet valid at T1");
    }

    @Test
    void asOf_relationIncludedWhenBothEndpointsAndRelationValid() {
        // Build a specific graph where all three (e1, e2, r12) are valid at T1
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(entityWithInterval("a", T0, T3));
        g.addEntity(entityWithInterval("b", T0, T3));
        Map<String, Object> attrs = Map.of("validFrom", T0.toString(), "validUntil", T3.toString());
        g.addRelation(GraphRelation.builder("rab", "a", "b")
                .type("CAUSES").weight(1.0).confidence(1.0)
                .attributes(attrs).build());

        TemporalView view = TemporalView.asOf(g, T1);
        assertEquals(2, view.entities().size(), "Both a and b valid at T1");
        assertEquals(1, view.relations().size(), "rab valid at T1 with both endpoints valid");
    }

    // ─── validTime() reads from attributes map ────────────────────────────────────

    @Test
    void validTime_readsFromAttributesMap() {
        GraphEntity e = entityWithInterval("x", T0, T2);
        TemporalInterval vt = e.validTime();
        assertNotNull(vt, "validTime() must return non-null when attributes have validFrom/validUntil");
        assertEquals(T0, vt.start());
        assertEquals(T2, vt.end());
    }

    @Test
    void validTime_absentWhenNoAttributes() {
        GraphEntity e = timelessEntity("x");
        assertNull(e.validTime(), "validTime() must return null when no temporal attributes");
    }

    // ─── isValidAt fallback to timestamp ─────────────────────────────────────────

    @Test
    void isValidAt_fallsBackToTimestampWhenNoInterval() {
        // An entity with only a timestamp (no validFrom/validUntil)
        GraphEntity stamped = entityWithTimestamp("ts-entity", T1);
        // At T1 itself: timestamp <= t → valid
        assertTrue(stamped.isValidAt(T1), "At the timestamp itself should be valid");
        // Before T1: timestamp > t → not valid
        assertFalse(stamped.isValidAt(T0), "Before the timestamp should not be valid");
        // After T1: timestamp < t → valid (still valid from ts onward)
        assertTrue(stamped.isValidAt(T2), "After the timestamp should still be valid");
    }

    @Test
    void isValidAt_timelessEntityAlwaysValid() {
        GraphEntity e = timelessEntity("always");
        assertTrue(e.isValidAt(T0));
        assertTrue(e.isValidAt(T3));
    }

    // ─── between: window filtering ────────────────────────────────────────────────

    @Test
    void between_windowCoveringE2Only_returnsOnlyE2AndTimeless() {
        // Window [T1, T2) — overlaps e2 [T1, T2) exactly; e1 [T0,T1) and e3 [T2,T3) don't overlap
        TemporalView view = TemporalView.between(graph, T1, T2);
        Set<String> ids = entityIds(view.entities());
        assertFalse(ids.contains("e1"), "e1 [T0,T1) does not overlap [T1,T2)");
        assertTrue(ids.contains("e2"), "e2 [T1,T2) overlaps [T1,T2)");
        assertFalse(ids.contains("e3"), "e3 [T2,T3) does not overlap [T1,T2)");
        assertTrue(ids.contains("e4"), "timeless e4 always included");
    }

    @Test
    void between_wideCoverageWindow_returnsAllEntities() {
        // Window [T0, T3) covers all three intervals
        TemporalView view = TemporalView.between(graph, T0, T3);
        assertEquals(4, view.entities().size(), "All 4 entities should be included in [T0,T3)");
    }

    @Test
    void between_openLeftWindow_includedAllUpToT1() {
        // Window [null, T1) — open left
        TemporalView view = TemporalView.between(graph, null, T1);
        Set<String> ids = entityIds(view.entities());
        assertTrue(ids.contains("e1"), "e1 [T0,T1) overlaps [null,T1)");
        assertFalse(ids.contains("e2"), "e2 starts at T1, [T1,T2) does not overlap [null,T1)");
    }

    // ─── between: relation with point-timestamp endpoint ─────────────────────────

    @Test
    void between_pointTimestampEntityIncludedInOverlappingWindow() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        // Entity with only a point timestamp at T1
        g.addEntity(entityWithTimestamp("pt", T1));

        TemporalView view = TemporalView.between(g, T0, T2);
        // point(T1) → [T1, T1+1ns), which overlaps [T0, T2)
        Set<String> ids = entityIds(view.entities());
        assertTrue(ids.contains("pt"), "Point-stamped entity at T1 should be in window [T0,T2)");
    }

    @Test
    void between_pointTimestampEntityExcludedFromNonOverlappingWindow() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(entityWithTimestamp("pt", T1));

        // Window [T2, T3) — point at T1 is outside
        TemporalView view = TemporalView.between(g, T2, T3);
        assertTrue(view.entities().isEmpty(), "Point-stamped entity at T1 not in window [T2,T3)");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private static Set<String> entityIds(Collection<GraphEntity> entities) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (GraphEntity e : entities) ids.add(e.id());
        return ids;
    }
}
