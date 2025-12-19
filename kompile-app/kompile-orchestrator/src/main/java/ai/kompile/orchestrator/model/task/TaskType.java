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
package ai.kompile.orchestrator.model.task;

/**
 * Types of tasks that can be executed by the orchestrator.
 */
public enum TaskType {
    /**
     * Shell command execution (bash, cmd, etc.).
     */
    SHELL,

    /**
     * HTTP request (GET, POST, etc.).
     */
    HTTP,

    /**
     * Java code execution via reflection or scripting.
     */
    CODE,

    /**
     * Direct LLM query.
     */
    LLM_QUERY,

    /**
     * Custom task type with user-provided executor.
     */
    CUSTOM
}
