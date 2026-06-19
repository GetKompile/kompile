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
package ai.kompile.event.observation.store;

import ai.kompile.event.observation.domain.EventObservationRecord;
import ai.kompile.event.observation.domain.EventType;
import ai.kompile.event.observation.domain.ObservedEvent;

import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for observed events and their Beta priors. Implemented by concrete
 * backends ({@code jpa}, {@code vector}) and fronted by the {@code @Primary}
 * {@link CompositeObservedEventStore}, which dual-writes to whichever backends the JSON config
 * enables ("store in both") and reads from the authoritative one.
 */
public interface ObservedEventStore {

    Optional<ObservedEvent> findByKey(String eventKey);

    ObservedEvent save(ObservedEvent event);

    void appendRecord(EventObservationRecord record);

    List<ObservedEvent> findBySubjectNodeId(String nodeId);

    Optional<ObservedEvent> findByConnection(String sourceNodeId, String edgeType, String targetNodeId);

    List<ObservedEvent> topEvents(Long factSheetId, EventType type, int limit);

    List<ObservedEvent> findAll();

    List<EventObservationRecord> history(String eventKey);
}
