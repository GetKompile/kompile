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
package ai.kompile.react.hook;

import ai.kompile.react.context.AgentContext;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.ReActResult;
import ai.kompile.react.model.ToolCall;

import java.util.List;

/**
 * Hook interface for intercepting and customizing ReAct agent execution.
 * Hooks can be used for logging, metrics, validation, or modifying behavior.
 *
 * <p>Implementation based on LoongFlow's hook system.
 */
public interface AgentHook {

    /**
     * Get the priority of this hook. Lower values execute first.
     */
    default int getPriority() {
        return 100;
    }

    /**
     * Called before the agent starts execution.
     *
     * @param context The agent context
     */
    default void preRun(AgentContext context) {}

    /**
     * Called after the agent completes execution.
     *
     * @param context The agent context
     * @param result The execution result
     */
    default void postRun(AgentContext context, ReActResult result) {}

    /**
     * Called before the reasoning phase.
     *
     * @param context The agent context
     * @param step The current step number
     */
    default void preReason(AgentContext context, int step) {}

    /**
     * Called after the reasoning phase.
     *
     * @param context The agent context
     * @param step The current step number
     * @param message The reasoning result
     */
    default void postReason(AgentContext context, int step, ReActMessage message) {}

    /**
     * Called before the acting phase.
     *
     * @param context The agent context
     * @param toolCalls The tool calls to execute
     */
    default void preAct(AgentContext context, List<ToolCall> toolCalls) {}

    /**
     * Called after the acting phase.
     *
     * @param context The agent context
     * @param results The action results
     */
    default void postAct(AgentContext context, List<ReActMessage> results) {}

    /**
     * Called before the observation phase.
     *
     * @param context The agent context
     * @param actionResults The action results to observe
     */
    default void preObserve(AgentContext context, List<ReActMessage> actionResults) {}

    /**
     * Called after the observation phase.
     *
     * @param context The agent context
     * @param observation The observation result
     */
    default void postObserve(AgentContext context, ReActMessage observation) {}

    /**
     * Called when an error occurs during execution.
     *
     * @param context The agent context
     * @param error The error that occurred
     * @return true to continue execution, false to stop
     */
    default boolean onError(AgentContext context, Throwable error) {
        return false; // Default: stop on error
    }
}
