/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FactAuditEvent} — JSON round-trip, factories, and field contracts.
 */
class FactAuditEventTest {

    // ── Factory: derived ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("derived factory: eventType=DERIVED, actor=CASCADE, valueBefore/After set correctly")
    void derived_factoryPopulatesCorrectly() {
        FactAuditEvent event = FactAuditEvent.derived(
                "isActive(alice)", 0.3, 0.67, 0.3, 0.67, "run-abc123", "session-1");

        assertEquals("DERIVED", event.eventType());
        assertEquals("isActive(alice)", event.atomKey());
        assertEquals("CASCADE", event.actor());
        assertEquals(0.3, event.valueBefore(), 1e-9);
        assertEquals(0.67, event.valueAfter(), 1e-9);
        assertEquals("run-abc123", event.runId());
        assertEquals("runId:run-abc123", event.derivationTrailRef());
        assertFalse(event.pinnedAfter());
        assertNotNull(event.eventId());
        assertNotNull(event.occurredAt());
    }

    // ── Factory: asserted ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("asserted factory: eventType=ASSERTED, actor set from parameter")
    void asserted_factoryPopulatesCorrectly() {
        FactAuditEvent event = FactAuditEvent.asserted(
                "employedAt(alice,acme)", 0.95, "agent-session-7", "CRAWL");

        assertEquals("ASSERTED", event.eventType());
        assertEquals("employedAt(alice,acme)", event.atomKey());
        assertEquals("CRAWL", event.actor());
        assertEquals(0.95, event.valueAfter(), 1e-9);
        assertFalse(event.pinnedAfter());
    }

    // ── Factory: corrected ────────────────────────────────────────────────────────

    @Test
    @DisplayName("corrected factory: eventType=CORRECTED, pinnedAfter=true, reason preserved")
    void corrected_factoryPopulatesCorrectly() {
        FactAuditEvent event = FactAuditEvent.corrected(
                "isActive(alice)", 0.67, 0.0,
                0.67, 0.0,
                "HUMAN:adam", "session-99", true,
                "Alice is no longer active");

        assertEquals("CORRECTED", event.eventType());
        assertEquals("isActive(alice)", event.atomKey());
        assertEquals("HUMAN:adam", event.actor());
        assertEquals(0.67, event.valueBefore(), 1e-9);
        assertEquals(0.0, event.valueAfter(), 1e-9);
        assertTrue(event.pinnedAfter());
        assertEquals("Alice is no longer active", event.correctionReason());
    }

    // ── Factory: weightTuned ──────────────────────────────────────────────────────

    @Test
    @DisplayName("weightTuned factory: eventType=WEIGHT_TUNED, weightBefore/After set, atomKey=null")
    void weightTuned_factoryPopulatesCorrectly() {
        FactAuditEvent event = FactAuditEvent.weightTuned(
                "3.20: State(?X) -> derived_State(?X)", 3.20, 2.41,
                "HUMAN:adam", "session-99");

        assertEquals("WEIGHT_TUNED", event.eventType());
        assertNull(event.atomKey());
        assertEquals(3.20, event.weightBefore(), 1e-9);
        assertEquals(2.41, event.weightAfter(), 1e-9);
        assertEquals("3.20: State(?X) -> derived_State(?X)", event.ruleId());
    }

    // ── JSON round-trip: DERIVED ──────────────────────────────────────────────────

