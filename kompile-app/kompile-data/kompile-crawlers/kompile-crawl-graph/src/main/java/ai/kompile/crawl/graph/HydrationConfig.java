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

import java.util.Set;

/**
 * Configuration for the {@link GraphHydrationOrchestrator} ENRICHMENT pass.
 *
 * <p>Enables selective stage execution: when {@code enabledStageIds} is empty every
 * stage runs; when non-empty only stages whose IDs appear in the set run.
 *
 * <p>Stage IDs mirror the design-doc labels:
 * {@code DERIVATION}, {@code PRUNE_COMPACT}, {@code HEALTH}.
 *
 * <p>Use {@link #defaults()} for a "run everything" configuration.
 */
public record HydrationConfig(
        /**
         * Stage IDs to execute. Empty set = run all stages (default behaviour).
         * Valid values: {@code DERIVATION}, {@code PRUNE_COMPACT}, {@code HEALTH}.
         */
        Set<String> enabledStageIds,

        /**
         * Confidence threshold for pruning low-confidence inferred edges.
         * Forwarded to the prune/compact orchestrator. Default: 0.4.
         */
        double confidencePruneThreshold,

        /**
         * When true no writes are performed — stages execute in dry-run mode.
         */
        boolean dryRun) {

    /**
     * All stages enabled (DERIVATION + PRUNE_COMPACT + HEALTH + ONTOLOGY_CONFORMANCE).
     * Threshold 0.4, live mode.
     *
     * <p>DERIVATION was previously disabled because the PSL FactStore was always empty
     * (graph projection crashed on {@code _KGE_EDGE_TYPE} pseudo-nodes, leaving the store
     * empty and causing an early-exit that produced 0 facts).  That crash was fixed by the
     * {@code isUserNodeType} guard in {@code MatrixKnowledgeGraphService.getNodesInFactSheet}.
     * With a non-empty graph (9 403 entities + 17 539 edges) the MAP solve now runs and
     * derives non-zero facts, so DERIVATION is re-enabled here.
     *
     * <p>The {@link GraphHydrationOrchestrator} honours
     * {@link ai.kompile.knowledgegraph.confidence.KbConfig#getDerivationTimeBudgetMs()} so
     * large graphs are time-bounded and will not stall a crawl indefinitely.</p>
     */
    public static HydrationConfig defaults() {
        return new HydrationConfig(
                Set.of("DERIVATION", "PRUNE_COMPACT", "HEALTH", "ONTOLOGY_CONFORMANCE"),
                0.4, false);
    }

    /** Returns true when the given stage should run (empty enabledStageIds = run all). */
    public boolean stageEnabled(String stageId) {
        return enabledStageIds == null || enabledStageIds.isEmpty()
                || enabledStageIds.contains(stageId);
    }
}
