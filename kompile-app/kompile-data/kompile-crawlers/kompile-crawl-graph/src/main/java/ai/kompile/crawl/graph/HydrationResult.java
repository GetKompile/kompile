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

package ai.kompile.crawl.graph;

/**
 * Outcome of a {@link GraphHydrationOrchestrator#run} invocation.
 *
 * <p>Counters follow the design-doc sub-stage labels; zero means the stage was skipped
 * (either because {@link HydrationConfig#stageEnabled} returned false or because the
 * requisite Spring bean was absent).</p>
 *
 * @param relationsDerived    inferred-fact versions written during MAP derivation (S4/S9/S10)
 * @param retractedAtomCount  atom keys retracted during the TMS pass
 * @param factsMaterialized   INFERRED graph edges created during materialization
 * @param factsRetractedPruned INFERRED edges removed during P1 (retracted-atom pruning)
 * @param factsConfidencePruned INFERRED edges removed during P3 (confidence pruning)
 * @param mergesPerformed     entity merges in P2 compaction
 * @param orphansRemoved      orphan nodes removed in P4
 * @param componentNodesRemoved disconnected-component nodes removed in P5
 * @param stagesRun           how many of the three top-level stages actually ran
 * @param runId               the PSL re-ground run ID; null when derivation was skipped
 */
public record HydrationResult(
        int relationsDerived,
        int retractedAtomCount,
        int factsMaterialized,
        int factsRetractedPruned,
        int factsConfidencePruned,
        int mergesPerformed,
        int orphansRemoved,
        int componentNodesRemoved,
        int stagesRun,
        String runId) {

    /** Zero-result for when hydration is fully skipped. */
    public static HydrationResult empty() {
        return new HydrationResult(0, 0, 0, 0, 0, 0, 0, 0, 0, null);
    }

    /** Total facts changed (derived + materialized). */
    public int totalFactsChanged() {
        return relationsDerived + factsMaterialized;
    }

    /** Total facts pruned (retracted + confidence). */
    public int totalFactsPruned() {
        return factsRetractedPruned + factsConfidencePruned;
    }
}
