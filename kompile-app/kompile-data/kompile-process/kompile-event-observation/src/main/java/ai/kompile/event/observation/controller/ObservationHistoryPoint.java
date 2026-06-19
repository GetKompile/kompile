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

import ai.kompile.event.observation.domain.EventObservationRecord;

import java.time.LocalDateTime;

/**
 * One point on an event's prior time-series — the empirical probability at the moment of an
 * observation, used by the UI history chart.
 */
public record ObservationHistoryPoint(
        LocalDateTime observedAt,
        double probability,
        long occurrences,
        long opportunities,
        String source) {

    public static ObservationHistoryPoint of(EventObservationRecord r) {
        return new ObservationHistoryPoint(
                r.getObservedAt(),
                r.getProbabilityAt(),
                r.getOccurrences(),
                r.getOpportunities(),
                r.getSource() == null ? null : r.getSource().name());
    }
}
