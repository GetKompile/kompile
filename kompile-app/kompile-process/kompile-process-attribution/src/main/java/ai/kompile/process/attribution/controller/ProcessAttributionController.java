/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.process.attribution.controller;

import ai.kompile.process.attribution.domain.ProcessEventAlert;
import ai.kompile.process.attribution.domain.ProcessRiskAssessment;
import ai.kompile.process.attribution.domain.StepAttributionResult;
import ai.kompile.process.attribution.service.ProcessAttributionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for process-level event attribution and risk assessment.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/process/attribution/run/{runId}/risk} — full run risk assessment</li>
 *   <li>{@code GET /api/process/attribution/run/{runId}/step/{stepId}/explain} — explain a step</li>
 *   <li>{@code GET /api/process/attribution/run/{runId}/step/{stepId}/control/{controlId}} — explain control failure</li>
 *   <li>{@code GET /api/process/attribution/definition/{defId}/risk} — definition-level risk assessment</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/process/attribution")
@CrossOrigin(origins = "*")
public class ProcessAttributionController {

    private final ProcessAttributionService service;

    @Autowired
    public ProcessAttributionController(ProcessAttributionService service) {
        this.service = service;
    }

    /**
     * Full risk assessment for a running workflow.
     */
    @GetMapping("/run/{runId}/risk")
    public ResponseEntity<ProcessRiskAssessment> assessRunRisk(
            @PathVariable String runId,
            @RequestParam(defaultValue = "true") boolean useLlm) {
        return ResponseEntity.ok(service.assessRunRisk(runId, useLlm));
    }

    /**
     * Explain why a specific step failed or produced unexpected results.
     */
    @GetMapping("/run/{runId}/step/{stepId}/explain")
    public ResponseEntity<StepAttributionResult> explainStep(
            @PathVariable String runId,
            @PathVariable String stepId,
            @RequestParam(defaultValue = "true") boolean useLlm) {
        return ResponseEntity.ok(service.explainStep(runId, stepId, useLlm));
    }

    /**
     * Explain why a control gate failed on a specific step.
     */
    @GetMapping("/run/{runId}/step/{stepId}/control/{controlId}")
    public ResponseEntity<ProcessEventAlert> explainControlFailure(
            @PathVariable String runId,
            @PathVariable String stepId,
            @PathVariable String controlId,
            @RequestParam(defaultValue = "true") boolean useLlm) {
        return ResponseEntity.ok(service.explainControlFailure(runId, stepId, controlId, useLlm));
    }

    /**
     * Pre-flight risk assessment for a process definition before starting a run.
     */
    @GetMapping("/definition/{defId}/risk")
    public ResponseEntity<ProcessRiskAssessment> assessDefinitionRisk(
            @PathVariable String defId,
            @RequestParam(defaultValue = "1") int version,
            @RequestParam(defaultValue = "true") boolean useLlm) {
        return ResponseEntity.ok(service.assessDefinitionRisk(defId, version, useLlm));
    }
}
