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

/**
 * A single deterministic assertion to evaluate against agent output.
 * Assertions are the backbone of outcome tracking — they turn a subjective
 * "did the agent do well?" into a concrete pass/fail check.
 *
 * <p>Supported assertion types:
 * <ul>
 *   <li>{@code OUTPUT_CONTAINS} — output text contains the given value (case-insensitive)</li>
 *   <li>{@code OUTPUT_NOT_CONTAINS} — output must NOT contain the value</li>
 *   <li>{@code OUTPUT_MATCHES_REGEX} — output matches the given regex pattern</li>
 *   <li>{@code TOOL_WAS_CALLED} — a specific tool was invoked at least once</li>
 *   <li>{@code TOOL_NOT_CALLED} — a specific tool was NOT invoked</li>
 *   <li>{@code NO_ESCAPE} — agent did not escape (refuse, empty, loop)</li>
 *   <li>{@code NO_TOOL_ERRORS} — agent completed without tool execution errors</li>
 *   <li>{@code MAX_STEPS_NOT_HIT} — agent finished before hitting step limit</li>
 *   <li>{@code SCORE_ABOVE} — judge composite score above threshold</li>
 *   <li>{@code JUDGE_CORRECTNESS_ABOVE} — judge correctness dimension above threshold</li>
 *   <li>{@code JUDGE_COMPLETENESS_ABOVE} — judge completeness dimension above threshold</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Assertion {

    public enum Type {
        OUTPUT_CONTAINS,
        OUTPUT_NOT_CONTAINS,
        OUTPUT_MATCHES_REGEX,
        TOOL_WAS_CALLED,
        TOOL_NOT_CALLED,
        NO_ESCAPE,
        NO_TOOL_ERRORS,
        MAX_STEPS_NOT_HIT,
        SCORE_ABOVE,
        JUDGE_CORRECTNESS_ABOVE,
        JUDGE_COMPLETENESS_ABOVE
    }

    @JsonProperty
    private Type type;

    /** The value to check against — meaning depends on type:
     *  - For OUTPUT_CONTAINS/NOT_CONTAINS: substring to match
     *  - For OUTPUT_MATCHES_REGEX: regex pattern
     *  - For TOOL_WAS_CALLED/NOT_CALLED: tool name
     *  - For SCORE_ABOVE/JUDGE_*: numeric threshold as string (e.g. "3.5")
     */
    @JsonProperty
    private String value;

    /** Human-readable description of what this assertion checks. */
    @JsonProperty
    private String description;

    /** If true, this assertion is critical — failure means the entire case fails.
     *  Non-critical assertions contribute to partial score. Default: true. */
    @JsonProperty
    private boolean critical = true;

    public Assertion() {}

    public Assertion(Type type, String value, String description, boolean critical) {
        this.type = type;
        this.value = value;
        this.description = description;
        this.critical = critical;
    }

    // Convenience factories
    public static Assertion outputContains(String substring) {
        return new Assertion(Type.OUTPUT_CONTAINS, substring,
                "Output contains: " + substring, true);
    }

    public static Assertion outputNotContains(String substring) {
        return new Assertion(Type.OUTPUT_NOT_CONTAINS, substring,
                "Output does not contain: " + substring, true);
    }

    public static Assertion outputMatchesRegex(String regex) {
        return new Assertion(Type.OUTPUT_MATCHES_REGEX, regex,
                "Output matches regex: " + regex, true);
    }

    public static Assertion toolWasCalled(String toolName) {
        return new Assertion(Type.TOOL_WAS_CALLED, toolName,
                "Tool was called: " + toolName, true);
    }

    public static Assertion toolNotCalled(String toolName) {
        return new Assertion(Type.TOOL_NOT_CALLED, toolName,
                "Tool was not called: " + toolName, false);
    }

    public static Assertion noEscape() {
        return new Assertion(Type.NO_ESCAPE, null,
                "Agent did not escape", true);
    }

    public static Assertion noToolErrors() {
        return new Assertion(Type.NO_TOOL_ERRORS, null,
                "No tool execution errors", false);
    }

    public static Assertion maxStepsNotHit() {
        return new Assertion(Type.MAX_STEPS_NOT_HIT, null,
                "Agent finished before step limit", true);
    }

    public static Assertion scoreAbove(float threshold) {
        return new Assertion(Type.SCORE_ABOVE, String.valueOf(threshold),
                "Composite score above " + threshold, false);
    }

    // Getters
    public Type getType() { return type; }
    public String getValue() { return value; }
    public String getDescription() { return description; }
    public boolean isCritical() { return critical; }

    // Setters for Jackson
    public void setType(Type type) { this.type = type; }
    public void setValue(String value) { this.value = value; }
    public void setDescription(String description) { this.description = description; }
    public void setCritical(boolean critical) { this.critical = critical; }
}
