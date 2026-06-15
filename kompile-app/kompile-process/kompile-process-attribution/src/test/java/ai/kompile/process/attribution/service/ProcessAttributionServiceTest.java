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

import ai.kompile.event.attribution.domain.*;
import ai.kompile.event.attribution.service.EventAttributionService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.attribution.domain.*;
import ai.kompile.process.execution.RunStatus;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStatus;
import ai.kompile.process.workflow.ProcessStep;
import ai.kompile.process.workflow.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ProcessAttributionServiceTest {

    @Mock
    private EventAttributionService eventAttributionService;

    @Mock
    private ProcessEngineService processEngineService;

    @Mock
    private KnowledgeGraphService graphService;

    private ProcessAttributionService service;

    private ProcessDefinition testDefinition;
    private WorkflowRun testRun;

    @BeforeEach
    void setUp() {
        service = new ProcessAttributionService(eventAttributionService, processEngineService, graphService);

        // Build a process definition with two steps, each with graph bindings
        ProcessStep step1 = ProcessStep.builder()
                .id("1.1").name("Extract Data").stepType(StepType.AUTO)
                .graphNodeIds(List.of("node-extract"))
                .build();
        ProcessStep step2 = ProcessStep.builder()
                .id("1.2").name("Validate Data").stepType(StepType.CONTROL_GATE)
                .graphNodeIds(List.of("node-validate"))
                .dependsOn(List.of("1.1"))
                .build();
        ProcessStep step3 = ProcessStep.builder()
                .id("2.1").name("Manual Review").stepType(StepType.HUMAN)
                .build(); // No graph bindings

        testDefinition = ProcessDefinition.builder()
                .id("proc-1").version(1).name("Test Process")
                .status(ProcessStatus.APPROVED)
                .phases(List.of(
                        ProcessPhase.builder().id("phase-1").name("Extract & Validate").order(1)
                                .steps(List.of(step1, step2)).build(),
                        ProcessPhase.builder().id("phase-2").name("Review").order(2)
                                .steps(List.of(step3)).build()
                ))
                .build();

        testRun = WorkflowRun.builder()
                .id("run-1").processDefinitionId("proc-1").processVersion(1)
                .status(RunStatus.RUNNING).startedAt(Instant.now())
                .stepExecutions(List.of(
                        StepExecution.builder().stepId("1.1").stepName("Extract Data")
                                .status(StepExecutionStatus.COMPLETED).build(),
                        StepExecution.builder().stepId("1.2").stepName("Validate Data")
                                .status(StepExecutionStatus.PENDING).build()
                ))
                .build();

        when(processEngineService.getRun("run-1")).thenReturn(testRun);
        when(processEngineService.getProcess("proc-1", 1)).thenReturn(testDefinition);
    }

    @Test
    void explainStep_withGraphBindings_returnsResult() {
        AttributionResult attrResult = AttributionResult.builder()
                .targetNodeId("node-extract").targetTitle("Extract Data Node")
                .chains(List.of(
                        AttributionChain.builder()
                                .chainId("c1").rootCauseNodeId("root-1").rootCauseTitle("Bad Source")
                                .targetEventNodeId("node-extract").targetEventTitle("Extract Data Node")
                                .hops(new ArrayList<>()).overallConfidence(0.85)
                                .confidenceBand(AttributionConfidence.HIGH)
                                .build()
                ))
                .influenceScores(new HashMap<>(Map.of("root-1", 0.85)))
                .counterfactuals(new ArrayList<>())
                .deadEnds(new ArrayList<>())
                .computationTimeMs(50).nodesVisited(10).edgesExamined(15)
                .build();

        PredictionResult predResult = PredictionResult.builder()
                .sourceNodeId("node-extract").sourceTitle("Extract Data Node")
                .predictions(new ArrayList<>())
                .computationTimeMs(30).nodesVisited(5)
                .build();

        when(eventAttributionService.explain(any())).thenReturn(attrResult);
        when(eventAttributionService.predict(any())).thenReturn(predResult);

        StepAttributionResult result = service.explainStep("run-1", "1.1", false);

        assertTrue(result.isHasGraphBindings());
        assertEquals("1.1", result.getStepId());
        assertNotNull(result.getAttribution());
        assertFalse(result.getAttribution().getChains().isEmpty());
        assertTrue(result.getRiskScore() > 0);
    }

    @Test
    void explainStep_noGraphBindings_returnsFalse() {
        StepAttributionResult result = service.explainStep("run-1", "2.1", false);

        assertFalse(result.isHasGraphBindings());
        assertNull(result.getAttribution());
    }

    @Test
    void assessRunRisk_detectsFailedStep() {
        // Mark step 1.1 as FAILED
        WorkflowRun failedRun = WorkflowRun.builder()
                .id("run-1").processDefinitionId("proc-1").processVersion(1)
                .status(RunStatus.FAILED).startedAt(Instant.now())
                .stepExecutions(List.of(
                        StepExecution.builder().stepId("1.1").stepName("Extract Data")
                                .status(StepExecutionStatus.FAILED).build(),
                        StepExecution.builder().stepId("1.2").stepName("Validate Data")
                                .status(StepExecutionStatus.PENDING).build()
                ))
                .build();
        when(processEngineService.getRun("run-1")).thenReturn(failedRun);

        AttributionResult attrResult = AttributionResult.builder()
                .targetNodeId("node-extract").targetTitle("Extract Data Node")
                .chains(List.of(
                        AttributionChain.builder()
                                .chainId("c1").rootCauseNodeId("root-1").rootCauseTitle("Data Corruption")
                                .targetEventNodeId("node-extract").targetEventTitle("Extract Data Node")
                                .hops(new ArrayList<>()).overallConfidence(0.9)
                                .confidenceBand(AttributionConfidence.DEFINITIVE)
                                .build()
                ))
                .influenceScores(new HashMap<>())
                .counterfactuals(new ArrayList<>())
                .deadEnds(new ArrayList<>())
                .computationTimeMs(50).nodesVisited(10).edgesExamined(15)
                .build();

        PredictionResult predResult = PredictionResult.builder()
                .sourceNodeId("node-validate").sourceTitle("Validate Data Node")
                .predictions(new ArrayList<>())
                .computationTimeMs(20).nodesVisited(3)
                .build();

        when(eventAttributionService.explain(any())).thenReturn(attrResult);
        when(eventAttributionService.predict(any())).thenReturn(predResult);

        ProcessRiskAssessment assessment = service.assessRunRisk("run-1", false);

        assertNotNull(assessment);
        assertTrue(assessment.getOverallRiskScore() > 0.5);
        assertFalse(assessment.getAlerts().isEmpty());
        assertEquals("ROOT_CAUSE", assessment.getAlerts().get(0).getAlertType());
        assertEquals(AlertSeverity.CRITICAL, assessment.getAlerts().get(0).getSeverity());
    }

    @Test
    void assessRunRisk_detectsPredictedRisks() {
        AttributionResult attrResult = AttributionResult.builder()
                .targetNodeId("node-extract")
                .chains(new ArrayList<>())
                .influenceScores(new HashMap<>())
                .counterfactuals(new ArrayList<>())
                .deadEnds(new ArrayList<>())
                .build();

        PredictionResult predResult = PredictionResult.builder()
                .sourceNodeId("node-extract").sourceTitle("Extract Data Node")
                .predictions(new ArrayList<>(List.of(
                        PredictedEvent.builder()
                                .nodeId("downstream-1").title("Downstream Failure")
                                .probability(0.75).hopsFromSource(1)
                                .pathFromSource(new ArrayList<>(List.of("node-extract", "downstream-1")))
                                .pathEdgeTypes(new ArrayList<>())
                                .evidence(new ArrayList<>())
                                .build()
                )))
                .computationTimeMs(30).nodesVisited(5)
                .build();

        PredictionResult emptyPred = PredictionResult.builder()
                .sourceNodeId("node-validate")
                .predictions(new ArrayList<>())
                .computationTimeMs(10).nodesVisited(2)
                .build();

        when(eventAttributionService.explain(any())).thenReturn(attrResult);
        when(eventAttributionService.predict(argThat(q ->
                q != null && "node-extract".equals(q.getSourceNodeId()))))
                .thenReturn(predResult);
        when(eventAttributionService.predict(argThat(q ->
                q != null && "node-validate".equals(q.getSourceNodeId()))))
                .thenReturn(emptyPred);

        ProcessRiskAssessment assessment = service.assessRunRisk("run-1", false);

        assertNotNull(assessment);
        boolean hasPredictionAlert = assessment.getAlerts().stream()
                .anyMatch(a -> "PREDICTION".equals(a.getAlertType()));
        assertTrue(hasPredictionAlert, "Should detect prediction-based alert");
    }

    @Test
    void assessDefinitionRisk_scansGraphBindings() {
        AttributionResult emptyAttr = AttributionResult.builder()
                .targetNodeId("node-extract")
                .chains(new ArrayList<>())
                .influenceScores(new HashMap<>())
                .counterfactuals(new ArrayList<>())
                .deadEnds(new ArrayList<>())
                .build();

        PredictionResult predResult = PredictionResult.builder()
                .sourceNodeId("node-extract").sourceTitle("Extract Data Node")
                .predictions(new ArrayList<>(List.of(
                        PredictedEvent.builder()
                                .nodeId("risk-1").title("Potential Issue")
                                .probability(0.5).hopsFromSource(2)
                                .pathFromSource(new ArrayList<>())
                                .pathEdgeTypes(new ArrayList<>())
                                .evidence(new ArrayList<>())
                                .build()
                )))
                .computationTimeMs(20).nodesVisited(4)
                .build();

        PredictionResult emptyPred = PredictionResult.builder()
                .sourceNodeId("node-validate")
                .predictions(new ArrayList<>())
                .computationTimeMs(10).nodesVisited(2)
                .build();

        when(eventAttributionService.explain(any())).thenReturn(emptyAttr);
        when(eventAttributionService.predict(argThat(q ->
                q != null && "node-extract".equals(q.getSourceNodeId()))))
                .thenReturn(predResult);
        when(eventAttributionService.predict(argThat(q ->
                q != null && "node-validate".equals(q.getSourceNodeId()))))
                .thenReturn(emptyPred);

        ProcessRiskAssessment assessment = service.assessDefinitionRisk("proc-1", 1, false);

        assertNotNull(assessment);
        assertFalse(assessment.getAlerts().isEmpty());
        assertEquals("RISK_ASSESSMENT", assessment.getAlerts().get(0).getAlertType());
    }

    @Test
    void explainControlFailure_producesAlert() {
        AttributionResult attrResult = AttributionResult.builder()
                .targetNodeId("node-validate").targetTitle("Validate Data Node")
                .chains(List.of(
                        AttributionChain.builder()
                                .chainId("c1").rootCauseNodeId("root-2").rootCauseTitle("Invalid Input")
                                .targetEventNodeId("node-validate").targetEventTitle("Validate Data Node")
                                .hops(new ArrayList<>()).overallConfidence(0.7)
                                .confidenceBand(AttributionConfidence.HIGH)
                                .build()
                ))
                .synthesizedExplanation("The control failed because the input data was corrupted at source.")
                .influenceScores(new HashMap<>())
                .counterfactuals(new ArrayList<>())
                .deadEnds(new ArrayList<>())
                .computationTimeMs(50).nodesVisited(8).edgesExamined(12)
                .build();

        when(eventAttributionService.explain(any())).thenReturn(attrResult);

        ProcessEventAlert alert = service.explainControlFailure("run-1", "1.2", "C-01", false);

        assertNotNull(alert);
        assertEquals("CONTROL_FAILURE_EXPLANATION", alert.getAlertType());
        assertEquals(AlertSeverity.HIGH, alert.getSeverity());
        assertFalse(alert.getCausalChains().isEmpty());
        assertTrue(alert.getExplanation().contains("corrupted"));
    }

    @Test
    void riskPropagation_upstreamRiskInheritedByDownstream() {
        // Only step 1.1 has predictions, but step 1.2 depends on 1.1
        PredictionResult riskyPred = PredictionResult.builder()
                .sourceNodeId("node-extract")
                .predictions(new ArrayList<>(List.of(
                        PredictedEvent.builder()
                                .nodeId("bad-event").title("Critical Failure")
                                .probability(0.9).hopsFromSource(1)
                                .pathFromSource(new ArrayList<>())
                                .pathEdgeTypes(new ArrayList<>())
                                .evidence(new ArrayList<>())
                                .build()
                )))
                .computationTimeMs(10).nodesVisited(3)
                .build();

        PredictionResult emptyPred = PredictionResult.builder()
                .sourceNodeId("node-validate")
                .predictions(new ArrayList<>())
                .computationTimeMs(5).nodesVisited(1)
                .build();

        AttributionResult emptyAttr = AttributionResult.builder()
                .targetNodeId("node-extract")
                .chains(new ArrayList<>())
                .influenceScores(new HashMap<>())
                .counterfactuals(new ArrayList<>())
                .deadEnds(new ArrayList<>())
                .build();

        when(eventAttributionService.explain(any())).thenReturn(emptyAttr);
        when(eventAttributionService.predict(argThat(q ->
                q != null && "node-extract".equals(q.getSourceNodeId()))))
                .thenReturn(riskyPred);
        when(eventAttributionService.predict(argThat(q ->
                q != null && "node-validate".equals(q.getSourceNodeId()))))
                .thenReturn(emptyPred);

        ProcessRiskAssessment assessment = service.assessRunRisk("run-1", false);

        // Step 1.2 should inherit some risk from 1.1 via dependsOn propagation
        Double step2Risk = assessment.getStepRiskScores().get("1.2");
        assertNotNull(step2Risk, "Step 1.2 should have inherited risk from 1.1");
        assertTrue(step2Risk > 0, "Inherited risk should be > 0");
    }
}
