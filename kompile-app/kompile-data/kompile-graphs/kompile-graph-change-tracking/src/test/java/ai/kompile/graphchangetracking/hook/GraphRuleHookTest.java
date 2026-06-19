/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graphchangetracking.hook;

import ai.kompile.graphchangetracking.domain.GraphRuleConfig;
import ai.kompile.graphchangetracking.event.GraphChangesetCompletedEvent;
import ai.kompile.graphchangetracking.event.GraphMutationEvent;
import ai.kompile.graphchangetracking.repository.GraphRuleConfigRepository;
import ai.kompile.graphchangetracking.service.GraphActionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GraphRuleHookTest {

    private final GraphRuleConfigRepository repo = mock(GraphRuleConfigRepository.class);
    private final GraphActionService action = mock(GraphActionService.class);
    private final GraphRuleHook hook = new GraphRuleHook(repo, action, new ObjectMapper());

    private GraphChangesetCompletedEvent event(int nodes, int edges, Long factSheetId) {
        return new GraphChangesetCompletedEvent(this, "cs1", nodes, 0, 0, edges, 0, factSheetId);
    }

    /** A concrete GraphMutationEvent (the class is abstract; ctor is protected). */
    private GraphMutationEvent mutation(String type, String kind, String id, Long factSheetId, String snapshotAfter) {
        return new GraphMutationEvent(this, type, kind, id, factSheetId, "cs1", "API", null, null, snapshotAfter) {};
    }

    @Test
    void firesActionWhenNodeThresholdMet() {
        GraphRuleConfig rule = GraphRuleConfig.builder()
                .ruleId("r1").name("big crawl").enabled(true).minNodesCreated(10).actionType("LOG").build();
        when(repo.findByEnabledTrue()).thenReturn(List.of(rule));
        GraphChangesetCompletedEvent e = event(50, 0, 42L);

        hook.onChangesetComplete(e);

        verify(action).fire(eq(rule), eq(e));
    }

    @Test
    void doesNotFireBelowThreshold() {
        GraphRuleConfig rule = GraphRuleConfig.builder()
                .ruleId("r1").name("big crawl").enabled(true).minNodesCreated(100).build();
        when(repo.findByEnabledTrue()).thenReturn(List.of(rule));

        hook.onChangesetComplete(event(50, 0, 42L));

        verifyNoInteractions(action);
    }

    @Test
    void factSheetScopedRuleIgnoresOtherFactSheets() {
        GraphRuleConfig rule = GraphRuleConfig.builder()
                .ruleId("r1").name("scoped").enabled(true).factSheetId(99L).build();
        when(repo.findByEnabledTrue()).thenReturn(List.of(rule));

        hook.onChangesetComplete(event(50, 0, 42L)); // event's fact sheet is 42, rule scoped to 99

        verifyNoInteractions(action);
    }

    @Test
    void catchAllRuleFiresOnAnyChangesetInScope() {
        GraphRuleConfig rule = GraphRuleConfig.builder()
                .ruleId("r1").name("catch-all").enabled(true).actionType("LOG").build();
        when(repo.findByEnabledTrue()).thenReturn(List.of(rule));
        GraphChangesetCompletedEvent e = event(1, 0, 7L);

        hook.onChangesetComplete(e);

        verify(action).fire(eq(rule), eq(e));
    }

    // ── per-mutation rules (Phase 4 follow-on) ──────────────────────────────────────

    @Test
    void mutationRule_firesOnMatchingMutationType() {
        GraphRuleConfig rule = GraphRuleConfig.builder().ruleId("m1").name("node-created").enabled(true)
                .triggerType("MUTATION").onMutationType("NODE_CREATED").actionType("LOG").build();
        when(repo.findByEnabledTrue()).thenReturn(List.of(rule));
        GraphMutationEvent e = mutation("NODE_CREATED", "NODE", "n1", 42L, "{\"nodeType\":\"PERSON\"}");

        hook.onGraphMutated(e);

        verify(action).fire(eq(rule), eq(e));
    }

    @Test
    void mutationRule_doesNotFireOnNonMatchingType() {
        GraphRuleConfig rule = GraphRuleConfig.builder().ruleId("m1").name("on-delete").enabled(true)
                .triggerType("MUTATION").onMutationType("NODE_DELETED").build();
        when(repo.findByEnabledTrue()).thenReturn(List.of(rule));

        hook.onGraphMutated(mutation("NODE_CREATED", "NODE", "n1", 42L, null));

        verifyNoInteractions(action);
    }

    @Test
    void mutationRule_matchesOnSemanticEntityType() {
        GraphRuleConfig rule = GraphRuleConfig.builder().ruleId("m1").name("person").enabled(true)
                .triggerType("MUTATION").onEntityType("PERSON").build();
        when(repo.findByEnabledTrue()).thenReturn(List.of(rule));
        GraphMutationEvent e = mutation("NODE_CREATED", "NODE", "n1", 42L, "{\"nodeType\":\"PERSON\"}");

        hook.onGraphMutated(e);

        verify(action).fire(eq(rule), eq(e));
    }

    @Test
    void mutationRule_entityTypeMismatch_doesNotFire() {
        GraphRuleConfig rule = GraphRuleConfig.builder().ruleId("m1").name("person").enabled(true)
                .triggerType("MUTATION").onEntityType("PERSON").build();
        when(repo.findByEnabledTrue()).thenReturn(List.of(rule));

        hook.onGraphMutated(mutation("NODE_CREATED", "NODE", "n1", 42L, "{\"nodeType\":\"ORG\"}"));

        verifyNoInteractions(action);
    }

    @Test
    void mutationRule_isSkippedOnChangeset() {
        GraphRuleConfig rule = GraphRuleConfig.builder().ruleId("m1").name("mut").enabled(true)
                .triggerType("MUTATION").build();
        when(repo.findByEnabledTrue()).thenReturn(List.of(rule));

        hook.onChangesetComplete(event(50, 0, 42L));

        verifyNoInteractions(action);
    }

    @Test
    void changesetRule_isSkippedOnMutation() {
        GraphRuleConfig rule = GraphRuleConfig.builder().ruleId("c1").name("changeset").enabled(true)
                .triggerType("CHANGESET").build();
        when(repo.findByEnabledTrue()).thenReturn(List.of(rule));

        hook.onGraphMutated(mutation("NODE_CREATED", "NODE", "n1", 42L, null));

        verifyNoInteractions(action);
    }
}
