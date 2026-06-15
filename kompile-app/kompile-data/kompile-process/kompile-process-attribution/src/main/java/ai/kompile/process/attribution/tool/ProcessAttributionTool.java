/*
 *  Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.process.attribution.tool;

import ai.kompile.process.attribution.domain.ProcessEventAlert;
import ai.kompile.process.attribution.domain.ProcessRiskAssessment;
import ai.kompile.process.attribution.domain.StepAttributionResult;
import ai.kompile.process.attribution.domain.StepAttributionSummary;
import ai.kompile.process.attribution.service.ProcessAttributionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tools for process attribution, risk assessment, and causal explanation.
 * Wraps {@link ProcessAttributionService} to expose run-level risk scoring,
 * step failure root-cause analysis, control failure explanation, and
 * process-definition pre-flight risk assessment as LLM-accessible Spring AI tools.
 */
@Component
public class ProcessAttributionTool {

    private static final Logger log = LoggerFactory.getLogger(ProcessAttributionTool.class);

    private final ProcessAttributionService processAttributionService;

    public ProcessAttributionTool(ProcessAttributionService processAttributionService) {
        this.processAttributionService = processAttributionService;
    }

    // ---- Input Records ----

    public record RunRiskInput(String runId, boolean useLlm) {}

    public record ExplainStepInput(String runId, String stepId, boolean useLlm) {}

    public record ExplainControlInput(String runId, String stepId, String controlId, boolean useLlm) {}

    public record DefinitionRiskInput(String processDefinitionId, int version, boolean useLlm) {}

    // ---- Tool Methods ----

