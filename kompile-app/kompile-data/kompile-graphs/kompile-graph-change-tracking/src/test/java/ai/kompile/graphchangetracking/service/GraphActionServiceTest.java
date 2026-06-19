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

import ai.kompile.graphchangetracking.domain.GraphRuleConfig;
import ai.kompile.graphchangetracking.event.GraphChangesetCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GraphActionServiceTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final GraphActionService service = new GraphActionService(restTemplate);

    private GraphChangesetCompletedEvent event() {
        return new GraphChangesetCompletedEvent(this, "cs1", 50, 0, 0, 10, 0, 42L);
    }

    @Test
    void webhookAction_postsPayloadToTarget() {
        GraphRuleConfig rule = GraphRuleConfig.builder()
                .ruleId("r1").name("big crawl").actionType("WEBHOOK")
                .actionTarget("http://example.test/hook").build();
        when(restTemplate.postForEntity(eq("http://example.test/hook"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        service.fire(rule, event());

        verify(restTemplate).postForEntity(eq("http://example.test/hook"), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void logAction_doesNotCallHttp() {
        GraphRuleConfig rule = GraphRuleConfig.builder().ruleId("r1").name("x").actionType("LOG").build();
        service.fire(rule, event());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void webhookWithoutTarget_isSkipped() {
        GraphRuleConfig rule = GraphRuleConfig.builder().ruleId("r1").name("x").actionType("WEBHOOK").build();
        service.fire(rule, event());
        verifyNoInteractions(restTemplate);
    }
}
