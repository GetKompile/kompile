/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.reasoning;

import java.util.Set;

/**
 * Result of {@link IncrementalReasoningOrchestrator#runFullReground}.
 *
 * <p>Carries the number of {@code InferredFact} versions written, the hydration run ID
 * (identifies which materialized INFERRED edges belong to this run), and the set of
 * atom keys retracted by {@code BeliefReviser} during this run (currently always empty —
 * BeliefReviser wiring is a future iteration).
 */
public record RegroundResult(int versionsWritten, String runId, Set<String> retractedAtomKeys) {

    /** Empty result: no versions written, no run, no retractions. */
    public static RegroundResult empty() {
        return new RegroundResult(0, null, Set.of());
    }
}
