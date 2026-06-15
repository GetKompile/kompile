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
package ai.kompile.orchestrator.service;

import ai.kompile.orchestrator.api.LlmIntegrationService;
import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.llm.*;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.repository.LlmTriggerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages automatic LLM triggers based on state changes, errors, and patterns.
 */
@Slf4j
@RequiredArgsConstructor
public class TriggerManager {

    private final LlmTriggerRepository triggerRepository;
    private final LlmIntegrationService llmService;

    // Cache of triggers by type
    private final Map<LlmTriggerType, List<LlmTrigger>> triggerCache = new ConcurrentHashMap<>();

    // Track error counts for threshold-based triggers
    private final Map<String, Map<String, Integer>> errorCounts = new ConcurrentHashMap<>();

    /**
     * Check and fire triggers for state entry.
     */
    public void checkStateEnterTriggers(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {
        List<LlmTrigger> triggers = getEnabledTriggers(LlmTriggerType.ON_STATE_ENTER);

        for (LlmTrigger trigger : triggers) {
            if (trigger.appliesToState(state.getStateId())) {
                fireTrigger(trigger, instance, context);
            }
        }

        // Also check state-specific LLM configuration
        if (state.hasLlmTriggerOnEnter()) {
            LlmTriggerConfig config = state.getLlmTriggerConfig();
            fireStateTrigger(config, instance, state, context);
        }
    }

    /**
     * Check and fire triggers for state exit.
     */
    public void checkStateExitTriggers(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {
        List<LlmTrigger> triggers = getEnabledTriggers(LlmTriggerType.ON_STATE_EXIT);

        for (LlmTrigger trigger : triggers) {
            if (trigger.appliesToState(state.getStateId())) {
                fireTrigger(trigger, instance, context);
            }
        }

        // Also check state-specific LLM configuration
        if (state.hasLlmTriggerOnExit()) {
            LlmTriggerConfig config = state.getLlmTriggerConfig();
            fireStateTrigger(config, instance, state, context);
        }
    }

    /**
     * Check and fire triggers for task errors.
     */
    public void checkTaskErrorTriggers(OrchestratorInstance instance, TaskInstance task, String errorOutput) {
        List<LlmTrigger> triggers = getEnabledTriggers(LlmTriggerType.ON_TASK_ERROR);

        Map<String, Object> context = new HashMap<>();
        context.put("taskId", task.getId());
        context.put("taskName", task.getName());
        context.put("taskType", task.getTaskType().name());
        context.put("exitCode", task.getExitCode());
        context.put("errorMessage", task.getErrorMessage());
        context.put("output", errorOutput);

        for (LlmTrigger trigger : triggers) {
            if (trigger.appliesToTask(task.getTaskDefinitionId())) {
                // Check error count threshold
                int count = incrementErrorCount(instance.getInstanceId(), trigger.getTriggerId());
                if (count >= trigger.getErrorCountThreshold()) {
                    fireTrigger(trigger, instance, context);
                    resetErrorCount(instance.getInstanceId(), trigger.getTriggerId());
                }
            }
        }
    }

    /**
     * Check and fire triggers for pattern matches in output.
     */
    public void checkPatternMatchTriggers(OrchestratorInstance instance, String output) {
        if (output == null || output.isEmpty()) {
            return;
        }

        List<LlmTrigger> triggers = getEnabledTriggers(LlmTriggerType.ON_PATTERN_MATCH);

        Map<String, Object> context = new HashMap<>();
        context.put("output", output);

        for (LlmTrigger trigger : triggers) {
            if (trigger.matchesPattern(output)) {
                log.debug("Pattern match trigger {} fired on output", trigger.getTriggerId());
                fireTrigger(trigger, instance, context);
            }
        }
    }

    /**
     * Check and fire triggers for repeated errors.
     */
    public void checkRepeatedErrorTriggers(OrchestratorInstance instance, int errorCount) {
        List<LlmTrigger> triggers = getEnabledTriggers(LlmTriggerType.ON_ERROR_REPEATED);

        Map<String, Object> context = new HashMap<>();
        context.put("errorCount", errorCount);

        for (LlmTrigger trigger : triggers) {
            if (errorCount >= trigger.getErrorCountThreshold()) {
                fireTrigger(trigger, instance, context);
            }
        }
    }

    /**
     * Check and fire triggers for manual invocation.
     */
    public void checkManualTriggers(OrchestratorInstance instance, String triggerId, Map<String, Object> context) {
        List<LlmTrigger> triggers = getEnabledTriggers(LlmTriggerType.MANUAL);

        for (LlmTrigger trigger : triggers) {
            if (trigger.getTriggerId().equals(triggerId)) {
                fireTrigger(trigger, instance, context);
                break;
            }
        }
    }

    /**
     * Fire a specific trigger.
     */
    public void fireTrigger(LlmTrigger trigger, OrchestratorInstance instance, Map<String, Object> context) {
        if (!trigger.isEnabled()) {
            log.debug("Trigger {} is disabled, skipping", trigger.getTriggerId());
            return;
        }

        log.info("Firing LLM trigger {} for orchestrator {}", trigger.getTriggerId(), instance.getInstanceId());

        // Resolve prompt with context
        String prompt = trigger.resolvePrompt(context);

        // Build session request
        LlmSessionRequest.LlmSessionRequestBuilder requestBuilder = LlmSessionRequest.builder()
                .prompt(prompt)
                .orchestratorInstanceId(instance.getInstanceId())
                .triggerId(trigger.getTriggerId())
                .workingDirectory(instance.getWorkingDirectory());

        if (trigger.getSystemPrompt() != null) {
            requestBuilder.systemPrompt(trigger.getSystemPrompt());
        }
        if (trigger.getMaxTokens() != null) {
            requestBuilder.maxTokens(trigger.getMaxTokens());
        }
        if (trigger.getTemperature() != null) {
            requestBuilder.temperature(trigger.getTemperature());
        }

        try {
            LlmSession session;
            if (trigger.getLlmProviderId() != null) {
                session = llmService.startSession(trigger.getLlmProviderId(), requestBuilder.build());
            } else {
                session = llmService.startSession(requestBuilder.build());
            }

            log.info("Started LLM session {} for trigger {}", session.getId(), trigger.getTriggerId());
        } catch (Exception e) {
            log.error("Failed to fire trigger {}: {}", trigger.getTriggerId(), e.getMessage(), e);
        }
    }

    /**
     * Fire a trigger from state-specific configuration.
     */
    private void fireStateTrigger(LlmTriggerConfig config, OrchestratorInstance instance,
                                  StateDefinition state, Map<String, Object> context) {
        if (config == null || config.getPromptTemplate() == null) {
            return;
        }

        // Add state info to context
        context.put("stateId", state.getStateId());
        context.put("stateName", state.getName());
        context.put("stateCategory", state.getCategory().name());

        // Create ad-hoc trigger
        LlmTrigger trigger = LlmTrigger.builder()
                .triggerId("state-" + state.getStateId())
                .name("State Trigger: " + state.getName())
                .promptTemplate(config.getPromptTemplate())
                .systemPrompt(config.getSystemPrompt())
                .llmProviderId(config.getLlmProviderId())
                .autoExecuteProposal(config.isAutoExecuteProposal())
                .enabled(true)
                .build();

        fireTrigger(trigger, instance, context);
    }

    /**
     * Get enabled triggers by type (cached).
     */
    private List<LlmTrigger> getEnabledTriggers(LlmTriggerType type) {
        return triggerCache.computeIfAbsent(type, t ->
                triggerRepository.findEnabledByTriggerType(t));
    }

    /**
     * Invalidate the trigger cache.
     */
    public void invalidateCache() {
        triggerCache.clear();
    }

    /**
     * Reload a specific trigger type.
     */
    public void reloadTriggers(LlmTriggerType type) {
        triggerCache.put(type, triggerRepository.findEnabledByTriggerType(type));
    }

    /**
     * Increment error count for an orchestrator/trigger combination.
     */
    private int incrementErrorCount(String instanceId, String triggerId) {
        Map<String, Integer> counts = errorCounts.computeIfAbsent(instanceId, k -> new ConcurrentHashMap<>());
        return counts.compute(triggerId, (k, v) -> (v == null) ? 1 : v + 1);
    }

    /**
     * Reset error count for an orchestrator/trigger combination.
     */
    private void resetErrorCount(String instanceId, String triggerId) {
        Map<String, Integer> counts = errorCounts.get(instanceId);
        if (counts != null) {
            counts.remove(triggerId);
        }
    }

    /**
     * Clear all error counts for an orchestrator.
     */
    public void clearErrorCounts(String instanceId) {
        errorCounts.remove(instanceId);
    }
}
