/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.process.discovery;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.process.workflow.ApprovalMode;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessDiscoveryServiceImplPolicyTest {

    @Test
    void staticArtifactWriterStoresPortableProcessArtifacts() {
        UnifiedGraph graph = new UnifiedGraph();
        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .id("suggestion-1")
                .name("Portable process")
                .phases(List.of(ProcessSuggestion.SuggestedPhase.builder().name("Phase").build()))
                .build();
        ProcessDefinition definition = ProcessDefinition.builder()
                .id("definition-1")
                .name("Portable process")
                .version(1)
                .build();

        ProcessUnifiedGraphArtifacts.putArtifacts(graph, List.of(suggestion), List.of(definition));

        assertTrue(graph.artifactText(ProcessUnifiedGraphArtifacts.SUGGESTIONS_JSON).contains("Portable process"));
        assertTrue(graph.artifactText(ProcessUnifiedGraphArtifacts.DEFINITIONS_JSON).contains("definition-1"));
    }

    @Test
    void acceptSuggestionCopiesGraphPolicyFieldsToProcessStep() {
        ProcessDiscoveryServiceImpl service = new ProcessDiscoveryServiceImpl();
        ProcessSuggestion.SuggestedStep suggestedStep = ProcessSuggestion.SuggestedStep.builder()
                .name("Review Forecast")
                .description("Review forecast confidence and approval gate")
                .stepType("APPROVE")
                .graphNodeIds(List.of("node:review"))
                .controlIds(List.of("C-04"))
                .requiredRoles(List.of("Forecast gate owner"))
                .requiredPermissions(List.of("planning:approve"))
                .metadata(Map.of(
                        "approvalPolicy", "owner sign-off",
                        "confidenceThreshold", 0.85,
                        "amountThreshold", 10_000))
                .conditionExpression("#confidence == null || #confidence >= 0.85")
                .conditionLabel("Policy: confidence >= 0.85")
                .build();
        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Policy process")
                .description("Policy process")
                .discoverySource("PROCESS_MINING")
                .confidence(0.91)
                .phases(List.of(ProcessSuggestion.SuggestedPhase.builder()
                        .name("Review")
                        .steps(List.of(suggestedStep))
                        .build()))
                .build();

        ProcessDefinition definition = service.acceptSuggestion(suggestion);

        ProcessStep step = definition.getPhases().get(0).getSteps().get(0);
        assertEquals(List.of("C-04"), step.getControlIds());
        assertEquals(List.of("Forecast gate owner"), step.getRequiredRoles());
        assertEquals(List.of("planning:approve"), step.getRequiredPermissions());
        assertEquals("#confidence == null || #confidence >= 0.85", step.getConditionExpression());
        assertEquals("Policy: confidence >= 0.85", step.getConditionLabel());
        assertEquals(0.85, ((Number) step.getMetadata().get("confidenceThreshold")).doubleValue(), 1.0e-9);
        assertNotNull(step.getApprovalPolicy());
        assertEquals(ApprovalMode.SINGLE, step.getApprovalPolicy().getMode());
        assertTrue(step.getApprovalPolicy().getApproverPool().contains("Forecast gate owner"));
        assertEquals(10_000, step.getApprovalPolicy().getDollarThreshold());
    }
}
