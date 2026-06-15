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

import ai.kompile.react.api.Actor;
import ai.kompile.react.api.Finalizer;
import ai.kompile.react.api.Observer;
import ai.kompile.react.api.Reasoner;
import ai.kompile.react.context.AgentContext;
import ai.kompile.react.hook.AgentHook;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.ReActResult;
import ai.kompile.react.model.ToolCall;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The main ReAct (Reason-Act-Observe) agent implementation.
 * Orchestrates the four primary components: Reasoner, Actor, Observer, and Finalizer.
 *
 * <p>Execution Flow:
 * <ol>
 *   <li>Reason phase: Analyze context and decide on next action</li>
 *   <li>Act phase: Execute tool calls</li>
 *   <li>Observe phase: Process tool outputs</li>
 *   <li>Finalize check: Determine if task is complete</li>
 * </ol>
 *
 * <p>The loop continues until either the finalizer indicates completion
 * or max steps are exceeded.
 *
 * <p>Implementation based on LoongFlow's ReAct pattern.
 */
@Slf4j
@Getter
public class ReActAgent {

    private final String name;
    private final Reasoner reasoner;
    private final Actor actor;
    private final Observer observer;
    private final Finalizer finalizer;
    private final List<AgentHook> hooks;

    @Builder
    public ReActAgent(
            String name,
            Reasoner reasoner,
            Actor actor,
            Observer observer,
            Finalizer finalizer,
            List<AgentHook> hooks
    ) {
        this.name = name != null ? name : "react-agent";
        this.reasoner = reasoner;
        this.actor = actor;
        this.observer = observer;
        this.finalizer = finalizer;
        this.hooks = hooks != null
                ? hooks.stream()
                    .sorted(Comparator.comparingInt(AgentHook::getPriority))
                    .toList()
                : new ArrayList<>();
    }

    /**
     * Run the agent with the given context.
     *
     * @param context The agent context
     * @return A future containing the execution result
     */
    public CompletableFuture<ReActResult> run(AgentContext context) {
        return CompletableFuture.supplyAsync(() -> runSync(context));
    }

    /**
     * Run the agent synchronously.
     *
     * @param context The agent context
     * @return The execution result
     */
    public ReActResult runSync(AgentContext context) {
        Instant startTime = Instant.now();
        log.info("Agent {} starting execution: {}", name, context.getExecutionId());

        // Call pre-run hooks
        invokePreRun(context);

        try {
            // Main execution loop
            while (!context.shouldStop() && !context.hasReachedMaxSteps()) {
                int step = context.incrementStep();
                log.debug("Agent {} step {}/{}", name, step, context.getMaxSteps());

                // === REASON PHASE ===
                invokePreReason(context, step);
                ReActMessage reasoningResult = reasoner.reasonSync(context);
                invokePostReason(context, step, reasoningResult);

                if (reasoningResult == null) {
                    log.warn("Reasoner returned null result at step {}", step);
                    break;
                }

                // Add reasoning to memory
                context.addMessage(reasoningResult);
                context.addUsage(reasoningResult.getUsage());

                // Check if this is a final answer
                if (finalizer.isComplete(reasoningResult)) {
                    log.info("Agent {} completing at step {}", name, step);
                    ReActMessage finalAnswer = finalizer.resolveAnswerSync(context, reasoningResult);
                    context.addMessage(finalAnswer);

                    ReActResult result = ReActResult.success(
                            finalAnswer.getContent(),
                            context.getMessages(),
                            step,
                            context.getTotalUsage(),
                            startTime,
                            Instant.now()
                    );
                    invokePostRun(context, result);
                    return result;
                }

                // Check if there are tool calls to execute
                if (!reasoningResult.hasToolCalls()) {
                    log.debug("No tool calls at step {}, continuing reasoning", step);
                    continue;
                }

                List<ToolCall> toolCalls = reasoningResult.getToolCalls();

                // === ACT PHASE ===
                invokePreAct(context, toolCalls);
                List<ReActMessage> actionResults = actor.actSync(context, toolCalls);
                invokePostAct(context, actionResults);

                // Add action results to memory
                context.addMessages(actionResults);

                // === OBSERVE PHASE ===
                invokePreObserve(context, actionResults);
                Optional<ReActMessage> observation = observer.observeSync(context, actionResults);
                if (observation.isPresent()) {
                    context.addMessage(observation.get());
                    invokePostObserve(context, observation.get());
                }
            }

            // Max steps exceeded
            if (context.hasReachedMaxSteps()) {
                log.warn("Agent {} reached max steps ({})", name, context.getMaxSteps());
                ReActMessage summary = finalizer.summarizeOnExceedSync(context);
                context.addMessage(summary);

                ReActResult result = ReActResult.maxStepsExceeded(
                        summary.getContent(),
                        context.getMessages(),
                        context.getCurrentStep(),
                        context.getTotalUsage(),
                        startTime,
                        Instant.now()
                );
                invokePostRun(context, result);
                return result;
            }

            // Cancelled or interrupted
            if (context.isCancelled()) {
                log.info("Agent {} was cancelled", name);
                return ReActResult.builder()
                        .status(ReActResult.Status.CANCELLED)
                        .messages(context.getMessages())
                        .stepsExecuted(context.getCurrentStep())
                        .totalUsage(context.getTotalUsage())
                        .startTime(startTime)
                        .endTime(Instant.now())
                        .build();
            }

            // Unexpected exit
            return ReActResult.error(
                    "Agent exited without completing",
                    context.getMessages(),
                    context.getCurrentStep(),
                    context.getTotalUsage(),
                    startTime,
                    Instant.now()
            );

        } catch (Exception e) {
            log.error("Agent {} encountered error: {}", name, e.getMessage(), e);

            // Try hooks error handling
            boolean shouldContinue = invokeOnError(context, e);
            if (shouldContinue) {
                // Hooks want to continue - this is rare but supported
                log.info("Hook requested continuation after error");
            }

            return ReActResult.error(
                    e.getMessage(),
                    context.getMessages(),
                    context.getCurrentStep(),
                    context.getTotalUsage(),
                    startTime,
                    Instant.now()
            );
        }
    }

