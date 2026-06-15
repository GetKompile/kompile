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

import ai.kompile.event.attribution.domain.*;
import ai.kompile.event.attribution.service.EventAttributionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * REST API for event attribution and prediction queries.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code POST /api/attribution/explain} — "Why did X happen?" query</li>
 *   <li>{@code POST /api/attribution/predict} — "What will happen next?" query</li>
 *   <li>{@code GET /api/attribution/explain/{nodeId}} — Quick attribution with defaults</li>
 *   <li>{@code GET /api/attribution/predict/{nodeId}} — Quick prediction with defaults</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/attribution")
public class EventAttributionController {

    private final EventAttributionService attributionService;

    @Autowired
    public EventAttributionController(EventAttributionService attributionService) {
        this.attributionService = attributionService;
    }

    /**
     * Full attribution query with all parameters.
     */
    @PostMapping("/explain")
    public ResponseEntity<AttributionResult> explain(@RequestBody AttributionQueryRequest request) {
        AttributionQuery query = AttributionQuery.builder()
                .targetNodeId(request.targetNodeId())
                .naturalLanguageQuery(request.question())
                .factSheetId(request.factSheetId())
                .maxDepth(request.maxDepth() != null ? request.maxDepth() : 5)
                .maxChains(request.maxChains() != null ? request.maxChains() : 5)
                .minConfidence(request.minConfidence() != null ? request.minConfidence() : 0.1)
                .useLlm(request.useLlm() != null ? request.useLlm() : true)
                .includeCounterfactuals(request.includeCounterfactuals() != null
                        ? request.includeCounterfactuals() : false)
                .allowedCausalTypes(request.allowedCausalTypes())
                .requiredEvidenceTypes(request.requiredEvidenceTypes())
                .build();

        AttributionResult result = attributionService.explain(query);
        return ResponseEntity.ok(result);
    }

    /**
     * Quick attribution with sensible defaults.
     */
    @GetMapping("/explain/{nodeId}")
    public ResponseEntity<AttributionResult> explainQuick(
            @PathVariable String nodeId,
            @RequestParam(required = false) String question,
            @RequestParam(required = false) Long factSheetId,
            @RequestParam(defaultValue = "5") int maxDepth,
            @RequestParam(defaultValue = "5") int maxChains,
            @RequestParam(defaultValue = "true") boolean useLlm,
            @RequestParam(defaultValue = "false") boolean includeCounterfactuals) {

        AttributionQuery query = AttributionQuery.builder()
                .targetNodeId(nodeId)
                .naturalLanguageQuery(question)
                .factSheetId(factSheetId)
                .maxDepth(maxDepth)
                .maxChains(maxChains)
                .useLlm(useLlm)
                .includeCounterfactuals(includeCounterfactuals)
                .build();

        return ResponseEntity.ok(attributionService.explain(query));
    }

    /**
     * Full prediction query with all parameters.
     */
    @PostMapping("/predict")
    public ResponseEntity<PredictionResult> predict(@RequestBody PredictionQueryRequest request) {
        PredictionQuery query = PredictionQuery.builder()
                .sourceNodeId(request.sourceNodeId())
                .naturalLanguageContext(request.context())
                .factSheetId(request.factSheetId())
                .maxDepth(request.maxDepth() != null ? request.maxDepth() : 3)
                .maxPredictions(request.maxPredictions() != null ? request.maxPredictions() : 10)
                .minProbability(request.minProbability() != null ? request.minProbability() : 0.1)
                .useLlm(request.useLlm() != null ? request.useLlm() : true)
                .build();

        PredictionResult result = attributionService.predict(query);
        return ResponseEntity.ok(result);
    }

    /**
     * Quick prediction with sensible defaults.
     */
    @GetMapping("/predict/{nodeId}")
    public ResponseEntity<PredictionResult> predictQuick(
            @PathVariable String nodeId,
            @RequestParam(required = false) String context,
            @RequestParam(required = false) Long factSheetId,
            @RequestParam(defaultValue = "3") int maxDepth,
            @RequestParam(defaultValue = "10") int maxPredictions,
            @RequestParam(defaultValue = "true") boolean useLlm) {

        PredictionQuery query = PredictionQuery.builder()
                .sourceNodeId(nodeId)
                .naturalLanguageContext(context)
                .factSheetId(factSheetId)
                .maxDepth(maxDepth)
                .maxPredictions(maxPredictions)
                .useLlm(useLlm)
                .build();

        return ResponseEntity.ok(attributionService.predict(query));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUEST DTOs
    // ═══════════════════════════════════════════════════════════════════════════

    public record AttributionQueryRequest(
            String targetNodeId,
            String question,
            Long factSheetId,
            Integer maxDepth,
            Integer maxChains,
            Double minConfidence,
            Boolean useLlm,
            Boolean includeCounterfactuals,
            Set<CausalEdgeType> allowedCausalTypes,
            Set<EvidenceType> requiredEvidenceTypes
    ) {}

    public record PredictionQueryRequest(
            String sourceNodeId,
            String context,
            Long factSheetId,
            Integer maxDepth,
            Integer maxPredictions,
            Double minProbability,
            Boolean useLlm
    ) {}
}
