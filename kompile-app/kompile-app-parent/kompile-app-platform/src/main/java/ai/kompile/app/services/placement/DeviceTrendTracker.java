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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-device VRAM trend + saturation forecast. The real impl is fed one sample per device per
 * telemetry tick (from {@code ResourceTelemetryService}); it keeps a bounded ring buffer and fits a
 * least-squares line over {@code freeBytes(t)} to estimate "device N saturates in T ms".
 *
 * <p>Deterministic + unit-testable: sample timestamps are passed in (no {@code System.currentTimeMillis}
 * in the math), so tests can replay recorded trends. A deadband suppresses noise: a |slope| below
 * {@link #minSlopeBytesPerMs} reports steady state (no false strand-guard trips).</p>
 */
public class DeviceTrendTracker implements CapacityForecastProvider {

    private record Sample(long tMs, long freeBytes) {}

    private final int window;              // ring-buffer depth (e.g. 60 = 60s at 1s poll)
    private final double minSlopeBytesPerMs; // deadband; below this |slope| → steady

    private final Map<Integer, Deque<Sample>> byDevice = new ConcurrentHashMap<>();

    public DeviceTrendTracker() { this(60, 100.0 * 1024 * 1024 / 1000.0); } // 60 samples, 100 MB/s deadband

    public DeviceTrendTracker(int window, double minSlopeBytesPerMs) {
        this.window = Math.max(2, window);
        this.minSlopeBytesPerMs = minSlopeBytesPerMs;
    }

    /** Record a telemetry sample. Timestamp is supplied (testable); real caller passes the poll time. */
    public void record(int deviceId, long freeBytes, long timestampMs) {
        Deque<Sample> ring = byDevice.computeIfAbsent(deviceId, k -> new ArrayDeque<>());
        synchronized (ring) {
            ring.addLast(new Sample(timestampMs, freeBytes));
            while (ring.size() > window) ring.removeFirst();
        }
    }

    @Override
    public DeviceCapacityForecast forecast() {
        Map<Integer, DeviceCapacityForecast.DeviceForecast> out = new HashMap<>();
        for (Map.Entry<Integer, Deque<Sample>> e : byDevice.entrySet()) {
            out.put(e.getKey(), forecastDevice(e.getKey(), e.getValue()));
        }
        return new DeviceCapacityForecast(out);
    }

    private DeviceCapacityForecast.DeviceForecast forecastDevice(int deviceId, Deque<Sample> ring) {
        Sample[] samples;
        synchronized (ring) { samples = ring.toArray(new Sample[0]); }
        if (samples.length < 2) {
            long free = samples.length == 1 ? samples[0].freeBytes() : Long.MAX_VALUE;
            return new DeviceCapacityForecast.DeviceForecast(deviceId, Long.MAX_VALUE, free, 0.0);
        }

        // Least-squares slope of freeBytes over time (bytes per ms).
        long t0 = samples[0].tMs();
        double n = samples.length, sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (Sample s : samples) {
            double x = s.tMs() - t0, y = s.freeBytes();
            sx += x; sy += y; sxx += x * x; sxy += x * y;
        }
        double denom = n * sxx - sx * sx;
        double slope = denom == 0 ? 0.0 : (n * sxy - sx * sy) / denom; // bytes/ms; negative = filling
        long freeNow = samples[samples.length - 1].freeBytes();

        // Deadband: treat near-flat / draining trends as steady (never trips the strand guard).
        if (slope >= 0 || Math.abs(slope) < minSlopeBytesPerMs) {
            return new DeviceCapacityForecast.DeviceForecast(deviceId, Long.MAX_VALUE, freeNow, slope);
        }
        long saturatesInMs = (long) (freeNow / -slope);        // free / consumption-rate
        long projectedFree = 0L;                                // full at the horizon
        return new DeviceCapacityForecast.DeviceForecast(deviceId, saturatesInMs, projectedFree, slope);
    }
}
