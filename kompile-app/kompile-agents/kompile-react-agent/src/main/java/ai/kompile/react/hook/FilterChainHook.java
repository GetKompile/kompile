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

import ai.kompile.core.filter.FilterContext;
import ai.kompile.core.filter.FilterPhase;
import ai.kompile.core.filter.FilterResult;
import ai.kompile.filterchain.service.FilterChainService;
import ai.kompile.react.context.AgentContext;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.ReActResult;
import ai.kompile.react.model.ToolCall;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hook that integrates the ReAct agent with the filter chain.
 * Applies filters at different phases of agent execution.
 *
 * <p>Phase mapping:
 * <ul>
 *   <li>PRE_RETRIEVAL - Applied before reasoning (preReason)</li>
 *   <li>POST_RETRIEVAL - Applied after tool results (postAct)</li>
 *   <li>PRE_LLM - Applied before finalizing</li>
 *   <li>POST_LLM - Applied after completion (postRun)</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class FilterChainHook implements AgentHook {

    private final FilterChainService filterChainService;

    @Override
    public int getPriority() {
        return 50; // Run early in the hook chain
    }

    @Override
    public void preRun(AgentContext context) {
        // Apply PRE_RETRIEVAL filters to the initial context
        if (!filterChainService.isEnabled()) {
            return;
        }

        List<ReActMessage> userMessages = context.getMessages().stream()
                .filter(m -> m.getRole() == ReActMessage.Role.USER)
                .toList();

        if (userMessages.isEmpty()) {
            return;
        }

        ReActMessage lastUserMessage = userMessages.get(userMessages.size() - 1);
        FilterContext filterContext = createFilterContext(context, lastUserMessage);

        try {
            FilterResult result = filterChainService.execute(filterContext, FilterPhase.PRE_RETRIEVAL);

            if (result != null && result.isTerminating()) {
                log.warn("Input filter chain stopped execution: {}", result.getMessage());
                context.setCancelled(true);
            }

            // Store filter results in context metadata
            if (result != null && result.getMutatedContext() != null) {
                String rewritten = result.getMutatedContext().getRewrittenQuery();
                if (rewritten != null) {
                    context.setMetadata("filtered_input", rewritten);
                }
            }

        } catch (Exception e) {
            log.error("Error executing input filters: {}", e.getMessage(), e);
        }
    }

    @Override
    public void preReason(AgentContext context, int step) {
        // Apply reasoning-phase filters
        if (!filterChainService.isEnabled()) {
            return;
        }

        // Create filter context with current state
        FilterContext filterContext = FilterContext.builder()
                .userMessage(buildContextSummary(context))
                .originalQuery(buildContextSummary(context))
                .build();

        filterContext.getRequestMetadata().put("step", step);
        filterContext.getRequestMetadata().put("max_steps", context.getMaxSteps());

        try {
            // Use POST_RETRIEVAL phase for reasoning input filtering
            FilterResult result = filterChainService.execute(filterContext, FilterPhase.POST_RETRIEVAL);

            if (result != null && result.isTerminating()) {
                log.warn("Reasoning filter stopped at step {}: {}", step, result.getMessage());
            }

        } catch (Exception e) {
            log.debug("No reasoning filters applied or error: {}", e.getMessage());
        }
    }

    @Override
    public void postReason(AgentContext context, int step, ReActMessage message) {
        // Apply post-reasoning filters
        if (!filterChainService.isEnabled() || message == null) {
            return;
        }

        // Check for potentially unsafe reasoning
        if (message.getThought() != null) {
            FilterContext filterContext = createFilterContext(context, message);

            try {
                FilterResult result = filterChainService.execute(filterContext, FilterPhase.PRE_LLM);

                if (result != null && result.isTerminating()) {
                    log.warn("Reasoning output filtered at step {}: {}", step, result.getMessage());
                    // Clear tool calls if filtered
                    message.getToolCalls().clear();
                }

            } catch (Exception e) {
                log.debug("No post-reasoning filters applied: {}", e.getMessage());
            }
        }
    }

    @Override
    public void postAct(AgentContext context, List<ReActMessage> results) {
        // Apply filters to tool results
        if (!filterChainService.isEnabled() || results == null || results.isEmpty()) {
            return;
        }

        for (ReActMessage result : results) {
            if (result.getContent() != null) {
                FilterContext filterContext = createFilterContext(context, result);

                try {
                    FilterResult filterResult = filterChainService.execute(filterContext, FilterPhase.POST_RETRIEVAL);

                    if (filterResult != null && filterResult.getMutatedContext() != null) {
                        // Log that filtering occurred
                        log.debug("Tool result filtered: {}", result.getToolName());
                    }

                } catch (Exception e) {
                    log.debug("No tool result filters applied: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public void postRun(AgentContext context, ReActResult result) {
        // Apply POST_LLM filters to final result
        if (!filterChainService.isEnabled() || result == null) {
            return;
        }

        if (result.getAnswer() != null) {
            FilterContext filterContext = FilterContext.builder()
                    .userMessage(result.getAnswer())
                    .originalQuery(result.getAnswer())
                    .llmResponse(result.getAnswer())
                    .build();

            filterContext.getRequestMetadata().put("status", result.getStatus().name());
            filterContext.getRequestMetadata().put("steps_executed", result.getStepsExecuted());

            try {
                FilterResult filterResult = filterChainService.execute(filterContext, FilterPhase.POST_LLM);

                if (filterResult != null) {
                    if (filterResult.isTerminating()) {
                        log.warn("Output filter blocked final answer: {}", filterResult.getMessage());
                    }
                }

            } catch (Exception e) {
                log.debug("No output filters applied: {}", e.getMessage());
            }
        }
    }

    private FilterContext createFilterContext(AgentContext context, ReActMessage message) {
        String content = message.getContent();
        if (content == null && message.getThought() != null) {
            content = message.getThought();
        }

        FilterContext filterContext = FilterContext.builder()
                .userMessage(content != null ? content : "")
                .originalQuery(content != null ? content : "")
                .build();

        filterContext.getRequestMetadata().put("execution_id", context.getExecutionId());
        filterContext.getRequestMetadata().put("current_step", context.getCurrentStep());
        filterContext.getRequestMetadata().put("message_role", message.getRole().name());

        if (message.getToolName() != null) {
            filterContext.getRequestMetadata().put("tool_name", message.getToolName());
        }

        return filterContext;
    }

    private String buildContextSummary(AgentContext context) {
        StringBuilder summary = new StringBuilder();
        summary.append("Step: ").append(context.getCurrentStep())
                .append("/").append(context.getMaxSteps()).append("\n");

        List<ReActMessage> messages = context.getMessages();
        if (!messages.isEmpty()) {
            ReActMessage last = messages.get(messages.size() - 1);
            summary.append("Last message role: ").append(last.getRole()).append("\n");
            if (last.getContent() != null) {
                summary.append("Content preview: ")
                        .append(last.getContent().substring(0, Math.min(100, last.getContent().length())));
            }
        }

        return summary.toString();
    }
}
