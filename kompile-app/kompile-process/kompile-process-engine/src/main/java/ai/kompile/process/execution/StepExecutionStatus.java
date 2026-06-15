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
 * Execution status of a single {@link StepExecution}.
 */
public enum StepExecutionStatus {
    /** Step has not yet started (dependencies not yet satisfied). */
    PENDING,
    /** Step is actively executing. */
    RUNNING,
    /** Step has completed its automated work and is waiting for human approval. */
    AWAITING_APPROVAL,
    /** Step finished successfully. */
    COMPLETED,
    /** Step encountered an error. */
    FAILED,
    /** Step was deliberately skipped (e.g., by timeout policy or conditional logic). */
    SKIPPED
}
