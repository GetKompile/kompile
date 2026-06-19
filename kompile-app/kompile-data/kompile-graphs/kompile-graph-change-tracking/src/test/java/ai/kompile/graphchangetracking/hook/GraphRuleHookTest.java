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
import ai.kompile.graphchangetracking.repository.GraphRuleConfigRepository;
import ai.kompile.graphchangetracking.service.GraphActionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GraphRuleHookTest {

    private final GraphRuleConfigRepository repo = mock(GraphRuleConfigRepository.class);
    private final GraphActionService action = mock(GraphActionService.class);
    private final GraphRuleHook hook = new GraphRuleHook(repo, action);

    private GraphChangesetCompletedEvent event(int nodes, int edges, Long factSheetId) {
        return new GraphChangesetCompletedEvent(this, "cs1", nodes, 0, 0, edges, 0, factSheetId);
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
}
