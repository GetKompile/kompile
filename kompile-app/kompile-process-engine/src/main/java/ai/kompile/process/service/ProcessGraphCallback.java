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

package ai.kompile.process.service;

import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.WorkflowRun;

/**
 * Callback interface for writing process execution results to external systems
 * (e.g., the knowledge graph). Implementations are called after step completion
 * or run completion to persist execution metadata, outputs, and provenance.
 *
 * <p>Implementations should be async/non-blocking — failures must not
 * prevent the process engine from advancing the workflow.</p>
 */
public interface ProcessGraphCallback {

    /**
     * Called after a step completes (success or failure).
     *
     * @param run           the current workflow run
     * @param stepExecution the completed step execution
     */
    void onStepCompleted(WorkflowRun run, StepExecution stepExecution);

    /**
     * Called after a workflow run reaches a terminal state (COMPLETED, FAILED, CANCELLED).
     *
     * @param run the completed workflow run
     */
    void onRunCompleted(WorkflowRun run);
}
