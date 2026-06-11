/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.web.controllers;

import ai.kompile.orchestrator.api.OrchestratorService;
import ai.kompile.orchestrator.api.WorkflowService;
import ai.kompile.orchestrator.model.workflow.Workflow;
import ai.kompile.orchestrator.model.workflow.WorkflowStep;
import ai.kompile.orchestrator.web.dto.StartWorkflowRequest;
import ai.kompile.orchestrator.web.dto.WorkflowStepFeedbackRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for workflow management.
 */
@Slf4j
@RestController("orchestratorWorkflowController")
@RequestMapping("/api/orchestrator/{instanceId}/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final OrchestratorService orchestratorService;
    private final WorkflowService workflowService;

    /**
     * Start a new workflow.
     */
    @PostMapping
    public ResponseEntity<Workflow> startWorkflow(
            @PathVariable("instanceId") String instanceId,
            @RequestBody StartWorkflowRequest request) {
        log.info("Starting workflow {} for orchestrator: {}", request.getName(), instanceId);

        Workflow workflow = orchestratorService.startWorkflow(
                instanceId,
                request.getName(),
                request.getInitialPrompt(),
                request.isAutoAdvance(),
                request.getMaxSteps());

        return ResponseEntity.status(HttpStatus.CREATED).body(workflow);
    }

    /**
     * Get a workflow by ID.
     */
    @GetMapping("/{workflowId}")
    public ResponseEntity<Workflow> getWorkflow(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("workflowId") Long workflowId) {
        return workflowService.getWorkflow(workflowId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get active workflows for an orchestrator.
     */
    @GetMapping("/active")
    public ResponseEntity<List<Workflow>> getActiveWorkflows(@PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(workflowService.getActiveWorkflows(instanceId));
    }

    /**
     * Get all workflows for an orchestrator.
     */
    @GetMapping
    public ResponseEntity<List<Workflow>> getWorkflows(@PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(workflowService.getWorkflowHistory(instanceId, 100));
    }

    /**
     * Advance a workflow to the next step.
     */
    @PostMapping("/{workflowId}/advance")
    public ResponseEntity<Map<String, Object>> advanceWorkflow(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("workflowId") Long workflowId) {
        log.info("Advancing workflow {} for orchestrator: {}", workflowId, instanceId);
        orchestratorService.advanceWorkflow(workflowId);
        return ResponseEntity.ok(Map.of(
                "workflowId", workflowId,
                "message", "Workflow advanced to next step"));
    }

    /**
     * Approve a workflow step.
     */
    @PostMapping("/{workflowId}/steps/{stepNumber}/approve")
    public ResponseEntity<Map<String, Object>> approveStep(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("workflowId") Long workflowId,
            @PathVariable("stepNumber") Integer stepNumber) {
        log.info("Approving step {} of workflow {} for orchestrator: {}", stepNumber, workflowId, instanceId);
        orchestratorService.approveWorkflowStep(workflowId, stepNumber);
        return ResponseEntity.ok(Map.of(
                "workflowId", workflowId,
                "stepNumber", stepNumber,
                "message", "Step approved successfully"));
    }

    /**
     * Reject a workflow step with feedback.
     */
    @PostMapping("/{workflowId}/steps/{stepNumber}/reject")
    public ResponseEntity<Map<String, Object>> rejectStep(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("workflowId") Long workflowId,
            @PathVariable("stepNumber") Integer stepNumber,
            @RequestBody WorkflowStepFeedbackRequest request) {
        log.info("Rejecting step {} of workflow {} for orchestrator: {}", stepNumber, workflowId, instanceId);
        orchestratorService.rejectWorkflowStep(workflowId, stepNumber, request.getFeedback());
        return ResponseEntity.ok(Map.of(
                "workflowId", workflowId,
                "stepNumber", stepNumber,
                "message", "Step rejected with feedback"));
    }

    /**
     * Cancel a workflow.
     */
    @PostMapping("/{workflowId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelWorkflow(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("workflowId") Long workflowId) {
        log.info("Cancelling workflow {} for orchestrator: {}", workflowId, instanceId);
        orchestratorService.cancelWorkflow(workflowId);
        return ResponseEntity.ok(Map.of(
                "workflowId", workflowId,
                "message", "Workflow cancelled"));
    }

    /**
     * Get steps for a workflow.
     */
    @GetMapping("/{workflowId}/steps")
    public ResponseEntity<List<WorkflowStep>> getWorkflowSteps(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("workflowId") Long workflowId) {
        return ResponseEntity.ok(workflowService.getSteps(workflowId));
    }
}
