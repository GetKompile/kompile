/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.process.attribution.service;

import ai.kompile.event.attribution.algorithm.bayesian.BayesianNetwork;
import ai.kompile.event.attribution.algorithm.bayesian.BayesianNetworkBuilder;
import ai.kompile.event.attribution.algorithm.bayesian.BayesianNode;
import ai.kompile.event.attribution.algorithm.bayesian.VariableElimination;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.KgMTheoryBuilder;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.MTheory;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.SSBNGenerator;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.logic.GraphKnowledgeBase;
import ai.kompile.event.attribution.domain.*;
import ai.kompile.event.attribution.service.EventAttributionService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.attribution.domain.*;
import ai.kompile.process.attribution.domain.StepAttributionSummary;
import ai.kompile.event.attribution.domain.AttributionConfidence;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bridges the event attribution framework into the process engine.
 *
 * <h3>Capabilities</h3>
 * <ul>
 *   <li><b>Step-level root cause analysis</b> — for a failed or anomalous step, traces
 *       causal chains through the knowledge graph to explain what went wrong</li>
 *   <li><b>Run-level risk assessment</b> — scans all steps with KG bindings, runs
 *       forward prediction to detect potential downstream failures before they happen</li>
 *   <li><b>Control failure explanation</b> — when a SOX/J-SOX control gate fails,
 *       traces the causal chain to explain the root data issue</li>
 *   <li><b>Process definition risk scoring</b> — analyzes a process definition's
 *       graph node bindings to assess inherent risk before the first run</li>
 * </ul>
 */
@Service
public class ProcessAttributionService {

    private static final Logger log = LoggerFactory.getLogger(ProcessAttributionService.class);
    private static final double HIGH_RISK_THRESHOLD = 0.6;

    private final EventAttributionService attributionService;
    private final ProcessEngineService processEngineService;
    private final KnowledgeGraphService graphService;

