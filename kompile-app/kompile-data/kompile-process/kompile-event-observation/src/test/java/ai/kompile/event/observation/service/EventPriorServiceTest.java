/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.observation.service;

import ai.kompile.event.observation.config.EventObservationConfigService;
import ai.kompile.event.observation.domain.EventKeys;
import ai.kompile.event.observation.domain.EventSource;
import ai.kompile.event.observation.domain.EventType;
import ai.kompile.event.observation.domain.ObservedEvent;
import ai.kompile.event.observation.support.InMemoryObservedEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventPriorServiceTest {

    @TempDir
    Path tmp;

    private InMemoryObservedEventStore store;
    private EventObservationConfigService config;
    private EventPriorService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryObservedEventStore();
        config = new EventObservationConfigService(tmp.toString());
        config.init();
        service = new EventPriorService(store, config);
    }

    private ObservedEvent entityIdentity(String nodeId) {
        return ObservedEvent.builder()
                .eventKey(EventKeys.entity(nodeId))
                .eventType(EventType.ENTITY_OCCURRENCE)
                .subjectNodeId(nodeId)
                .build();
    }

    @Test
    void recordedEntityWithEnoughEvidenceExposesPrior() {
        service.recordObservation(entityIdentity("n1"), 8, 10, EventSource.CRAWL, null);
        OptionalDouble prior = service.getNodePrior("n1");
        assertTrue(prior.isPresent());
        assertTrue(prior.getAsDouble() > 0.5, "8/10 successes should push the prior above 0.5");
    }

    @Test
    void belowMinEvidenceNoPriorExposed() {
        // One opportunity ⇒ real evidence 1 < default minEvidenceForPrior (3).
        service.recordObservation(entityIdentity("n2"), 1, 1, EventSource.CRAWL, null);
        assertFalse(service.getNodePrior("n2").isPresent());
    }

    @Test
    void priorDecaysWhenReObservedMuchLater() {
        Clock t0 = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service.setClock(t0);
        service.recordObservation(entityIdentity("n3"), 50, 50, EventSource.CRAWL, null);
        double strongEvidence = store.findByKey(EventKeys.entity("n3")).orElseThrow().evidenceStrength();

        // 90 days later (3 half-lives at the default 30-day half-life), a tiny observation.
        Clock t1 = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC);
        service.setClock(t1);
        service.recordObservation(entityIdentity("n3"), 0, 1, EventSource.CRAWL, null);
        double decayedEvidence = store.findByKey(EventKeys.entity("n3")).orElseThrow().evidenceStrength();

        assertTrue(decayedEvidence < strongEvidence,
                "evidence should decay over time: before=" + strongEvidence + " after=" + decayedEvidence);
    }

    @Test
    void ledgerRecordsEachObservation() {
        service.recordObservation(entityIdentity("n4"), 2, 3, EventSource.CRAWL, "job-1");
        service.recordObservation(entityIdentity("n4"), 1, 2, EventSource.MUTATION, null);
        assertTrue(service.history(EventKeys.entity("n4")).size() == 2);
    }
}
