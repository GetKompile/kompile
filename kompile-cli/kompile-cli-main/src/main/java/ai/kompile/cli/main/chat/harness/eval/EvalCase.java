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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A single evaluation test case — a task to give an agent with
 * assertions that determine if the task was completed successfully.
 *
 * <p>Loaded from YAML/JSON eval suite files. Example YAML:
 * <pre>
 * - id: read-file-test
 *   name: Can read a file and report contents
 *   prompt: "Read the file /tmp/test.txt and tell me what's in it"
 *   agent: claude
 *   assertions:
 *     - type: NO_ESCAPE
 *     - type: TOOL_WAS_CALLED
 *       value: read
 *     - type: OUTPUT_CONTAINS
 *       value: "hello world"
 *   tags: [basic, file-io]
 *   timeoutMs: 60000
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalCase {

    /** Unique ID within the suite. */
    @JsonProperty
    private String id;

    /** Human-readable name. */
    @JsonProperty
    private String name;

    /** The prompt to send to the agent — the task to perform. */
    @JsonProperty
    private String prompt;

    /** Optional setup commands to run before the agent (shell commands). */
    @JsonProperty
    private List<String> setup;

    /** Optional teardown commands to run after the agent. */
    @JsonProperty
    private List<String> teardown;

    /** Agent name to use (e.g. "claude", "codex", "qwen"). Defaults to suite-level agent. */
    @JsonProperty
    private String agent;

    /** Model to use (e.g. "claude-sonnet-4-20250514"). Defaults to suite-level model. */
    @JsonProperty
    private String model;

    /** Provider to use (e.g. "anthropic"). Defaults to suite-level provider. */
    @JsonProperty
    private String provider;

    /** Deterministic assertions to check against the agent's output. */
    @JsonProperty
    @Builder.Default
    private List<Assertion> assertions = new ArrayList<>();

    /** Optional task-specific rubric for the judge LLM.
     *  Overrides the default judge system prompt with case-specific criteria. */
    @JsonProperty
    private String judgeRubric;

    /** Maximum time for the agent to complete the task. Default: 120000ms (2 min). */
    @JsonProperty
    @Builder.Default
    private long timeoutMs = 120_000;

    /** Maximum agentic steps. Default: 50. */
    @JsonProperty
    @Builder.Default
    private int maxSteps = 50;

    /** Tags for filtering and grouping cases. */
    @JsonProperty
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /** Priority (1-5, higher = more important). Default: 3. */
    @JsonProperty
    @Builder.Default
    private int priority = 3;

    /** If false, this case is skipped during eval runs. Default: true. */
    @JsonProperty
    @Builder.Default
    private boolean enabled = true;

    /** Working directory for the agent. Defaults to suite-level or cwd. */
    @JsonProperty
    private String workingDirectory;

    /** Environment variables to set for this case. */
    @JsonProperty
    private Map<String, String> env;

    @Override
    public String toString() {
        return "EvalCase{id='" + id + "', name='" + name + "', assertions=" + assertions.size() + "}";
    }
}
