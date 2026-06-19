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

import java.util.Optional;

/**
 * Picks an alternate backend for a stage when the current one is rate-limited or quota-exhausted.
 *
 * <p>Decouples {@link BatchRetryPolicy} from any concrete router: callers supply a selector backed by
 * the live capacity/quota state (e.g. {@link ProcessingCapacityTracker}). Stages with no backend chain
 * (local-model embedding/vector indexing) pass {@code null}, in which case rate-limited batches simply
 * retry the same path with a heavier backoff.</p>
 */
@FunctionalInterface
public interface FallbackBackendSelector {

    /**
     * Select a healthy fallback backend for the stage, excluding the one that just failed.
     *
     * @param stage            the pipeline stage (e.g. {@code "graph_extraction"})
     * @param excludeBackendId the backend to avoid (may be {@code null} if unknown)
     * @return the fallback backend id, or empty if none is available
     */
    Optional<String> selectFallback(String stage, String excludeBackendId);
}
