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

package ai.kompile.core.graphrag.partition;

/** Where a partition is in its lifecycle. Phases advance; they never run backwards. */
public enum PartitionPhase {

    /** Created, nothing discovered yet. */
    NEW,

    /** A discovery round is running channels against the current frontier. */
    DISCOVERING,

    /** Mini-batches are being processed and committed. */
    PROCESSING,

    /** Newly processed members are being used to expand the frontier. */
    EXPANDING,

    /** Discovery is exhausted; contradictions and bridge entities are being settled. */
    RECONCILING,

    /**
     * No outstanding members and no new candidates under this policy version. Closed is always
     * relative to a policy: a new policy re-opens the question rather than contradicting it.
     */
    CLOSED;

    public boolean isTerminal() {
        return this == CLOSED;
    }
}
