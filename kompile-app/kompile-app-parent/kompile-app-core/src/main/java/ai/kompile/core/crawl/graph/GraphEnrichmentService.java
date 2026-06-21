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
package ai.kompile.core.crawl.graph;

/**
 * SPI for graph post-processing / enrichment after a fact sheet changes.
 *
 * <p>Implemented by {@code GraphHydrationOrchestrator} in {@code kompile-crawl-graph},
 * which sequences DERIVATION → PRUNE_COMPACT → HEALTH on the given fact sheet.
 *
 * <p>Injected optionally into {@code GroundingCascadeHook} (in
 * {@code kompile-graph-change-tracking}) so that the CASCADE incremental path
 * reuses the same orchestrated pipeline as the BATCH crawl ENRICHMENT step,
 * without creating a compile-time dependency between those two modules.
 *
 * <p>Both callers pass a no-op progress callback since the cascade runs
 * asynchronously after the SSE has already closed.
 */
public interface GraphEnrichmentService {

    /**
     * Run the full enrichment pipeline (derivation, pruning, health) for
     * the given fact sheet.
     *
     * <p>Implementations must be safe to call from a background thread and
     * must not throw; errors should be logged internally.
     *
     * @param factSheetId the fact sheet to enrich
     */
    void enrich(long factSheetId);
}
