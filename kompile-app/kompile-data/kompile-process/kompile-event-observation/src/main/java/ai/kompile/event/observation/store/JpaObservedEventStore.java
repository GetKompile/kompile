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
import ai.kompile.event.observation.repository.EventObservationRecordRepository;
import ai.kompile.event.observation.repository.ObservedEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Authoritative, transactional backend: the running Beta counters and the observation ledger live
 * in relational tables (see {@link ObservedEvent} / {@link EventObservationRecord}).
 */
@Service
public class JpaObservedEventStore implements ObservedEventStoreBackend {

    private final ObservedEventRepository events;
    private final EventObservationRecordRepository records;

    public JpaObservedEventStore(ObservedEventRepository events, EventObservationRecordRepository records) {
        this.events = events;
        this.records = records;
    }

    @Override
    public String backendName() {
        return "jpa";
    }

    @Override
    public Optional<ObservedEvent> findByKey(String eventKey) {
        return events.findByEventKey(eventKey);
    }

    @Override
    @Transactional
    public ObservedEvent save(ObservedEvent event) {
        return events.save(event);
    }

    @Override
    @Transactional
    public void appendRecord(EventObservationRecord record) {
        records.save(record);
    }

    @Override
    public List<ObservedEvent> findBySubjectNodeId(String nodeId) {
        return events.findBySubjectNodeId(nodeId);
    }

    @Override
    public Optional<ObservedEvent> findByConnection(String sourceNodeId, String edgeType, String targetNodeId) {
        return events.findBySourceNodeIdAndEdgeTypeAndTargetNodeId(sourceNodeId, edgeType, targetNodeId);
    }

    @Override
    public List<ObservedEvent> topEvents(Long factSheetId, EventType type, int limit) {
        Pageable page = PageRequest.of(0, Math.max(1, limit));
        if (factSheetId != null && type != null) {
            return events.findByFactSheetIdAndEventTypeOrderByOccurrenceCountDesc(factSheetId, type, page);
        }
        if (factSheetId != null) {
            return events.findByFactSheetIdOrderByOccurrenceCountDesc(factSheetId, page);
        }
        if (type != null) {
            return events.findByEventTypeOrderByOccurrenceCountDesc(type, page);
        }
        return events.findAllByOrderByOccurrenceCountDesc(page);
    }

    @Override
    public List<ObservedEvent> findAll() {
        return events.findAll();
    }

    @Override
    public List<EventObservationRecord> history(String eventKey) {
        return records.findByEventKeyOrderByObservedAtAsc(eventKey);
    }
}
