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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The reactive rule engine: on each completed graph changeset, evaluate the enabled
 * {@link GraphRuleConfig}s and fire their outbound action when a rule matches. Wired into the
 * existing {@code GraphUpdateHookRegistry} (see GraphChangeTrackingAutoConfiguration).
 */
@Component
@Slf4j
public class GraphRuleHook implements GraphUpdateHook {

    private final GraphRuleConfigRepository ruleRepository;
    private final GraphActionService actionService;
    private final ObjectMapper objectMapper;

    public GraphRuleHook(GraphRuleConfigRepository ruleRepository, GraphActionService actionService,
                         ObjectMapper objectMapper) {
        this.ruleRepository = ruleRepository;
        this.actionService = actionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getId() {
        return "graph-rule-engine";
    }

    @Override
    public int getPriority() {
        return 200;
    }

    @Override
    public void onChangesetComplete(GraphChangesetCompletedEvent event) {
        for (GraphRuleConfig rule : ruleRepository.findByEnabledTrue()) {
            if (isMutationRule(rule)) {
                continue; // mutation-scoped rules fire in onGraphMutated, not here
            }
            try {
                if (matches(rule, event)) {
                    actionService.fire(rule, event);
                }
            } catch (Exception e) {
                log.warn("Graph rule '{}' evaluation failed for changeset {}: {}",
                        rule.getName(), event.getChangesetId(), e.getMessage());
            }
        }
    }

    /**
     * Per-mutation rule path: on each individual node/edge mutation, fire the enabled MUTATION-scoped
     * rules whose match fields fit. Complements the changeset-threshold path above.
     */
    @Override
    public void onGraphMutated(GraphMutationEvent event) {
        for (GraphRuleConfig rule : ruleRepository.findByEnabledTrue()) {
            if (!isMutationRule(rule)) {
                continue;
            }
            try {
                if (matchesMutation(rule, event)) {
                    actionService.fire(rule, event);
                }
            } catch (Exception e) {
                log.warn("Graph rule '{}' mutation evaluation failed for {} {}: {}",
                        rule.getName(), event.getEntityKind(), event.getEntityId(), e.getMessage());
            }
        }
    }

    static boolean isMutationRule(GraphRuleConfig rule) {
        return "MUTATION".equalsIgnoreCase(rule.getTriggerType());
    }

    /** A rule matches when its fact-sheet scope fits and every threshold it sets is met. */
    static boolean matches(GraphRuleConfig rule, GraphChangesetCompletedEvent event) {
        if (rule.getFactSheetId() != null && !rule.getFactSheetId().equals(event.getFactSheetId())) {
            return false;
        }
        if (rule.getMinNodesCreated() != null && event.getNodesCreated() < rule.getMinNodesCreated()) {
            return false;
        }
        if (rule.getMinEdgesCreated() != null && event.getEdgesCreated() < rule.getMinEdgesCreated()) {
            return false;
        }
        // No thresholds set → catch-all (fires on every changeset within scope).
        return true;
    }

    /**
     * A MUTATION rule matches when its fact-sheet scope fits and each set match field
     * (mutationType / entityKind / semantic entityType) equals the event's; unset fields are wildcards.
     */
    boolean matchesMutation(GraphRuleConfig rule, GraphMutationEvent event) {
        if (rule.getFactSheetId() != null && !rule.getFactSheetId().equals(event.getFactSheetId())) {
            return false;
        }
        if (rule.getOnMutationType() != null
                && !rule.getOnMutationType().equalsIgnoreCase(event.getMutationType())) {
            return false;
        }
        if (rule.getOnEntityKind() != null
                && !rule.getOnEntityKind().equalsIgnoreCase(event.getEntityKind())) {
            return false;
        }
        if (rule.getOnEntityType() != null
                && !rule.getOnEntityType().equalsIgnoreCase(entityTypeOf(event))) {
            return false;
        }
        return true;
    }

    /** Semantic type (nodeType, or relationType/edgeType) parsed from the mutation snapshot; null if unavailable. */
    private String entityTypeOf(GraphMutationEvent event) {
        String json = event.getSnapshotAfter() != null ? event.getSnapshotAfter() : event.getSnapshotBefore();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> state = objectMapper.readValue(json, Map.class);
            Object type = "EDGE".equals(event.getEntityKind())
                    ? (state.get("relationType") != null ? state.get("relationType") : state.get("edgeType"))
                    : state.get("nodeType");
            return type == null ? null : String.valueOf(type);
        } catch (Exception e) {
            return null;
        }
    }
}
