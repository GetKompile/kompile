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
 * SPI for a <em>cluster-wide</em> LLM-backend circuit breaker (distributed-crawl hardening Phase 4). It lets the
 * per-JVM {@link CrawlLlmDispatcher} consult and feed a shared view so a flaky/rate-limited backend trips ONCE
 * for the whole cluster instead of once per worker (each worker otherwise has to independently hit its local
 * threshold, multiplying the failure traffic against an already-struggling backend).
 *
 * <p>The implementation lives in {@code kompile-app-main} (the orchestrator aggregates events into an
 * authoritative breaker; workers report their local failures and cache the cluster's open-set) — crawl-graph
 * cannot depend on app-main, hence this SPI seam. It is purely <strong>advisory and fail-open</strong>: when no
 * implementation is wired (single-node, or the feature is off) the {@link #NOOP} is used and the dispatcher
 * behaves exactly as before.</p>
 */
public interface ClusterBackendHealth {

    /** The kinds of local backend outcome worth sharing cluster-wide (successes are not reported). */
    enum Event { FAILURE, RATE_LIMITED, QUOTA_EXHAUSTED }

    /** Whether {@code backendId} is currently tripped cluster-wide. Advisory; {@code false} when unknown. */
    boolean isOpen(String backendId);

    /** Report a local backend failure outcome to the cluster aggregator (kept off the happy path). */
    void record(String backendId, Event event);

    /** Inert default used when no cluster is wired — single-node behavior is unchanged. */
    ClusterBackendHealth NOOP = new ClusterBackendHealth() {
        @Override
        public boolean isOpen(String backendId) {
            return false;
        }

        @Override
        public void record(String backendId, Event event) {
            // no-op
        }
    };
}