    @Tool(name = "process_attribution_run_risk",
          description = "Assesses risk for a running workflow by analyzing every step that has knowledge-graph " +
                  "bindings. Runs root-cause attribution on failed steps and forward prediction on pending steps. " +
                  "Returns an overall risk score (0.0 = no risk, 1.0 = critical), risk level, alert count, " +
                  "high-risk step IDs, and the computation time in milliseconds. " +
                  "runId: the workflow run ID to assess. " +
                  "useLlm: whether to use LLM for narrative generation (default true).")
    public Map<String, Object> assessRunRisk(RunRiskInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            boolean useLlm = input.useLlm();
            ProcessRiskAssessment assessment = processAttributionService.assessRunRisk(input.runId(), useLlm);
            result.put("assessmentId", assessment.getAssessmentId());
            result.put("workflowRunId", assessment.getWorkflowRunId());
            result.put("processDefinitionId", assessment.getProcessDefinitionId());
            result.put("overallRiskScore", assessment.getOverallRiskScore());
            result.put("riskLevel", assessment.getRiskLevel() != null ? assessment.getRiskLevel().name() : null);
            result.put("alertsCount", assessment.getAlerts() != null ? assessment.getAlerts().size() : 0);
            result.put("highRiskSteps", assessment.getHighRiskStepIds() != null ? assessment.getHighRiskStepIds() : List.of());
            result.put("computationTimeMs", assessment.getComputationTimeMs());
            result.put("llmUsed", assessment.isLlmUsed());
            result.put("computedAt", assessment.getComputedAt() != null ? assessment.getComputedAt().toString() : null);
            // Include per-step Bayesian posteriors and priors
            if (assessment.getStepAttributionResults() != null && !assessment.getStepAttributionResults().isEmpty()) {
                result.put("stepAttributions", serializeStepAttributions(assessment.getStepAttributionResults()));
            }
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error assessing run risk for runId={}", input.runId(), e);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_attribution_explain_step",
          description = "Explains why a process step failed or produced unexpected results by tracing causal " +
                  "chains through the step's bound knowledge-graph nodes. Returns the step name, risk score, " +
                  "whether graph bindings were found, the number of causal chains, and the top root-cause title. " +
                  "runId: the workflow run ID. " +
                  "stepId: the ID of the step to explain. " +
                  "useLlm: whether to use LLM for narrative generation (default true).")
    public Map<String, Object> explainStep(ExplainStepInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            boolean useLlm = input.useLlm();
            StepAttributionResult stepResult = processAttributionService.explainStep(
                    input.runId(), input.stepId(), useLlm);
            result.put("stepId", stepResult.getStepId());
            result.put("stepName", stepResult.getStepName());
            result.put("riskScore", stepResult.getRiskScore());
            result.put("hasGraphBindings", stepResult.isHasGraphBindings());

            int chainCount = 0;
            String topRootCause = null;
            if (stepResult.getAttribution() != null && stepResult.getAttribution().getChains() != null) {
                chainCount = stepResult.getAttribution().getChains().size();
                if (!stepResult.getAttribution().getChains().isEmpty()) {
                    topRootCause = stepResult.getAttribution().getChains().get(0).getRootCauseTitle();
                }
            }
            result.put("chainCount", chainCount);
            result.put("topRootCause", topRootCause);
            // Include Bayesian posteriors and priors
            if (stepResult.getBayesianPosteriors() != null && !stepResult.getBayesianPosteriors().isEmpty()) {
                result.put("bayesianPosteriors", stepResult.getBayesianPosteriors());
            }
            if (stepResult.getBayesianPriors() != null && !stepResult.getBayesianPriors().isEmpty()) {
                result.put("bayesianPriors", stepResult.getBayesianPriors());
            }
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error explaining step runId={} stepId={}", input.runId(), input.stepId(), e);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_attribution_explain_control",
          description = "Explains why a SOX/J-SOX control gate failed by tracing causal chains through the " +
                  "knowledge-graph nodes bound to the step that triggered the control. Returns the alert type, " +
                  "severity, title, detailed explanation, and confidence score. " +
                  "runId: the workflow run ID. " +
                  "stepId: the step ID on which the control fired. " +
                  "controlId: the control definition ID that failed. " +
                  "useLlm: whether to use LLM for narrative generation (default true).")
    public Map<String, Object> explainControlFailure(ExplainControlInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            boolean useLlm = input.useLlm();
            ProcessEventAlert alert = processAttributionService.explainControlFailure(
                    input.runId(), input.stepId(), input.controlId(), useLlm);
            result.put("alertId", alert.getAlertId());
            result.put("alertType", alert.getAlertType());
            result.put("severity", alert.getSeverity() != null ? alert.getSeverity().name() : null);
            result.put("title", alert.getTitle());
            result.put("explanation", alert.getExplanation());
            result.put("confidence", alert.getConfidence());
            result.put("llmUsed", alert.isLlmUsed());
            result.put("createdAt", alert.getCreatedAt() != null ? alert.getCreatedAt().toString() : null);
            if (alert.getBayesianPosteriors() != null && !alert.getBayesianPosteriors().isEmpty()) {
                result.put("bayesianPosteriors", alert.getBayesianPosteriors());
            }
            if (alert.getBayesianPriors() != null && !alert.getBayesianPriors().isEmpty()) {
                result.put("bayesianPriors", alert.getBayesianPriors());
            }
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error explaining control failure runId={} stepId={} controlId={}",
                    input.runId(), input.stepId(), input.controlId(), e);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_attribution_definition_risk",
          description = "Performs a pre-flight risk assessment for a process definition before any run is started. " +
                  "Analyzes the graph node bindings of each step and runs forward prediction to surface " +
                  "potential risks. Returns an overall risk score, risk level, alert count, and high-risk step IDs. " +
                  "processDefinitionId: the process definition to analyze. " +
                  "version: the definition version to analyze (default 1). " +
                  "useLlm: whether to use LLM for narrative generation (default true).")
    public Map<String, Object> assessDefinitionRisk(DefinitionRiskInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            boolean useLlm = input.useLlm();
            ProcessRiskAssessment assessment = processAttributionService.assessDefinitionRisk(
                    input.processDefinitionId(), input.version(), useLlm);
            result.put("assessmentId", assessment.getAssessmentId());
            result.put("processDefinitionId", assessment.getProcessDefinitionId());
            result.put("overallRiskScore", assessment.getOverallRiskScore());
            result.put("riskLevel", assessment.getRiskLevel() != null ? assessment.getRiskLevel().name() : null);
            result.put("alertsCount", assessment.getAlerts() != null ? assessment.getAlerts().size() : 0);
            result.put("highRiskSteps", assessment.getHighRiskStepIds() != null ? assessment.getHighRiskStepIds() : List.of());
            result.put("computationTimeMs", assessment.getComputationTimeMs());
            result.put("llmUsed", assessment.isLlmUsed());
            result.put("computedAt", assessment.getComputedAt() != null ? assessment.getComputedAt().toString() : null);
            // Include per-step Bayesian posteriors and priors
            if (assessment.getStepAttributionResults() != null && !assessment.getStepAttributionResults().isEmpty()) {
                result.put("stepAttributions", serializeStepAttributions(assessment.getStepAttributionResults()));
            }
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error assessing definition risk for processDefinitionId={} version={}",
                    input.processDefinitionId(), input.version(), e);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    // ---- Helpers ----

    private Map<String, Object> serializeStepAttributions(Map<String, StepAttributionSummary> stepResults) {
        Map<String, Object> serialized = new LinkedHashMap<>();
        for (Map.Entry<String, StepAttributionSummary> entry : stepResults.entrySet()) {
            StepAttributionSummary s = entry.getValue();
            Map<String, Object> stepMap = new LinkedHashMap<>();
            stepMap.put("stepName", s.getStepName());
            stepMap.put("riskScore", s.getRiskScore());
            stepMap.put("bayesianInferenceAvailable", s.isBayesianInferenceAvailable());
            if (!s.getBayesianPosteriors().isEmpty()) {
                stepMap.put("bayesianPosteriors", s.getBayesianPosteriors());
            }
            if (!s.getBayesianPriors().isEmpty()) {
                stepMap.put("bayesianPriors", s.getBayesianPriors());
            }
            serialized.put(entry.getKey(), stepMap);
        }
        return serialized;
    }
}
