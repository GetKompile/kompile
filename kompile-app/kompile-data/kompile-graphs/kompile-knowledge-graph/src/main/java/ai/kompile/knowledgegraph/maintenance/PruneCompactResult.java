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
package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.core.graphrag.maintenance.model.GraphHealthSnapshot;

/**
 * Aggregated result of one {@link PruneCompactOrchestrator} run (all P1–P6 stages).
 *
 * <p>Stage labels:
 * <ul>
 *   <li>P1 — retracted-atom INFERRED edge removal</li>
 *   <li>P2 — entity compaction + RESOLVES_TO rematerialization</li>
 *   <li>P3 — low-confidence INFERRED edge removal</li>
 *   <li>P4 — orphan GC</li>
 *   <li>P5 — component sweep</li>
 *   <li>P6 — prior-based (Subjective-Logic Opinion) edge prune</li>
 * </ul>
 */
public record PruneCompactResult(
        /** Edges removed in P1 (retracted-atom prune). */
        int edgesRemovedP1,
        /** Edges removed in P3 (low-confidence prune). */
        int edgesRemovedP3,
        /** Entity merges performed in P2 (compaction). */
        int mergesPerformedP2,
        /** Nodes removed in P4 (orphan GC). */
        int orphansRemovedP4,
        /** Nodes removed in P5 (component sweep). */
        int componentNodesRemovedP5,
        /** Edges removed in P6 (Opinion / prior-based prune). */
        int edgesRemovedP6,
        /** Graph health snapshot computed after all stages completed (may be null on failure). */
        GraphHealthSnapshot postHealth,
        /** Whether this was a dry run (no writes performed). */
        boolean dryRun
) {

    /**
     * Backwards-compatible compact constructor: P6 defaults to zero.
     * Existing callers that pass 7 args continue to work; new callers pass 8.
     */
    public static PruneCompactResult of(int p1, int p3, int p2, int p4, int p5,
                                        GraphHealthSnapshot postHealth, boolean dryRun) {
        return new PruneCompactResult(p1, p3, p2, p4, p5, 0, postHealth, dryRun);
    }

    /** Total edges removed across P1, P3, and P6. */
    public int totalEdgesRemoved() {
        return edgesRemovedP1 + edgesRemovedP3 + edgesRemovedP6;
    }
}
