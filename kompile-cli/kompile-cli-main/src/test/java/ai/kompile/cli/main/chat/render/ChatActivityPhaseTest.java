/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatActivityPhaseTest {
    @Test
    void mapsStatusLabelsToStablePhases() {
        assertEquals(ChatActivityPhase.THINKING, ChatActivityPhase.fromLabel("Thinking"));
        assertEquals(ChatActivityPhase.WORKING, ChatActivityPhase.fromLabel("Working: read"));
        assertEquals(ChatActivityPhase.RESPONDING, ChatActivityPhase.fromLabel("Responding"));
        assertEquals(ChatActivityPhase.AWAITING_INPUT, ChatActivityPhase.fromLabel("Awaiting approval"));
        assertEquals(ChatActivityPhase.READY, ChatActivityPhase.fromLabel(null));
    }

    @Test
    void extractsWorkingDetailForTabTitle() {
        assertEquals("read src/Main.java",
                ChatActivityPhase.detailFromLabel("Working: read src/Main.java"));
        assertEquals("", ChatActivityPhase.detailFromLabel("Thinking"));
    }

    @Test
    void onlyActiveWorkPhasesAreBusy() {
        assertTrue(ChatActivityPhase.THINKING.isBusy());
        assertTrue(ChatActivityPhase.WORKING.isBusy());
        assertTrue(ChatActivityPhase.RESPONDING.isBusy());
        assertFalse(ChatActivityPhase.READY.isBusy());
        assertFalse(ChatActivityPhase.AWAITING_INPUT.isBusy());
        assertFalse(ChatActivityPhase.INTERRUPTED.isBusy());
        assertFalse(ChatActivityPhase.FAILED.isBusy());
    }
}
