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

import ai.kompile.event.attribution.domain.*;
import ai.kompile.event.attribution.service.EventAttributionService;
import ai.kompile.process.attribution.domain.ProcessRiskAssessment;
import ai.kompile.process.attribution.domain.StepAttributionResult;
import ai.kompile.process.attribution.domain.StepAttributionSummary;
import ai.kompile.process.attribution.service.ProcessAttributionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.ontology.ProvenanceCitation;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * REST endpoints for querying the full interpretability chain of a process:
 * Graph Evidence → Suggestion → Definition → Execution → Attribution.
 *
 * <p>Every response carries likelihood scores, evidence provenance, and causal
 * chains so that the full reasoning behind any process step is auditable.</p>
 */
@RestController
@RequestMapping("/api/process/lineage")
@ConditionalOnBean(ProcessAttributionService.class)
public class ProcessLineageController {

    private static final Logger log = LoggerFactory.getLogger(ProcessLineageController.class);
    private final ProcessEngineService processEngineService;
    private final ProcessAttributionService attributionService;

    @Autowired(required = false)
    private EventAttributionService eventAttributionService;

    @Autowired
    public ProcessLineageController(ProcessEngineService processEngineService,
                                     ProcessAttributionService attributionService) {
        this.processEngineService = processEngineService;
        this.attributionService = attributionService;
    }

    /**
     * Full lineage for a process definition: discovery provenance, per-step evidence,
     * graph node bindings, and pre-execution risk assessment.
     */
    @GetMapping("/definition/{definitionId}")
    public ResponseEntity<Map<String, Object>> getDefinitionLineage(
            @PathVariable String definitionId,
            @RequestParam(defaultValue = "-1") int version,
            @RequestParam(defaultValue = "false") boolean includeRiskAssessment) {

        ProcessDefinition def = processEngineService.getProcess(definitionId, version);
        Map<String, Object> lineage = new LinkedHashMap<>();

        // 1. Discovery provenance
        Map<String, Object> discovery = new LinkedHashMap<>();
        discovery.put("sourceSuggestionId", def.getSourceSuggestionId());
        discovery.put("sourceGraphNodeIds", def.getSourceGraphNodeIds());
        discovery.put("discoveryConfidence", def.getDiscoveryConfidence());
        discovery.put("factSheetId", def.getFactSheetId());
        lineage.put("discovery", discovery);

        // 2. Definition identity
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("id", def.getId());
        identity.put("name", def.getName());
        identity.put("version", def.getVersion());
        identity.put("status", def.getStatus());
        identity.put("parentProcessId", def.getParentProcessId());
        identity.put("childProcessIds", def.getChildProcessIds());
        lineage.put("definition", identity);

        // 3. Per-step provenance
        List<Map<String, Object>> stepLineages = new ArrayList<>();
        if (def.getPhases() != null) {
            for (ProcessPhase phase : def.getPhases()) {
                if (phase.getSteps() == null) continue;
                for (ProcessStep step : phase.getSteps()) {
                    Map<String, Object> stepInfo = new LinkedHashMap<>();
                    stepInfo.put("stepId", step.getId());
                    stepInfo.put("stepName", step.getName());
                    stepInfo.put("stepType", step.getStepType());
                    stepInfo.put("confidence", step.getConfidence());
                    stepInfo.put("graphNodeIds", step.getGraphNodeIds());

                    // Provenance citations
                    if (step.getProvenance() != null && !step.getProvenance().isEmpty()) {
                        List<Map<String, Object>> citations = new ArrayList<>();
                        for (ProvenanceCitation pc : step.getProvenance()) {
                            Map<String, Object> cite = new LinkedHashMap<>();
                            cite.put("sourceType", pc.getSourceType());
                            cite.put("sourceId", pc.getSourceId());
                            cite.put("graphNodeId", pc.getGraphNodeId());
                            cite.put("extractedText", pc.getExtractedText());
                            cite.put("confidence", pc.getConfidence());
                            cite.put("contentHash", pc.getContentHash());
                            citations.add(cite);
                        }
                        stepInfo.put("provenanceCitations", citations);
                    }
                    stepLineages.add(stepInfo);
                }
            }
        }
        lineage.put("steps", stepLineages);

        // 4. Optional: pre-execution risk assessment
        if (includeRiskAssessment) {
            try {
                ProcessRiskAssessment risk = attributionService.assessDefinitionRisk(
                        definitionId, version, false);
                lineage.put("riskAssessment", buildRiskSummary(risk));
            } catch (Exception e) {
                lineage.put("riskAssessment", Map.of("error", e.getMessage()));
            }
        }

        return ResponseEntity.ok(lineage);
    }

