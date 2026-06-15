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
package ai.kompile.react.component.impl;

import ai.kompile.react.api.Actor;
import ai.kompile.react.context.AgentContext;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.ToolCall;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * An Actor implementation that executes tool calls in parallel.
 * All tool calls are executed concurrently for better performance.
 */
@Slf4j
public class ParallelActor implements Actor {

    private final String id;
    private final String name;

    @Builder
    public ParallelActor(String id, String name) {
        this.id = id != null ? id : "parallel-actor";
        this.name = name != null ? name : "Parallel Actor";
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ExecutionMode getExecutionMode() {
        return ExecutionMode.PARALLEL;
    }

    @Override
    public CompletableFuture<List<ReActMessage>> act(AgentContext context, List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.debug("No tool calls to execute");
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        log.debug("Executing {} tool calls in parallel", toolCalls.size());

        // Execute all tool calls in parallel
        return context.getToolkit().executeParallel(toolCalls)
                .thenApply(resultMap -> convertResults(toolCalls, resultMap));
    }

    private List<ReActMessage> convertResults(List<ToolCall> toolCalls, Map<String, String> resultMap) {
        List<ReActMessage> results = new ArrayList<>();

        for (ToolCall toolCall : toolCalls) {
            String result = resultMap.get(toolCall.getId());

            if (result != null && !result.startsWith("Tool execution failed:") &&
                !result.startsWith("Tool not found:")) {
                results.add(ReActMessage.toolResult(
                        toolCall.getId(),
                        toolCall.getName(),
                        result,
                        true
                ));
            } else {
                results.add(ReActMessage.toolError(
                        toolCall.getId(),
                        toolCall.getName(),
                        result != null ? result : "Unknown error"
                ));
            }
        }

        return results;
    }

    @Override
    public List<ReActMessage> actSync(AgentContext context, List<ToolCall> toolCalls) {
        return act(context, toolCalls).join();
    }
}
