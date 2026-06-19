/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.event.observation.service;

import ai.kompile.core.events.EmpiricalPriorSource;
import ai.kompile.core.events.EventObserver;
import ai.kompile.core.events.ObservedEventStat;
import ai.kompile.event.observation.config.EventObservationConfigService;
import ai.kompile.event.observation.domain.EventChannel;
import ai.kompile.event.observation.domain.EventSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Bridge bean implementing both app-core SPIs: {@link EmpiricalPriorSource} (read, consumed by the
 * Bayesian/MEBN and process-attribution layers) and {@link EventObserver} (write, used by producers
 * such as the process engine's step-completion callback). Delegates to {@link EventPriorService} for
 * reads and {@link EventObservationService} for writes.
 */
@Service
public class EmpiricalPriorService implements EmpiricalPriorSource, EventObserver {

    private final EventPriorService priorService;
    private final EventObservationService observationService;
    private final EventObservationConfigService configService;

    public EmpiricalPriorService(EventPriorService priorService,
                                 EventObservationService observationService,
                                 EventObservationConfigService configService) {
        this.priorService = priorService;
        this.observationService = observationService;
        this.configService = configService;
    }

    @Override
    public boolean isEnabled() {
        return configService.getConfig().enabled();
    }

    @Override
    public double blendK() {
        return configService.getConfig().priorBlendK();
    }

    // ── EmpiricalPriorSource (read) ─────────────────────────────────────────────

    @Override
    public OptionalDouble priorForNode(String nodeId) {
        return priorService.getNodePrior(nodeId);
    }

    @Override
    public OptionalDouble priorForConnection(String sourceNodeId, String edgeType, String targetNodeId) {
        return priorService.getConnectionPrior(sourceNodeId, edgeType, targetNodeId);
    }

    @Override
    public Optional<ObservedEventStat> statForNode(String nodeId) {
        return priorService.getNodeStat(nodeId);
    }

    // ── EventObserver (write) ───────────────────────────────────────────────────

    @Override
    public void observeEntity(String nodeId, long occurrences, long opportunities) {
        observationService.observeEntity(nodeId, occurrences, opportunities, EventSource.MANUAL, null, null);
    }

    @Override
    public void observeConnection(String sourceNodeId, String edgeType, String targetNodeId,
                                  long occurrences, long opportunities) {
        observationService.observeConnection(new EventChannel(sourceNodeId, edgeType, targetNodeId),
                occurrences, opportunities, EventSource.MANUAL, null, null);
    }

    @Override
    public void observeProcessStep(String processDefinitionId, String stepId, List<String> graphNodeIds, boolean success) {
        observationService.observeProcessStep(processDefinitionId, stepId, graphNodeIds, success);
    }
}
