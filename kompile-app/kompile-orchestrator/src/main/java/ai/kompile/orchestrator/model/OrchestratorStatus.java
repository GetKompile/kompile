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
package ai.kompile.orchestrator.model;

/**
 * Status of an orchestrator instance.
 */
public enum OrchestratorStatus {
    /**
     * Orchestrator has been created but not yet started.
     */
    CREATED,

    /**
     * Orchestrator is initializing (loading states, handlers, etc.).
     */
    INITIALIZING,

    /**
     * Orchestrator is actively running.
     */
    RUNNING,

    /**
     * Orchestrator is paused and can be resumed.
     */
    PAUSED,

    /**
     * Orchestrator completed successfully.
     */
    COMPLETED,

    /**
     * Orchestrator failed with an error.
     */
    FAILED,

    /**
     * Orchestrator was cancelled by user.
     */
    CANCELLED,

    /**
     * Orchestrator is recovering from a crash.
     */
    RECOVERING;

    /**
     * Check if this status represents an active orchestrator.
     */
    public boolean isActive() {
        return this == RUNNING || this == PAUSED || this == INITIALIZING || this == RECOVERING;
    }

    /**
     * Check if this status represents a terminal state.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * Check if transition to the given status is valid.
     */
    public boolean canTransitionTo(OrchestratorStatus target) {
        return switch (this) {
            case CREATED -> target == INITIALIZING || target == CANCELLED;
            case INITIALIZING -> target == RUNNING || target == FAILED || target == CANCELLED;
            case RUNNING -> target == PAUSED || target == COMPLETED || target == FAILED || target == CANCELLED;
            case PAUSED -> target == RUNNING || target == CANCELLED;
            case RECOVERING -> target == RUNNING || target == FAILED;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }
}
