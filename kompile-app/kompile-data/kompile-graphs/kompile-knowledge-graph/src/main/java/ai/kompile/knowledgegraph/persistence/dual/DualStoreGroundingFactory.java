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
package ai.kompile.knowledgegraph.persistence.dual;

import ai.kompile.knowledgegraph.reasoning.InferredFactGraphMaterializer;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Spring-managed factory that creates per-fact-sheet instances of the dual-store
 * grounding persistence classes.
 *
 * <p>Callers obtain a scoped store by calling {@link #weightStoreFor(Long)} or
 * {@link #factStoreFor(Long)}; each call constructs a fresh instance bound to the
 * supplied {@code factSheetId}. The factory itself is stateless and thread-safe.</p>
 *
 * <p>The {@link InferredFactGraphMaterializer} is optional: when absent (e.g. in
 * plain-Java test contexts where JPA is unavailable), the fact store simply skips
 * the async graph-edge write without error. Callers can also explicitly suppress the
 * materializer by using {@link #factStoreFor(Long, boolean)}.</p>
 */
@Component
public class DualStoreGroundingFactory {

    private final PslWeightRowRepository weightRepo;
    private final InferredFactRowRepository factRepo;

    @Nullable
    private final InferredFactGraphMaterializer materializer;

    /**
     * Primary constructor: Spring injects all three collaborators.
     * {@code InferredFactGraphMaterializer} is optional — set to null in contexts where
     * the full graph service stack is not wired (e.g. lightweight integration tests).
     *
     * @param weightRepo   JPA repository for PSL weight rows
     * @param factRepo     JPA repository for inferred fact rows
     * @param materializer optional materializer for async graph edge/attribute writes
     */
    public DualStoreGroundingFactory(PslWeightRowRepository weightRepo,
                                      InferredFactRowRepository factRepo,
                                      @Nullable InferredFactGraphMaterializer materializer) {
        this.weightRepo = weightRepo;
        this.factRepo = factRepo;
        this.materializer = materializer;
    }

    // ── Factory methods ───────────────────────────────────────────────────────────

    /**
     * Create a {@link DualStoreWeightStore} scoped to the given fact sheet.
     *
     * @param factSheetId the fact-sheet scope; null creates a global (project-level) store
     * @return a fresh dual-store weight store for this fact sheet
     */
    public DualStoreWeightStore weightStoreFor(@Nullable Long factSheetId) {
        return new DualStoreWeightStore(weightRepo, factSheetId);
    }

    /**
     * Create a {@link DualStoreInferredFactStore} scoped to the given fact sheet,
     * with the optional graph materializer wired in.
     *
     * @param factSheetId the fact-sheet scope; must not be null
     * @return a fresh dual-store fact store for this fact sheet
     */
    public DualStoreInferredFactStore factStoreFor(Long factSheetId) {
        return new DualStoreInferredFactStore(factRepo, factSheetId, materializer);
    }

    /**
     * Create a {@link DualStoreInferredFactStore} scoped to the given fact sheet,
     * with the option to suppress the graph materializer.
     *
     * @param factSheetId       the fact-sheet scope; must not be null
     * @param skipMaterializer  when true the materializer is not wired into the store
     *                          (useful in batch/test scenarios where async writes are unwanted)
     * @return a fresh dual-store fact store for this fact sheet
     */
    public DualStoreInferredFactStore factStoreFor(Long factSheetId, boolean skipMaterializer) {
        return new DualStoreInferredFactStore(factRepo, factSheetId,
                skipMaterializer ? null : materializer);
    }
}
