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
import ai.kompile.react.model.ToolCall;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Actor component that executes tool calls based on the reasoning.
 * This is the "Act" phase of the ReAct loop.
 *
 * <p>The Actor is responsible for:
 * <ul>
 *   <li>Executing tool calls identified by the Reasoner</li>
 *   <li>Managing execution order (sequential or parallel)</li>
 *   <li>Handling tool execution errors gracefully</li>
 *   <li>Returning execution results as messages</li>
 * </ul>
 *
 * <p>Implementation based on LoongFlow's ReAct pattern.
 */
public interface Actor {

    /**
     * Execution mode for tool calls.
     */
    enum ExecutionMode {
        /**
         * Execute tool calls one after another.
         */
        SEQUENTIAL,

        /**
         * Execute tool calls in parallel.
         */
        PARALLEL
    }

    /**
     * Get the unique identifier for this actor.
     */
    String getId();

    /**
     * Get the display name for this actor.
     */
    default String getName() {
        return getId();
    }

    /**
     * Get the execution mode for this actor.
     */
    ExecutionMode getExecutionMode();

    /**
     * Execute the given tool calls.
     *
     * @param context The current agent context with toolkit
     * @param toolCalls The tool calls to execute
     * @return A future containing the list of execution result messages
     */
    CompletableFuture<List<ReActMessage>> act(AgentContext context, List<ToolCall> toolCalls);

    /**
     * Synchronous version of act.
     */
    default List<ReActMessage> actSync(AgentContext context, List<ToolCall> toolCalls) {
        return act(context, toolCalls).join();
    }
}
