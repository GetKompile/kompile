/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.execution;

/**
 * Overall status of a {@link WorkflowRun}.
 */
public enum RunStatus {
    /** One or more steps are actively executing. */
    RUNNING,
    /** Execution is paused, waiting for a human approval response. */
    PAUSED_FOR_APPROVAL,
    /** Execution is paused, waiting for a human to complete a manual step. */
    PAUSED_FOR_HUMAN,
    /** All steps have completed successfully. */
    COMPLETED,
    /** One or more steps encountered an unrecoverable error. */
    FAILED,
    /** The run was deliberately stopped before completion. */
    CANCELLED
}