    /**
     * Full lineage for a specific step within a workflow run: execution evidence,
     * causal chains, influence scores, and likelihood.
     */
    @GetMapping("/step/{runId}/{stepId}")
    public ResponseEntity<Map<String, Object>> getStepLineage(
            @PathVariable String runId,
            @PathVariable String stepId) {

        WorkflowRun run = processEngineService.getRun(runId);
        ProcessDefinition def = processEngineService.getProcess(
                run.getProcessDefinitionId(), run.getProcessVersion());

        Map<String, Object> lineage = new LinkedHashMap<>();

        // 1. Step definition provenance
        ProcessStep stepDef = findStep(def, stepId);
        if (stepDef != null) {
            Map<String, Object> defInfo = new LinkedHashMap<>();
            defInfo.put("stepId", stepDef.getId());
            defInfo.put("stepName", stepDef.getName());
            defInfo.put("stepType", stepDef.getStepType());
            defInfo.put("definitionConfidence", stepDef.getConfidence());
            defInfo.put("graphNodeIds", stepDef.getGraphNodeIds());
            if (stepDef.getProvenance() != null) {
                defInfo.put("provenanceCitationCount", stepDef.getProvenance().size());
                defInfo.put("provenanceCitations", stepDef.getProvenance());
            }
            lineage.put("definition", defInfo);
        }

        // 2. Execution evidence
        StepExecution exec = findExecution(run, stepId);
        if (exec != null) {
            Map<String, Object> execInfo = new LinkedHashMap<>();
            execInfo.put("status", exec.getStatus());
            execInfo.put("executedBy", exec.getExecutedBy());
            execInfo.put("startedAt", exec.getStartedAt());
            execInfo.put("completedAt", exec.getCompletedAt());
            execInfo.put("inputHash", exec.getInputHash());
            execInfo.put("outputHash", exec.getOutputHash());
            execInfo.put("graphNodeIds", exec.getGraphNodeIds());

            if (exec.getEvidenceReliedOn() != null) {
                execInfo.put("evidenceReliedOn", exec.getEvidenceReliedOn());
            }
            if (exec.getStructuredEvidence() != null) {
                execInfo.put("structuredEvidence", exec.getStructuredEvidence());
            }
            lineage.put("execution", execInfo);
        }

        // 3. Attribution analysis
        if (stepDef != null && stepDef.getGraphNodeIds() != null && !stepDef.getGraphNodeIds().isEmpty()
                && eventAttributionService != null) {
            try {
                Map<String, Object> attribution = new LinkedHashMap<>();
                List<AttributionChain> allChains = new ArrayList<>();
                Map<String, Double> allInfluence = new LinkedHashMap<>();

                for (String nodeId : stepDef.getGraphNodeIds()) {
                    AttributionQuery query = AttributionQuery.builder()
                            .targetNodeId(nodeId)
                            .maxDepth(3).maxChains(5)
                            .minConfidence(0.1)
                            .useLlm(false)
                            .build();
                    AttributionResult result = eventAttributionService.explain(query);
                    allChains.addAll(result.getChains());
                    if (result.getInfluenceScores() != null) {
                        result.getInfluenceScores().forEach((k, v) ->
                                allInfluence.merge(k, v, Math::max));
                    }
                }

                attribution.put("causalChainCount", allChains.size());
                attribution.put("causalChains", allChains);
                attribution.put("influenceScores", allInfluence);

                // Overall likelihood from chain confidences
                if (!allChains.isEmpty()) {
                    double avgConfidence = allChains.stream()
                            .mapToDouble(AttributionChain::getOverallConfidence)
                            .average().orElse(0);
                    attribution.put("overallLikelihood", avgConfidence);
                    attribution.put("confidenceBand", AttributionConfidence.fromScore(avgConfidence).name());
                }

                // Include Bayesian posteriors and priors from the step attribution
                try {
                    StepAttributionResult stepAttr = attributionService.explainStep(runId, stepId, false);
                    if (stepAttr.getBayesianPosteriors() != null && !stepAttr.getBayesianPosteriors().isEmpty()) {
                        attribution.put("bayesianPosteriors", stepAttr.getBayesianPosteriors());
                    }
                    if (stepAttr.getBayesianPriors() != null && !stepAttr.getBayesianPriors().isEmpty()) {
                        attribution.put("bayesianPriors", stepAttr.getBayesianPriors());
                    }
                    attribution.put("riskScore", stepAttr.getRiskScore());
                } catch (Exception ex) {
                    log.debug("Bayesian inference unavailable for step lineage {}/{}: {}", runId, stepId, ex.getMessage());
                }

                lineage.put("attribution", attribution);
            } catch (Exception e) {
                lineage.put("attribution", Map.of("error", e.getMessage()));
            }
        }

        // 4. Run-level context
        Map<String, Object> runContext = new LinkedHashMap<>();
        runContext.put("runId", run.getId());
        runContext.put("processDefinitionId", run.getProcessDefinitionId());
        runContext.put("processVersion", run.getProcessVersion());
        runContext.put("runStatus", run.getStatus());
        runContext.put("overallLikelihood", run.getOverallLikelihood());
        runContext.put("riskAssessmentId", run.getRiskAssessmentId());
        runContext.put("sourceSuggestionId", run.getSourceSuggestionId());
        lineage.put("run", runContext);

        return ResponseEntity.ok(lineage);
    }

