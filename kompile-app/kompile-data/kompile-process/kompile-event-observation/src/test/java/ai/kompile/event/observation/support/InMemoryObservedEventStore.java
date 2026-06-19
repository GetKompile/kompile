/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.observation.support;

import ai.kompile.event.observation.domain.EventObservationRecord;
import ai.kompile.event.observation.domain.EventType;
import ai.kompile.event.observation.domain.ObservedEvent;
import ai.kompile.event.observation.store.ObservedEventStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Simple in-memory {@link ObservedEventStore} for unit tests (no JPA / vector store). */
public class InMemoryObservedEventStore implements ObservedEventStore {

    public final Map<String, ObservedEvent> events = new LinkedHashMap<>();
    public final List<EventObservationRecord> records = new ArrayList<>();
    private long idSeq = 1;

    @Override
    public Optional<ObservedEvent> findByKey(String eventKey) {
        return Optional.ofNullable(events.get(eventKey));
    }

    @Override
    public ObservedEvent save(ObservedEvent event) {
        if (event.getId() == null) {
            event.setId(idSeq++);
        }
        events.put(event.getEventKey(), event);
        return event;
    }

    @Override
    public void appendRecord(EventObservationRecord record) {
        records.add(record);
    }

    @Override
    public List<ObservedEvent> findBySubjectNodeId(String nodeId) {
        return events.values().stream().filter(e -> nodeId.equals(e.getSubjectNodeId())).collect(Collectors.toList());
    }

    @Override
    public Optional<ObservedEvent> findByConnection(String sourceNodeId, String edgeType, String targetNodeId) {
        return events.values().stream()
                .filter(e -> sourceNodeId.equals(e.getSourceNodeId())
                        && edgeType.equals(e.getEdgeType())
                        && targetNodeId.equals(e.getTargetNodeId()))
                .findFirst();
    }

    @Override
    public List<ObservedEvent> topEvents(Long factSheetId, EventType type, int limit) {
        return events.values().stream()
                .filter(e -> type == null || type == e.getEventType())
                .limit(limit).collect(Collectors.toList());
    }

    @Override
    public List<ObservedEvent> findAll() {
        return new ArrayList<>(events.values());
    }

    @Override
    public List<EventObservationRecord> history(String eventKey) {
        return records.stream().filter(r -> eventKey.equals(r.getEventKey())).collect(Collectors.toList());
    }
}
