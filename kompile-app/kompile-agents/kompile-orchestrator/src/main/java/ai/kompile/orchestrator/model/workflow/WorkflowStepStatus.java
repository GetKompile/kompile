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
package ai.kompile.orchestrator.model.workflow;

/**
 * Status of a workflow step.
 */
public enum WorkflowStepStatus {
    /**
     * Step is pending execution.
     */
    PENDING,

    /**
     * Step is waiting for user approval.
     */
    WAITING_APPROVAL,

    /**
     * Step is currently running.
     */
    RUNNING,

    /**
     * Step completed successfully.
     */
    COMPLETED,

    /**
     * Step failed.
     */
    FAILED,

    /**
     * Step was skipped.
     */
    SKIPPED,

    /**
     * Step was rejected by user.
     */
    REJECTED;

    /**
     * Check if step is waiting for something.
     */
    public boolean isWaiting() {
        return this == PENDING || this == WAITING_APPROVAL;
    }

    /**
     * Check if step has finished.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == SKIPPED || this == REJECTED;
    }
}
