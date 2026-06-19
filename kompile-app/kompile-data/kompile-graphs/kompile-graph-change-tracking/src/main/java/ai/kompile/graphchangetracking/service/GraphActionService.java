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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fires the outbound action when a {@link GraphRuleConfig} matches a graph changeset.
 * Implements {@code LOG} and {@code WEBHOOK} (HTTP POST) — the previously-missing "outbound
 * action" side of reactive graphs (the old {@code TaskCompletionHandler} NOTIFY TODO).
 */
@Service
@Slf4j
public class GraphActionService {

    private final RestTemplate restTemplate;

    public GraphActionService() {
        this(new RestTemplate());
    }

    /** Test seam. */
    GraphActionService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void fire(GraphRuleConfig rule, GraphChangesetCompletedEvent event) {
        String actionType = rule.getActionType() != null ? rule.getActionType().toUpperCase() : "LOG";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ruleId", rule.getRuleId());
        payload.put("ruleName", rule.getName());
        payload.put("changesetId", event.getChangesetId());
        payload.put("factSheetId", event.getFactSheetId());
        payload.put("nodesCreated", event.getNodesCreated());
        payload.put("edgesCreated", event.getEdgesCreated());

        switch (actionType) {
            case "WEBHOOK" -> fireWebhook(rule, payload);
            case "LOG" -> log.info("Graph rule '{}' matched changeset {}: {}",
                    rule.getName(), event.getChangesetId(), payload);
            default -> log.warn("Graph rule '{}' has unknown actionType '{}'; ignoring",
                    rule.getName(), actionType);
        }
    }

    private void fireWebhook(GraphRuleConfig rule, Map<String, Object> payload) {
        String url = rule.getActionTarget();
        if (url == null || url.isBlank()) {
            log.warn("Graph rule '{}' is WEBHOOK but has no actionTarget; skipping", rule.getName());
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(url, new HttpEntity<>(payload, headers), String.class);
            log.info("Graph rule '{}' fired webhook to {}", rule.getName(), url);
        } catch (Exception e) {
            log.warn("Graph rule '{}' webhook to {} failed: {}", rule.getName(), url, e.getMessage());
        }
    }
}
