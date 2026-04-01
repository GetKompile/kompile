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
import java.util.concurrent.CompletableFuture;

/**
 * An Actor implementation that executes tool calls sequentially.
 * Each tool call is executed one after another, in order.
 */
@Slf4j
public class SequenceActor implements Actor {

    private final String id;
    private final String name;

    @Builder
    public SequenceActor(String id, String name) {
        this.id = id != null ? id : "sequence-actor";
        this.name = name != null ? name : "Sequence Actor";
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
        return ExecutionMode.SEQUENTIAL;
    }

    @Override
    public CompletableFuture<List<ReActMessage>> act(AgentContext context, List<ToolCall> toolCalls) {
        return CompletableFuture.supplyAsync(() -> actSync(context, toolCalls));
    }

    @Override
    public List<ReActMessage> actSync(AgentContext context, List<ToolCall> toolCalls) {
        List<ReActMessage> results = new ArrayList<>();

        if (toolCalls == null || toolCalls.isEmpty()) {
            log.debug("No tool calls to execute");
            return results;
        }

        log.debug("Executing {} tool calls sequentially", toolCalls.size());

        for (ToolCall toolCall : toolCalls) {
            if (context.shouldStop()) {
                log.info("Execution stopped, aborting remaining tool calls");
                break;
            }

            try {
                log.debug("Executing tool: {}", toolCall.getName());
                String result = context.getToolkit().executeSync(toolCall);

                results.add(ReActMessage.toolResult(
                        toolCall.getId(),
                        toolCall.getName(),
                        result,
                        true
                ));

                log.debug("Tool {} completed successfully", toolCall.getName());

            } catch (Exception e) {
                log.error("Tool {} failed: {}", toolCall.getName(), e.getMessage(), e);

                results.add(ReActMessage.toolError(
                        toolCall.getId(),
                        toolCall.getName(),
                        e.getMessage()
                ));
            }
        }

        return results;
    }
}
