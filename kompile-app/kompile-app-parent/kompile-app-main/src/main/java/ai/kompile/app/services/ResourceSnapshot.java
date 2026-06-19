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

package ai.kompile.app.services;

import java.util.List;

/**
 * Immutable point-in-time view of host resource pressure, published by
 * {@link ResourceTelemetryService} and consumed by {@link ResourceGovernor}.
 *
 * <p>One instance is produced per poll cycle and stored in a single {@code volatile} field — a
 * single writer (the telemetry thread) and many lock-free readers (crawl/scheduler threads). Because
 * the record and its nested {@link GpuSnapshot} list are immutable, any reader sees a fully consistent
 * snapshot without locking.</p>
 *
 * <p>All fractional fields are in {@code [0.0, 1.0]} except {@code systemCpuLoad}, which is {@code -1.0}
 * when the JVM cannot report CPU load (treated as NOMINAL).</p>
 */
public record ResourceSnapshot(
        long capturedAtNanos,
        double systemCpuLoad,          // 0..1, or -1.0 if unavailable
        double systemRamUsedFraction,  // 0..1
        double jvmHeapUsedFraction,    // 0..1
        double nativeOffHeapFraction,  // 0..1 (0 on app-main; crawl side tracks JavaCPP native separately)
        PressureLevel cpuPressure,
        PressureLevel ramPressure,
        PressureLevel heapPressure,
        PressureLevel nativePressure,
        List<GpuSnapshot> gpus,
        boolean gpuBackendAvailable) {

    /** Ordered severity of a single resource dimension. */
    public enum PressureLevel {
        NOMINAL, ELEVATED, HIGH, CRITICAL;

        /** True when this level is at least as severe as {@code other}. */
        public boolean atLeast(PressureLevel other) {
            return this.ordinal() >= other.ordinal();
        }
    }

    /** Per-GPU VRAM view. {@code usedFraction} is the conservative max of actual and reserved usage. */
    public record GpuSnapshot(
            int deviceIndex,
            long liveFreeBytes,
            long totalBytes,
            long reservedBytes,
            double usedFraction,        // 0..1, max(actual-used, reserved)/total
            PressureLevel vramPressure) {
    }

    /** Worst (highest) VRAM used fraction across all devices, or 0 when no GPU. */
    public double worstGpuUsedFraction() {
        double worst = 0.0;
        for (GpuSnapshot g : gpus) {
            worst = Math.max(worst, g.usedFraction());
        }
        return worst;
    }

    /** Worst (most severe) VRAM pressure across all devices, or NOMINAL when no GPU. */
    public PressureLevel worstGpuPressure() {
        PressureLevel worst = PressureLevel.NOMINAL;
        for (GpuSnapshot g : gpus) {
            if (g.vramPressure().atLeast(worst)) {
                worst = g.vramPressure();
            }
        }
        return worst;
    }

    /** Safe sentinel used when telemetry has not yet run or is unavailable (CPU-only, errors). */
    public static ResourceSnapshot unavailable() {
        return new ResourceSnapshot(0L, -1.0, 0.0, 0.0, 0.0,
                PressureLevel.NOMINAL, PressureLevel.NOMINAL, PressureLevel.NOMINAL, PressureLevel.NOMINAL,
                List.of(), false);
    }
}
