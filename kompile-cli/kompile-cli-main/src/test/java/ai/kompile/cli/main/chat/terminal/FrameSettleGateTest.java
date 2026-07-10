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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the extracted settle+dedup gate (WP6). Uses a fake clock advanced by the fake
 * sleeper so the settle window is exercised without real time.
 */
class FrameSettleGateTest {

    /** A clock that advances by whatever the sleeper is asked to sleep. */
    private static final class FakeClock {
        final AtomicLong t = new AtomicLong(0);
        long now() { return t.get(); }
        void advance(long ms) { t.addAndGet(ms); }
    }

    @Test
    void awaitSettleFalseWhenBytesAvailableImmediately() {
        FrameSettleGate gate = new FrameSettleGate();
        FakeClock clock = new FakeClock();
        boolean settled = gate.awaitSettle(() -> true, 90L, clock::now, clock::advance);
        assertFalse(settled, "bytes available up front → not settled, no wait");
        assertTrue(clock.now() == 0, "must not sleep when bytes are already available");
    }

    @Test
    void awaitSettleTrueWhenQuietForWholeWindow() {
        FrameSettleGate gate = new FrameSettleGate();
        FakeClock clock = new FakeClock();
        boolean settled = gate.awaitSettle(() -> false, 90L, clock::now, clock::advance);
        assertTrue(settled, "no bytes for the whole window → settled");
        assertTrue(clock.now() >= 90L, "must have waited at least the settle window");
    }

    @Test
    void awaitSettleFalseWhenBytesAppearMidWindow() {
        FrameSettleGate gate = new FrameSettleGate();
        FakeClock clock = new FakeClock();
        // Available becomes true once the clock passes 30ms.
        BooleanSupplier hasBytes = () -> clock.now() >= 30L;
        boolean settled = gate.awaitSettle(hasBytes, 90L, clock::now, clock::advance);
        assertFalse(settled, "bytes appearing mid-window → not settled");
    }

    @Test
    void shouldRenderWhenSettledAndScreenChanged() {
        FrameSettleGate gate = new FrameSettleGate();
        assertTrue(gate.shouldRender(true, 111L, 1000L, 1200L), "settled + new hash renders");
        // Same hash again → deduped even though settled.
        assertFalse(gate.shouldRender(true, 111L, 1050L, 1200L), "same hash is deduped");
    }

    @Test
    void shouldRenderOnTimeCapEvenWhenNotSettled() {
        FrameSettleGate gate = new FrameSettleGate();
        assertTrue(gate.shouldRender(true, 1L, 0L, 1200L), "first frame renders");
        // Not settled, but the time cap has elapsed and the hash changed.
        assertTrue(gate.shouldRender(false, 2L, 1300L, 1200L), "time cap forces a render");
    }

    @Test
    void shouldNotRenderWhenNotSettledAndWithinTimeCap() {
        FrameSettleGate gate = new FrameSettleGate();
        assertTrue(gate.shouldRender(true, 1L, 0L, 1200L));
        assertFalse(gate.shouldRender(false, 2L, 500L, 1200L),
                "not settled and within the time cap → hold the frame");
    }

    @Test
    void forceNextRenderOverridesHashDedup() {
        FrameSettleGate gate = new FrameSettleGate();
        assertTrue(gate.shouldRender(true, 42L, 0L, 1200L));
        assertFalse(gate.shouldRender(true, 42L, 10L, 1200L), "same hash deduped");
        gate.forceNextRender();
        assertTrue(gate.shouldRender(true, 42L, 20L, 1200L), "forceNextRender re-renders same hash");
    }
}
