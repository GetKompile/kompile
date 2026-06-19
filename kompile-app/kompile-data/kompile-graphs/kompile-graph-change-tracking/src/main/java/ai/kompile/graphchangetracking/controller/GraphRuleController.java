/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graphchangetracking.controller;

import ai.kompile.graphchangetracking.domain.GraphRuleConfig;
import ai.kompile.graphchangetracking.repository.GraphRuleConfigRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD for reactive graph rules (condition → outbound action). Mirrors the
 * GraphUpdatePipelineController surface.
 */
@RestController
@RequestMapping("/api/graph/rules")
public class GraphRuleController {

    private final GraphRuleConfigRepository repository;

    public GraphRuleController(GraphRuleConfigRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<GraphRuleConfig> list() {
        return repository.findAll();
    }

    @PostMapping
    public GraphRuleConfig create(@RequestBody GraphRuleConfig config) {
        config.setId(null);
        return repository.save(config);
    }

    @GetMapping("/{ruleId}")
    public ResponseEntity<GraphRuleConfig> get(@PathVariable("ruleId") String ruleId) {
        return repository.findByRuleId(ruleId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<GraphRuleConfig> update(@PathVariable("ruleId") String ruleId,
                                                  @RequestBody GraphRuleConfig update) {
        return repository.findByRuleId(ruleId).map(existing -> {
            if (update.getName() != null) existing.setName(update.getName());
            if (update.getEnabled() != null) existing.setEnabled(update.getEnabled());
            if (update.getActionType() != null) existing.setActionType(update.getActionType());
            existing.setActionTarget(update.getActionTarget());
            existing.setFactSheetId(update.getFactSheetId());
            existing.setMinNodesCreated(update.getMinNodesCreated());
            existing.setMinEdgesCreated(update.getMinEdgesCreated());
            return ResponseEntity.ok(repository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> delete(@PathVariable("ruleId") String ruleId) {
        return repository.findByRuleId(ruleId).map(rule -> {
            repository.delete(rule);
            return ResponseEntity.ok().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{ruleId}/enable")
    public ResponseEntity<GraphRuleConfig> enable(@PathVariable("ruleId") String ruleId) {
        return setEnabled(ruleId, true);
    }

    @PostMapping("/{ruleId}/disable")
    public ResponseEntity<GraphRuleConfig> disable(@PathVariable("ruleId") String ruleId) {
        return setEnabled(ruleId, false);
    }

    private ResponseEntity<GraphRuleConfig> setEnabled(String ruleId, boolean enabled) {
        return repository.findByRuleId(ruleId).map(rule -> {
            rule.setEnabled(enabled);
            return ResponseEntity.ok(repository.save(rule));
        }).orElse(ResponseEntity.notFound().build());
    }
}
