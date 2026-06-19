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

import ai.kompile.event.observation.config.EventObservationConfig;
import ai.kompile.event.observation.config.EventObservationConfigService;
import ai.kompile.event.observation.domain.EventChannel;
import ai.kompile.event.observation.domain.EventKeys;
import ai.kompile.event.observation.domain.EventSource;
import ai.kompile.event.observation.domain.EventType;
import ai.kompile.event.observation.domain.ObservedEvent;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The write/ingest API for observed events. Each method builds the event identity and delegates the
 * Beta update to {@link EventPriorService}, honouring the per-event-type switches in config.
 */
@Service
public class EventObservationService {

    private final EventPriorService priorService;
    private final EventObservationConfigService configService;

    public EventObservationService(EventPriorService priorService, EventObservationConfigService configService) {
        this.priorService = priorService;
        this.configService = configService;
    }

    /** "Entities are events in the number of times they occur." */
    public Optional<ObservedEvent> observeEntity(String nodeId, long occurrences, long opportunities,
                                                 EventSource source, Long factSheetId, String crawlJobId) {
        EventObservationConfig cfg = configService.getConfig();
        if (!cfg.enabled() || !cfg.entityEventsEnabled() || nodeId == null) {
            return Optional.empty();
        }
        ObservedEvent identity = ObservedEvent.builder()
                .eventKey(EventKeys.entity(nodeId))
                .eventType(EventType.ENTITY_OCCURRENCE)
                .subjectNodeId(nodeId)
                .factSheetId(factSheetId)
                .build();
        return Optional.of(priorService.recordObservation(identity, occurrences, opportunities, source, crawlJobId));
    }

    /** "Connections produce observed events." */
    public Optional<ObservedEvent> observeConnection(EventChannel channel, long occurrences, long opportunities,
                                                     EventSource source, Long factSheetId, String crawlJobId) {
        EventObservationConfig cfg = configService.getConfig();
        if (!cfg.enabled() || !cfg.connectionEventsEnabled() || channel == null) {
            return Optional.empty();
        }
        ObservedEvent identity = ObservedEvent.builder()
                .eventKey(channel.eventKey())
                .eventType(EventType.CONNECTION_OCCURRENCE)
                .sourceNodeId(channel.sourceNodeId())
                .edgeType(channel.edgeType())
                .targetNodeId(channel.targetNodeId())
                .factSheetId(factSheetId)
                .build();
        return Optional.of(priorService.recordObservation(identity, occurrences, opportunities, source, crawlJobId));
    }

    /** Process step execution as an event: success contributes an occurrence, every call an opportunity. */
    public Optional<ObservedEvent> observeProcessStep(String processDefinitionId, String stepId,
                                                      List<String> graphNodeIds, boolean success) {
        EventObservationConfig cfg = configService.getConfig();
        if (!cfg.enabled() || !cfg.processStepEventsEnabled() || processDefinitionId == null || stepId == null) {
            return Optional.empty();
        }
        String subject = (graphNodeIds != null && !graphNodeIds.isEmpty()) ? graphNodeIds.get(0) : null;
        ObservedEvent identity = ObservedEvent.builder()
                .eventKey(EventKeys.processStep(processDefinitionId, stepId))
                .eventType(EventType.PROCESS_STEP_OCCURRENCE)
                .subjectNodeId(subject)
                .build();
        return Optional.of(priorService.recordObservation(identity, success ? 1L : 0L, 1L, EventSource.PROCESS, null));
    }

    /** Apply an observation to a fully-specified event identity (used by the manual REST endpoint). */
    public ObservedEvent observe(ObservedEvent identity, long occurrences, long opportunities, EventSource source) {
        return priorService.recordObservation(identity, occurrences, opportunities, source, null);
    }

    /** Manual observation from the REST API. */
    public ObservedEvent observeManual(EventType type, String eventKey, String subjectNodeId, Long factSheetId,
                                       long occurrences, long opportunities) {
        ObservedEvent identity = ObservedEvent.builder()
                .eventKey(eventKey)
                .eventType(type == null ? EventType.USER_DEFINED : type)
                .subjectNodeId(subjectNodeId)
                .factSheetId(factSheetId)
                .build();
        return priorService.recordObservation(identity, occurrences, opportunities, EventSource.MANUAL, null);
    }
}
