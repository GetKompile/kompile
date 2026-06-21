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
package ai.kompile.knowledgegraph.grounding;

import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.fol.grounding.ConcurrentFactStore;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.tms.JustificationIndex;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Per-fact-sheet grounding state: holds the materialized inferred fact store, the MVCC
 * observed-fact store, the justification index, and the per-fact-sheet read/write lock.
 *
 * <p>This is a plain value object — no Spring annotations. It is created lazily by
 * {@link KbGroundingService#getState(Long)} and stored in a {@code ConcurrentHashMap}.</p>
 *
 * <p>Lock discipline:
 * <ul>
 *   <li>{@code verify}, {@code query}, {@code explain} — acquire {@code lock().readLock()}
 *       (parallel reads allowed).</li>
 *   <li>{@code assertFact} — acquires {@code lock().writeLock()} for the
 *       {@link ConcurrentFactStore#assertFact} + contradiction-check window; releases
 *       the lock before publishing any Spring event.</li>
 * </ul>
 * </p>
 *
 * @param factSheetId        the fact sheet this state belongs to
 * @param inferredFactStore  the materialized MAP results (read by verify/query/explain)
 * @param concurrentFactStore MVCC wrapper for agent-asserted observed facts
 * @param factStore          the observed fact store backing {@code DefaultKbVerifier}
 * @param justificationIndex derivation lineage for explain
 * @param lock               per-fact-sheet read/write lock
 */
public record FactSheetKbState(
        Long factSheetId,
        InferredFactStore inferredFactStore,
        ConcurrentFactStore concurrentFactStore,
        FactStore factStore,
        JustificationIndex justificationIndex,
        ReadWriteLock lock
) {

    public FactSheetKbState {
        Objects.requireNonNull(factSheetId, "factSheetId must not be null");
        Objects.requireNonNull(inferredFactStore, "inferredFactStore must not be null");
        Objects.requireNonNull(concurrentFactStore, "concurrentFactStore must not be null");
        Objects.requireNonNull(factStore, "factStore must not be null");
        Objects.requireNonNull(justificationIndex, "justificationIndex must not be null");
        Objects.requireNonNull(lock, "lock must not be null");
    }

    /**
     * Convenience factory that creates a fresh state with empty stores and a new lock.
     *
     * @param factSheetId        the fact sheet id
     * @param inferredFactStore  the inferred fact store (caller provides the implementation)
     * @return a new {@link FactSheetKbState}
     */
    /**
     * Create an empty {@link JustificationIndex} by running {@code build} against an empty
     * inference result and an empty fact store. This is the only way to create an empty
     * index since the constructor is private in the lib.
     *
     * @return an empty (no-entry) justification index
     */
    static JustificationIndex emptyJustificationIndex() {
        HlMrfMapInference.Result empty = new HlMrfMapInference.Result(
                Map.of(), List.of(), 0, 0.0, true);
        return JustificationIndex.build(empty, new FactStore());
    }

    /**
     * Convenience factory that creates a fresh state with empty stores and a new lock.
     *
     * @param factSheetId        the fact sheet id
     * @param inferredFactStore  the inferred fact store (caller provides the implementation)
     * @return a new {@link FactSheetKbState}
     */
    public static FactSheetKbState fresh(Long factSheetId,
                                          InferredFactStore inferredFactStore) {
        return new FactSheetKbState(
                factSheetId,
                inferredFactStore,
                new ConcurrentFactStore(),
                new FactStore(),
                emptyJustificationIndex(),
                new ReentrantReadWriteLock()
        );
    }
}
