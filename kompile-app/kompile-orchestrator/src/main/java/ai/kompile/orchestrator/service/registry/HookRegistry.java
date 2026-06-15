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
package ai.kompile.orchestrator.service.registry;

import ai.kompile.orchestrator.api.OrchestratorHook;
import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.model.workflow.Workflow;
import ai.kompile.orchestrator.model.workflow.WorkflowStep;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for orchestrator hooks.
 * Manages hook registration and execution.
 */
@Slf4j
public class HookRegistry {

    private final Map<String, OrchestratorHook> hooks = new ConcurrentHashMap<>();
    private volatile List<OrchestratorHook> sortedHooks = new ArrayList<>();

    /**
     * Register a hook.
     */
    public void register(OrchestratorHook hook) {
        if (hook == null || hook.getId() == null) {
            throw new IllegalArgumentException("Hook and ID must not be null");
        }
        hooks.put(hook.getId(), hook);
        rebuildSortedList();
        log.info("Registered orchestrator hook: {}", hook.getId());
    }

    /**
     * Unregister a hook.
     */
    public void unregister(String hookId) {
        hooks.remove(hookId);
        rebuildSortedList();
        log.info("Unregistered orchestrator hook: {}", hookId);
    }

    /**
     * Get a hook by ID.
     */
    public Optional<OrchestratorHook> get(String hookId) {
        return Optional.ofNullable(hooks.get(hookId));
    }

    /**
     * Get all hooks (sorted by priority descending).
     */
    public List<OrchestratorHook> getAll() {
        return Collections.unmodifiableList(sortedHooks);
    }

    private void rebuildSortedList() {
        List<OrchestratorHook> list = new ArrayList<>(hooks.values());
        list.sort(Comparator.comparingInt(OrchestratorHook::getPriority).reversed());
        this.sortedHooks = list;
    }

    // ==================== Lifecycle Hook Execution ====================

