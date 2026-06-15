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
package ai.kompile.orchestrator.model.state;

/**
 * Categories of states in the orchestrator state machine.
 */
public enum StateCategory {
    /**
     * Initial states where orchestration begins.
     */
    INITIAL,

    /**
     * States where active processing is happening.
     */
    PROCESSING,

    /**
     * States where the orchestrator is waiting for external input,
     * task completion, or LLM response.
     */
    WAITING,

    /**
     * Terminal success states.
     */
    TERMINAL,

    /**
     * Error or failure states.
     */
    ERROR;

    /**
     * Check if this category represents an active state.
     */
    public boolean isActive() {
        return this == PROCESSING || this == WAITING;
    }

    /**
     * Check if this category represents a final state.
     */
    public boolean isFinal() {
        return this == TERMINAL || this == ERROR;
    }
}
