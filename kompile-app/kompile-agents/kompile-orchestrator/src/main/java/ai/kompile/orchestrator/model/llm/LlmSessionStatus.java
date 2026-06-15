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
package ai.kompile.orchestrator.model.llm;

/**
 * Status of an LLM session.
 */
public enum LlmSessionStatus {
    /**
     * Session is starting.
     */
    STARTING,

    /**
     * Session is running.
     */
    RUNNING,

    /**
     * Session completed successfully.
     */
    COMPLETED,

    /**
     * Session failed.
     */
    FAILED,

    /**
     * Session timed out.
     */
    TIMEOUT,

    /**
     * Session was cancelled.
     */
    CANCELLED,

    /**
     * Session requires user action (e.g., terms of service update).
     */
    ACTION_REQUIRED,

    /**
     * API rate limit reached.
     */
    RATE_LIMITED;

    /**
     * Check if session is still active.
     */
    public boolean isActive() {
        return this == STARTING || this == RUNNING;
    }

    /**
     * Check if session has finished.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == TIMEOUT ||
               this == CANCELLED || this == ACTION_REQUIRED || this == RATE_LIMITED;
    }

    /**
     * Check if session was successful.
     */
    public boolean isSuccess() {
        return this == COMPLETED;
    }
}
