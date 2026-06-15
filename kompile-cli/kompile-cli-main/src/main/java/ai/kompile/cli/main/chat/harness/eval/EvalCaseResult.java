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

package ai.kompile.cli.main.chat.harness.eval;

import ai.kompile.cli.main.chat.harness.TaskOutcome;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Complete result of running one {@link EvalCase} against an agent.
 * Captures the outcome, all assertion results, judge scores, timing, and agent output.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalCaseResult {

    @JsonProperty private String caseId;
    @JsonProperty private String caseName;
    @JsonProperty private String prompt;

    // Outcome
    @JsonProperty private TaskOutcome outcome;
    @JsonProperty private String outcomeReason;

    // Assertions
    @JsonProperty private List<AssertionResult> assertionResults = new ArrayList<>();
    @JsonProperty private int assertionsPassed;
    @JsonProperty private int assertionsTotal;
    @JsonProperty private int criticalAssertionsFailed;

    // Agent execution
    @JsonProperty private String agentOutput;
    @JsonProperty private String agent;
    @JsonProperty private String model;
    @JsonProperty private String provider;
    @JsonProperty private int agenticSteps;
    @JsonProperty private int toolCallsTotal;
    @JsonProperty private int toolCallErrors;
    @JsonProperty private boolean hitMaxSteps;
    @JsonProperty private boolean hadEscape;
    @JsonProperty private String escapeType;

    // Judge scores
    @JsonProperty private float compositeScore;
    @JsonProperty private float judgeCorrectness;
    @JsonProperty private float judgeCompleteness;
    @JsonProperty private String judgeReasoning;

    // Timing
    @JsonProperty private long executionTimeMs;
    @JsonProperty private long inputTokens;
    @JsonProperty private long outputTokens;
    @JsonProperty private Instant timestamp;

    // Error
    @JsonProperty private String error;

    public EvalCaseResult() {
        this.timestamp = Instant.now();
    }

    public boolean passed() {
        return outcome == TaskOutcome.COMPLETED;
    }

    // Getters
    public String getCaseId() { return caseId; }
    public String getCaseName() { return caseName; }
    public String getPrompt() { return prompt; }
    public TaskOutcome getOutcome() { return outcome; }
    public String getOutcomeReason() { return outcomeReason; }
    public List<AssertionResult> getAssertionResults() { return assertionResults; }
    public int getAssertionsPassed() { return assertionsPassed; }
    public int getAssertionsTotal() { return assertionsTotal; }
    public int getCriticalAssertionsFailed() { return criticalAssertionsFailed; }
    public String getAgentOutput() { return agentOutput; }
    public String getAgent() { return agent; }
    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public int getAgenticSteps() { return agenticSteps; }
    public int getToolCallsTotal() { return toolCallsTotal; }
    public int getToolCallErrors() { return toolCallErrors; }
    public boolean isHitMaxSteps() { return hitMaxSteps; }
    public boolean isHadEscape() { return hadEscape; }
    public String getEscapeType() { return escapeType; }
    public float getCompositeScore() { return compositeScore; }
    public float getJudgeCorrectness() { return judgeCorrectness; }
    public float getJudgeCompleteness() { return judgeCompleteness; }
    public String getJudgeReasoning() { return judgeReasoning; }
    public long getExecutionTimeMs() { return executionTimeMs; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public Instant getTimestamp() { return timestamp; }
    public String getError() { return error; }

    // Setters
    public void setCaseId(String v) { this.caseId = v; }
    public void setCaseName(String v) { this.caseName = v; }
    public void setPrompt(String v) { this.prompt = v; }
    public void setOutcome(TaskOutcome v) { this.outcome = v; }
    public void setOutcomeReason(String v) { this.outcomeReason = v; }
    public void setAssertionResults(List<AssertionResult> v) { this.assertionResults = v; }
    public void setAssertionsPassed(int v) { this.assertionsPassed = v; }
    public void setAssertionsTotal(int v) { this.assertionsTotal = v; }
    public void setCriticalAssertionsFailed(int v) { this.criticalAssertionsFailed = v; }
    public void setAgentOutput(String v) { this.agentOutput = v; }
    public void setAgent(String v) { this.agent = v; }
    public void setModel(String v) { this.model = v; }
    public void setProvider(String v) { this.provider = v; }
    public void setAgenticSteps(int v) { this.agenticSteps = v; }
    public void setToolCallsTotal(int v) { this.toolCallsTotal = v; }
    public void setToolCallErrors(int v) { this.toolCallErrors = v; }
    public void setHitMaxSteps(boolean v) { this.hitMaxSteps = v; }
    public void setHadEscape(boolean v) { this.hadEscape = v; }
    public void setEscapeType(String v) { this.escapeType = v; }
    public void setCompositeScore(float v) { this.compositeScore = v; }
    public void setJudgeCorrectness(float v) { this.judgeCorrectness = v; }
    public void setJudgeCompleteness(float v) { this.judgeCompleteness = v; }
    public void setJudgeReasoning(String v) { this.judgeReasoning = v; }
    public void setExecutionTimeMs(long v) { this.executionTimeMs = v; }
    public void setInputTokens(long v) { this.inputTokens = v; }
    public void setOutputTokens(long v) { this.outputTokens = v; }
    public void setTimestamp(Instant v) { this.timestamp = v; }
    public void setError(String v) { this.error = v; }

    /** Create a result for a case that failed with an error before execution. */
    public static EvalCaseResult error(EvalCase evalCase, String errorMsg) {
        EvalCaseResult r = new EvalCaseResult();
        r.setCaseId(evalCase.getId());
        r.setCaseName(evalCase.getName());
        r.setPrompt(evalCase.getPrompt());
        r.setOutcome(TaskOutcome.FAILED);
        r.setOutcomeReason("Error: " + errorMsg);
        r.setError(errorMsg);
        return r;
    }
}
