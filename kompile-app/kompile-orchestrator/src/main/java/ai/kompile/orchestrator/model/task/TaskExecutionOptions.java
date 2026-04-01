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
package ai.kompile.orchestrator.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Options for task execution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecutionOptions {

    /**
     * Run the task asynchronously.
     */
    @Builder.Default
    private boolean async = true;

    /**
     * Stream output to WebSocket in real-time.
     */
    @Builder.Default
    private boolean streamOutput = true;

    /**
     * Override the working directory from the task definition.
     */
    private String workingDirectory;

    /**
     * Override the timeout from the task definition.
     */
    private Duration timeout;

    /**
     * Environment variables to set for the task.
     */
    @Builder.Default
    private Map<String, String> environment = new HashMap<>();

    /**
     * Auto-invoke LLM on failure.
     */
    @Builder.Default
    private boolean autoInvokeLlmOnError = false;

    /**
     * LLM provider ID to use for error handling.
     */
    private String llmProviderId;

    /**
     * Custom prompt for LLM error handling.
     */
    private String llmErrorPrompt;

    /**
     * Capture stderr separately from stdout.
     */
    @Builder.Default
    private boolean captureStderr = false;

    /**
     * Maximum output size in bytes (0 = unlimited).
     */
    @Builder.Default
    private long maxOutputSize = 10 * 1024 * 1024; // 10 MB

    /**
     * Retry on failure.
     */
    @Builder.Default
    private boolean retryOnFailure = false;

    /**
     * Number of retry attempts.
     */
    @Builder.Default
    private int retryCount = 0;

    /**
     * Delay between retries.
     */
    @Builder.Default
    private Duration retryDelay = Duration.ofSeconds(5);

    /**
     * Create default options.
     */
    public static TaskExecutionOptions defaults() {
        return TaskExecutionOptions.builder().build();
    }

    /**
     * Create synchronous execution options.
     */
    public static TaskExecutionOptions synchronous() {
        return TaskExecutionOptions.builder()
                .async(false)
                .build();
    }

    /**
     * Create options with a specific timeout.
     */
    public static TaskExecutionOptions withTimeout(Duration timeout) {
        return TaskExecutionOptions.builder()
                .timeout(timeout)
                .build();
    }
}
