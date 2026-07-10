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
 * A source of deferred work that should be re-run when the resource it needs frees up.
 *
 * <p>This is the generalization of the "resume-when-available" pattern that previously existed only
 * for deferred embeddings. Any subsystem that shelves heavy work under pressure — deferred embedding
 * chunks, crawl steps archived after a wholesale failure, KGE runs skipped under the OOM floor — can
 * implement this interface as a Spring bean; a single governor-driven {@code DeferredWorkDrainer}
 * polls every registered source and drains it when its resource is available. This replaces N
 * bespoke pollers with one queue-of-sources gated on per-source resource requirements.</p>
 *
 * <p>Implementations are injected as a {@code List<DeferredWorkSource>}; registering a new source is
 * as simple as adding a {@code @Service} that implements this interface.</p>
 */
public interface DeferredWorkSource {

    /** Stable identifier for logging (e.g. {@code "embedding"}, {@code "kge-training"}). */
    String name();

    /**
     * Whether the host currently has the resource this source needs. The drainer checks this before
     * calling {@link #drainAvailable()} and skips the source for this tick when it returns
     * {@code false}, retrying on the next period.
     *
     * @return {@code true} when there is capacity to drain at least some of this source's work
     */
    boolean hasCapacity();

    /**
     * Drain the deferred work that is ready and fits current capacity. Implementations that consume a
     * shared resource per item should re-check capacity between items and stop early. Must not block
     * indefinitely — return promptly with the count actually drained so other sources get a turn.
     *
     * @return number of deferred work items drained on this call (0 when nothing was ready)
     */
    int drainAvailable();
}
