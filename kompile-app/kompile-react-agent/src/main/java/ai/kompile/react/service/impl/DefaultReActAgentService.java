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
package ai.kompile.react.service.impl;

import ai.kompile.react.api.Actor;
import ai.kompile.react.api.Finalizer;
import ai.kompile.react.api.Observer;
import ai.kompile.react.api.Reasoner;
import ai.kompile.react.context.AgentContext;
import ai.kompile.react.context.Toolkit;
import ai.kompile.react.context.impl.InMemoryMemory;
import ai.kompile.react.hook.AgentHook;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.ReActResult;
import ai.kompile.react.service.ReActAgent;
import ai.kompile.react.service.ReActAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of the ReActAgentService.
 * Manages agent execution lifecycle and provides a high-level API.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultReActAgentService implements ReActAgentService {

    private final Reasoner reasoner;
    private final Actor actor;
    private final Observer observer;
    private final Finalizer finalizer;
    private final List<AgentHook> hooks;

    // Track running executions
    private final Map<String, ExecutionEntry> runningExecutions = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<ReActResult> run(String query, Toolkit toolkit) {
        return run(query, toolkit, AgentOptions.defaults());
    }

    @Override
    public CompletableFuture<ReActResult> run(String query, Toolkit toolkit, AgentOptions options) {
        // Create agent context
        AgentContext context = AgentContext.builder()
                .executionId(UUID.randomUUID().toString())
                .memory(new InMemoryMemory())
                .toolkit(toolkit)
                .maxSteps(options.maxSteps())
                .systemPrompt(options.systemPrompt())
                .metadata(options.metadata())
                .build();

        // Add user query as first message
        context.addMessage(ReActMessage.user(query));

        return run(context);
    }

    @Override
    public CompletableFuture<ReActResult> run(AgentContext context) {
        String executionId = context.getExecutionId();
        log.info("Starting agent execution: {}", executionId);

        // Create the agent
        ReActAgent agent = ReActAgent.builder()
                .name("react-agent-" + executionId)
                .reasoner(reasoner)
                .actor(actor)
                .observer(observer)
                .finalizer(finalizer)
                .hooks(hooks)
                .build();

        // Track the execution
        CompletableFuture<ReActResult> future = agent.run(context);
        runningExecutions.put(executionId, new ExecutionEntry(context, future));

        // Clean up when done
        future.whenComplete((result, error) -> {
            runningExecutions.remove(executionId);
            if (error != null) {
                log.error("Execution {} failed: {}", executionId, error.getMessage());
            } else {
                log.info("Execution {} completed with status: {}", executionId, result.getStatus());
            }
        });

        return future;
    }

    @Override
    public boolean cancel(String executionId) {
        ExecutionEntry entry = runningExecutions.get(executionId);
        if (entry == null) {
            log.warn("Execution not found: {}", executionId);
            return false;
        }

        log.info("Cancelling execution: {}", executionId);
        entry.context().setCancelled(true);
        entry.future().cancel(true);
        runningExecutions.remove(executionId);
        return true;
    }

    @Override
    public ExecutionStatus getStatus(String executionId) {
        ExecutionEntry entry = runningExecutions.get(executionId);
        if (entry == null) {
            return ExecutionStatus.NOT_FOUND;
        }

        if (entry.context().isCancelled()) {
            return ExecutionStatus.CANCELLED;
        }

        if (entry.future().isDone()) {
            try {
                ReActResult result = entry.future().get();
                return result.isSuccess() ? ExecutionStatus.COMPLETED : ExecutionStatus.ERROR;
            } catch (Exception e) {
                return ExecutionStatus.ERROR;
            }
        }

        return ExecutionStatus.RUNNING;
    }

    private record ExecutionEntry(AgentContext context, CompletableFuture<ReActResult> future) {}
}
