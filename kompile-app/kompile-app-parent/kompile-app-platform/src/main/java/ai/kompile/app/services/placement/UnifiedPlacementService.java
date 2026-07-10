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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production placement oracle. Composes the mockable providers with the PURE {@link PlacementPolicy}
 * and records assignments; a separate {@code PlacementApplier} (WS2) turns a decision into
 * device-agnostic effect ({@code DeviceMemoryManager.switchDevice}/{@code setMemoryCap},
 * {@code org.nd4j.{cpu,gpu}.priority}) — NEVER {@code CUDA_VISIBLE_DEVICES}.
 *
 * <p>This class is a thin, side-effect-light composer: all the logic lives in the pure policy so it
 * stays unit-testable (see {@code PlacementPolicyScenarioTest}); this only gathers snapshots and
 * remembers what was assigned. Registered as a Spring {@code @Service} in the app context via a
 * config class that supplies the real provider beans; kept POJO here so the platform module has no
 * Spring hard dependency and the class is trivially constructable in tests.</p>
 */
public class UnifiedPlacementService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedPlacementService.class);

    private final DeviceInventoryProvider devices;
    private final ModelResourceCatalog models;
    private final CliAgentStateProvider cliAgents;
    private final CapacityForecastProvider forecasts;
    private final ThroughputOracle throughput;
    private final PlacementPolicy policy;

    /** jobId → the decision it was placed with (for observability + the churn/lifecycle loop). */
    private final Map<String, PlacementDecision> activePlacements = new ConcurrentHashMap<>();

    public UnifiedPlacementService(DeviceInventoryProvider devices,
                                   ModelResourceCatalog models,
                                   CliAgentStateProvider cliAgents,
                                   CapacityForecastProvider forecasts,
                                   ThroughputOracle throughput,
                                   PlacementPolicy policy) {
        this.devices = devices;
        this.models = models;
        this.cliAgents = cliAgents;
        this.forecasts = forecasts;
        this.throughput = throughput;
        this.policy = policy;
    }

    /** Gather live snapshots and run the pure decision. Never throws — falls back to CPU. */
    public PlacementDecision decide(WorkloadRequest req) {
        try {
            ModelResourceProfile model = req.modelId() == null
                    ? null
                    : models.profile(req.modelId()).orElse(null);
            PlacementDecision d = policy.decide(
                    req, devices.snapshot(), model, cliAgents.snapshot(), forecasts.forecast(), throughput);
            log.debug("[placement] {} → {} (device={}, regime={}, boundMB={}): {}",
                    req.serviceType(), d.route(), d.deviceId(), d.regime(),
                    d.memoryBoundBytes() / (1024 * 1024), d.rationale());
            return d;
        } catch (RuntimeException e) {
            log.warn("[placement] decide failed for {} — CPU fallback: {}", req.serviceType(), e.getMessage());
            return PlacementDecision.local(DeviceInfo.CPU_DEVICE_ID,
                    PlacementDecision.MemoryRegime.COMFORTABLE_FIT, 0L, 0.0,
                    "CPU fallback (placement error: " + e.getMessage() + ")");
        }
    }

    public void recordAssignment(String jobId, PlacementDecision decision) {
        if (jobId != null) activePlacements.put(jobId, decision);
    }

    public void releaseAssignment(String jobId) {
        if (jobId != null) activePlacements.remove(jobId);
    }

    public Map<String, PlacementDecision> activePlacements() {
        return Map.copyOf(activePlacements);
    }
}