    /**
     * Full run lineage: all steps with their evidence trails and risk assessment.
     */
    @GetMapping("/run/{runId}")
    public ResponseEntity<Map<String, Object>> getRunLineage(
            @PathVariable String runId,
            @RequestParam(defaultValue = "false") boolean includeAttribution) {

        WorkflowRun run = processEngineService.getRun(runId);
        ProcessDefinition def = processEngineService.getProcess(
                run.getProcessDefinitionId(), run.getProcessVersion());

        Map<String, Object> lineage = new LinkedHashMap<>();

        // 1. Run identity and provenance
        Map<String, Object> runInfo = new LinkedHashMap<>();
        runInfo.put("runId", run.getId());
        runInfo.put("processDefinitionId", run.getProcessDefinitionId());
        runInfo.put("processName", def.getName());
        runInfo.put("processVersion", run.getProcessVersion());
        runInfo.put("status", run.getStatus());
        runInfo.put("startedAt", run.getStartedAt());
        runInfo.put("completedAt", run.getCompletedAt());
        runInfo.put("overallLikelihood", run.getOverallLikelihood());
        runInfo.put("riskAssessmentId", run.getRiskAssessmentId());
        runInfo.put("sourceSuggestionId", run.getSourceSuggestionId());
        lineage.put("run", runInfo);

        // 2. Discovery provenance
        Map<String, Object> discovery = new LinkedHashMap<>();
        discovery.put("sourceSuggestionId", def.getSourceSuggestionId());
        discovery.put("sourceGraphNodeIds", def.getSourceGraphNodeIds());
        discovery.put("discoveryConfidence", def.getDiscoveryConfidence());
        discovery.put("factSheetId", def.getFactSheetId());
        lineage.put("discovery", discovery);

        // 3. Per-step execution evidence
        List<Map<String, Object>> stepTrails = new ArrayList<>();
        if (run.getStepExecutions() != null) {
            for (StepExecution exec : run.getStepExecutions()) {
                Map<String, Object> trail = new LinkedHashMap<>();
                trail.put("stepId", exec.getStepId());
                trail.put("stepName", exec.getStepName());
                trail.put("status", exec.getStatus());
                trail.put("executedBy", exec.getExecutedBy());
                trail.put("inputHash", exec.getInputHash());
                trail.put("outputHash", exec.getOutputHash());

                // Definition-time provenance
                ProcessStep stepDef = findStep(def, exec.getStepId());
                if (stepDef != null) {
                    trail.put("definitionConfidence", stepDef.getConfidence());
                    trail.put("graphNodeIds", stepDef.getGraphNodeIds());
                    if (stepDef.getProvenance() != null) {
                        trail.put("provenanceCitationCount", stepDef.getProvenance().size());
                    }
                }

                // Execution-time evidence
                if (exec.getEvidenceReliedOn() != null) {
                    trail.put("evidenceReliedOn", exec.getEvidenceReliedOn());
                }
                if (exec.getStructuredEvidence() != null) {
                    trail.put("structuredEvidenceCount", exec.getStructuredEvidence().size());
                    trail.put("structuredEvidence", exec.getStructuredEvidence());
                }

                stepTrails.add(trail);
            }
        }
        lineage.put("steps", stepTrails);

        // 4. Risk assessment with full attribution
        if (includeAttribution) {
            try {
                ProcessRiskAssessment risk = attributionService.assessRunRisk(runId, false);
                lineage.put("riskAssessment", buildRiskSummary(risk));
            } catch (Exception e) {
                lineage.put("riskAssessment", Map.of("error", e.getMessage()));
            }
        }

        // 5. Control attestations
        if (run.getControlResults() != null && !run.getControlResults().isEmpty()) {
            lineage.put("controlAttestationCount", run.getControlResults().size());
            lineage.put("controlAttestations", run.getControlResults());
        }

        return ResponseEntity.ok(lineage);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> buildRiskSummary(ProcessRiskAssessment risk) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("assessmentId", risk.getAssessmentId());
        summary.put("overallRiskScore", risk.getOverallRiskScore());
        summary.put("riskLevel", risk.getRiskLevel());
        summary.put("alertCount", risk.getAlerts().size());
        summary.put("highRiskStepIds", risk.getHighRiskStepIds());
        summary.put("stepRiskScores", risk.getStepRiskScores());
        summary.put("computedAt", risk.getComputedAt());
        summary.put("computationTimeMs", risk.getComputationTimeMs());

        // Step attribution summaries with likelihood
        if (risk.getStepAttributionResults() != null && !risk.getStepAttributionResults().isEmpty()) {
            Map<String, Object> attributions = new LinkedHashMap<>();
            for (Map.Entry<String, StepAttributionSummary> entry : risk.getStepAttributionResults().entrySet()) {
                StepAttributionSummary s = entry.getValue();
                Map<String, Object> stepAttr = new LinkedHashMap<>();
                stepAttr.put("stepName", s.getStepName());
                stepAttr.put("riskScore", s.getRiskScore());
                stepAttr.put("confidenceBand", s.getConfidenceBand());
                stepAttr.put("causalChainCount", s.getCausalChains().size());
                stepAttr.put("influenceScoreCount", s.getInfluenceScores().size());
                stepAttr.put("bayesianInferenceAvailable", s.isBayesianInferenceAvailable());
                if (!s.getBayesianPosteriors().isEmpty()) {
                    stepAttr.put("bayesianPosteriors", s.getBayesianPosteriors());
                }
                if (!s.getBayesianPriors().isEmpty()) {
                    stepAttr.put("bayesianPriors", s.getBayesianPriors());
                }
                if (s.getNarrative() != null) {
                    stepAttr.put("narrative", s.getNarrative());
                }
                attributions.put(entry.getKey(), stepAttr);
            }
            summary.put("stepAttributions", attributions);
        }

        return summary;
    }

    private ProcessStep findStep(ProcessDefinition def, String stepId) {
        if (def.getPhases() == null) return null;
        for (ProcessPhase phase : def.getPhases()) {
            if (phase.getSteps() == null) continue;
            for (ProcessStep step : phase.getSteps()) {
                if (stepId.equals(step.getId())) return step;
            }
        }
        return null;
    }

    private StepExecution findExecution(WorkflowRun run, String stepId) {
        if (run.getStepExecutions() == null) return null;
        for (StepExecution exec : run.getStepExecutions()) {
            if (stepId.equals(exec.getStepId())) return exec;
        }
        return null;
    }
}
