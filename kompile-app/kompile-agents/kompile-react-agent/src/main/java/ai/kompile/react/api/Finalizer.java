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
package ai.kompile.react.api;

import ai.kompile.react.context.AgentContext;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.ToolDefinition;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Finalizer component that determines task completion and constructs final responses.
 * This handles the terminal phase of the ReAct loop.
 *
 * <p>The Finalizer is responsible for:
 * <ul>
 *   <li>Detecting when the task is complete</li>
 *   <li>Extracting the final answer from the reasoning process</li>
 *   <li>Handling max steps exceeded scenarios</li>
 *   <li>Generating summaries when needed</li>
 * </ul>
 *
 * <p>Implementation based on LoongFlow's ReAct pattern.
 */
public interface Finalizer {

    /**
     * Get the unique identifier for this finalizer.
     */
    String getId();

    /**
     * Get the display name for this finalizer.
     */
    default String getName() {
        return getId();
    }

    /**
     * Get the schema for the final answer tool.
     * This tool is used by the LLM to provide structured final answers.
     *
     * @return The tool definition for the final answer
     */
    ToolDefinition getAnswerSchema();

    /**
     * Check if the current message indicates task completion.
     *
     * @param message The message to check
     * @return true if the task is complete
     */
    boolean isComplete(ReActMessage message);

    /**
     * Resolve the final answer from a completion message.
     *
     * @param context The current agent context
     * @param message The completion message
     * @return A future containing the final answer message
     */
    CompletableFuture<ReActMessage> resolveAnswer(AgentContext context, ReActMessage message);

    /**
     * Generate a summary when the maximum steps are exceeded.
     *
     * @param context The current agent context
     * @return A future containing the summary message
     */
    CompletableFuture<ReActMessage> summarizeOnExceed(AgentContext context);

    /**
     * Synchronous version of resolveAnswer.
     */
    default ReActMessage resolveAnswerSync(AgentContext context, ReActMessage message) {
        return resolveAnswer(context, message).join();
    }

    /**
     * Synchronous version of summarizeOnExceed.
     */
    default ReActMessage summarizeOnExceedSync(AgentContext context) {
        return summarizeOnExceed(context).join();
    }
}
