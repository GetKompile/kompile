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

import java.util.List;
import java.util.Map;

/**
 * Reusable device-agnostic placement state for ANY subprocess launcher — base-derived or standalone.
 * This is the shared base infra so backend/device/memory delivery is uniform across every subprocess
 * type (embedding, serving, training, vlm, ingest, vector-population, model-init, pipeline, graph,
 * learning), never re-implemented per launcher.
 *
 * <p>A standalone launcher composes one of these, implements {@link BackendConfigurable} by delegating
 * {@link #applyPlacement}, and — at its command/env build site — calls {@link #jvmFlags()} and
     * {@link #applyEnv(Map)}. All translation goes through {@link SubprocessBackendFlags}: real ND4J knobs
     * only ({@code org.nd4j.{cpu,gpu}.priority}, {@code nd4j.placement.defaultDevice},
     * {@code nd4j.environment.maxDeviceMemory}, plus {@code SD_MAX_DEVICE_BYTES} for early native
     * process setup) — <b>never {@code CUDA_VISIBLE_DEVICES} or any vendor env var</b>.</p>
 */
public final class SubprocessPlacementSupport implements BackendConfigurable {

    private volatile SubprocessPlacement placement;
    private final BackendPreference fallback;

    /** Fallback backend {@link BackendPreference#INHERIT} (emit nothing) when no placement is set. */
    public SubprocessPlacementSupport() {
        this(BackendPreference.INHERIT);
    }

    /** @param fallback backend used when the scheduler has not assigned a placement (null → INHERIT). */
    public SubprocessPlacementSupport(BackendPreference fallback) {
        this.fallback = fallback != null ? fallback : BackendPreference.INHERIT;
    }

    /** {@link BackendConfigurable} — the scheduler sets the device-agnostic placement before spawn. */
    @Override
    public void applyPlacement(SubprocessPlacement placement) {
        this.placement = placement;
    }

    public SubprocessPlacement placement() {
        return placement;
    }

    public boolean hasPlacement() {
        return placement != null;
    }

    /** Device-agnostic JVM flags: backend priority, GPU device pin, and physical memory cap. */
    public List<String> jvmFlags() {
        return SubprocessBackendFlags.jvmFlags(placement, fallback);
    }

    /** Apply the early native per-device memory bound ({@code SD_MAX_DEVICE_BYTES}) to a child env. */
    public void applyEnv(Map<String, String> env) {
        SubprocessBackendFlags.applyEnv(env, placement);
    }

    /** The effective backend (placement wins, else the fallback). */
    public BackendPreference effectiveBackend() {
        return SubprocessBackendFlags.effectiveBackend(placement, fallback);
    }
}
