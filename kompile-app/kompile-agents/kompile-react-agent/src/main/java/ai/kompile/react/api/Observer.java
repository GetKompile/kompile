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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Observer component that processes action results for the next reasoning iteration.
 * This is the "Observe" phase of the ReAct loop.
 *
 * <p>The Observer is responsible for:
 * <ul>
 *   <li>Processing results from tool executions</li>
 *   <li>Formatting observations for the next reasoning step</li>
 *   <li>Updating memory with relevant information</li>
 *   <li>Detecting patterns or anomalies in results</li>
 * </ul>
 *
 * <p>Implementation based on LoongFlow's ReAct pattern.
 */
public interface Observer {

    /**
     * Get the unique identifier for this observer.
     */
    String getId();

    /**
     * Get the display name for this observer.
     */
    default String getName() {
        return getId();
    }

    /**
     * Process action results and prepare for the next reasoning iteration.
     *
     * @param context The current agent context
     * @param actionResults The results from the Actor's tool executions
     * @return A future containing an optional observation message to add to memory
     */
    CompletableFuture<Optional<ReActMessage>> observe(AgentContext context, List<ReActMessage> actionResults);

    /**
     * Synchronous version of observe.
     */
    default Optional<ReActMessage> observeSync(AgentContext context, List<ReActMessage> actionResults) {
        return observe(context, actionResults).join();
    }
}
