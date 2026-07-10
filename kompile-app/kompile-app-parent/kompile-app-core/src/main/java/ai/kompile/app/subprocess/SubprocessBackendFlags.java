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

package ai.kompile.app.subprocess;

import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;
import org.nd4j.common.config.ND4JSystemProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The ONE device-agnostic backend/device/memory delivery for every subprocess — base-derived and
 * standalone alike. Both {@link ManagedSubprocessLauncher} and each standalone launcher route through
 * here so a {@link SubprocessPlacement} is turned into JVM flags + env identically, with no per-type
 * one-off wiring and — the hard mandate — <b>never {@code CUDA_VISIBLE_DEVICES} or any vendor knob</b>.
 *
 * <p>Real ND4J knobs only:
 * <ul>
 *   <li><b>Backend select</b> — {@code org.nd4j.cpu.priority} / {@code org.nd4j.gpu.priority}
 *       (ServiceLoader ordering read by {@code Nd4jBackend.load()}; {@code nd4j.backend.priority} is inert).</li>
 *   <li><b>Device pin</b> — {@code nd4j.placement.defaultDevice} (read by {@code DevicePlacementPlanner}).</li>
 *   <li><b>Per-device memory bound</b> — {@code nd4j.environment.maxDeviceMemory} for the physical
 *       {@code Nd4j.getEnvironment().setMaxDeviceMemory(...)} path, plus {@code SD_MAX_DEVICE_BYTES}
 *       as the early native-process bridge read by the CUDA pool.</li>
 * </ul>
 */
public final class SubprocessBackendFlags {

    /** The child-process env var the ND4J memory pool reads before Java-side environment config is applied. */
    public static final String MAX_DEVICE_BYTES_ENV = "SD_MAX_DEVICE_BYTES";

    /** The system property child startup code applies to {@code Nd4j.getEnvironment().setMaxDeviceMemory}. */
    public static final String MAX_DEVICE_MEMORY_PROPERTY = ND4JSystemProperties.ENV_MAX_DEVICE_MEMORY;

    private SubprocessBackendFlags() {
    }

    /** Placement wins; else the launcher's static declaration ({@code null} fallback → INHERIT). */
    public static BackendPreference effectiveBackend(SubprocessPlacement placement, BackendPreference fallback) {
        if (placement != null) {
            return placement.backend();
        }
        return fallback != null ? fallback : BackendPreference.INHERIT;
    }

    /**
     * Device-agnostic JVM flags for backend selection, device pin, and the Java-side physical device
     * memory cap. The native-process cap is still delivered through {@link #applyEnv(Map, SubprocessPlacement)}.
     */
    public static List<String> jvmFlags(SubprocessPlacement placement, BackendPreference fallback) {
        List<String> flags = new ArrayList<>();
        switch (effectiveBackend(placement, fallback)) {
            case CPU -> {
                flags.add("-Dorg.nd4j.cpu.priority=1000");
                flags.add("-Dorg.nd4j.gpu.priority=0");
            }
            case GPU -> {
                flags.add("-Dorg.nd4j.gpu.priority=1000");
                flags.add("-Dorg.nd4j.cpu.priority=0");
            }
            case INHERIT -> { /* emit nothing — legacy tie-break behaviour */ }
        }
        if (placement != null && placement.isGpu()) {
            flags.add("-Dnd4j.placement.defaultDevice=" + placement.deviceId());
        }
        if (placement != null && placement.maxDeviceMemoryBytes() > 0) {
            flags.add("-D" + MAX_DEVICE_MEMORY_PROPERTY + "=" + placement.maxDeviceMemoryBytes());
        }
        return flags;
    }

    /**
     * Apply the early native per-device memory bound to a child-process environment. No-op when the
     * placement is null or unbounded ({@code maxDeviceMemoryBytes <= 0}).
     */
    public static void applyEnv(Map<String, String> env, SubprocessPlacement placement) {
        if (placement != null && placement.maxDeviceMemoryBytes() > 0) {
            env.put(MAX_DEVICE_BYTES_ENV, Long.toString(placement.maxDeviceMemoryBytes()));
        }
    }
}
