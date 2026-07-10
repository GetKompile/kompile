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
import ai.kompile.app.services.placement.PlacementDecision.RouteKind;
import ai.kompile.app.services.placement.WorkloadRequest.TaskKind;
import ai.kompile.app.services.scheduler.JobResourceProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Table-driven coverage of the combinatorial placement space (plan §Testability). Each row declares
 * a world (devices, model, CLI state, forecast) with fluent builders and asserts the decision —
 * pure, deterministic, no hardware.
 */
class PlacementPolicyScenarioTest {

    private static final long GB = 1L << 30;

    /** Fixed rates so decisions are deterministic: GPU(200) > CLI(150) > spill≈70 > CPU(20). */
    private static final ThroughputOracle RATES = (svc, target) -> switch (target) {
        case LOCAL_GPU -> 200.0;
        case CLI -> 150.0;
        case LOCAL_CPU -> 20.0;
    };

    private final PlacementPolicy policy = new PlacementPolicy();

    record Scenario(String name, WorkloadRequest req, DeviceInventorySnapshot devices,
                    ModelResourceProfile model, CliAgentStateSnapshot cli, DeviceCapacityForecast forecast,
                    RouteKind expectRoute, Integer expectDevice, MemoryRegime expectRegime) {
        @Override public String toString() { return name; }
    }

    // ── fluent builders ──────────────────────────────────────────────────────
    private static WorkloadRequest llm(long footprint) {
        return new WorkloadRequest("extraction",
                JobResourceProfile.gpuRequired("extraction", "Extraction", footprint, 2 * GB),
                TaskKind.LLM, "m");
    }
    private static WorkloadRequest embedding(long footprint) {
        return new WorkloadRequest("embedding",
                JobResourceProfile.gpuRequired("embedding", "Embedding", footprint, 4 * GB),
                TaskKind.EMBEDDING, "bge");
    }
    private static WorkloadRequest cpuOnly() {
        return new WorkloadRequest("graph-matrix",
                JobResourceProfile.cpuOnly("graph-matrix", "Graph Matrix", 8 * GB),
                TaskKind.OTHER, null);
    }
    private static DeviceInventorySnapshot devices(DeviceInfo... d) { return new DeviceInventorySnapshot(List.of(d)); }
    private static DeviceInfo gpu(int id, long totalGb, long freeGb, int users) {
        return new DeviceInfo(id, DeviceInfo.DeviceKind.GPU, totalGb * GB, freeGb * GB, 8.9, users);
    }
    private static ModelResourceProfile model(long footprintGb, boolean cpuOk, boolean maxed) {
        return new ModelResourceProfile("m", footprintGb * GB, cpuOk, maxed);
    }
    private static CliAgentStateSnapshot cliViable() {
        return new CliAgentStateSnapshot(List.of(CliAgentState.of("opencode-cli", true, 5000, 150.0)));
    }
    private static CliAgentStateSnapshot cliDry() { return CliAgentStateSnapshot.none(); }
    private static DeviceCapacityForecast saturatingSoon(int deviceId, long inMs) {
        return new DeviceCapacityForecast(Map.of(deviceId,
                new DeviceCapacityForecast.DeviceForecast(deviceId, inMs, 0L, 5_000_000.0)));
    }

    static Stream<Scenario> scenarios() {
        return Stream.of(
                new Scenario("LLM, single GPU saturated, CLI viable → CLI",
                        llm(8 * GB), devices(gpu(0, 24, 1, 1)), model(8, true, false),
                        cliViable(), DeviceCapacityForecast.steady(),
                        RouteKind.CLI, null, MemoryRegime.NA),

                new Scenario("LLM, comfortable GPU → local GPU comfortable (beats CLI)",
                        llm(2 * GB), devices(gpu(0, 24, 24, 0)), model(2, true, false),
                        cliViable(), DeviceCapacityForecast.steady(),
                        RouteKind.LOCAL, 0, MemoryRegime.COMFORTABLE_FIT),

                new Scenario("LLM, maxed-out model on idle device → whole device",
                        llm(22 * GB), devices(gpu(0, 24, 24, 0)), model(22, true, true),
                        cliViable(), DeviceCapacityForecast.steady(),
                        RouteKind.LOCAL, 0, MemoryRegime.WHOLE_DEVICE),

                new Scenario("LLM, maxed-out on busy device, CLI dry → bounded spill",
                        llm(22 * GB), devices(gpu(0, 24, 24, 1)), model(22, true, true),
                        cliDry(), DeviceCapacityForecast.steady(),
                        RouteKind.LOCAL, 0, MemoryRegime.BOUNDED_SPILL),

                new Scenario("LLM, GPU saturated, CLI dry, cpuCapable → stay local bounded spill",
                        llm(8 * GB), devices(gpu(0, 24, 1, 1)), model(8, true, false),
                        cliDry(), DeviceCapacityForecast.steady(),
                        RouteKind.LOCAL, 0, MemoryRegime.BOUNDED_SPILL),

                new Scenario("LLM, two GPUs, device0 saturating soon → don't strand, pick device1",
                        llm(2 * GB), devices(gpu(0, 24, 24, 0), gpu(1, 24, 24, 0)), model(2, true, false),
                        cliDry(), saturatingSoon(0, 5_000L),
                        RouteKind.LOCAL, 1, MemoryRegime.COMFORTABLE_FIT),

                new Scenario("CPU-only workload → CPU regardless of GPUs",
                        cpuOnly(), devices(gpu(0, 24, 24, 0)), null,
                        cliViable(), DeviceCapacityForecast.steady(),
                        RouteKind.LOCAL, DeviceInfo.CPU_DEVICE_ID, MemoryRegime.COMFORTABLE_FIT),

                new Scenario("Embedding is not CLI-routable → stays local even with CLI available",
                        embedding(2 * GB), devices(gpu(0, 24, 24, 0)), model(2, true, false),
                        cliViable(), DeviceCapacityForecast.steady(),
                        RouteKind.LOCAL, 0, MemoryRegime.COMFORTABLE_FIT)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void placementMatchesExpectation(Scenario s) {
        PlacementDecision d = policy.decide(s.req(), s.devices(), s.model(), s.cli(), s.forecast(), RATES);
        assertEquals(s.expectRoute(), d.route(), () -> "route for " + s.name() + " — rationale: " + d.rationale());
        assertEquals(s.expectDevice(), d.deviceId(), () -> "device for " + s.name() + " — " + d.rationale());
        assertEquals(s.expectRegime(), d.regime(), () -> "regime for " + s.name() + " — " + d.rationale());
    }
}
