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
package ai.kompile.core.kgembedding;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.nd4j.linalg.api.ndarray.INDArray;

/**
 * Seam for out-of-process KGE training execution.
 *
 * <p>This interface lives in {@code kompile-app-core} so that
 * {@link ai.kompile.knowledgegraph.embedding.service.KGEmbeddingJobService} in
 * {@code kompile-knowledge-graph} can optionally delegate the expensive
 * {@code TransE}/{@code RotatE} training step to an out-of-process launcher
 * without depending on {@code kompile-app-main}.</p>
 *
 * <p>The concrete implementation ({@code LearningSubprocessLauncher}) lives in
 * {@code kompile-app-main} and is wired by the app-main Spring context via
 * {@code @Autowired(required = false)} in {@code KGEmbeddingJobService}. When absent
 * (unit tests, CPU-only builds, or when
 * {@code kompile.learning.subprocess.enabled=false}) the in-JVM path is used
 * unchanged.</p>
 *
 * <h3>Contract</h3>
 * <ol>
 *   <li>The caller supplies the triples list (already extracted from the graph store).</li>
 *   <li>The implementation trains in a separate JVM, writes embeddings to a temp file,
 *       and returns a {@link KgeTrainingResult} with an already-populated
 *       {@link KGEmbeddingModel} shim that {@code adapter.storeEmbeddings()} can consume
 *       directly.</li>
 *   <li>If training fails, {@link KgeTrainingResult#success()} is {@code false} and
 *       the caller falls through to the existing failure path unchanged.</li>
 * </ol>
 */
public interface KgeTrainingExecutor {

    /**
     * Callback invoked on each epoch progress update from the training subprocess.
     * Implementations must be thread-safe (called from the stdout-reader thread).
     */
    @FunctionalInterface
    interface ProgressCallback {
        /**
         * Called for each epoch progress message.
         *
         * @param crawlJobId  the owning crawl job ID (for keying UI events)
         * @param epoch       current epoch (1-based)
         * @param totalEpochs total epochs configured
         * @param loss        current training loss
         */
        void onProgress(String crawlJobId, int epoch, int totalEpochs, double loss);
    }

    /**
     * Trains KGE embeddings for the supplied triples out-of-process.
     *
     * @param factSheetId fact sheet identifier (used for labelling and output paths)
     * @param algorithm   {@code KGEmbeddingAlgorithm.TRANSE} or {@code ROTATE}
     * @param config      training hyper-parameters (epochs, batchSize, lr, margin, etc.)
     * @param triples     triples extracted by the caller's adapter (entity IDs are whatever the
     *                    adapter produced — the executor must preserve them verbatim)
     * @return result holding a ready-to-use {@link KGEmbeddingModel} shim on success,
     *         or an error message on failure
     */
    KgeTrainingResult trainOutOfProcess(Long factSheetId,
                                        KGEmbeddingAlgorithm algorithm,
                                        KGEmbeddingConfig config,
                                        List<Triple> triples);

    /**
     * Trains KGE embeddings out-of-process, publishing per-epoch progress via {@code callback}.
     *
     * <p>Default implementation ignores the callback and delegates to
     * {@link #trainOutOfProcess(Long, KGEmbeddingAlgorithm, KGEmbeddingConfig, List)}.
     * Override in {@code LearningSubprocessLauncher} to wire the stdout-reader thread
     * to the callback so epoch/loss updates reach the crawl UI.</p>
     *
     * @param crawlJobId  crawl job owning this training run (forwarded to {@code callback})
     * @param factSheetId fact sheet identifier
     * @param algorithm   training algorithm
     * @param config      hyper-parameters
     * @param triples     triples to train on
     * @param callback    receives per-epoch progress; may be {@code null}
     * @return training result
     */
    default KgeTrainingResult trainOutOfProcess(String crawlJobId,
                                                Long factSheetId,
                                                KGEmbeddingAlgorithm algorithm,
                                                KGEmbeddingConfig config,
                                                List<Triple> triples,
                                                ProgressCallback callback) {
        return trainOutOfProcess(factSheetId, algorithm, config, triples);
    }

