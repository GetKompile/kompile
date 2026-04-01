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
package ai.kompile.orchestrator.service.prompt;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Request for building a prompt with all necessary context.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptBuildRequest {

    /**
     * Current state ID.
     */
    private String stateId;

    /**
     * Previous state ID (for transition context).
     */
    private String previousStateId;

    /**
     * The orchestrator instance ID.
     */
    private String orchestratorInstanceId;

    /**
     * The task description or prompt.
     */
    private String taskDescription;

    /**
     * Output from previous task/command.
     */
    private String output;

    /**
     * Exit code from previous task/command.
     */
    private Integer exitCode;

    /**
     * Error message if any.
     */
    private String errorMessage;

    /**
     * Classification type from output classifier.
     */
    private String classification;

    /**
     * Current retry count.
     */
    @Builder.Default
    private int retryCount = 0;

    /**
     * Maximum retries allowed.
     */
    @Builder.Default
    private int maxRetries = 5;

    /**
     * Context variables.
     */
    @Builder.Default
    private Map<String, Object> context = new HashMap<>();

    /**
     * Additional variables for template rendering.
     */
    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();

    /**
     * System prompt override.
     */
    private String systemPromptOverride;

    /**
     * Task ID (if applicable).
     */
    private String taskId;

    /**
     * Task name (if applicable).
     */
    private String taskName;

    /**
     * Working directory.
     */
    private String workingDirectory;

    /**
     * Workflow ID (if applicable).
     */
    private Long workflowId;

    /**
     * Workflow step ID (if applicable).
     */
    private Long workflowStepId;

    /**
     * Whether to include routing advice.
     */
    @Builder.Default
    private boolean includeRoutingAdvice = true;

    /**
     * Add a context variable.
     */
    public PromptBuildRequest addContext(String key, Object value) {
        if (context == null) {
            context = new HashMap<>();
        }
        context.put(key, value);
        return this;
    }

    /**
     * Add a template variable.
     */
    public PromptBuildRequest addVariable(String key, Object value) {
        if (variables == null) {
            variables = new HashMap<>();
        }
        variables.put(key, value);
        return this;
    }

    /**
     * Create a request for executing state.
     */
    public static PromptBuildRequest forExecution(String taskDescription, String workingDirectory) {
        return PromptBuildRequest.builder()
                .stateId("EXECUTING")
                .taskDescription(taskDescription)
                .workingDirectory(workingDirectory)
                .build();
    }

    /**
     * Create a request for failed state.
     */
    public static PromptBuildRequest forFailure(String output, Integer exitCode, String errorMessage) {
        return PromptBuildRequest.builder()
                .stateId("FAILED")
                .output(output)
                .exitCode(exitCode)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Create a request for analysis state.
     */
    public static PromptBuildRequest forAnalysis(String output, Integer exitCode, String taskName) {
        return PromptBuildRequest.builder()
                .stateId("ANALYZING")
                .output(output)
                .exitCode(exitCode)
                .taskName(taskName)
                .build()
                .addVariable("taskStatus", exitCode != null && exitCode == 0 ? "SUCCESS" : "FAILURE");
    }

    /**
     * Create a request with state transition.
     */
    public static PromptBuildRequest forTransition(String fromState, String toState) {
        return PromptBuildRequest.builder()
                .stateId(toState)
                .previousStateId(fromState)
                .build();
    }
}
