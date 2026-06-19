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
package ai.kompile.kclaw.task;

/**
 * Which engine performs an {@link AgentTask}'s work.
 */
public enum TaskEngine {

    /** Run via kclaw's native ReAct agents (an {@code AgentDefinition} + toolkit, in-process). */
    REACT,

    /** Run via the kompile CLI ({@code kompile exec --json}) as a subprocess. */
    KOMPILE_CLI;

    /**
     * Lenient parse — defaults to {@link #REACT} for null/blank/unknown input.
     * Accepts "react", "kompile", "kompile-cli", "cli", "exec" (case/dash insensitive).
     */
    public static TaskEngine fromString(String s) {
        if (s == null || s.isBlank()) {
            return REACT;
        }
        String n = s.trim().toLowerCase().replace('-', '_');
        return switch (n) {
            case "kompile", "kompile_cli", "cli", "exec" -> KOMPILE_CLI;
            default -> REACT;
        };
    }
}
