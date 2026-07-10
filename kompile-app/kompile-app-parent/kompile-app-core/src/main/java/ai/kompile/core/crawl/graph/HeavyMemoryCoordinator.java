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
 * Serialization gate for heavy in-memory model operations (KGE training, embedding runs, etc.).
 *
 * <p>On a 128 GB host an OOM crash proved that KGE training triggered {@code @Async} after
 * ENRICHMENT can overlap the embedding step — there is no global serialization. This interface
 * exposes a mutual-exclusion permit so that at most one heavy-memory op runs at a time.</p>
 *
 * <p>Mirror of the {@link ResourceGovernorAdapter} pattern: a dependency-free interface lives
 * in {@code kompile-app-core}; the concrete implementation ({@code HeavyMemoryCoordinatorImpl})
 * lives in {@code kompile-app-main} and is injected as
 * {@code @Autowired(required = false)} everywhere. When absent (CPU-only builds, unit tests,
 * or contexts without app-main services) callers receive a no-op token and proceed without
 * blocking — the {@code null} adapter is always safe.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * try (AutoCloseable token = coordinator.acquire("kge-training", jobId)) {
 *     model.train(triples, config);
 * }
 * }</pre>
 *
 * <p>The {@link #acquire} call blocks until the permit is available (indefinitely, not
 * interruptibly for correctness; the caller's thread name should make visibility obvious in
 * thread dumps). When the coordinator is disabled ({@link #isEnabled()} returns {@code false})
 * {@link #acquire} returns immediately with a no-op token.</p>
 */
public interface HeavyMemoryCoordinator {

    /**
     * Acquire the heavy-memory serialization permit.
     *
     * <p>Blocks until the permit is available if another heavy op is running.
     * Returns an {@link AutoCloseable} whose {@code close()} releases the permit.
     * Always use in a try-with-resources block.</p>
     *
     * @param opLabel short human-readable label for the operation (e.g. {@code "kge-training"})
     * @param jobId   crawl job id for logging/event publishing; may be {@code null}
     * @return a token whose {@code close()} releases the permit (never {@code null})
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    AutoCloseable acquire(String opLabel, String jobId) throws InterruptedException;

    /**
     * Acquire heavy-memory admission, declaring the op's estimated peak native footprint in MB.
     *
     * <p>Under budgeted admission (the default), several heavy ops may run concurrently as long as
     * the sum of their declared footprints stays under the host's heavy-op headroom
     * ({@code MemAvailable − OOM floor}) × a safety fraction. This replaces the legacy binary mutex,
     * which forbade e.g. embedding ∥ KGE training even when tens of GB were free. When the host is
     * too constrained to fit two ops (or the governor cannot report headroom), admission degrades to
     * one op at a time — equivalent to the old single permit.</p>
     *
     * <p>The default implementation ignores the estimate and delegates to
     * {@link #acquire(String, String)} so alternate implementations stay source-compatible.</p>
     *
     * @param opLabel           short human-readable label (e.g. {@code "kge-training"})
     * @param jobId             crawl job id for logging/events; may be {@code null}
     * @param estimatedNativeMb declared peak native/off-heap footprint in MB; {@code <= 0} lets the
     *                          coordinator derive an estimate from {@code opLabel} / configuration
     * @return a token whose {@code close()} releases the admission (never {@code null})
     * @throws InterruptedException if interrupted while waiting for headroom
     */
    default AutoCloseable acquire(String opLabel, String jobId, long estimatedNativeMb)
            throws InterruptedException {
        return acquire(opLabel, jobId);
    }

    /**
     * Whether the serialization gate is active.
     *
     * @return {@code true} when the gate is enabled and calls to {@link #acquire} may block;
     *         {@code false} when the gate is disabled and {@link #acquire} is a no-op
     */
    boolean isEnabled();
}