    @Autowired
    public ProcessAttributionService(EventAttributionService attributionService,
                                     ProcessEngineService processEngineService,
                                     KnowledgeGraphService graphService) {
        this.attributionService = attributionService;
        this.processEngineService = processEngineService;
        this.graphService = graphService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STEP-LEVEL ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Explains why a specific step failed or produced unexpected results by tracing
     * causal chains through the step's bound graph nodes.
     *
     * @param runId  the workflow run ID
     * @param stepId the step that failed or needs explanation
     * @param useLlm whether to use LLM for narrative generation
     * @return attribution result for the step
     */
    public StepAttributionResult explainStep(String runId, String stepId, boolean useLlm) {
        WorkflowRun run = processEngineService.getRun(runId);
        ProcessDefinition def = processEngineService.getProcess(
                run.getProcessDefinitionId(), run.getProcessVersion());

        ProcessStep stepDef = findStepDef(def, stepId);
        if (stepDef == null) {
            return StepAttributionResult.builder()
                    .stepId(stepId).hasGraphBindings(false).build();
        }

        List<String> graphNodeIds = stepDef.getGraphNodeIds();
        if (graphNodeIds == null || graphNodeIds.isEmpty()) {
            return StepAttributionResult.builder()
                    .stepId(stepId).stepName(stepDef.getName())
                    .hasGraphBindings(false).build();
        }

        // Run attribution on each bound graph node and merge
        AttributionResult mergedAttribution = null;
        PredictionResult mergedPrediction = null;
        double maxRisk = 0.0;

        for (String nodeId : graphNodeIds) {
            AttributionQuery attrQuery = AttributionQuery.builder()
                    .targetNodeId(nodeId)
                    .maxDepth(5).maxChains(5).minConfidence(0.05)
                    .useLlm(useLlm).includeCounterfactuals(true)
                    .build();
            AttributionResult attrResult = attributionService.explain(attrQuery);

            PredictionQuery predQuery = PredictionQuery.builder()
                    .sourceNodeId(nodeId)
                    .maxDepth(3).maxPredictions(5)
                    .useLlm(useLlm)
                    .build();
            PredictionResult predResult = attributionService.predict(predQuery);

            if (mergedAttribution == null) {
                mergedAttribution = attrResult;
            } else {
                mergeAttributionInto(mergedAttribution, attrResult);
            }

            if (mergedPrediction == null) {
                mergedPrediction = predResult;
            } else {
                mergePredictionInto(mergedPrediction, predResult);
            }

            double nodeRisk = computeNodeRisk(attrResult, predResult);
            maxRisk = Math.max(maxRisk, nodeRisk);
        }

        // Bayesian inference for the step's graph nodes.
        // If this step failed, observe its graph nodes as TRUE evidence so that
        // posteriors reflect the downstream impact of the failure.
        StepAttributionResult.StepAttributionResultBuilder builder = StepAttributionResult.builder()
                .stepId(stepId)
                .stepName(stepDef.getName())
                .attribution(mergedAttribution)
                .prediction(mergedPrediction)
                .riskScore(maxRisk)
                .hasGraphBindings(true);

        try {
            Set<String> failedStepIds = getFailedStepIds(run);
            Set<String> observedEvidence = new LinkedHashSet<>();
            // Observe failed steps' graph nodes as evidence
            for (ProcessStep s : flattenSteps(def)) {
                if (failedStepIds.contains(s.getId()) && s.getGraphNodeIds() != null) {
                    observedEvidence.addAll(s.getGraphNodeIds());
                }
            }
            BayesianResult bayesian = computePosteriorsAndPriors(graphNodeIds, observedEvidence);
            if (!bayesian.posteriors().isEmpty()) {
                builder.bayesianPosteriors(bayesian.posteriors());
                builder.bayesianPriors(bayesian.priors());
            }
        } catch (Exception e) {
            log.debug("Bayesian inference unavailable for step {}: {}", stepId, e.getMessage());
        }

        return builder.build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RUN-LEVEL RISK ASSESSMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Performs a comprehensive risk assessment for a running workflow.
     * Analyzes every step that has KG bindings and generates alerts for
     * detected risks and predicted events.
     *
     * @param runId  the workflow run ID
     * @param useLlm whether to use LLM for narrative generation
     * @return the risk assessment with alerts and per-step scores
     */
    public ProcessRiskAssessment assessRunRisk(String runId, boolean useLlm) {
        long startTime = System.currentTimeMillis();
        WorkflowRun run = processEngineService.getRun(runId);
        ProcessDefinition def = processEngineService.getProcess(
                run.getProcessDefinitionId(), run.getProcessVersion());

        List<ProcessStep> allSteps = flattenSteps(def);
        List<ProcessEventAlert> alerts = new ArrayList<>();
        Map<String, Double> stepRiskScores = new LinkedHashMap<>();
        Map<String, StepAttributionSummary> stepAttributionResults = new LinkedHashMap<>();
        Set<String> failedStepIds = getFailedStepIds(run);

        // Collect graph node IDs from failed steps as observed evidence for Bayesian inference
        Set<String> failedNodeIds = new LinkedHashSet<>();
        for (ProcessStep step : allSteps) {
            if (failedStepIds.contains(step.getId()) && step.getGraphNodeIds() != null) {
                failedNodeIds.addAll(step.getGraphNodeIds());
            }
        }

        for (ProcessStep step : allSteps) {
            List<String> nodeIds = step.getGraphNodeIds();
            if (nodeIds == null || nodeIds.isEmpty()) {
                continue;
            }

            double stepRisk = 0.0;

            for (String nodeId : nodeIds) {
                // If the step already failed, do root cause analysis
                if (failedStepIds.contains(step.getId())) {
                    AttributionQuery query = AttributionQuery.builder()
                            .targetNodeId(nodeId)
                            .maxDepth(5).maxChains(3).minConfidence(0.05)
                            .useLlm(useLlm).includeCounterfactuals(true)
                            .build();
                    AttributionResult result = attributionService.explain(query);

                    if (!result.getChains().isEmpty()) {
                        alerts.add(buildRootCauseAlert(run, step, nodeId, result));
                    }
                    stepRisk = Math.max(stepRisk, 0.9); // Failed step is high risk
                }

                // Forward prediction for pending/running steps
                if (!failedStepIds.contains(step.getId())) {
                    PredictionQuery predQuery = PredictionQuery.builder()
                            .sourceNodeId(nodeId)
                            .maxDepth(3).maxPredictions(5)
                            .useLlm(useLlm)
                            .build();
                    PredictionResult predResult = attributionService.predict(predQuery);

                    List<PredictedEvent> riskyPredictions = predResult.getPredictions().stream()
                            .filter(p -> p.getProbability() > 0.3)
                            .toList();

                    if (!riskyPredictions.isEmpty()) {
                        alerts.add(buildPredictionAlert(run, step, nodeId, predResult, riskyPredictions));
                        double maxProb = riskyPredictions.stream()
                                .mapToDouble(PredictedEvent::getProbability).max().orElse(0);
                        stepRisk = Math.max(stepRisk, maxProb * 0.7);
                    }
                }
            }

            // Store full attribution summary for this step (including Bayesian posteriors)
            // Pass failedNodeIds as evidence so posteriors differ from priors
            if (!nodeIds.isEmpty()) {
                stepAttributionResults.put(step.getId(),
                        buildStepAttributionSummary(step, nodeIds, stepRisk, failedNodeIds));
            }

            if (stepRisk > 0) {
                stepRiskScores.put(step.getId(), stepRisk);
            }
        }

        // Propagate risk from upstream steps to downstream dependencies
        propagateRisk(allSteps, stepRiskScores);

        // Sort alerts by severity then confidence
        alerts.sort(Comparator
                .comparing(ProcessEventAlert::getSeverity)
                .thenComparing(Comparator.comparingDouble(ProcessEventAlert::getConfidence).reversed()));

        double overallRisk = stepRiskScores.isEmpty() ? 0.0 :
                stepRiskScores.values().stream().mapToDouble(d -> d).max().orElse(0.0);

        List<String> highRiskSteps = stepRiskScores.entrySet().stream()
                .filter(e -> e.getValue() > HIGH_RISK_THRESHOLD)
                .map(Map.Entry::getKey)
                .toList();

        // Bayesian inference on alerts: attach posteriors conditioned on failures
        for (int i = 0; i < alerts.size(); i++) {
            ProcessEventAlert alert = alerts.get(i);
            if (alert.getStepId() != null) {
                ProcessStep step = findStepDef(def, alert.getStepId());
                if (step != null && step.getGraphNodeIds() != null && !step.getGraphNodeIds().isEmpty()) {
                    try {
                        BayesianResult bayesian = computePosteriorsAndPriors(
                                step.getGraphNodeIds(), failedNodeIds);
                        if (!bayesian.posteriors().isEmpty()) {
                            alerts.set(i, alert.toBuilder()
                                    .bayesianPosteriors(bayesian.posteriors())
                                    .bayesianPriors(bayesian.priors())
                                    .build());
                        }
                    } catch (Exception e) {
                        log.debug("Bayesian inference unavailable for alert {}: {}",
                                alert.getAlertId(), e.getMessage());
                    }
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        return ProcessRiskAssessment.builder()
                .assessmentId(UUID.randomUUID().toString())
                .workflowRunId(runId)
                .processDefinitionId(run.getProcessDefinitionId())
                .overallRiskScore(overallRisk)
                .riskLevel(scoreToSeverity(overallRisk))
                .alerts(alerts)
                .stepRiskScores(stepRiskScores)
                .highRiskStepIds(highRiskSteps)
                .stepAttributionResults(stepAttributionResults)
                .llmUsed(useLlm)
                .computedAt(Instant.now())
                .computationTimeMs(elapsed)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS DEFINITION RISK ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Analyzes a process definition's graph node bindings to assess inherent risk
     * before any run is started. Useful for "what could go wrong?" pre-flight checks.
     *
     * @param processDefinitionId the process definition to analyze
     * @param version            the version to analyze
     * @param useLlm             whether to use LLM
     * @return risk assessment at the definition level
     */
    public ProcessRiskAssessment assessDefinitionRisk(String processDefinitionId,
                                                       int version, boolean useLlm) {
        long startTime = System.currentTimeMillis();
        ProcessDefinition def = processEngineService.getProcess(processDefinitionId, version);
        List<ProcessStep> allSteps = flattenSteps(def);
        List<ProcessEventAlert> alerts = new ArrayList<>();
        Map<String, Double> stepRiskScores = new LinkedHashMap<>();
        Map<String, StepAttributionSummary> stepAttributionResults = new LinkedHashMap<>();

        for (ProcessStep step : allSteps) {
            List<String> nodeIds = step.getGraphNodeIds();
            if (nodeIds == null || nodeIds.isEmpty()) continue;

            double stepRisk = 0.0;

            for (String nodeId : nodeIds) {
                PredictionQuery query = PredictionQuery.builder()
                        .sourceNodeId(nodeId)
                        .maxDepth(3).maxPredictions(5)
                        .useLlm(useLlm)
                        .build();
                PredictionResult result = attributionService.predict(query);

                List<PredictedEvent> riskyPredictions = result.getPredictions().stream()
                        .filter(p -> p.getProbability() > 0.3)
                        .toList();

                if (!riskyPredictions.isEmpty()) {
                    ProcessEventAlert alert = ProcessEventAlert.builder()
                            .alertId(UUID.randomUUID().toString())
                            .processDefinitionId(processDefinitionId)
                            .stepId(step.getId())
                            .targetNodeId(nodeId)
                            .severity(AlertSeverity.MEDIUM)
                            .alertType("RISK_ASSESSMENT")
                            .title("Potential risk in step: " + step.getName())
                            .explanation("Forward prediction from graph node " + nodeId +
                                    " shows " + riskyPredictions.size() + " potential downstream events")
                            .predictions(riskyPredictions)
                            .confidence(riskyPredictions.stream()
                                    .mapToDouble(PredictedEvent::getProbability).average().orElse(0))
                            .llmUsed(result.isLlmUsed())
                            .createdAt(Instant.now())
                            .build();
                    alerts.add(alert);

                    double maxProb = riskyPredictions.stream()
                            .mapToDouble(PredictedEvent::getProbability).max().orElse(0);
                    stepRisk = Math.max(stepRisk, maxProb * 0.6);
                }
            }

            // Store full attribution summary for this step (including Bayesian posteriors)
            // No run exists for definition risk — pass empty evidence
            if (!nodeIds.isEmpty()) {
                stepAttributionResults.put(step.getId(),
                        buildStepAttributionSummary(step, nodeIds, stepRisk, Set.of()));
            }

            if (stepRisk > 0) {
                stepRiskScores.put(step.getId(), stepRisk);
            }
        }

        propagateRisk(allSteps, stepRiskScores);

        alerts.sort(Comparator
                .comparing(ProcessEventAlert::getSeverity)
                .thenComparing(Comparator.comparingDouble(ProcessEventAlert::getConfidence).reversed()));

        double overallRisk = stepRiskScores.isEmpty() ? 0.0 :
                stepRiskScores.values().stream().mapToDouble(d -> d).max().orElse(0.0);

        List<String> highRiskSteps = stepRiskScores.entrySet().stream()
                .filter(e -> e.getValue() > HIGH_RISK_THRESHOLD)
                .map(Map.Entry::getKey)
                .toList();

        long elapsed = System.currentTimeMillis() - startTime;

        return ProcessRiskAssessment.builder()
                .assessmentId(UUID.randomUUID().toString())
                .processDefinitionId(processDefinitionId)
                .overallRiskScore(overallRisk)
                .riskLevel(scoreToSeverity(overallRisk))
                .alerts(alerts)
                .stepRiskScores(stepRiskScores)
                .highRiskStepIds(highRiskSteps)
                .stepAttributionResults(stepAttributionResults)
                .llmUsed(useLlm)
                .computedAt(Instant.now())
                .computationTimeMs(elapsed)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONTROL FAILURE EXPLANATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * When a control gate fails, explains the root cause by tracing causal
     * chains through the graph nodes that the control's step is bound to.
     *
     * @param runId     the workflow run
     * @param stepId    the step with the failed control
     * @param controlId the control that failed
     * @param useLlm    whether to use LLM
     * @return alert explaining the control failure
     */
    public ProcessEventAlert explainControlFailure(String runId, String stepId,
                                                     String controlId, boolean useLlm) {
        WorkflowRun run = processEngineService.getRun(runId);
        ProcessDefinition def = processEngineService.getProcess(
                run.getProcessDefinitionId(), run.getProcessVersion());

        ProcessStep stepDef = findStepDef(def, stepId);
        if (stepDef == null || stepDef.getGraphNodeIds() == null || stepDef.getGraphNodeIds().isEmpty()) {
            return ProcessEventAlert.builder()
                    .alertId(UUID.randomUUID().toString())
                    .workflowRunId(runId)
                    .processDefinitionId(run.getProcessDefinitionId())
                    .stepId(stepId)
                    .severity(AlertSeverity.HIGH)
                    .alertType("CONTROL_FAILURE_EXPLANATION")
                    .title("Control " + controlId + " failed on step " + stepId)
                    .explanation("No graph node bindings available — cannot trace root cause through knowledge graph")
                    .createdAt(Instant.now())
                    .build();
        }

        // Run attribution on the first graph node
        String targetNodeId = stepDef.getGraphNodeIds().get(0);
        AttributionQuery query = AttributionQuery.builder()
                .targetNodeId(targetNodeId)
                .naturalLanguageQuery("Why did control " + controlId + " fail?")
                .maxDepth(5).maxChains(3).minConfidence(0.05)
                .useLlm(useLlm).includeCounterfactuals(true)
                .build();
        AttributionResult result = attributionService.explain(query);

        // Compute Bayesian posteriors and priors for the control's graph nodes
        ProcessEventAlert.ProcessEventAlertBuilder alertBuilder = ProcessEventAlert.builder()
                .alertId(UUID.randomUUID().toString())
                .workflowRunId(runId)
                .processDefinitionId(run.getProcessDefinitionId())
                .stepId(stepId)
                .targetNodeId(targetNodeId)
                .severity(AlertSeverity.HIGH)
                .alertType("CONTROL_FAILURE_EXPLANATION")
                .title("Control " + controlId + " failed: root cause analysis")
                .explanation(result.getSynthesizedExplanation() != null
                        ? result.getSynthesizedExplanation()
                        : "Found " + result.getChains().size() + " causal chains explaining the failure")
                .causalChains(result.getChains())
                .confidence(result.getChains().isEmpty() ? 0.0 :
                        result.getChains().get(0).getOverallConfidence())
                .llmUsed(result.isLlmUsed())
                .createdAt(Instant.now());

        try {
            // Control failure means the step's graph nodes are observed as TRUE
            Set<String> observedEvidence = new LinkedHashSet<>(stepDef.getGraphNodeIds());
            // Also include any other failed steps as evidence
            Set<String> failedStepIds = getFailedStepIds(run);
            for (ProcessStep s : flattenSteps(def)) {
                if (failedStepIds.contains(s.getId()) && s.getGraphNodeIds() != null) {
                    observedEvidence.addAll(s.getGraphNodeIds());
                }
            }
            BayesianResult bayesian = computePosteriorsAndPriors(
                    stepDef.getGraphNodeIds(), observedEvidence);
            if (!bayesian.posteriors().isEmpty()) {
                alertBuilder.bayesianPosteriors(bayesian.posteriors());
                alertBuilder.bayesianPriors(bayesian.priors());
            }
        } catch (Exception e) {
            log.debug("Bayesian inference unavailable for control failure explanation: {}", e.getMessage());
        }

        return alertBuilder.build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Builds a full attribution summary for a single step, including causal chains,
     * influence scores, and Bayesian posterior probabilities.
     *
     * <p>The Bayesian inference constructs a network from the step's graph node IDs
     * and computes P(node=true | empty evidence) for each node. This gives a
     * "baseline likelihood" for each entity involved in the step — nodes with
     * high posteriors are inherently more likely to be active/relevant.</p>
     */
    private StepAttributionSummary buildStepAttributionSummary(ProcessStep step,
                                                                 List<String> nodeIds,
                                                                 double stepRisk,
                                                                 Set<String> observedTrueNodeIds) {
        StepAttributionSummary.StepAttributionSummaryBuilder summaryBuilder = StepAttributionSummary.builder()
                .stepId(step.getId())
                .stepName(step.getName())
                .graphNodeIds(nodeIds)
                .riskScore(stepRisk)
                .confidenceBand(AttributionConfidence.fromScore(1.0 - stepRisk))
                .computedAt(Instant.now());

        // Merge causal chains and influence scores from all graph nodes
        List<AttributionChain> allChains = new ArrayList<>();
        Map<String, Double> allInfluence = new LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            AttributionQuery attrQuery = AttributionQuery.builder()
                    .targetNodeId(nodeId).maxDepth(3).maxChains(3)
                    .minConfidence(0.1).useLlm(false).build();
            AttributionResult attrResult = attributionService.explain(attrQuery);
            allChains.addAll(attrResult.getChains());
            if (attrResult.getInfluenceScores() != null) {
                attrResult.getInfluenceScores().forEach((k, v) ->
                        allInfluence.merge(k, v, Math::max));
            }
        }
        summaryBuilder.causalChains(allChains).influenceScores(allInfluence);

        // Bayesian inference: try MEBN first (entity-specific posteriors), fall back to flat BN
        try {
            BayesianResult bayesian = computePosteriorsAndPriors(nodeIds, observedTrueNodeIds);
            if (!bayesian.posteriors().isEmpty()) {
                summaryBuilder.bayesianPosteriors(bayesian.posteriors());
                summaryBuilder.bayesianPriors(bayesian.priors());
                summaryBuilder.bayesianInferenceAvailable(true);
            }
        } catch (Exception e) {
            log.debug("Bayesian inference unavailable for step {}: {}", step.getId(), e.getMessage());
            summaryBuilder.bayesianInferenceAvailable(false);
        }

        return summaryBuilder.build();
    }

    /**
     * Compute posterior probabilities for a set of graph node IDs.
     *
     * <p>Strategy: try MEBN first (entity-type-aware, per-entity posteriors via
     * MTheory → SSBN → VariableElimination). If the MEBN network has fewer than
     * 3 nodes (too small for meaningful entity-level inference), fall back to the
     * flat BN path (structure-aware but not entity-type-aware).</p>
     *
     * @param nodeIds KG node IDs bound to the process step
     * @return map of KG node ID → posterior P(node=TRUE | evidence)
     */
    private record BayesianResult(Map<String, Double> posteriors, Map<String, Double> priors) {
        static final BayesianResult EMPTY = new BayesianResult(Map.of(), Map.of());
    }

    private BayesianResult computePosteriorsAndPriors(List<String> nodeIds) {
        return computePosteriorsAndPriors(nodeIds, Set.of());
    }

    /**
     * Compute priors (no evidence) and posteriors (with observed evidence) for
     * a set of graph node IDs.
     *
     * <p>When {@code observedTrueNodeIds} is non-empty, the posteriors reflect
     * conditioned probabilities — P(node=TRUE | observed nodes are TRUE). This
     * produces a meaningful prior→posterior shift for nodes downstream of
     * observed failures or events.</p>
     *
     * @param nodeIds             KG node IDs to compute over
     * @param observedTrueNodeIds KG node IDs observed as TRUE (e.g. failed steps' nodes)
     */
    private BayesianResult computePosteriorsAndPriors(List<String> nodeIds,
                                                       Set<String> observedTrueNodeIds) {
        // Try MEBN path: entity-type-aware inference
        try {
            KgMTheoryBuilder mebnBuilder = new KgMTheoryBuilder(graphService)
                    .maxDepth(2)
                    .maxNodes(50);
            MTheory mTheory = mebnBuilder.build(nodeIds);

            if (!mTheory.getMFrags().isEmpty()) {
                GraphKnowledgeBase kb = new GraphKnowledgeBase(graphService);
                for (var entityType : mTheory.getEntityTypes()) {
                    kb.registerEntityType(entityType.getTypeName(), entityType.getEntityIds());
                }

                SSBNGenerator generator = new SSBNGenerator(mTheory, kb);
                BayesianNetwork ssbn = generator.generate();

                if (ssbn.size() >= 3) {
                    // Priors: marginal probabilities with no evidence
                    Map<String, Double> rawPriors = VariableElimination.queryAll(ssbn, Map.of());

                    // Build evidence map: observed KG nodes → TRUE (state 1)
                    Map<String, Integer> evidence = buildEvidence(ssbn, observedTrueNodeIds);

                    // Posteriors: conditioned on observed evidence
                    Map<String, Double> rawPosteriors = evidence.isEmpty()
                            ? rawPriors
                            : VariableElimination.queryAll(ssbn, evidence);

                    // Map grounded variable names back to KG node IDs
                    Map<String, Double> posteriors = new LinkedHashMap<>();
                    Map<String, Double> priors = new LinkedHashMap<>();
                    for (Map.Entry<String, Double> entry : rawPosteriors.entrySet()) {
                        BayesianNode bnNode = ssbn.getNode(entry.getKey());
                        if (bnNode != null) {
                            String kgNodeId = bnNode.getKgNodeId();
                            posteriors.merge(kgNodeId, entry.getValue(), Math::max);
                        }
                    }
                    for (Map.Entry<String, Double> entry : rawPriors.entrySet()) {
                        BayesianNode bnNode = ssbn.getNode(entry.getKey());
                        if (bnNode != null) {
                            String kgNodeId = bnNode.getKgNodeId();
                            priors.merge(kgNodeId, entry.getValue(), Math::max);
                        }
                    }

                    if (!posteriors.isEmpty()) {
                        log.debug("MEBN inference: {} posteriors from {} SSBN nodes, {} evidence vars",
                                posteriors.size(), ssbn.size(), evidence.size());
                        return new BayesianResult(posteriors, priors);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("MEBN inference failed, falling back to flat BN: {}", e.getMessage());
        }

        // Flat BN fallback: structure-aware but not entity-type-aware
        BayesianNetworkBuilder bnBuilder = new BayesianNetworkBuilder(graphService)
                .maxDepth(2)
                .maxNodes(50);
        BayesianNetwork network = bnBuilder.build(nodeIds);

        if (network.size() == 0) {
            return BayesianResult.EMPTY;
        }

        // Priors: marginal probabilities with no evidence
        Map<String, Double> rawPriors = VariableElimination.queryAll(network, Map.of());

        // Build evidence map and compute posteriors
        Map<String, Integer> evidence = buildEvidence(network, observedTrueNodeIds);
        Map<String, Double> rawPosteriors = evidence.isEmpty()
                ? rawPriors
                : VariableElimination.queryAll(network, evidence);

        Map<String, Double> posteriors = new LinkedHashMap<>();
        Map<String, Double> priors = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : rawPosteriors.entrySet()) {
            BayesianNode bnNode = network.getNode(entry.getKey());
            if (bnNode != null) {
                posteriors.put(bnNode.getKgNodeId(), entry.getValue());
            }
        }
        for (Map.Entry<String, Double> entry : rawPriors.entrySet()) {
            BayesianNode bnNode = network.getNode(entry.getKey());
            if (bnNode != null) {
                priors.put(bnNode.getKgNodeId(), entry.getValue());
            }
        }
        return new BayesianResult(posteriors, priors);
    }

    /**
     * Maps a set of observed-TRUE KG node IDs to BN variable evidence.
     * Only includes nodes that actually exist in the network.
     */
    private Map<String, Integer> buildEvidence(BayesianNetwork network,
                                                Set<String> observedTrueNodeIds) {
        if (observedTrueNodeIds == null || observedTrueNodeIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> evidence = new LinkedHashMap<>();
        for (BayesianNode node : network.getNodes()) {
            if (node.getKgNodeId() != null && observedTrueNodeIds.contains(node.getKgNodeId())) {
                evidence.put(node.getVariableName(), 1); // TRUE
            }
        }
        return evidence;
    }

    private ProcessStep findStepDef(ProcessDefinition def, String stepId) {
        if (def.getPhases() == null) return null;
        for (ProcessPhase phase : def.getPhases()) {
            if (phase.getSteps() == null) continue;
            for (ProcessStep step : phase.getSteps()) {
                if (stepId.equals(step.getId())) return step;
            }
        }
        return null;
    }

    private List<ProcessStep> flattenSteps(ProcessDefinition def) {
        List<ProcessStep> result = new ArrayList<>();
        if (def.getPhases() == null) return result;
        for (ProcessPhase phase : def.getPhases()) {
            if (phase.getSteps() != null) {
                result.addAll(phase.getSteps());
            }
        }
        return result;
    }

    private Set<String> getFailedStepIds(WorkflowRun run) {
        if (run.getStepExecutions() == null) return Set.of();
        return run.getStepExecutions().stream()
                .filter(se -> se.getStatus() == StepExecutionStatus.FAILED)
                .map(StepExecution::getStepId)
                .collect(Collectors.toSet());
    }

    private ProcessEventAlert buildRootCauseAlert(WorkflowRun run, ProcessStep step,
                                                    String nodeId, AttributionResult result) {
        String topRootCause = result.getChains().isEmpty() ? "unknown" :
                result.getChains().get(0).getRootCauseTitle();

        return ProcessEventAlert.builder()
                .alertId(UUID.randomUUID().toString())
                .workflowRunId(run.getId())
                .processDefinitionId(run.getProcessDefinitionId())
                .stepId(step.getId())
                .targetNodeId(nodeId)
                .severity(AlertSeverity.CRITICAL)
                .alertType("ROOT_CAUSE")
                .title("Step '" + step.getName() + "' failed — root cause: " + topRootCause)
                .explanation(result.getSynthesizedExplanation() != null
                        ? result.getSynthesizedExplanation()
                        : "Found " + result.getChains().size() + " causal chains from KG node " + nodeId)
                .causalChains(result.getChains())
                .confidence(result.getChains().isEmpty() ? 0.0 :
                        result.getChains().get(0).getOverallConfidence())
                .llmUsed(result.isLlmUsed())
                .createdAt(Instant.now())
                .build();
    }

    private ProcessEventAlert buildPredictionAlert(WorkflowRun run, ProcessStep step,
                                                     String nodeId, PredictionResult result,
                                                     List<PredictedEvent> riskyPredictions) {
        double maxProb = riskyPredictions.stream()
                .mapToDouble(PredictedEvent::getProbability).max().orElse(0);
        AlertSeverity severity = maxProb > 0.7 ? AlertSeverity.HIGH :
                maxProb > 0.5 ? AlertSeverity.MEDIUM : AlertSeverity.LOW;

        String topPrediction = riskyPredictions.get(0).getTitle();

        return ProcessEventAlert.builder()
                .alertId(UUID.randomUUID().toString())
                .workflowRunId(run.getId())
                .processDefinitionId(run.getProcessDefinitionId())
                .stepId(step.getId())
                .targetNodeId(nodeId)
                .severity(severity)
                .alertType("PREDICTION")
                .title("Step '" + step.getName() + "' may lead to: " + topPrediction)
                .explanation(result.getSynthesizedForecast() != null
                        ? result.getSynthesizedForecast()
                        : riskyPredictions.size() + " potential downstream events detected with probability > 30%")
                .predictions(riskyPredictions)
                .confidence(maxProb)
                .llmUsed(result.isLlmUsed())
                .createdAt(Instant.now())
                .build();
    }

    /**
     * Propagates risk from upstream steps to downstream dependent steps using
     * topological ordering (Kahn's algorithm). This guarantees that when a step's
     * risk is propagated to its dependents, the step's own inherited risk has already
     * been folded in — correctly handling chains of 3+ levels (e.g. C→B→A).
     *
     * <p>For each step processed in topological order, its effective risk (max of
     * direct and inherited) is pushed to every step that depends on it, attenuated
     * by 0.5 per hop. The algorithm converges in a single pass over the ordering
     * and handles arbitrary DAG structures.</p>
     */
    private void propagateRisk(List<ProcessStep> allSteps, Map<String, Double> stepRiskScores) {
        // forwardEdges: step → list of step IDs that depend on it (forward edges)
        Map<String, List<String>> forwardEdges = new HashMap<>();
        // inDegree: how many dependencies each step has (within this step list)
        Map<String, Integer> inDegree = new HashMap<>();

        Set<String> stepIds = new HashSet<>();
        for (ProcessStep step : allSteps) {
            stepIds.add(step.getId());
        }

        for (ProcessStep step : allSteps) {
            String id = step.getId();
            forwardEdges.putIfAbsent(id, new ArrayList<>());
            List<String> deps = step.getDependsOn();
            if (deps != null && !deps.isEmpty()) {
                // Only count edges whose source is in the known step set
                List<String> knownDeps = deps.stream()
                        .filter(stepIds::contains)
                        .toList();
                inDegree.put(id, knownDeps.size());
                for (String depId : knownDeps) {
                    forwardEdges.computeIfAbsent(depId, k -> new ArrayList<>()).add(id);
                }
            } else {
                inDegree.put(id, 0);
            }
        }

        // Kahn's algorithm: start with all steps that have no unresolved dependencies
        Queue<String> queue = new ArrayDeque<>();
        for (ProcessStep step : allSteps) {
            if (inDegree.getOrDefault(step.getId(), 0) == 0) {
                queue.add(step.getId());
            }
        }

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            double currentRisk = stepRiskScores.getOrDefault(currentId, 0.0);

            // Push attenuated risk to every step that depends on this one
            if (currentRisk > 0) {
                List<String> downstream = forwardEdges.getOrDefault(currentId, List.of());
                for (String downstreamId : downstream) {
                    double inherited = currentRisk * 0.5;
                    stepRiskScores.merge(downstreamId, inherited, Math::max);
                }
            }

            // Reduce in-degree of dependents; enqueue those now fully resolved
            List<String> downstream = forwardEdges.getOrDefault(currentId, List.of());
            for (String downstreamId : downstream) {
                int remaining = inDegree.getOrDefault(downstreamId, 1) - 1;
                inDegree.put(downstreamId, remaining);
                if (remaining == 0) {
                    queue.add(downstreamId);
                }
            }
        }
    }

    private double computeNodeRisk(AttributionResult attrResult, PredictionResult predResult) {
        double attrRisk = 0.0;
        if (!attrResult.getChains().isEmpty()) {
            // If there are strong causal chains pointing at this node, it has inherent risk
            attrRisk = attrResult.getChains().stream()
                    .mapToDouble(AttributionChain::getOverallConfidence)
                    .max().orElse(0.0) * 0.3;
        }

        double predRisk = 0.0;
        if (!predResult.getPredictions().isEmpty()) {
            predRisk = predResult.getPredictions().stream()
                    .mapToDouble(PredictedEvent::getProbability)
                    .max().orElse(0.0) * 0.7;
        }

        return Math.min(1.0, attrRisk + predRisk);
    }

    private void mergeAttributionInto(AttributionResult target, AttributionResult source) {
        target.getChains().addAll(source.getChains());
        if (source.getInfluenceScores() != null && !source.getInfluenceScores().isEmpty()) {
            Map<String, Double> mutable = new LinkedHashMap<>(target.getInfluenceScores());
            source.getInfluenceScores().forEach((k, v) -> mutable.merge(k, v, Math::max));
            target.setInfluenceScores(mutable);
        }
        if (source.getCounterfactuals() != null) {
            target.getCounterfactuals().addAll(source.getCounterfactuals());
        }
    }

    private void mergePredictionInto(PredictionResult target, PredictionResult source) {
        // Add predictions that aren't already present (by nodeId)
        Set<String> existingNodeIds = target.getPredictions().stream()
                .map(PredictedEvent::getNodeId)
                .collect(Collectors.toSet());
        for (PredictedEvent pred : source.getPredictions()) {
            if (!existingNodeIds.contains(pred.getNodeId())) {
                target.getPredictions().add(pred);
            }
        }
    }

    private AlertSeverity scoreToSeverity(double score) {
        if (score >= 0.8) return AlertSeverity.CRITICAL;
        if (score >= 0.6) return AlertSeverity.HIGH;
        if (score >= 0.4) return AlertSeverity.MEDIUM;
        if (score >= 0.2) return AlertSeverity.LOW;
        return AlertSeverity.INFO;
    }
}