    @Test
    @DisplayName("toJson/fromJson round-trip: DERIVED event is lossless")
    void jsonRoundTrip_derivedEvent() {
        FactAuditEvent original = FactAuditEvent.derived(
                "isActive(alice)", 0.3, 0.67, 0.3, 0.67, "run-abc", "session-1");

        String json = original.toJson();
        assertNotNull(json);
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));

        FactAuditEvent restored = FactAuditEvent.fromJson(json);

        assertEquals(original.eventId(), restored.eventId());
        assertEquals(original.eventType(), restored.eventType());
        assertEquals(original.atomKey(), restored.atomKey());
        assertEquals(original.actor(), restored.actor());
        assertEquals(original.sessionId(), restored.sessionId());
        assertEquals(original.valueBefore(), restored.valueBefore(), 1e-9);
        assertEquals(original.valueAfter(), restored.valueAfter(), 1e-9);
        assertEquals(original.runId(), restored.runId());
        assertEquals(original.pinnedAfter(), restored.pinnedAfter());
        // Instant round-trip (truncated to millis by ISO-8601 string)
        assertEquals(original.occurredAt().toEpochMilli(), restored.occurredAt().toEpochMilli());
    }

    // ── JSON round-trip: CORRECTED with reason ────────────────────────────────────

    @Test
    @DisplayName("toJson/fromJson round-trip: CORRECTED event with reason and pinnedAfter=true is lossless")
    void jsonRoundTrip_correctedEvent() {
        FactAuditEvent original = FactAuditEvent.corrected(
                "isActive(alice)", 0.67, 0.0,
                0.67, 0.0,
                "HUMAN:adam", "s42", true,
                "Left org 2026-06-15");

        FactAuditEvent restored = FactAuditEvent.fromJson(original.toJson());

        assertEquals("CORRECTED", restored.eventType());
        assertTrue(restored.pinnedAfter());
        assertEquals("Left org 2026-06-15", restored.correctionReason());
        assertEquals("isActive(alice)", restored.atomKey());
    }

    // ── JSON round-trip: WEIGHT_TUNED (null atomKey) ──────────────────────────────

    @Test
    @DisplayName("toJson/fromJson round-trip: WEIGHT_TUNED event with null atomKey is lossless")
    void jsonRoundTrip_weightTunedEvent() {
        FactAuditEvent original = FactAuditEvent.weightTuned(
                "0.8: foo(?X) -> derived_foo(?X)", 0.8, 0.55, "HUMAN:adam", "s1");

        FactAuditEvent restored = FactAuditEvent.fromJson(original.toJson());

        assertEquals("WEIGHT_TUNED", restored.eventType());
        assertNull(restored.atomKey());
        assertEquals(0.8, restored.weightBefore(), 1e-9);
        assertEquals(0.55, restored.weightAfter(), 1e-9);
        assertEquals("0.8: foo(?X) -> derived_foo(?X)", restored.ruleId());
    }

    // ── JSON round-trip: special characters in reason ─────────────────────────────

    @Test
    @DisplayName("toJson/fromJson: reason with quotes and backslashes survives round-trip")
    void jsonRoundTrip_specialCharsInReason() {
        String reason = "She said \"no\" and left\\home";
        FactAuditEvent original = FactAuditEvent.corrected(
                "foo(x)", 0.5, 0.0, 0.5, 0.0,
                "HUMAN:adam", null, true, reason);

        FactAuditEvent restored = FactAuditEvent.fromJson(original.toJson());

        assertEquals(reason, restored.correctionReason());
    }

    // ── StrengthLayer integration ─────────────────────────────────────────────────

    @Test
    @DisplayName("StrengthLayer.of(): confidence bands resolve correctly with defaults")
    void strengthLayer_defaultCutoffs() {
        assertEquals(StrengthLayer.ESTABLISHED,  StrengthLayer.of(0.90));
        assertEquals(StrengthLayer.ESTABLISHED,  StrengthLayer.of(0.85));
        assertEquals(StrengthLayer.PROBABLE,     StrengthLayer.of(0.70));
        assertEquals(StrengthLayer.PROBABLE,     StrengthLayer.of(0.50));
        assertEquals(StrengthLayer.SPECULATIVE,  StrengthLayer.of(0.35));
        assertEquals(StrengthLayer.SPECULATIVE,  StrengthLayer.of(0.20));
        assertEquals(StrengthLayer.SUPPRESSED,   StrengthLayer.of(0.10));
        assertEquals(StrengthLayer.SUPPRESSED,   StrengthLayer.of(0.0));
    }

    @Test
    @DisplayName("StrengthLayer.isAtLeast(): ordinal comparison works correctly")
    void strengthLayer_isAtLeast() {
        assertTrue(StrengthLayer.ESTABLISHED.isAtLeast(StrengthLayer.PROBABLE));
        assertTrue(StrengthLayer.PROBABLE.isAtLeast(StrengthLayer.PROBABLE));
        assertFalse(StrengthLayer.SPECULATIVE.isAtLeast(StrengthLayer.PROBABLE));
        assertFalse(StrengthLayer.SUPPRESSED.isAtLeast(StrengthLayer.SPECULATIVE));
    }
}
