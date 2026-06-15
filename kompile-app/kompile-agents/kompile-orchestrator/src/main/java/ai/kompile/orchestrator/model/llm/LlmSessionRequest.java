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
package ai.kompile.orchestrator.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Request to start an LLM session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmSessionRequest {

    /**
     * Initial prompt for the LLM.
     */
    private String prompt;

    /**
     * System prompt (optional).
     */
    private String systemPrompt;

    /**
     * Model-specific parameters.
     */
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();

    /**
     * Session timeout.
     */
    @Builder.Default
    private Duration timeout = Duration.ofMinutes(10);

    /**
     * Working directory for file-aware providers (e.g., Claude Code).
     */
    private String workingDirectory;

    /**
     * Allow file system access.
     */
    @Builder.Default
    private boolean fileAccess = false;

    /**
     * Maximum tokens for the response.
     */
    private Integer maxTokens;

    /**
     * Temperature for the response.
     */
    private Double temperature;

    /**
     * Model ID to use (provider-specific).
     */
    private String modelId;

    /**
     * Stream the response.
     */
    @Builder.Default
    private boolean stream = true;

    /**
     * Orchestrator instance ID (for tracking).
     */
    private String orchestratorInstanceId;

    /**
     * Trigger ID that initiated this request.
     */
    private String triggerId;

    /**
     * Task instance ID (if triggered by a task).
     */
    private Long taskInstanceId;

    /**
     * Workflow ID (if part of a workflow).
     */
    private Long workflowId;

    /**
     * Workflow step ID (if part of a workflow step).
     */
    private Long workflowStepId;

    /**
     * Create a simple request with just a prompt.
     */
    public static LlmSessionRequest simple(String prompt) {
        return LlmSessionRequest.builder()
                .prompt(prompt)
                .build();
    }

    /**
     * Create a request with a system prompt.
     */
    public static LlmSessionRequest withSystem(String systemPrompt, String prompt) {
        return LlmSessionRequest.builder()
                .systemPrompt(systemPrompt)
                .prompt(prompt)
                .build();
    }

    /**
     * Create a request for file-aware processing.
     */
    public static LlmSessionRequest fileAware(String prompt, String workingDirectory) {
        return LlmSessionRequest.builder()
                .prompt(prompt)
                .workingDirectory(workingDirectory)
                .fileAccess(true)
                .build();
    }
}
