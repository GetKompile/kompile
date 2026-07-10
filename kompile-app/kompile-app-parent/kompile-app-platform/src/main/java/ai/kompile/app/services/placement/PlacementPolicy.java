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

import ai.kompile.app.services.placement.PlacementDecision.MemoryRegime;
import ai.kompile.app.services.placement.ThroughputOracle.ExecTarget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The PURE placement decision — no side effects, no live ND4J/nvidia/CLI calls, deterministic given
 * its inputs. Scores every candidate {CLI agent} ∪ {local device × memory-regime} on throughput,
 * memory-fit (with a host-spill penalty for bounding a maxed-out footprint), and a forecast
 * strand-guard, then returns the winner. Applied device-agnostically by a separate effector.
 *
 * <p>All tuning is in {@link Tuning} so tests pin behaviour; production overrides from config.</p>
 */
public final class PlacementPolicy {

    /** Tunables (production wires these from {@code ResourceSchedulerConfig}; defaults are sane). */
    public record Tuning(
            double headroomFraction,          // usable fraction of a device's FREE memory
            double boundedRatioFraction,      // per-tenant cap as a fraction of TOTAL when sharing
            double hostSpillThroughputFactor, // throughput multiplier when overflow spills to host
            double cpuThroughputFactor,       // fallback multiplier already baked into the CPU rate
            long forecastStrandHeadroomMs     // skip a device forecast to saturate within this window
    ) {
        public static Tuning defaults() {
            return new Tuning(0.90, 0.50, 0.35, 1.0, 10_000L);
        }
    }

    private final Tuning tuning;

    public PlacementPolicy() { this(Tuning.defaults()); }
    public PlacementPolicy(Tuning tuning) { this.tuning = tuning; }

    private record Candidate(PlacementDecision decision, double score) {}

    /**
     * Decide where {@code req} runs. {@code model} may be null (a GPU step with no specific model —
     * the job profile's peak GPU bytes is used as the footprint).
     */
    public PlacementDecision decide(WorkloadRequest req,
                                    DeviceInventorySnapshot devices,
                                    ModelResourceProfile model,
                                    CliAgentStateSnapshot cli,
                                    DeviceCapacityForecast forecast,
                                    ThroughputOracle throughput) {
        // 1. CPU-only workloads never touch a device decision.
        if (req.profile() != null && !req.profile().requiresGpu()) {
            return PlacementDecision.local(DeviceInfo.CPU_DEVICE_ID, MemoryRegime.COMFORTABLE_FIT, 0L,
                    throughput.ratePerSec(req.serviceType(), ExecTarget.LOCAL_CPU),
                    "CPU-only workload (profile.requiresGpu=false)");
        }

        long footprint = footprintBytes(req, model);
        List<Candidate> candidates = new ArrayList<>();

        // 2a. CLI candidates (only for CLI-routable task kinds with a viable agent).
        if (req.isCliRoutable()) {
            for (CliAgentState agent : cli.viable()) {
                double rate = agent.tokensPerSec() > 0
                        ? agent.tokensPerSec()
                        : throughput.ratePerSec(req.serviceType(), ExecTarget.CLI);
                double score = rate - costPenalty(agent);
                candidates.add(new Candidate(
                        PlacementDecision.cli(agent.agentName(), score,
                                "CLI route: " + agent.agentName() + " (rate=" + fmt(rate)
                                        + ", quota=" + agent.quotaRemaining() + ")"),
                        score));
            }
        }

        // 2b. Local GPU candidates, one per device (strand-guarded).
        List<DeviceInfo> gpus = devices.gpus();
        boolean anyGpuFeasible = false;
        for (DeviceInfo dev : gpus) {
            Candidate c = localGpuCandidate(req, dev, footprint, model, forecast, throughput, gpus.size());
            if (c != null) {
                candidates.add(c);
                anyGpuFeasible = true;
            }
        }
        // If every GPU was strand-skipped, relax the guard rather than fail (pick least-bad).
        if (!anyGpuFeasible && !gpus.isEmpty()) {
            for (DeviceInfo dev : gpus) {
                candidates.add(localGpuCandidate(req, dev, footprint, model, forecast, throughput, /*ignoreStrand*/ -1));
            }
        }

        // 2c. CPU fallback for a GPU workload whose model can run on CPU.
        if (model == null || model.cpuCapable()) {
            double rate = throughput.ratePerSec(req.serviceType(), ExecTarget.LOCAL_CPU);
            candidates.add(new Candidate(
                    PlacementDecision.local(DeviceInfo.CPU_DEVICE_ID, MemoryRegime.COMFORTABLE_FIT, 0L, rate,
                            "CPU fallback (cpuCapable)"),
                    rate));
        }

        // 3. Pick the max-score candidate; deterministic tiebreak keeps tests stable.
        return candidates.stream()
                .max(Comparator.comparingDouble(Candidate::score)
                        .thenComparing(c -> tiebreak(c.decision())))
                .map(Candidate::decision)
                .orElse(PlacementDecision.local(DeviceInfo.CPU_DEVICE_ID, MemoryRegime.COMFORTABLE_FIT, 0L,
                        0.0, "no feasible candidate — CPU last resort"));
    }

