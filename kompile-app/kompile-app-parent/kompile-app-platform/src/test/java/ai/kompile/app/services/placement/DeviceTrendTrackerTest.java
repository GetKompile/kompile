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

package ai.kompile.app.services.placement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceTrendTrackerTest {

    private static final long GB = 1L << 30;

    @Test
    void fillingTrend_forecastsFiniteSaturation() {
        DeviceTrendTracker t = new DeviceTrendTracker(60, 100.0 * 1024 * 1024 / 1000.0);
        // Device 0 draining 1 GB/s from 10 GB free over 5 samples (1s apart).
        for (int i = 0; i < 5; i++) {
            t.record(0, (10 - i) * GB, i * 1000L);
        }
        var f = t.forecast().forDevice(0);
        assertTrue(f.consumptionRateBytesPerMs() < 0, "should detect draining (negative slope)");
        // ~6 GB free at last sample / ~1 GB/s ≈ 6000 ms.
        assertTrue(f.estimatedSaturatesInMs() > 4_000 && f.estimatedSaturatesInMs() < 8_000,
                "saturates ~6s, got " + f.estimatedSaturatesInMs());
    }

    @Test
    void flatTrend_reportsSteady() {
        DeviceTrendTracker t = new DeviceTrendTracker(60, 100.0 * 1024 * 1024 / 1000.0);
        for (int i = 0; i < 5; i++) {
            t.record(1, 20 * GB, i * 1000L); // dead flat
        }
        var f = t.forecast().forDevice(1);
        assertEquals(Long.MAX_VALUE, f.estimatedSaturatesInMs(), "flat trend must not trip the strand guard");
    }

    @Test
    void slowDrain_belowDeadband_reportsSteady() {
        DeviceTrendTracker t = new DeviceTrendTracker(60, 100.0 * 1024 * 1024 / 1000.0);
        // Drain only 10 MB/s — below the 100 MB/s deadband → treated as steady.
        for (int i = 0; i < 5; i++) {
            t.record(2, 20 * GB - (long) i * 10 * 1024 * 1024, i * 1000L);
        }
        assertEquals(Long.MAX_VALUE, t.forecast().forDevice(2).estimatedSaturatesInMs());
    }

    @Test
    void unknownDevice_defaultsToSteady() {
        DeviceTrendTracker t = new DeviceTrendTracker();
        assertEquals(Long.MAX_VALUE, t.forecast().forDevice(99).estimatedSaturatesInMs());
    }
}
