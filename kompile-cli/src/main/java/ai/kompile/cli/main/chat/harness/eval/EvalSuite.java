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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * A suite of evaluation cases to run against an agent.
 * Loaded from a YAML file. Example:
 * <pre>
 * name: Basic Agent Tests
 * description: Core capability tests for CLI agents
 * agent: claude
 * model: claude-sonnet-4-20250514
 * provider: anthropic
 * requiredPassRate: 0.8
 * cases:
 *   - id: greet
 *     name: Basic greeting
 *     prompt: "Say hello"
 *     assertions:
 *       - type: NO_ESCAPE
 *       - type: OUTPUT_CONTAINS
 *         value: hello
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalSuite {

    /** Suite name. */
    @JsonProperty
    private String name;

    /** Description of what this suite tests. */
    @JsonProperty
    private String description;

    /** Default agent for all cases (can be overridden per case). */
    @JsonProperty
    private String agent;

    /** Default model for all cases. */
    @JsonProperty
    private String model;

    /** Default provider for all cases. */
    @JsonProperty
    private String provider;

    /** Default working directory for all cases. */
    @JsonProperty
    private String workingDirectory;

    /** Required fraction of cases that must pass for the suite to pass. Default: 0.8 */
    @JsonProperty
    private double requiredPassRate = 0.8;

    /** The test cases. */
    @JsonProperty
    private List<EvalCase> cases = new ArrayList<>();

    /** Tags for filtering suites. */
    @JsonProperty
    private List<String> tags = new ArrayList<>();

    public EvalSuite() {}

    /** Returns the effective agent for a case (case-level overrides suite-level). */
    public String effectiveAgent(EvalCase evalCase) {
        return evalCase.getAgent() != null ? evalCase.getAgent() : agent;
    }

    /** Returns the effective model for a case. */
    public String effectiveModel(EvalCase evalCase) {
        return evalCase.getModel() != null ? evalCase.getModel() : model;
    }

    /** Returns the effective provider for a case. */
    public String effectiveProvider(EvalCase evalCase) {
        return evalCase.getProvider() != null ? evalCase.getProvider() : provider;
    }

    /** Returns the effective working directory for a case. */
    public String effectiveWorkingDirectory(EvalCase evalCase) {
        return evalCase.getWorkingDirectory() != null ? evalCase.getWorkingDirectory() : workingDirectory;
    }

    /** Returns only enabled cases. */
    public List<EvalCase> enabledCases() {
        return cases.stream().filter(EvalCase::isEnabled).toList();
    }

    // Getters
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getAgent() { return agent; }
    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public String getWorkingDirectory() { return workingDirectory; }
    public double getRequiredPassRate() { return requiredPassRate; }
    public List<EvalCase> getCases() { return cases; }
    public List<String> getTags() { return tags; }

    // Setters for Jackson
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setAgent(String agent) { this.agent = agent; }
    public void setModel(String model) { this.model = model; }
    public void setProvider(String provider) { this.provider = provider; }
    public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }
    public void setRequiredPassRate(double requiredPassRate) { this.requiredPassRate = requiredPassRate; }
    public void setCases(List<EvalCase> cases) { this.cases = cases; }
    public void setTags(List<String> tags) { this.tags = tags; }
}