    // === Hook Invocation Methods ===

    private void invokePreRun(AgentContext context) {
        for (AgentHook hook : hooks) {
            try {
                hook.preRun(context);
            } catch (Exception e) {
                log.warn("Hook preRun failed: {}", e.getMessage());
            }
        }
    }

    private void invokePostRun(AgentContext context, ReActResult result) {
        for (AgentHook hook : hooks) {
            try {
                hook.postRun(context, result);
            } catch (Exception e) {
                log.warn("Hook postRun failed: {}", e.getMessage());
            }
        }
    }

    private void invokePreReason(AgentContext context, int step) {
        for (AgentHook hook : hooks) {
            try {
                hook.preReason(context, step);
            } catch (Exception e) {
                log.warn("Hook preReason failed: {}", e.getMessage());
            }
        }
    }

    private void invokePostReason(AgentContext context, int step, ReActMessage message) {
        for (AgentHook hook : hooks) {
            try {
                hook.postReason(context, step, message);
            } catch (Exception e) {
                log.warn("Hook postReason failed: {}", e.getMessage());
            }
        }
    }

    private void invokePreAct(AgentContext context, List<ToolCall> toolCalls) {
        for (AgentHook hook : hooks) {
            try {
                hook.preAct(context, toolCalls);
            } catch (Exception e) {
                log.warn("Hook preAct failed: {}", e.getMessage());
            }
        }
    }

    private void invokePostAct(AgentContext context, List<ReActMessage> results) {
        for (AgentHook hook : hooks) {
            try {
                hook.postAct(context, results);
            } catch (Exception e) {
                log.warn("Hook postAct failed: {}", e.getMessage());
            }
        }
    }

    private void invokePreObserve(AgentContext context, List<ReActMessage> actionResults) {
        for (AgentHook hook : hooks) {
            try {
                hook.preObserve(context, actionResults);
            } catch (Exception e) {
                log.warn("Hook preObserve failed: {}", e.getMessage());
            }
        }
    }

    private void invokePostObserve(AgentContext context, ReActMessage observation) {
        for (AgentHook hook : hooks) {
            try {
                hook.postObserve(context, observation);
            } catch (Exception e) {
                log.warn("Hook postObserve failed: {}", e.getMessage());
            }
        }
    }

    private boolean invokeOnError(AgentContext context, Throwable error) {
        for (AgentHook hook : hooks) {
            try {
                if (hook.onError(context, error)) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("Hook onError failed: {}", e.getMessage());
            }
        }
        return false;
    }
}
