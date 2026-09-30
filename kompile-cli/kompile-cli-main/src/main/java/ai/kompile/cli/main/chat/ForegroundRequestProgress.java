/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Request-scoped progress for the one active foreground chat request:
 * a working word, live generated-token progress, and elapsed time.
 * Rendered by the StatusBar foreground segment (and the legacy spinner);
 * never written to the transcript.
 *
 * <p>Lifecycle: {@link #begin()} opens a request and returns its sequence
 * number; only the matching {@link #finish(long)} closes it. Text/usage
 * deltas attach to whatever request is currently active, so late events from
 * a finished turn are ignored and cannot pollute the next one.</p>
 *
 * <p>Token display rule: shown count is {@code max(exactOutput, chars/4)}.
 * Estimates stream in from generated text; provider usage deltas arrive at
 * model-call completion and are monotonic against the estimate, so the exact
 * figure simply overtakes it — no double counting, no per-call attribution.
 * The estimate flag is true only while the estimate still leads.</p>
 */
public final class ForegroundRequestProgress {

    /** Working words shown while a request runs. "Kompiling" honours the old default. */
    public static final String[] WORK_WORDS = {
            "Kompiling", "Pondering", "Brewing", "Cogitating", "Deliberating",
            "Percolating", "Synthesizing", "Contemplating", "Mulling", "Reasoning"
    };

    /** Immutable view rendered by UI surfaces. */
    public record Snapshot(boolean active, String word, long elapsedMillis,
                           long tokens, boolean estimate) {
        public static Snapshot inactive() {
            return new Snapshot(false, null, 0, 0, false);
        }
    }

    private static final int ESTIMATED_CHARS_PER_TOKEN = 4;

    private final Object lock = new Object();
    private long seq = 0;
    private boolean active = false;
    private String word = null;
    private long startMillis = 0;
    private long textChars = 0;
    private long exactOutput = 0;

    private LongSupplier clockMillis = System::currentTimeMillis;
    private Supplier<String> wordSupplier =
            () -> WORK_WORDS[ThreadLocalRandom.current().nextInt(WORK_WORDS.length)];

    /**
     * Start a new request. Cancels any still-active previous request and
     * resets all counters. Returns the sequence number guarding this request.
     */
    public long begin() {
        synchronized (lock) {
            seq++;
            active = true;
            word = wordSupplier.get();
            startMillis = clockMillis.getAsLong();
            textChars = 0;
            exactOutput = 0;
            return seq;
        }
    }

    /** Feed streamed generated text (visible model output) for the active request. */
    public void recordTextDelta(String delta) {
        if (delta == null || delta.isEmpty()) return;
        synchronized (lock) {
            if (active) textChars += delta.length();
        }
    }

    /** Feed a provider-reported output-token delta for the active request. */
    public void recordExactOutput(long outputDelta) {
        if (outputDelta <= 0) return;
        synchronized (lock) {
            if (active) exactOutput += outputDelta;
        }
    }

    /** Finish the request; no-op when a newer request has already taken over. */
    public void finish(long requestSeq) {
        synchronized (lock) {
            if (active && requestSeq == seq) {
                active = false;
            }
        }
    }

    /** Close any active request unconditionally (session close / recovery). */
    public void finishAll() {
        synchronized (lock) {
            active = false;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            if (!active) return Snapshot.inactive();
            long now = clockMillis.getAsLong();
            long estimated = textChars / ESTIMATED_CHARS_PER_TOKEN;
            boolean estimate = estimated > exactOutput;
            return new Snapshot(true, word, Math.max(0, now - startMillis),
                    Math.max(exactOutput, estimated), estimate);
        }
    }

    public boolean isActive() {
        synchronized (lock) {
            return active;
        }
    }

    // ── Test seams ──────────────────────────────────────────────────────────

    public void setClockMillis(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    public void setWordSupplier(Supplier<String> wordSupplier) {
        this.wordSupplier = wordSupplier;
    }
}
