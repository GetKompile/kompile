/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.controller;

import ai.kompile.event.attribution.domain.PslInferenceResult;
import ai.kompile.event.attribution.service.PslReasoningService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for PSL (Probabilistic Soft Logic / HL-MRF) inference over the
 * knowledge graph — the soft-logic counterpart to {@link BayesianNetworkController}.
 */
@RestController
@RequestMapping("/api/attribution/psl")
public class PslController {

    private final PslReasoningService pslService;

    @Autowired
    public PslController(PslReasoningService pslService) {
        this.pslService = pslService;
    }

    /**
     * Build the default causal-propagation PSL program and infer soft-truth values for
     * all node states, given optional soft evidence.
     */
    @PostMapping("/infer")
    public ResponseEntity<PslInferenceResult> infer(@RequestBody PslQueryRequest request) {
        int maxDepth = request.maxDepth() != null ? request.maxDepth() : 3;
        int maxNodes = request.maxNodes() != null ? request.maxNodes() : 100;
        Map<String, Double> evidence = request.evidence() != null ? request.evidence() : Map.of();

        PslInferenceResult result = pslService.infer(
                request.seedNodeIds(), evidence, maxDepth, maxNodes);
        return ResponseEntity.ok(result);
    }

    /**
     * Quick inference from a single seed node (no evidence).
     * {@code nodeId} is a query param, not a path segment, because KG node IDs embed
     * filesystem paths ('/') that Tomcat rejects — matching the Bayesian controller.
     */
    @GetMapping("/infer")
    public ResponseEntity<PslInferenceResult> inferFromNode(
            @RequestParam String nodeId,
            @RequestParam(defaultValue = "3") int maxDepth,
            @RequestParam(defaultValue = "100") int maxNodes) {
        PslInferenceResult result = pslService.infer(
                List.of(nodeId), Map.of(), maxDepth, maxNodes);
        return ResponseEntity.ok(result);
    }

    /**
     * Infer using caller-supplied PSL rules over the {@code State}/{@code Link}/{@code Prior}
     * predicates (collective classification). Returns 400 with a message if a rule is malformed.
     */
    @PostMapping("/infer/rules")
    public ResponseEntity<?> inferWithRules(@RequestBody PslRulesRequest request) {
        int maxDepth = request.maxDepth() != null ? request.maxDepth() : 3;
        int maxNodes = request.maxNodes() != null ? request.maxNodes() : 100;
        Map<String, Double> evidence = request.evidence() != null ? request.evidence() : Map.of();
        try {
            PslInferenceResult result = pslService.inferWithRules(
                    request.seedNodeIds(), request.rules(), evidence, maxDepth, maxNodes);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** Program/grounding statistics for a subgraph without running inference. */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(
            @RequestParam String nodeId,
            @RequestParam(defaultValue = "3") int maxDepth,
            @RequestParam(defaultValue = "100") int maxNodes) {
        Map<String, Object> stats = pslService.programStatistics(
                List.of(nodeId), maxDepth, maxNodes);
        return ResponseEntity.ok(stats);
    }

    // ─── Request DTOs ───────────────────────────────────────────────────────

    record PslQueryRequest(
            List<String> seedNodeIds,
            Map<String, Double> evidence,
            Integer maxDepth,
            Integer maxNodes
    ) {}

    record PslRulesRequest(
            List<String> seedNodeIds,
            List<String> rules,
            Map<String, Double> evidence,
            Integer maxDepth,
            Integer maxNodes
    ) {}
}
