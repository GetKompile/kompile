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
package ai.kompile.event.observation.controller;

import ai.kompile.event.observation.domain.ObservedEvent;

import java.time.LocalDateTime;

/**
 * REST view of an {@link ObservedEvent} (avoids serialising the JPA entity directly).
 */
public record ObservedEventView(
        String eventKey,
        String eventType,
        String subjectNodeId,
        String sourceNodeId,
        String edgeType,
        String targetNodeId,
        Long factSheetId,
        double probability,
        long occurrenceCount,
        long opportunityCount,
        double evidenceStrength,
        LocalDateTime lastObservedAt) {

    public static ObservedEventView of(ObservedEvent e) {
        return new ObservedEventView(
                e.getEventKey(),
                e.getEventType() == null ? null : e.getEventType().name(),
                e.getSubjectNodeId(),
                e.getSourceNodeId(),
                e.getEdgeType(),
                e.getTargetNodeId(),
                e.getFactSheetId(),
                e.probability(),
                e.getOccurrenceCount(),
                e.getOpportunityCount(),
                e.evidenceStrength(),
                e.getLastObservedAt());
    }
}
