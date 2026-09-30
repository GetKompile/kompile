/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForegroundRequestProgressTest {

    @Test
    void beginResetsCountersAndPicksStableWord() {
        ForegroundRequestProgress progress = new ForegroundRequestProgress();
        AtomicLong clock = new AtomicLong(1000);
        progress.setClockMillis(clock::get);
        progress.setWordSupplier(() -> "Kompiling");

        long seq = progress.begin();
        progress.recordTextDelta("hello world hello world!"); // 24 chars -> 6 est tokens

        ForegroundRequestProgress.Snapshot math = progress.snapshot();
        assertTrue(math.active());
        assertEquals("Kompiling", math.word());
        assertEquals(6, math.tokens());
        assertTrue(math.estimate());
        assertEquals(0, math.elapsedMillis());

        clock.set(5000);
        assertEquals(4000, progress.snapshot().elapsedMillis());

        // New request resets everything and gets a fresh sequence.
        progress.setWordSupplier(() -> "Pondering");
        long seq2 = progress.begin();
        assertNotEquals(seq, seq2);
        ForegroundRequestProgress.Snapshot reset = progress.snapshot();
        assertEquals("Pondering", reset.word());
        assertEquals(0, reset.tokens());
        assertEquals(0, reset.elapsedMillis());
    }

    @Test
    void exactUsageOvertakesEstimateAndClearsTilde() {
        ForegroundRequestProgress progress = new ForegroundRequestProgress();
        progress.begin();

        progress.recordTextDelta("x".repeat(400)); // 100 estimated
        ForegroundRequestProgress.Snapshot estimated = progress.snapshot();
        assertEquals(100, estimated.tokens());
        assertTrue(estimated.estimate());

        // Exact delta of 80 is below the running estimate: estimate still leads.
        progress.recordExactOutput(80);
        assertTrue(progress.snapshot().estimate());

        // Exact delta of 40 more (total 120) overtakes: exact now shown.
        progress.recordExactOutput(40);
        ForegroundRequestProgress.Snapshot exact = progress.snapshot();
        assertEquals(120, exact.tokens());
        assertFalse(exact.estimate());

        // Further text does not drag the shown count backwards.
        progress.recordTextDelta("y".repeat(40)); // +10 estimated = 110 < 120
        assertEquals(120, progress.snapshot().tokens());
        assertFalse(progress.snapshot().estimate());
    }

    @Test
    void finishGuardsAgainstLateEventsFromPreviousRequest() {
        ForegroundRequestProgress progress = guard();
        long seq1 = progress.begin();
        progress.recordTextDelta("z".repeat(80)); // 20 tokens

        progress.finish(seq1);
        assertFalse(progress.snapshot().active());

        // Late event from the finished request is ignored.
        progress.recordTextDelta("late");
        progress.recordExactOutput(500);
        assertFalse(progress.snapshot().active());

        // Next request starts clean and stale-seq finish is a no-op.
        long seq2 = progress.begin();
        assertEquals(0, progress.snapshot().tokens());
        progress.finish(seq2 + 1);
        assertTrue(progress.snapshot().active());
        progress.finish(seq2);
        assertFalse(progress.snapshot().active());
    }

    @Test
    void finishAllClosesAnyActiveRequest() {
        ForegroundRequestProgress progress = guard();
        progress.begin();
        assertTrue(progress.isActive());
        progress.finishAll();
        assertFalse(progress.isActive());
        assertFalse(progress.snapshot().active());
    }

    @Test
    void inactiveSnapshotIsBlank() {
        ForegroundRequestProgress progress = guard();
        ForegroundRequestProgress.Snapshot snap = progress.snapshot();
        assertFalse(snap.active());
        assertEquals(0, snap.tokens());
        assertEquals(0, snap.elapsedMillis());
    }

    private ForegroundRequestProgress guard() {
        return new ForegroundRequestProgress();
    }
}