    public void executePreStart(OrchestratorInstance instance) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.preStart(instance);
            } catch (Exception e) {
                log.error("Error in preStart hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    public void executePostStart(OrchestratorInstance instance) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.postStart(instance);
            } catch (Exception e) {
                log.error("Error in postStart hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    public void executePreStop(OrchestratorInstance instance) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.preStop(instance);
            } catch (Exception e) {
                log.error("Error in preStop hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    public void executePostStop(OrchestratorInstance instance) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.postStop(instance);
            } catch (Exception e) {
                log.error("Error in postStop hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    public void executePreRecovery(OrchestratorInstance instance) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.preRecovery(instance);
            } catch (Exception e) {
                log.error("Error in preRecovery hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    public void executePostRecovery(OrchestratorInstance instance) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.postRecovery(instance);
            } catch (Exception e) {
                log.error("Error in postRecovery hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    // ==================== State Hook Execution ====================

    public boolean executePreStateEnter(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                if (!hook.preStateEnter(instance, state, context)) {
                    log.info("preStateEnter hook {} aborted transition", hook.getId());
                    return false;
                }
            } catch (Exception e) {
                log.error("Error in preStateEnter hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
        return true;
    }

    public void executePostStateEnter(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.postStateEnter(instance, state, context);
            } catch (Exception e) {
                log.error("Error in postStateEnter hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    public boolean executePreStateExit(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                if (!hook.preStateExit(instance, state, context)) {
                    log.info("preStateExit hook {} aborted transition", hook.getId());
                    return false;
                }
            } catch (Exception e) {
                log.error("Error in preStateExit hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
        return true;
    }

    public void executePostStateExit(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.postStateExit(instance, state, context);
            } catch (Exception e) {
                log.error("Error in postStateExit hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    public boolean executePreStateTransition(OrchestratorInstance instance, StateDefinition fromState,
                                             StateDefinition toState, Map<String, Object> context) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                if (!hook.preStateTransition(instance, fromState, toState, context)) {
                    log.info("preStateTransition hook {} aborted transition", hook.getId());
                    return false;
                }
            } catch (Exception e) {
                log.error("Error in preStateTransition hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
        return true;
    }

    public void executePostStateTransition(OrchestratorInstance instance, StateDefinition fromState,
                                           StateDefinition toState, Map<String, Object> context) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.postStateTransition(instance, fromState, toState, context);
            } catch (Exception e) {
                log.error("Error in postStateTransition hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    // ==================== Task Hook Execution ====================

    public boolean executePreTaskStart(OrchestratorInstance instance, TaskInstance task) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                if (!hook.preTaskStart(instance, task)) {
                    log.info("preTaskStart hook {} aborted task", hook.getId());
                    return false;
                }
            } catch (Exception e) {
                log.error("Error in preTaskStart hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
        return true;
    }

    public void executePostTaskComplete(OrchestratorInstance instance, TaskInstance task) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.postTaskComplete(instance, task);
            } catch (Exception e) {
                log.error("Error in postTaskComplete hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    public void executeOnTaskError(OrchestratorInstance instance, TaskInstance task) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.onTaskError(instance, task);
            } catch (Exception e) {
                log.error("Error in onTaskError hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    // ==================== LLM Hook Execution ====================

    public String executePreLlmInvoke(OrchestratorInstance instance, String prompt) {
        String currentPrompt = prompt;
        for (OrchestratorHook hook : sortedHooks) {
            try {
                currentPrompt = hook.preLlmInvoke(instance, currentPrompt);
            } catch (Exception e) {
                log.error("Error in preLlmInvoke hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
        return currentPrompt;
    }

    public void executePostLlmComplete(OrchestratorInstance instance, LlmSession session) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.postLlmComplete(instance, session);
            } catch (Exception e) {
                log.error("Error in postLlmComplete hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    public void executeOnLlmError(OrchestratorInstance instance, LlmSession session) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.onLlmError(instance, session);
            } catch (Exception e) {
                log.error("Error in onLlmError hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    // ==================== Workflow Hook Execution ====================

    public boolean executePreWorkflowStart(OrchestratorInstance instance, Workflow workflow) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                if (!hook.preWorkflowStart(instance, workflow)) {
                    log.info("preWorkflowStart hook {} aborted workflow", hook.getId());
                    return false;
                }
            } catch (Exception e) {
                log.error("Error in preWorkflowStart hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
        return true;
    }

    public void executePostWorkflowComplete(OrchestratorInstance instance, Workflow workflow) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.postWorkflowComplete(instance, workflow);
            } catch (Exception e) {
                log.error("Error in postWorkflowComplete hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    public boolean executePreWorkflowStep(OrchestratorInstance instance, WorkflowStep step) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                if (!hook.preWorkflowStep(instance, step)) {
                    log.info("preWorkflowStep hook {} skipped step", hook.getId());
                    return false;
                }
            } catch (Exception e) {
                log.error("Error in preWorkflowStep hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
        return true;
    }

    public void executePostWorkflowStep(OrchestratorInstance instance, WorkflowStep step) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.postWorkflowStep(instance, step);
            } catch (Exception e) {
                log.error("Error in postWorkflowStep hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    // ==================== Error Hook Execution ====================

    public boolean executeOnError(OrchestratorInstance instance, Throwable error, Map<String, Object> context) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                if (hook.onError(instance, error, context)) {
                    log.debug("Error handled by hook {}", hook.getId());
                    return true;
                }
            } catch (Exception e) {
                log.error("Error in onError hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
        return false;
    }

    public void executeOnErrorThreshold(OrchestratorInstance instance, int errorCount, Map<String, Object> context) {
        for (OrchestratorHook hook : sortedHooks) {
            try {
                hook.onErrorThreshold(instance, errorCount, context);
            } catch (Exception e) {
                log.error("Error in onErrorThreshold hook {}: {}", hook.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Clear all hooks.
     */
    public void clear() {
        hooks.clear();
        sortedHooks = new ArrayList<>();
    }
}
