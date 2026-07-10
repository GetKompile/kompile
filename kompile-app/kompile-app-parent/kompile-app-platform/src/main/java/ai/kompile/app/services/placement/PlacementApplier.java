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

import ai.kompile.app.subprocess.BackendConfigurable;
import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;
import ai.kompile.app.subprocess.SubprocessPlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a pure {@link PlacementDecision} into device-agnostic effect. The pure policy never touches
 * these; this is the only place device/memory knobs are actually set — via ND4J's real mechanisms
 * ({@code SubprocessPlacement} → {@code org.nd4j.{cpu,gpu}.priority} / {@code nd4j.placement.defaultDevice}
 * / {@code nd4j.environment.maxDeviceMemory} plus {@code SD_MAX_DEVICE_BYTES} for subprocesses;
 * {@code DeviceMemoryManager.switchDevice}/{@code setMemoryCap} in-process). NEVER {@code CUDA_VISIBLE_DEVICES}.
 */
public final class PlacementApplier {

    private static final Logger log = LoggerFactory.getLogger(PlacementApplier.class);

    /** Pure mapping (testable): a LOCAL decision → the launcher-side placement; CLI → null. */
    public static SubprocessPlacement toSubprocessPlacement(PlacementDecision d) {
        if (d == null || d.isCli() || d.deviceId() == null) {
            return null; // CLI route (or no local target) — nothing for a launcher to apply
        }
        int deviceId = d.deviceId();
        if (deviceId == SubprocessPlacement.CPU_DEVICE_ID) {
            return SubprocessPlacement.cpu();
        }
        return new SubprocessPlacement(BackendPreference.GPU, deviceId, d.memoryBoundBytes());
    }

    /**
     * Deliver a LOCAL decision to a launcher (applied on its next spawn). Returns true if a local
     * placement was applied; false for CLI routes (the CLI dispatch path handles those).
     */
    public boolean applyToLauncher(PlacementDecision d, BackendConfigurable launcher) {
        SubprocessPlacement spec = toSubprocessPlacement(d);
        if (spec == null) {
            return false;
        }
        launcher.applyPlacement(spec);
        log.debug("[placement-apply] launcher ← backend={}, device={}, boundMB={}",
                spec.backend(), spec.deviceId(), spec.maxDeviceMemoryBytes() / (1024 * 1024));
        return true;
    }

    /**
     * Apply a LOCAL decision to the CURRENT JVM (main-app own-device work) via ND4J's device-agnostic
     * {@code DeviceMemoryManager}. Best-effort + guarded: a missing/uninitialised backend must never
     * throw into the caller. No-op for CLI routes.
     */
    public void applyInProcess(PlacementDecision d) {
        if (d == null || d.isCli() || d.deviceId() == null) {
            return;
        }
        try {
            org.nd4j.linalg.api.device.DeviceMemoryManager mgr =
                    org.nd4j.linalg.api.device.DeviceMemoryManager.getInstance();
            int deviceId = d.deviceId();
            mgr.switchDevice(deviceId, "PlacementApplier", d.rationale());
            if (d.memoryBoundBytes() > 0) {
                org.nd4j.linalg.api.device.DeviceDescriptor desc =
                        deviceId == SubprocessPlacement.CPU_DEVICE_ID
                                ? org.nd4j.linalg.api.device.DeviceDescriptor.cpu()
                                : org.nd4j.linalg.api.device.DeviceDescriptor.cuda(deviceId);
                mgr.setMemoryCap(desc, d.memoryBoundBytes());
            }
        } catch (Throwable t) {
            log.warn("[placement-apply] in-process apply skipped (backend unavailable): {}", t.getMessage());
        }
    }
}
