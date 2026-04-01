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
package ai.kompile.orchestrator.model.audit;

/**
 * Types of entities that can be audited.
 */
public enum AuditEntityType {
    /**
     * Orchestrator instance.
     */
    ORCHESTRATOR,

    /**
     * State definition or state machine.
     */
    STATE,

    /**
     * Task definition or instance.
     */
    TASK,

    /**
     * LLM session.
     */
    LLM_SESSION,

    /**
     * Workflow.
     */
    WORKFLOW,

    /**
     * Workflow step.
     */
    WORKFLOW_STEP,

    /**
     * LLM trigger.
     */
    TRIGGER,

    /**
     * Orchestrator hook.
     */
    HOOK,

    /**
     * Configuration setting.
     */
    CONFIGURATION,

    /**
     * Snapshot.
     */
    SNAPSHOT,

    /**
     * State handler.
     */
    STATE_HANDLER,

    /**
     * Task executor.
     */
    TASK_EXECUTOR,

    /**
     * LLM provider.
     */
    LLM_PROVIDER
}
