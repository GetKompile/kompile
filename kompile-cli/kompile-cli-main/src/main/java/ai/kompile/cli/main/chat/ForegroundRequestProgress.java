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
 * number; only the matching {@link #finish(long)} closes it. Asynchronous
 * producers capture {@link #sequence()} and pass it with text/usage updates,
 * so late events cannot pollute a newer request.</p>
 *
 * <p>Provider usage deltas arrive at model-call completion and replace that
 * call's text estimate, even when the exact count is lower. Subsequent streamed
 * text is estimated on top of the completed calls' exact output. This prevents
 * a large reasoning-token count from hiding progress on the next call.</p>
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
    private long incompleteOutput = 0;

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
            incompleteOutput = 0;
            return seq;
        }
    }

    /** Sequence to capture when installing asynchronous stream callbacks. */
    public long sequence() {
        synchronized (lock) {
            return seq;
        }
    }

    /** Feed streamed generated text for the current request. */
    public void recordTextDelta(String delta) {
        recordTextDelta(sequence(), delta);
    }

    public void recordTextDelta(long requestSeq, String delta) {
        if (delta == null || delta.isEmpty()) return;
        synchronized (lock) {
            if (active && requestSeq == seq) textChars += delta.length();
        }
    }

    /** Reconcile a completed model call's output usage with its text estimate. */
    public void recordExactOutput(long outputDelta) {
        recordExactOutput(sequence(), outputDelta);
    }

    public void recordExactOutput(long requestSeq, long outputDelta) {
        if (outputDelta < 0) return;
        synchronized (lock) {
            if (active && requestSeq == seq) {
                exactOutput += outputDelta;
                textChars = 0;
            }
        }
    }

    /** Preserve a failed stream's text estimate and provider-reported lower bound. */
    public void recordIncompleteOutput(long requestSeq, long outputLowerBound) {
        synchronized (lock) {
            if (active && requestSeq == seq) {
                incompleteOutput += Math.max(Math.max(0, outputLowerBound),
                        textChars / ESTIMATED_CHARS_PER_TOKEN);
                textChars = 0;
            }
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
            boolean estimate = textChars > 0 || incompleteOutput > 0;
            return new Snapshot(true, word, Math.max(0, now - startMillis),
                    exactOutput + incompleteOutput + estimated, estimate);
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
