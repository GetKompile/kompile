/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services;

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.service.ProcessGraphCallback;
import ai.kompile.process.workflow.ProcessDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Writes process execution results back to the knowledge graph as entities and edges.
 * Creates PROCESS_RUN and STEP_EXECUTION entity nodes linked to any graph nodes
 * referenced during execution, enabling full provenance tracing from KG entities
 * through to the process outputs that consumed them.
 *
 * <p>All writes are async to avoid blocking the process engine. Failures are
 * logged but never propagate — the process engine always advances regardless.</p>
 */
@Service
@ConditionalOnBean(KnowledgeGraphService.class)
public class ProcessGraphWritebackService implements ProcessGraphCallback {

    private static final Logger log = LoggerFactory.getLogger(ProcessGraphWritebackService.class);

    private final KnowledgeGraphService knowledgeGraphService;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private ProcessEngineService processEngineService;

    @Autowired
    public ProcessGraphWritebackService(KnowledgeGraphService knowledgeGraphService,
                                         ObjectMapper objectMapper) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Async
    public void onStepCompleted(WorkflowRun run, StepExecution step) {
        try {
            // Only write back completed or failed steps with graph node associations
            if (step.getGraphNodeIds() == null || step.getGraphNodeIds().isEmpty()) {
                return;
            }

            // Deterministic external ID for the step execution entity
            String stepExtId = "step-exec:" + run.getId() + "/" + step.getStepId();

            // Build metadata
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("entity_subtype", "step_execution");
            meta.put("runId", run.getId());
            meta.put("stepId", step.getStepId());
            meta.put("stepName", step.getStepName());
            meta.put("status", step.getStatus().name());
            if (step.getExecutedBy() != null) meta.put("executedBy", step.getExecutedBy());
            if (step.getStartedAt() != null) meta.put("startedAt", step.getStartedAt().toString());
            if (step.getCompletedAt() != null) meta.put("completedAt", step.getCompletedAt().toString());
            if (step.getInputHash() != null) meta.put("inputHash", step.getInputHash());
            if (step.getOutputHash() != null) meta.put("outputHash", step.getOutputHash());
            if (step.getError() != null) meta.put("error", step.getError());

            // Create or update the step execution entity
            String title = step.getStepName() + " (" + step.getStatus().name().toLowerCase() + ")";
            GraphNode stepNode = knowledgeGraphService.createNode(
                    NodeLevel.ENTITY, stepExtId, title,
                    "Step execution: " + step.getStepName() + " in run " + run.getId(),
                    meta);

            // Create EXECUTED_ON edges from step → each referenced graph node
            for (String graphNodeId : step.getGraphNodeIds()) {
                try {
                    Optional<GraphNode> targetOpt = knowledgeGraphService.getNode(graphNodeId);
                    if (targetOpt.isPresent()) {
                        if (!knowledgeGraphService.edgeExists(stepNode.getNodeId(), graphNodeId)) {
                            knowledgeGraphService.createEdgeWithMetadata(
                                    stepNode.getNodeId(), graphNodeId,
                                    EdgeType.USER_DEFINED, 0.9,
                                    "EXECUTED_ON",
                                    "Step '" + step.getStepName() + "' executed on " + targetOpt.get().getTitle(),
                                    null, EdgeProvenance.INFERRED, null);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to create EXECUTED_ON edge to {}: {}", graphNodeId, e.getMessage());
                }
            }

            // If executedBy indicates a tool/script/excel/http call, create a USES_TOOL edge
            if (step.getExecutedBy() != null && step.getExecutedBy().contains(":")) {
                try {
                    String executor = step.getExecutedBy(); // e.g. "tool:ragQuery", "script:python", "excel:javascript"
                    String executorType = executor.substring(0, executor.indexOf(':'));
                    String executorName = executor.substring(executor.indexOf(':') + 1);
                    String toolExtId = "executor:" + executorType + ":" + executorName;

                    // Find or create a reusable entity for the tool/executor
                    Optional<GraphNode> toolNodeOpt = knowledgeGraphService.getNodeByExternalId(
                            toolExtId, NodeLevel.ENTITY);
                    GraphNode toolNode;
                    if (toolNodeOpt.isPresent()) {
                        toolNode = toolNodeOpt.get();
                    } else {
                        Map<String, Object> toolMeta = new LinkedHashMap<>();
                        toolMeta.put("entity_subtype", executorType + "_executor");
                        toolMeta.put("executorType", executorType);
                        toolMeta.put("executorName", executorName);
                        toolNode = knowledgeGraphService.createNode(
                                NodeLevel.ENTITY, toolExtId,
                                executorType.substring(0, 1).toUpperCase() + executorType.substring(1)
                                        + ": " + executorName,
                                executorType + " executor used by workflow steps",
                                toolMeta);
                    }

                    if (!knowledgeGraphService.edgeExists(stepNode.getNodeId(), toolNode.getNodeId())) {
                        knowledgeGraphService.createEdgeWithMetadata(
                                stepNode.getNodeId(), toolNode.getNodeId(),
                                EdgeType.USER_DEFINED, 0.8,
                                "USES_TOOL",
                                "Step '" + step.getStepName() + "' used " + executor,
                                null, EdgeProvenance.INFERRED, null);
                    }
                } catch (Exception e) {
                    log.debug("Failed to create USES_TOOL edge for {}: {}", step.getExecutedBy(), e.getMessage());
                }
            }

            // If the step produced outputs, create an OUTPUT entity and link it
            if (step.getStatus() == StepExecutionStatus.COMPLETED && step.getOutputs() != null
                    && !step.getOutputs().isEmpty()) {
                try {
                    String outputSummary = summarizeOutputs(step.getOutputs());
                    if (outputSummary != null) {
                        // Update step description with summary
                        knowledgeGraphService.updateNode(stepNode.getNodeId(), null, outputSummary, null);

                        // Create a dedicated OUTPUT entity for provenance tracing
                        String outputExtId = "step-output:" + run.getId() + "/" + step.getStepId();
                        Map<String, Object> outputMeta = new LinkedHashMap<>();
                        outputMeta.put("entity_subtype", "step_output");
                        outputMeta.put("runId", run.getId());
                        outputMeta.put("stepId", step.getStepId());
                        outputMeta.put("outputKeys", String.join(",", step.getOutputs().keySet()));
                        if (step.getOutputHash() != null) outputMeta.put("outputHash", step.getOutputHash());

                        GraphNode outputNode = knowledgeGraphService.createNode(
                                NodeLevel.ENTITY, outputExtId,
                                "Output: " + step.getStepName(),
                                outputSummary, outputMeta);

                        if (!knowledgeGraphService.edgeExists(stepNode.getNodeId(), outputNode.getNodeId())) {
                            knowledgeGraphService.createEdgeWithMetadata(
                                    stepNode.getNodeId(), outputNode.getNodeId(),
                                    EdgeType.USER_DEFINED, 1.0,
                                    "PRODUCED_OUTPUT",
                                    "Step '" + step.getStepName() + "' produced output",
                                    null, EdgeProvenance.INFERRED, null);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to create OUTPUT entity for step {}: {}", stepExtId, e.getMessage());
                }
            }

            // If the step failed, create an ERROR entity and link it
            if (step.getStatus() == StepExecutionStatus.FAILED && step.getError() != null
                    && !step.getError().isBlank()) {
                try {
                    String errorExtId = "step-error:" + run.getId() + "/" + step.getStepId();
                    Map<String, Object> errorMeta = new LinkedHashMap<>();
                    errorMeta.put("entity_subtype", "step_error");
                    errorMeta.put("runId", run.getId());
                    errorMeta.put("stepId", step.getStepId());
                    errorMeta.put("errorMessage", step.getError());

                    GraphNode errorNode = knowledgeGraphService.createNode(
                            NodeLevel.ENTITY, errorExtId,
                            "Error: " + step.getStepName(),
                            step.getError(), errorMeta);

                    if (!knowledgeGraphService.edgeExists(stepNode.getNodeId(), errorNode.getNodeId())) {
                        knowledgeGraphService.createEdgeWithMetadata(
                                stepNode.getNodeId(), errorNode.getNodeId(),
                                EdgeType.USER_DEFINED, 1.0,
                                "PRODUCED_ERROR",
                                "Step '" + step.getStepName() + "' failed: " + step.getError(),
                                null, EdgeProvenance.INFERRED, null);
                    }
                } catch (Exception e) {
                    log.debug("Failed to create ERROR entity for step {}: {}", stepExtId, e.getMessage());
                }
            }

            log.debug("Wrote step execution {} to KG (linked to {} graph nodes)",
                    stepExtId, step.getGraphNodeIds().size());
        } catch (Exception e) {
            log.warn("Failed to write step execution to KG: {}", e.getMessage());
        }
    }

    @Override
    @Async
    public void onRunCompleted(WorkflowRun run) {
        try {
            // Create a run-level entity that links all step executions
            String runExtId = "process-run:" + run.getId();

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("entity_subtype", "process_run");
            meta.put("processDefinitionId", run.getProcessDefinitionId());
            meta.put("status", run.getStatus().name());
            if (run.getStartedAt() != null) meta.put("startedAt", run.getStartedAt().toString());
            if (run.getCompletedAt() != null) meta.put("completedAt", run.getCompletedAt().toString());

            String title = "Process Run: " + run.getProcessDefinitionId() + " (" + run.getStatus().name().toLowerCase() + ")";
            GraphNode runNode = knowledgeGraphService.createNode(
                    NodeLevel.ENTITY, runExtId, title,
                    "Workflow run " + run.getId(), meta);

            // Link run → ProcessDefinition via INSTANCE_OF edge
            if (run.getProcessDefinitionId() != null) {
                String procDefExtId = "process-def:" + run.getProcessDefinitionId();
                Optional<GraphNode> procDefOpt = knowledgeGraphService.getNodeByExternalId(
                        procDefExtId, NodeLevel.ENTITY);
                if (procDefOpt.isEmpty()) {
                    // Create a stub entity for the process definition if it doesn't exist yet.
                    // Resolve the human-readable process name from the engine if available.
                    String processName = resolveProcessDefinitionName(run.getProcessDefinitionId());
                    Map<String, Object> defMeta = new LinkedHashMap<>();
                    defMeta.put("entity_subtype", "process_definition");
                    defMeta.put("processDefinitionId", run.getProcessDefinitionId());
                    GraphNode defNode = knowledgeGraphService.createNode(
                            NodeLevel.ENTITY, procDefExtId,
                            processName,
                            "Process definition: " + processName, defMeta);
                    procDefOpt = Optional.of(defNode);
                }
                if (!knowledgeGraphService.edgeExists(runNode.getNodeId(), procDefOpt.get().getNodeId())) {
                    knowledgeGraphService.createEdgeWithMetadata(
                            runNode.getNodeId(), procDefOpt.get().getNodeId(),
                            EdgeType.USER_DEFINED, 1.0,
                            "INSTANCE_OF",
                            "Process run is an instance of " + run.getProcessDefinitionId(),
                            null, EdgeProvenance.INFERRED, null);
                }
            }

            // Link run → each step execution entity
            if (run.getStepExecutions() != null) {
                for (StepExecution step : run.getStepExecutions()) {
                    String stepExtId = "step-exec:" + run.getId() + "/" + step.getStepId();
                    Optional<GraphNode> stepNodeOpt = knowledgeGraphService.getNodeByExternalId(
                            stepExtId, NodeLevel.ENTITY);
                    if (stepNodeOpt.isPresent()) {
                        if (!knowledgeGraphService.edgeExists(runNode.getNodeId(), stepNodeOpt.get().getNodeId())) {
                            knowledgeGraphService.createEdgeWithMetadata(
                                    runNode.getNodeId(), stepNodeOpt.get().getNodeId(),
                                    EdgeType.CONTAINS, 1.0,
                                    "CONTAINS_STEP",
                                    "Run contains step: " + step.getStepName(),
                                    null, EdgeProvenance.INFERRED, null);
                        }
                    }
                }
            }

            // Link run → all unique graph node IDs across all steps
            Set<String> allGraphNodeIds = new LinkedHashSet<>();
            if (run.getGraphNodeIds() != null) allGraphNodeIds.addAll(run.getGraphNodeIds());
            if (run.getStepExecutions() != null) {
                for (StepExecution step : run.getStepExecutions()) {
                    if (step.getGraphNodeIds() != null) allGraphNodeIds.addAll(step.getGraphNodeIds());
                }
            }
            for (String nodeId : allGraphNodeIds) {
                try {
                    if (knowledgeGraphService.getNode(nodeId).isPresent()
                            && !knowledgeGraphService.edgeExists(runNode.getNodeId(), nodeId)) {
                        knowledgeGraphService.createEdgeWithMetadata(
                                runNode.getNodeId(), nodeId,
                                EdgeType.USER_DEFINED, 0.7,
                                "REFERENCES_DATA",
                                "Process run referenced graph data",
                                null, EdgeProvenance.INFERRED, null);
                    }
                } catch (Exception e) {
                    log.debug("Failed to link run to graph node {}: {}", nodeId, e.getMessage());
                }
            }

            log.debug("Wrote process run {} to KG (linked to {} graph nodes)",
                    runExtId, allGraphNodeIds.size());
        } catch (Exception e) {
            log.warn("Failed to write process run to KG: {}", e.getMessage());
        }
    }

    private String summarizeOutputs(Map<String, Object> outputs) {
        if (outputs == null || outputs.isEmpty()) return null;
        // Build a brief summary excluding internal metadata keys
        StringBuilder sb = new StringBuilder("Outputs: ");
        int count = 0;
        for (Map.Entry<String, Object> entry : outputs.entrySet()) {
            if (entry.getKey().startsWith("_")) continue;
            if (count > 0) sb.append(", ");
            sb.append(entry.getKey()).append("=");
            Object val = entry.getValue();
            if (val instanceof Number || val instanceof Boolean) {
                sb.append(val);
            } else if (val instanceof String s) {
                sb.append(s.length() > 50 ? s.substring(0, 50) + "..." : s);
            } else {
                sb.append("[").append(val != null ? val.getClass().getSimpleName() : "null").append("]");
            }
            count++;
            if (count >= 5) { sb.append(", ..."); break; }
        }
        return count > 0 ? sb.toString() : null;
    }

    /**
     * Resolve a human-readable name for a process definition.
     * Queries the process engine if available; falls back to a generic label.
     */
    private String resolveProcessDefinitionName(String processDefinitionId) {
        if (processEngineService != null && processDefinitionId != null) {
            try {
                ProcessDefinition def = processEngineService.getProcess(processDefinitionId, -1);
                if (def != null && def.getName() != null && !def.getName().isBlank()) {
                    return def.getName();
                }
            } catch (Exception e) {
                log.debug("Could not resolve process name for {}: {}", processDefinitionId, e.getMessage());
            }
        }
        return "Process: " + processDefinitionId;
    }
}
