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
package ai.kompile.orchestrator.model.output;

/**
 * Severity levels for classified output patterns.
 */
public enum ClassificationSeverity {
    /**
     * Critical error requiring immediate attention.
     */
    CRITICAL(1),

    /**
     * Error that prevents successful completion.
     */
    ERROR(2),

    /**
     * Warning that may indicate a problem.
     */
    WARNING(3),

    /**
     * Informational message.
     */
    INFO(4);

    private final int priority;

    ClassificationSeverity(int priority) {
        this.priority = priority;
    }

    /**
     * Get the priority level (lower is more severe).
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Check if this severity is more severe than another.
     */
    public boolean isMoreSevereThan(ClassificationSeverity other) {
        return this.priority < other.priority;
    }
}
