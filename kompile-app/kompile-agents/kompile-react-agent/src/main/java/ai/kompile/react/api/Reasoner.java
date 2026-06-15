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

import java.util.concurrent.CompletableFuture;

/**
 * Reasoner component that analyzes the current context and decides on the next action.
 * This is the "Reason" phase of the ReAct loop.
 *
 * <p>The Reasoner is responsible for:
 * <ul>
 *   <li>Understanding the current state of the conversation/task</li>
 *   <li>Analyzing previous observations and results</li>
 *   <li>Deciding what tool(s) to invoke next</li>
 *   <li>Generating thought processes explaining the reasoning</li>
 * </ul>
 *
 * <p>Implementation based on LoongFlow's ReAct pattern.
 */
public interface Reasoner {

    /**
     * Get the unique identifier for this reasoner.
     */
    String getId();

    /**
     * Get the display name for this reasoner.
     */
    default String getName() {
        return getId();
    }

    /**
     * Reason about the current context and decide on the next action.
     *
     * @param context The current agent context with memory and toolkit
     * @return A future containing the reasoning message with thoughts and tool calls
     */
    CompletableFuture<ReActMessage> reason(AgentContext context);

    /**
     * Synchronous version of reason.
     */
    default ReActMessage reasonSync(AgentContext context) {
        return reason(context).join();
    }
}