    private Candidate localGpuCandidate(WorkloadRequest req, DeviceInfo dev, long footprint,
                                        ModelResourceProfile model, DeviceCapacityForecast forecast,
                                        ThroughputOracle throughput, int strandMode) {
        // strandMode >= 0 → apply the guard; -1 → ignore it (relaxed pass).
        if (strandMode >= 0) {
            long saturatesIn = forecast.forDevice(dev.deviceId()).estimatedSaturatesInMs();
            if (saturatesIn < tuning.forecastStrandHeadroomMs()) {
                return null; // don't strand a device forecast to be needed imminently
            }
        }

        long freeHeadroom = (long) (dev.freeMemoryBytes() * tuning.headroomFraction());
        double gpuRate = throughput.ratePerSec(req.serviceType(), ExecTarget.LOCAL_GPU);
        boolean idle = dev.currentUserCount() == 0;

        MemoryRegime regime;
        long bound;
        double rate;
        String why;

        if (model != null && model.maxedOut()) {
            if (idle && dev.freeMemoryBytes() >= footprint) {
                regime = MemoryRegime.WHOLE_DEVICE;
                bound = 0L;
                rate = gpuRate;
                why = "maxed-out model on idle device → whole device";
            } else {
                regime = MemoryRegime.BOUNDED_SPILL;
                bound = freeHeadroom;
                rate = gpuRate * tuning.hostSpillThroughputFactor();
                why = "maxed-out model bounded to " + mb(bound) + "MB → host spill";
            }
        } else if (footprint <= freeHeadroom) {
            if (idle) {
                regime = MemoryRegime.COMFORTABLE_FIT;
                bound = 0L;
                rate = gpuRate;
                why = "comfortable fit (" + mb(footprint) + "≤" + mb(freeHeadroom) + "MB free), device idle";
            } else {
                regime = MemoryRegime.BOUNDED_RATIO;
                bound = (long) (dev.totalMemoryBytes() * tuning.boundedRatioFraction());
                rate = gpuRate; // fits within cap → no spill
                why = "bounded-ratio share (cap " + mb(bound) + "MB), co-tenants present";
            }
        } else {
            regime = MemoryRegime.BOUNDED_SPILL;
            bound = freeHeadroom;
            rate = gpuRate * tuning.hostSpillThroughputFactor();
            why = "footprint " + mb(footprint) + ">" + mb(freeHeadroom) + "MB free → bound + host spill";
        }

        return new Candidate(
                PlacementDecision.local(dev.deviceId(), regime, bound, rate,
                        "GPU " + dev.deviceId() + ": " + why),
                rate);
    }

    private static long footprintBytes(WorkloadRequest req, ModelResourceProfile model) {
        if (model != null && model.trueFootprintBytes() > 0) {
            return model.trueFootprintBytes();
        }
        return req.profile() != null ? Math.max(0, req.profile().peakGpuMemoryBytes()) : 0L;
    }

    private static double costPenalty(CliAgentState agent) {
        // Cost is a mild deterrent; near-empty quota discourages routing there.
        double cost = agent.costPerKTok() * 100.0; // $/100k-tok scaled into the rate space
        double quotaPenalty = (agent.quotaRemaining() > 0 && agent.quotaRemaining() < 100) ? 1000.0 : 0.0;
        return cost + quotaPenalty;
    }

    /** Stable ordering when scores tie: comfortable/whole-device > bounded > spill; lower device id first. */
    private static int tiebreak(PlacementDecision d) {
        int regimeRank = switch (d.regime()) {
            case COMFORTABLE_FIT, WHOLE_DEVICE -> 0;
            case BOUNDED_RATIO -> 1;
            case BOUNDED_SPILL -> 2;
            case NA -> 3; // CLI after equal-scored local
        };
        int devRank = d.deviceId() == null ? Integer.MAX_VALUE : d.deviceId();
        return regimeRank * 1_000 + devRank;
    }

    private static long mb(long bytes) { return bytes / (1024 * 1024); }
    private static String fmt(double d) { return String.format("%.1f", d); }
}
