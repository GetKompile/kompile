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
    void exactUsageReplacesEstimateAndNextCallAddsItsOwnEstimate() {
        ForegroundRequestProgress progress = new ForegroundRequestProgress();
        progress.begin();

        progress.recordTextDelta("x".repeat(400)); // 100 estimated
        ForegroundRequestProgress.Snapshot estimated = progress.snapshot();
        assertEquals(100, estimated.tokens());
        assertTrue(estimated.estimate());

        // Provider counts are authoritative even when chars/4 overestimated.
        progress.recordExactOutput(80);
        assertEquals(80, progress.snapshot().tokens());
        assertFalse(progress.snapshot().estimate());

        // A second call's text adds to the completed first call, not max(...).
        progress.recordTextDelta("y".repeat(40));
        assertEquals(90, progress.snapshot().tokens());
        assertTrue(progress.snapshot().estimate());
        progress.recordExactOutput(40);
        ForegroundRequestProgress.Snapshot exact = progress.snapshot();
        assertEquals(120, exact.tokens());
        assertFalse(exact.estimate());

        progress.recordTextDelta("z".repeat(40));
        assertEquals(130, progress.snapshot().tokens());
        assertTrue(progress.snapshot().estimate());
        progress.recordExactOutput(0);
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
        progress.recordTextDelta(seq1, "stale text".repeat(100));
        progress.recordExactOutput(seq1, 500);
        assertEquals(0, progress.snapshot().tokens());
        progress.finish(seq2 + 1);
        assertTrue(progress.snapshot().active());
        progress.finish(seq2);
        assertFalse(progress.snapshot().active());
    }

    @Test
    void incompleteUsagePreservesEstimateAcrossLaterCompletedCalls() {
        var progress = guard();
        long seq = progress.begin();
        progress.recordTextDelta("x".repeat(400));
        progress.recordIncompleteOutput(seq, 1);
        assertEquals(100, progress.snapshot().tokens());
        assertTrue(progress.snapshot().estimate());
        progress.recordTextDelta("next");
        progress.recordExactOutput(seq, 2);
        assertEquals(102, progress.snapshot().tokens());
        assertTrue(progress.snapshot().estimate());
        progress.begin();
        progress.recordIncompleteOutput(seq, 999);
        assertEquals(0, progress.snapshot().tokens());
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
