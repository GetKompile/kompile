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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Complete result of running one {@link EvalCase} against an agent.
 * Captures the outcome, all assertion results, judge scores, timing, and agent output.
 */
@Data
@Builder
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalCaseResult {

    @JsonProperty private String caseId;
    @JsonProperty private String caseName;
    @JsonProperty private String prompt;

    // Outcome
    @JsonProperty private TaskOutcome outcome;
    @JsonProperty private String outcomeReason;

    // Assertions
    @JsonProperty @Builder.Default private List<AssertionResult> assertionResults = new ArrayList<>();
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
        this.assertionResults = new ArrayList<>();
    }

    public boolean passed() {
        return outcome == TaskOutcome.COMPLETED;
    }

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
