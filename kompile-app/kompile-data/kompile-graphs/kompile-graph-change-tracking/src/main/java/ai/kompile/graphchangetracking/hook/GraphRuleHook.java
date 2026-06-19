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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    public GraphRuleHook(GraphRuleConfigRepository ruleRepository, GraphActionService actionService) {
        this.ruleRepository = ruleRepository;
        this.actionService = actionService;
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
}
