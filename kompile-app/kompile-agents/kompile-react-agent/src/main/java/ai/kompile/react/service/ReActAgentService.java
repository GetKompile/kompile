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
package ai.kompile.react.service;

import ai.kompile.react.context.AgentContext;
import ai.kompile.react.context.Toolkit;
import ai.kompile.react.model.ReActResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Main service interface for the ReAct agent.
 * Provides high-level methods for running agent tasks.
 */
public interface ReActAgentService {

    /**
     * Run the agent with a user query.
     *
     * @param query The user query to process
     * @param toolkit The toolkit with available tools
     * @return A future containing the execution result
     */
    CompletableFuture<ReActResult> run(String query, Toolkit toolkit);

    /**
     * Run the agent with a user query and custom options.
     *
     * @param query The user query to process
     * @param toolkit The toolkit with available tools
     * @param options Execution options
     * @return A future containing the execution result
     */
    CompletableFuture<ReActResult> run(String query, Toolkit toolkit, AgentOptions options);

    /**
     * Run the agent with a pre-configured context.
     *
     * @param context The agent context
     * @return A future containing the execution result
     */
    CompletableFuture<ReActResult> run(AgentContext context);

    /**
     * Run the agent synchronously.
     *
     * @param query The user query to process
     * @param toolkit The toolkit with available tools
     * @return The execution result
     */
    default ReActResult runSync(String query, Toolkit toolkit) {
        return run(query, toolkit).join();
    }

    /**
     * Run the agent synchronously with options.
     *
     * @param query The user query to process
     * @param toolkit The toolkit with available tools
     * @param options Execution options
     * @return The execution result
     */
    default ReActResult runSync(String query, Toolkit toolkit, AgentOptions options) {
        return run(query, toolkit, options).join();
    }

    /**
     * Cancel a running agent execution.
     *
     * @param executionId The execution ID to cancel
     * @return true if the execution was cancelled
     */
    boolean cancel(String executionId);

    /**
     * Get the status of an execution.
     *
     * @param executionId The execution ID
     * @return The execution status, or null if not found
     */
    ExecutionStatus getStatus(String executionId);

    /**
     * Execution status.
     */
    enum ExecutionStatus {
        RUNNING,
        COMPLETED,
        CANCELLED,
        ERROR,
        NOT_FOUND
    }

    /**
     * Options for agent execution.
     */
    record AgentOptions(
            String systemPrompt,
            int maxSteps,
            boolean autoAdvance,
            Map<String, Object> metadata
    ) {
        public static AgentOptions defaults() {
            return new AgentOptions(null, 10, true, null);
        }

        public static AgentOptions withMaxSteps(int maxSteps) {
            return new AgentOptions(null, maxSteps, true, null);
        }

        public static AgentOptions withSystemPrompt(String systemPrompt) {
            return new AgentOptions(systemPrompt, 10, true, null);
        }
    }
}
