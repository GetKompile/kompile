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

/**
 * A model's device-memory characteristics, independent of any device. Produced by a
 * {@link ModelResourceCatalog} (real impl: staging catalog + measured footprints).
 *
 * @param modelId            catalog id (e.g. {@code bge-base-en-v1.5})
 * @param trueFootprintBytes the memory the model actually reserves at peak (NOT a hoped-for cap;
 *                           for the bge encoder this is the ~22 GB warmup-capture footprint —
 *                           bounding it below this spills to host)
 * @param cpuCapable         whether the model can run on the CPU backend at acceptable quality
 * @param maxedOut           true when {@code trueFootprintBytes} is a whole-device-class footprint —
 *                           the scheduler must give it a whole device, bound-and-spill, or route away,
 *                           never silently co-tenant it
 */
public record ModelResourceProfile(
        String modelId,
        long trueFootprintBytes,
        boolean cpuCapable,
        boolean maxedOut
) {
    public static ModelResourceProfile of(String modelId, long trueFootprintBytes, boolean cpuCapable) {
        return new ModelResourceProfile(modelId, trueFootprintBytes, cpuCapable, false);
    }
}
