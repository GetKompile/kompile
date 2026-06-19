/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.domain.GraphMutationRecord;
import ai.kompile.graphchangetracking.repository.GraphMutationRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TemporalGraphQueryService#semanticDiffFactSheet} — the Phase-5 entity-level
 * diff (added / removed / attribute-changed) computed from the mutation log's before/after snapshots.
 */
class TemporalGraphQueryServiceTest {

    private static final Long FS = 42L;

    private final GraphMutationRecordRepository repo = mock(GraphMutationRecordRepository.class);
    private final TemporalGraphQueryService service = new TemporalGraphQueryService(repo, new ObjectMapper());

    private GraphMutationRecord rec(String type, String kind, String id, String before, String after) {
        return GraphMutationRecord.builder()
                .mutationId(UUID.randomUUID().toString())
                .mutationType(type).entityKind(kind).entityId(id).factSheetId(FS)
                .snapshotBefore(before).snapshotAfter(after)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    /** The repo returns newest-first; tests pass records in that order. */
    private void stub(List<GraphMutationRecord> records) {
        when(repo.findByFactSheetIdAndOccurredAtBetweenOrderByOccurredAtDesc(anyLong(), any(), any(), any()))
                .thenReturn(new PageImpl<>(records));
    }

    private TemporalGraphQueryService.EntityDiff diff() {
        return service.semanticDiffFactSheet(FS, LocalDateTime.now().minusDays(1), LocalDateTime.now());
    }

    @Test
    void nodeCreated_isAdded() {
        stub(List.of(rec("NODE_CREATED", "NODE", "n1", null,
                "{\"nodeId\":\"n1\",\"nodeType\":\"PERSON\",\"title\":\"Alice\"}")));

        TemporalGraphQueryService.EntityDiff d = diff();

        assertEquals(1, d.addedCount());
        assertEquals(0, d.removedCount());
        assertEquals(0, d.changedCount());
        TemporalGraphQueryService.EntityRef ref = d.added().get(0);
        assertEquals("n1", ref.entityId());
        assertEquals("PERSON", ref.entityType());
        assertEquals("Alice", ref.label());
    }

    @Test
    void nodeDeleted_isRemoved() {
        stub(List.of(rec("NODE_DELETED", "NODE", "n1",
                "{\"nodeId\":\"n1\",\"nodeType\":\"PERSON\",\"title\":\"Alice\"}", null)));

        TemporalGraphQueryService.EntityDiff d = diff();

        assertEquals(1, d.removedCount());
        assertEquals(0, d.addedCount());
        assertEquals("Alice", d.removed().get(0).label());
    }

    @Test
    void nodeUpdated_titleChange_isReportedAsAttributeDelta() {
        stub(List.of(rec("NODE_UPDATED", "NODE", "n1",
                "{\"nodeId\":\"n1\",\"nodeType\":\"PERSON\",\"title\":\"Old\"}",
                "{\"nodeId\":\"n1\",\"nodeType\":\"PERSON\",\"title\":\"New\"}")));

        TemporalGraphQueryService.EntityDiff d = diff();

        assertEquals(1, d.changedCount());
        assertEquals(0, d.addedCount());
        assertEquals(0, d.removedCount());
        TemporalGraphQueryService.EntityChange c = d.changed().get(0);
        assertEquals("n1", c.entityId());
        assertEquals(1, c.changes().size());
        TemporalGraphQueryService.AttributeChange ac = c.changes().get(0);
        assertEquals("title", ac.attribute());
        assertEquals("Old", ac.before());
        assertEquals("New", ac.after());
    }

    @Test
    void identicalSnapshots_areNotChanged() {
        String state = "{\"nodeId\":\"n1\",\"nodeType\":\"PERSON\",\"title\":\"Alice\"}";
        stub(List.of(rec("NODE_UPDATED", "NODE", "n1", state, state)));

        assertEquals(0, diff().changedCount());
    }

    @Test
    void volatileKeysOnly_areIgnored() {
        // Only updatedAt differs — denylisted, so no semantic change.
        stub(List.of(rec("NODE_UPDATED", "NODE", "n1",
                "{\"nodeId\":\"n1\",\"title\":\"Alice\",\"updatedAt\":\"2026-06-19T10:00:00\"}",
                "{\"nodeId\":\"n1\",\"title\":\"Alice\",\"updatedAt\":\"2026-06-19T11:00:00\"}")));

        assertEquals(0, diff().changedCount());
    }

    @Test
    void createdThenDeletedInWindow_netsToNoChange() {
        // Newest-first: delete then create.
        stub(List.of(
                rec("NODE_DELETED", "NODE", "n1", "{\"nodeId\":\"n1\",\"title\":\"Alice\"}", null),
                rec("NODE_CREATED", "NODE", "n1", null, "{\"nodeId\":\"n1\",\"title\":\"Alice\"}")));

        TemporalGraphQueryService.EntityDiff d = diff();
        assertEquals(0, d.addedCount());
        assertEquals(0, d.removedCount());
        assertEquals(0, d.changedCount());
    }

    @Test
    void edgeWeightChange_isReported() {
        stub(List.of(rec("EDGE_UPDATED", "EDGE", "e1",
                "{\"edgeId\":\"e1\",\"edgeType\":\"REL\",\"weight\":0.5}",
                "{\"edgeId\":\"e1\",\"edgeType\":\"REL\",\"weight\":0.9}")));

        TemporalGraphQueryService.EntityDiff d = diff();

        assertEquals(1, d.changedCount());
        TemporalGraphQueryService.EntityChange c = d.changed().get(0);
        assertEquals("EDGE", c.entityKind());
        assertEquals("weight", c.changes().get(0).attribute());
        assertEquals("0.5", c.changes().get(0).before());
        assertEquals("0.9", c.changes().get(0).after());
    }
}