    /**
     * Trains KGE embeddings out-of-process with warm-start support.
     *
     * <p>When {@code priorEmbeddings} is non-empty, the subprocess seeds entity
     * embeddings from those vectors instead of random init, running a smaller
     * incremental update. When empty the full cold-start path is used unchanged.</p>
     *
     * <p>Default implementation ignores {@code priorEmbeddings} and delegates to
     * {@link #trainOutOfProcess(String, Long, KGEmbeddingAlgorithm, KGEmbeddingConfig, List, ProgressCallback)}.
     * Override in {@code LearningSubprocessLauncher} to serialize the prior embeddings
     * to a temp file and pass its path to the subprocess.</p>
     *
     * @param crawlJobId      crawl job owning this training run
     * @param factSheetId     fact sheet identifier
     * @param algorithm       training algorithm
     * @param config          hyper-parameters (already adjusted for warm-start epochs by caller)
     * @param triples         triples to train on
     * @param priorEmbeddings entity ID → INDArray from a previous training run; empty = cold start
     * @param callback        receives per-epoch progress; may be {@code null}
     * @return training result
     */
    default KgeTrainingResult trainOutOfProcess(String crawlJobId,
                                                Long factSheetId,
                                                KGEmbeddingAlgorithm algorithm,
                                                KGEmbeddingConfig config,
                                                List<Triple> triples,
                                                Map<String, INDArray> priorEmbeddings,
                                                ProgressCallback callback) {
        return trainOutOfProcess(crawlJobId, factSheetId, algorithm, config, triples, callback);
    }

    /**
     * Trains KGE embeddings out-of-process with full warm-start support for both entity and
     * relation embeddings.
     *
     * <p>When {@code priorRelationEmbeddings} is non-empty, relation vectors from the prior
     * training run are passed to the subprocess so known relations are seeded rather than
     * random-initialised. Combined with {@code priorEmbeddings} (entity warm-start) this
     * gives a complete incremental update for both TransE and RotatE.</p>
     *
     * <p>Default implementation ignores {@code priorRelationEmbeddings} and delegates to
     * the entity-only warm-start overload. Override in {@code LearningSubprocessLauncher}
     * to serialize relation vectors alongside entity vectors in the warm-start file.</p>
     *
     * @param crawlJobId              crawl job owning this training run
     * @param factSheetId             fact sheet identifier
     * @param algorithm               training algorithm
     * @param config                  hyper-parameters (already adjusted for warm-start epochs)
     * @param triples                 triples to train on
     * @param priorEmbeddings         entity ID → INDArray; empty = cold-start entities
     * @param priorRelationEmbeddings relation type → INDArray; empty = cold-start relations
     * @param callback                per-epoch progress; may be {@code null}
     * @return training result
     */
    default KgeTrainingResult trainOutOfProcess(String crawlJobId,
                                                Long factSheetId,
                                                KGEmbeddingAlgorithm algorithm,
                                                KGEmbeddingConfig config,
                                                List<Triple> triples,
                                                Map<String, INDArray> priorEmbeddings,
                                                Map<String, INDArray> priorRelationEmbeddings,
                                                ProgressCallback callback) {
        return trainOutOfProcess(crawlJobId, factSheetId, algorithm, config, triples,
                priorEmbeddings, callback);
    }

    /**
     * Result of an out-of-process KGE training run.
     *
     * @param success     whether training completed without error
     * @param model       populated model shim (non-null on success; null on failure)
     * @param finalLoss   final training loss (0.0 on failure)
     * @param errorMessage error description (null on success)
     */
    record KgeTrainingResult(boolean success,
                              KGEmbeddingModel model,
                              double finalLoss,
                              String errorMessage) {

        /** Convenience factory for a successful run. */
        public static KgeTrainingResult success(KGEmbeddingModel model, double finalLoss) {
            return new KgeTrainingResult(true, model, finalLoss, null);
        }

        /** Convenience factory for a failed run. */
        public static KgeTrainingResult failure(String reason) {
            return new KgeTrainingResult(false, null, 0.0, reason);
        }
    }
}
