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

/**
 * Contract for a subprocess launcher whose backend/device/memory placement can be set by the
 * scheduler before spawn. Every launcher (all 10 subprocess types) implements this via the common
 * {@link ManagedSubprocessLauncher} base, so device/backend/memory delivery is uniform — no
 * per-type one-off wiring, and no {@code CUDA_VISIBLE_DEVICES} anywhere.
 */
public interface BackendConfigurable {

    /** Assign the placement to apply on the next spawn. Null clears any prior assignment. */
    void applyPlacement(SubprocessPlacement placement);
}
