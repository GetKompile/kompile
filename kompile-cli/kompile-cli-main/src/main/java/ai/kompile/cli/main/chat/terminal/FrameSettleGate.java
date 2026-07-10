/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.terminal;

import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * The settle-and-dedup gate for decoder-owned (non-raw) rendering, extracted from the god-class
 * output pump (design WP6, {@code EmulatedPassthroughCommand} ~:3178-3205).
 *
 * <p>A single agent repaint can arrive across several PTY reads, so rendering on
 * {@code available()==0} alone catches mid-redraw frames (jumbled, different every tick). This gate
 * waits for the stream to SETTLE (fully drained AND quiet for a short window), then renders one
 * coherent frame — skipping no-op repaints by screen hash, with a time cap so the UI stays live if
 * a stream never pauses. Mirror mode uses a longer window/cap (it blits verbatim and must never
 * capture a mid-redraw frame); decoded mode filters jumbles downstream so it can be snappier.</p>
 *
 * <p>The gate is deliberately I/O-free and clock-injectable so it is unit-testable; the live pump
 * passes a real availability probe and {@link Thread#sleep}.</p>
 */
public final class FrameSettleGate {

    /** Mirror render windows: 90 ms settle, 1200 ms time cap (verbatim blit — must be coherent). */
    public static final long MIRROR_SETTLE_MS = 90L;
    public static final long MIRROR_TIME_CAP_MS = 1200L;

    /** Decoded render windows: 60 ms settle, 400 ms time cap (jumbles filtered downstream). */
    public static final long DECODED_SETTLE_MS = 60L;
    public static final long DECODED_TIME_CAP_MS = 400L;

    private long lastRenderAt = 0L;
    private long lastHash = Long.MIN_VALUE;

    /**
     * Wait up to {@code windowMs} for the stream to go quiet. Returns {@code true} if it settled
     * (was empty and stayed empty for the window), {@code false} if bytes were/became available.
     *
     * @param hasBytes probe for "bytes are available now"
     * @param windowMs the settle window
     * @param clockMs  monotonic-ish millisecond clock (injected for testing)
     * @param sleepMs  pause between polls (injected for testing; live pump passes Thread.sleep)
     */
    public boolean awaitSettle(BooleanSupplier hasBytes, long windowMs, LongSupplier clockMs, LongConsumer sleepMs) {
        if (hasBytes.getAsBoolean()) return false;
        long start = clockMs.getAsLong();
        while (clockMs.getAsLong() - start < windowMs) {
            if (hasBytes.getAsBoolean()) return false;
            sleepMs.accept(10L);
        }
        return true;
    }

    /**
     * Live convenience: real {@link System#currentTimeMillis()} clock + {@link Thread#sleep}. If the
     * pump thread is interrupted mid-settle this returns {@code true} (treat the partial wait as
     * settled and let the outer read loop exit) — matching the legacy {@code break} semantics.
     */
    public boolean awaitSettle(BooleanSupplier hasBytes, long windowMs) {
        try {
            return awaitSettle(hasBytes, windowMs, System::currentTimeMillis, ms -> {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SettleInterrupted();
                }
            });
        } catch (SettleInterrupted e) {
            return true;
        }
    }

    /**
     * Decide whether to render this frame and, if so, record it. Renders when the frame is
     * {@code settled} OR the time cap since the last render has elapsed, AND the screen actually
     * changed since the last rendered frame (dedup by hash).
     */
    public boolean shouldRender(boolean settled, long screenHash, long nowMs, long timeCapMs) {
        if (!(settled || nowMs - lastRenderAt >= timeCapMs)) return false;
        if (screenHash == lastHash) return false;
        lastHash = screenHash;
        lastRenderAt = nowMs;
        return true;
    }

    /** Force the next frame to render regardless of hash (e.g. after a mode switch / full repaint). */
    public void forceNextRender() {
        lastHash = Long.MIN_VALUE;
    }

    public void reset() {
        lastRenderAt = 0L;
        lastHash = Long.MIN_VALUE;
    }

    /** Internal signal: the pump thread was interrupted mid-settle. Caught by the live overload. */
    private static final class SettleInterrupted extends RuntimeException {
    }
}
