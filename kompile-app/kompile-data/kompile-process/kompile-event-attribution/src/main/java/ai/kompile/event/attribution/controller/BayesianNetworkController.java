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

import ai.kompile.graph.reasoning.domain.BayesianInferenceResult;
import ai.kompile.graph.reasoning.domain.MpeResult;
import ai.kompile.graph.reasoning.domain.SensitivityResult;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RandomVariable;
import ai.kompile.event.attribution.service.BayesianNetworkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Bayesian network construction and inference
 * over the knowledge graph.
 */
@RestController
@RequestMapping("/api/attribution/bayesian")
public class BayesianNetworkController {

    private final BayesianNetworkService bayesianService;

    @Autowired
    public BayesianNetworkController(BayesianNetworkService bayesianService) {
        this.bayesianService = bayesianService;
    }

    /**
     * Build a Bayesian network and query posterior probabilities for all variables.
     *
     * @param request seed nodes, evidence, and configuration
     * @return posterior probabilities for all variables
     */
    @PostMapping("/query")
    public ResponseEntity<BayesianInferenceResult> queryPosteriors(
            @RequestBody BayesianQueryRequest request) {
        int maxDepth = request.maxDepth() != null ? request.maxDepth() : 3;
        int maxNodes = request.maxNodes() != null ? request.maxNodes() : 100;
        Map<String, Integer> evidence = request.evidence() != null ? request.evidence() : Map.of();

        BayesianInferenceResult result;
        if (request.queryNodeId() != null) {
            result = bayesianService.queryPosterior(
                    request.seedNodeIds(), request.queryNodeId(),
                    evidence, maxDepth, maxNodes);
        } else {
            result = bayesianService.queryAllPosteriors(
                    request.seedNodeIds(), evidence, maxDepth, maxNodes);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Quick query: build network from a single target and get all posteriors.
     */
    // nodeId is a query param, not a path segment: KG node IDs (e.g. document nodes)
    // embed filesystem paths ('/'), which Tomcat rejects as %2F in the URL path.
    // Matches the KnowledgeGraphController GET /edges?nodeId=... convention.
    @GetMapping("/query")
    public ResponseEntity<BayesianInferenceResult> queryFromNode(
            @RequestParam String nodeId,
            @RequestParam(defaultValue = "3") int maxDepth,
            @RequestParam(defaultValue = "100") int maxNodes) {
        BayesianInferenceResult result = bayesianService.queryAllPosteriors(
                List.of(nodeId), Map.of(), maxDepth, maxNodes);
        return ResponseEntity.ok(result);
    }

    /**
     * Most probable explanation: find the most likely state of all variables
     * given evidence.
     */
    @PostMapping("/mpe")
    public ResponseEntity<MpeResult> mostProbableExplanation(
            @RequestBody BayesianQueryRequest request) {
        int maxDepth = request.maxDepth() != null ? request.maxDepth() : 3;
        int maxNodes = request.maxNodes() != null ? request.maxNodes() : 100;
        Map<String, Integer> evidence = request.evidence() != null ? request.evidence() : Map.of();

        MpeResult mpe = bayesianService.mostProbableExplanation(
                request.seedNodeIds(), evidence, maxDepth, maxNodes);
        return ResponseEntity.ok(mpe);
    }

    /**
     * Get network statistics without running inference.
     */
    @GetMapping("/network/stats")
    public ResponseEntity<Map<String, Object>> networkStats(
            @RequestParam(required = false) String nodeId,
            @RequestParam(defaultValue = "3") int maxDepth,
            @RequestParam(defaultValue = "100") int maxNodes) {
        List<String> seeds = (nodeId == null || nodeId.isBlank()) ? List.of() : List.of(nodeId);
        Map<String, Object> stats = bayesianService.getNetworkStatistics(
                seeds, maxDepth, maxNodes);
        return ResponseEntity.ok(stats);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MEBN ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build an MTheory from a KG subgraph and run MEBN inference.
     * Returns entity-specific posteriors like P(isRisky(node_42) | evidence).
     */
    @PostMapping("/mebn/query")
    public ResponseEntity<BayesianInferenceResult> queryMebn(
            @RequestBody MebnQueryRequest request) {
        int maxDepth = request.maxDepth() != null ? request.maxDepth() : 3;
        int maxNodes = request.maxNodes() != null ? request.maxNodes() : 100;
        Map<String, Integer> evidence = request.evidence() != null ? request.evidence() : Map.of();

        BayesianInferenceResult result = bayesianService.queryMebnFromKg(
                request.seedNodeIds(), evidence, maxDepth, maxNodes);
        return ResponseEntity.ok(result);
    }

    /**
     * Quick MEBN query from a single target node.
     */
    @GetMapping("/mebn/query")
    public ResponseEntity<BayesianInferenceResult> queryMebnFromNode(
            @RequestParam String nodeId,
            @RequestParam(defaultValue = "3") int maxDepth,
            @RequestParam(defaultValue = "100") int maxNodes) {
        BayesianInferenceResult result = bayesianService.queryMebnFromKg(
                List.of(nodeId), Map.of(), maxDepth, maxNodes);
        return ResponseEntity.ok(result);
    }

    /**
     * Get MTheory statistics (entity types, MFrags, variables) without inference.
     */
    @GetMapping("/mebn/stats")
    public ResponseEntity<Map<String, Object>> mebnStats(
            @RequestParam(required = false) String nodeId,
            @RequestParam(defaultValue = "3") int maxDepth,
            @RequestParam(defaultValue = "100") int maxNodes) {
        List<String> seeds = (nodeId == null || nodeId.isBlank()) ? List.of() : List.of(nodeId);
        Map<String, Object> stats = bayesianService.getMebnStatistics(
                seeds, maxDepth, maxNodes);
        return ResponseEntity.ok(stats);
    }

    /**
     * Get MEBN structure for a node: which MFrag it belongs to, its role,
     * entity type, and related variables — alongside inference results with
     * per-variable MEBN metadata for graph visualization.
     */
    @GetMapping("/mebn/structure")
    public ResponseEntity<BayesianInferenceResult> mebnStructure(
            @RequestParam(required = false) String nodeId,
            @RequestParam(defaultValue = "3") int maxDepth,
            @RequestParam(defaultValue = "100") int maxNodes) {
        List<String> seeds = (nodeId == null || nodeId.isBlank()) ? List.of() : List.of(nodeId);
        BayesianInferenceResult result = bayesianService.queryMebnFromKg(
                seeds, Map.of(), maxDepth, maxNodes);
        return ResponseEntity.ok(result);
    }

    /**
     * Get the MEBN theory STRUCTURE — fragments, their typed resident/input variables
     * (name, entity-type signature, states, role), context constraints, and parent→child
     * edges with learned strengths. Surfaces the rich fragment relationships for the UI,
     * with no inference run.
     */
    @GetMapping("/mebn/theory")
    public ResponseEntity<MTheoryStructureDto> mebnTheory(
            @RequestParam(required = false) String nodeId,
            @RequestParam(defaultValue = "3") int maxDepth,
            @RequestParam(defaultValue = "100") int maxNodes) {
        List<String> seeds = (nodeId == null || nodeId.isBlank()) ? List.of() : List.of(nodeId);
        MTheory theory = bayesianService.buildMebnTheory(seeds, maxDepth, maxNodes);
        return ResponseEntity.ok(MTheoryStructureDto.from(theory));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SENSITIVITY ANALYSIS & WHAT-IF
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Sensitivity analysis: how much does the posterior of a query variable
     * change when each other variable is observed?
     */
    @PostMapping("/sensitivity")
    public ResponseEntity<SensitivityResult> sensitivityAnalysis(
            @RequestBody SensitivityRequest request) {
        int maxDepth = request.maxDepth() != null ? request.maxDepth() : 3;
        int maxNodes = request.maxNodes() != null ? request.maxNodes() : 100;
        double epsilon = request.epsilon() != null ? request.epsilon() : 0.01;
        Map<String, Integer> evidence = request.evidence() != null ? request.evidence() : Map.of();

        SensitivityResult result = bayesianService.sensitivityAnalysis(
                request.seedNodeIds(), request.queryNodeId(),
                evidence, epsilon, maxDepth, maxNodes);
        return ResponseEntity.ok(result);
    }

    /**
     * What-if query: compute posteriors under hypothetical evidence.
     */
    @PostMapping("/whatif")
    public ResponseEntity<BayesianInferenceResult> whatIfQuery(
            @RequestBody WhatIfRequest request) {
        int maxDepth = request.maxDepth() != null ? request.maxDepth() : 3;
        int maxNodes = request.maxNodes() != null ? request.maxNodes() : 100;
        Map<String, Integer> hypothetical = request.hypotheticalEvidence() != null
                ? request.hypotheticalEvidence() : Map.of();

        BayesianInferenceResult result = bayesianService.whatIfQuery(
                request.seedNodeIds(), hypothetical, maxDepth, maxNodes);
        return ResponseEntity.ok(result);
    }

    /**
     * Quick sensitivity: which variables most influence a given node?
     */
    @GetMapping("/sensitivity")
    public ResponseEntity<SensitivityResult> quickSensitivity(
            @RequestParam String nodeId,
            @RequestParam(defaultValue = "3") int maxDepth,
            @RequestParam(defaultValue = "50") int maxNodes) {
        SensitivityResult result = bayesianService.sensitivityAnalysis(
                List.of(nodeId), nodeId, Map.of(), 0.01, maxDepth, maxNodes);
        return ResponseEntity.ok(result);
    }

    /**
     * Query MEBN posteriors filtered by entity type.
     * E.g. "give me all isRisky posteriors for entities of type ENTITY"
     */
    @PostMapping("/mebn/query/byType")
    public ResponseEntity<BayesianInferenceResult> queryMebnByType(
            @RequestBody MebnTypeQueryRequest request) {
        int maxDepth = request.maxDepth() != null ? request.maxDepth() : 3;
        int maxNodes = request.maxNodes() != null ? request.maxNodes() : 100;
        Map<String, Integer> evidence = request.evidence() != null ? request.evidence() : Map.of();

        BayesianInferenceResult fullResult = bayesianService.queryMebnFromKg(
                request.seedNodeIds(), evidence, maxDepth, maxNodes);

        // Filter posteriors to only variables whose KG node matches the entity type
        if (request.entityType() != null && !request.entityType().isBlank()) {
            Map<String, Double> filtered = new LinkedHashMap<>();
            Map<String, String> filteredNodeIds = new LinkedHashMap<>();
            Map<String, String> filteredTitles = new LinkedHashMap<>();

            for (Map.Entry<String, Double> entry : fullResult.getPosteriors().entrySet()) {
                String varName = entry.getKey();
                String nodeId = fullResult.getVariableToNodeId().get(varName);
                String title = fullResult.getVariableToTitle().get(varName);
                // Check if the variable name contains the entity type pattern
                if (varName.toUpperCase().contains(request.entityType().toUpperCase())
                        || (title != null && title.toUpperCase().contains(request.entityType().toUpperCase()))) {
                    filtered.put(varName, entry.getValue());
                    if (nodeId != null) filteredNodeIds.put(varName, nodeId);
                    if (title != null) filteredTitles.put(varName, title);
                }
            }

            // Also filter priors to match the same variable set
            if (fullResult.getPriors() != null) {
                Map<String, Double> filteredPriors = new LinkedHashMap<>();
                for (String varName : filtered.keySet()) {
                    Double prior = fullResult.getPriors().get(varName);
                    if (prior != null) {
                        filteredPriors.put(varName, prior);
                    }
                }
                fullResult.setPriors(filteredPriors);
            }

            fullResult.setPosteriors(filtered);
            fullResult.setVariableToNodeId(filteredNodeIds);
            fullResult.setVariableToTitle(filteredTitles);
        }

        return ResponseEntity.ok(fullResult);
    }

    // ─── Request DTOs ───────────────────────────────────────────────────────

    record BayesianQueryRequest(
            List<String> seedNodeIds,
            String queryNodeId,
            Map<String, Integer> evidence,
            Integer maxDepth,
            Integer maxNodes
    ) {}

    record MebnQueryRequest(
            List<String> seedNodeIds,
            Map<String, Integer> evidence,
            Integer maxDepth,
            Integer maxNodes
    ) {}

    record SensitivityRequest(
            List<String> seedNodeIds,
            String queryNodeId,
            Map<String, Integer> evidence,
            Double epsilon,
            Integer maxDepth,
            Integer maxNodes
    ) {}

    record WhatIfRequest(
            List<String> seedNodeIds,
            Map<String, Integer> hypotheticalEvidence,
            Integer maxDepth,
            Integer maxNodes
    ) {}

    record MebnTypeQueryRequest(
            List<String> seedNodeIds,
            String entityType,
            Map<String, Integer> evidence,
            Integer maxDepth,
            Integer maxNodes
    ) {}

    // ─── MEBN theory-structure response DTOs ────────────────────────────────

    /** A serialized MEBN theory: its fragments and their structure. */
    record MTheoryStructureDto(String name, List<MFragDto> fragments) {
        static MTheoryStructureDto from(MTheory t) {
            return new MTheoryStructureDto(
                    t.getName(),
                    t.getMFrags().stream().map(MFragDto::from).toList());
        }
    }

    /** One MEBN fragment: variables by role, context constraints, and parent→child edges. */
    record MFragDto(String name, List<RvDto> residentNodes, List<RvDto> inputNodes,
                    List<String> contexts, List<EdgeDto> edges) {
        static MFragDto from(MFrag f) {
            return new MFragDto(
                    f.getName(),
                    f.getResidentNodes().stream().map(RvDto::from).toList(),
                    f.getInputNodes().stream().map(RvDto::from).toList(),
                    f.getContextConstraints().stream().map(Object::toString).toList(),
                    f.getEdgeStrengths().entrySet().stream()
                            .map(e -> EdgeDto.parse(e.getKey(), e.getValue())).toList());
        }
    }

    /** A parameterized random variable: name, entity-type signature, states, and MEBN role. */
    record RvDto(String name, String signature, List<String> states, String role) {
        static RvDto from(RandomVariable rv) {
            return new RvDto(rv.getName(), rv.toString(), rv.getStates(), rv.getRole().name());
        }
    }

    /** A directed parent→child edge inside a fragment with its learned noisy-OR strength. */
    record EdgeDto(String parent, String child, double strength) {
        static EdgeDto parse(String key, double strength) {
            int idx = key.indexOf("->");
            String p = idx >= 0 ? key.substring(0, idx).trim() : key;
            String c = idx >= 0 ? key.substring(idx + 2).trim() : "";
            return new EdgeDto(p, c, strength);
        }
    }
}
