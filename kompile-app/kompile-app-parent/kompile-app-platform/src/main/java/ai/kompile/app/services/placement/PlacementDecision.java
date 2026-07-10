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
 * The result of one placement decision — the pure output of the engine. Applied device-agnostically
 * by {@code PlacementApplier} ({@code DeviceMemoryManager.switchDevice}/{@code setMemoryCap},
 * {@code org.nd4j.{cpu,gpu}.priority}); NEVER via {@code CUDA_VISIBLE_DEVICES}.
 *
 * @param route            CLI agent vs local model
 * @param cliAgent         agent name when {@code route == CLI}, else null
 * @param deviceId         ND4J device index when {@code route == LOCAL} (device-agnostic;
 *                        {@link DeviceInfo#CPU_DEVICE_ID} = CPU), else null
 * @param regime           applied memory-fit regime (NA when routed to CLI)
 * @param memoryBoundBytes per-device cap to apply via {@code setMemoryCap} (0 = unbounded /
 *                        comfortable-fit / whole-device); overflow past a bound spills to host
 * @param score            engine score of the winning candidate (higher is better)
 * @param rationale        human-readable why (for logs + the reasoning trail)
 */
public record PlacementDecision(
        RouteKind route,
        String cliAgent,
        Integer deviceId,
        MemoryRegime regime,
        long memoryBoundBytes,
        double score,
        String rationale
) {

    public enum RouteKind { CLI, LOCAL }

    /** How local device memory is granted; see the plan's memory-fit regimes. */
    public enum MemoryRegime {
        /** Footprint ≤ headroom; no bound, full speed. */
        COMFORTABLE_FIT,
        /** Capped to a fraction so co-tenants fit; footprint ≤ cap so no spill. */
        BOUNDED_RATIO,
        /** Maxed-out workload granted the whole device; no bound. */
        WHOLE_DEVICE,
        /** Maxed-out workload bounded below its footprint; overflow spills to host (slow). */
        BOUNDED_SPILL,
        /** Not applicable — routed to a CLI agent. */
        NA
    }

    public boolean isCli() { return route == RouteKind.CLI; }
    public boolean isLocal() { return route == RouteKind.LOCAL; }

    public static PlacementDecision cli(String agent, double score, String rationale) {
        return new PlacementDecision(RouteKind.CLI, agent, null, MemoryRegime.NA, 0L, score, rationale);
    }

    public static PlacementDecision local(int deviceId, MemoryRegime regime, long boundBytes,
                                          double score, String rationale) {
        return new PlacementDecision(RouteKind.LOCAL, null, deviceId, regime, boundBytes, score, rationale);
    }
}
