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

package ai.kompile.knowledgegraph.embedding.impl;

import ai.kompile.core.kgembedding.EmbeddingScore;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.kgembedding.TrainingProgress;
import ai.kompile.core.kgembedding.TrainingResult;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.knowledgegraph.embedding.training.NegativeSampler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bytedeco.javacpp.Pointer;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.learning.GradientUpdater;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SameDiff-backed KGE model that drives DL4J's {@code sd.graph().rotatE()} /
 * {@code sd.graph().transE()} scorers and {@code sd.graph().marginRankingLoss()} for training,
 * gated behind a managed-config flag ({@code kompile.kge.useSameDiffKge}).
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li>Embedding parameters ({@code entityRe}/{@code entityIm}/{@code relPhase} for RotatE;
 *       {@code entityEmb}/{@code relEmb} for TransE) live as SameDiff trainable variables.</li>
 *   <li>Per mini-batch the model feeds integer gather-index placeholders into a reusable SameDiff
 *       graph and calls {@link SameDiff#calculateGradients(Map, java.util.Collection)}, which runs
 *       the forward pass through the DL4J scorer ops, computes the margin ranking loss, and
 *       backpropagates — all in a single call whose intermediate tensors SameDiff frees before
 *       returning.</li>
 *   <li>Returned gradient arrays are applied via plain SGD then explicitly closed, preventing
 *       native-object accumulation across batches.</li>
 * </ul>
 *
 * <h3>Memory contract</h3>
 * The only persistent native allocations are the embedding parameter arrays (N×D and R×D float32),
 * identical in footprint to the hand-rolled {@link TransEModel}/{@link RotatEModel}.
 * Per-batch overhead is one integer index array ({@code int[batchSize × negPerPos]}) plus
 * SameDiff's own single-execution workspace, both freed before the next batch starts.
 * The JVM property {@code org.bytedeco.javacpp.maxphysicalbytes} is logged at construction time
 * so the OOM ceiling is visible in the subprocess log.
 *
 * <h3>Export (.sdz)</h3>
 * {@link #saveEmbeddings(Path)} writes a {@code .sdz} ZIP archive via
 * {@link SameDiff#saveShardedOptimized(File, boolean, List)} (updater state excluded) plus a
 * {@code .vocab.json} sidecar carrying the entity/relation index maps required for inference.
 *
 * <h3>Parity gate</h3>
 * {@code SameDiffKgeParityTest} must pass before the config flag is set to {@code true}
 * in production — see that test class for the Hits@K/MRR evaluation contract.
 */
public class SameDiffKgeModel implements KGEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(SameDiffKgeModel.class);

    // ── SameDiff variable names ───────────────────────────────────────────────
    static final String VAR_ENTITY_RE  = "entityRe";
    static final String VAR_ENTITY_IM  = "entityIm";
    static final String VAR_REL_PHASE  = "relPhase";
    static final String VAR_ENTITY_EMB = "entityEmb";
    static final String VAR_REL_EMB    = "relEmb";

    // ── Placeholder names ─────────────────────────────────────────────────────
    private static final String PH_H_IDX  = "hIdx";
    private static final String PH_T_IDX  = "tIdx";
    private static final String PH_R_IDX  = "rIdx";
    private static final String PH_NH_IDX = "nhIdx";
    private static final String PH_NT_IDX = "ntIdx";

    private final KGEmbeddingAlgorithm algorithm;

    // Vocabulary — rebuilt per train() call
    private Map<String, Integer> entityToIndex   = new HashMap<>();
    private Map<String, Integer> relationToIndex  = new HashMap<>();
    private List<String>         indexToEntity    = new ArrayList<>();
    private List<String>         indexToRelation  = new ArrayList<>();
    private int embeddingDim;

    // SameDiff graph — rebuilt per train() call; reused for scoring / save
    private SameDiff sd;

    // Warm-start buffers — populated by importEntityEmbeddings / importRelationEmbeddings
    // before train() is called; consumed and cleared inside train()
    private Map<String, INDArray> pendingEntityEmbeddings   = new HashMap<>();
    private Map<String, INDArray> pendingRelationEmbeddings = new HashMap<>();
    private Map<String, Integer>  pendingEntityIndex        = new HashMap<>();
    private Map<String, Integer>  pendingRelationIndex      = new HashMap<>();

    private final AtomicBoolean training  = new AtomicBoolean(false);
    private final AtomicBoolean cancelReq = new AtomicBoolean(false);
    private volatile boolean trained = false;

    // Adam updaters — one per parameter variable; (re-)initialised in train()
    private final Map<String, GradientUpdater<Adam>> adamUpdaters = new HashMap<>();
    private int adamIteration = 0;

    public SameDiffKgeModel(KGEmbeddingAlgorithm algorithm) {
        this.algorithm = algorithm;
        logNativeMemConfig();
    }

    private void logNativeMemConfig() {
        try {
            long capMb  = Pointer.maxPhysicalBytes() >> 20;
            long physMb = Pointer.physicalBytes()    >> 20;
            log.info("[SameDiff-KGE] algorithm={} maxPhysicalBytes={}MB currentPhysical={}MB",
                    algorithm.name(), capMb, physMb);
        } catch (Throwable t) {
            log.debug("Could not read JavaCPP memory info: {}", t.getMessage());
        }
    }

    // ─── KGEmbeddingModel: metadata ──────────────────────────────────────────

    @Override public String getAlgorithmName()       { return "SameDiff-" + algorithm.getDisplayName(); }
    @Override public KGEmbeddingAlgorithm getAlgorithm() { return algorithm; }
    @Override public int getEmbeddingDimension()     { return embeddingDim; }
    @Override public int getEntityCount()            { return entityToIndex.size(); }
    @Override public int getRelationCount()          { return relationToIndex.size(); }
    @Override public Set<String> getEntityIds()      { return Collections.unmodifiableSet(entityToIndex.keySet()); }
    @Override public Set<String> getRelationTypes()  { return Collections.unmodifiableSet(relationToIndex.keySet()); }
    @Override public boolean isTraining()            { return training.get(); }
    @Override public void   cancelTraining()         { cancelReq.set(true); }
    @Override public boolean isTrained()             { return trained; }

    // ─── Training ────────────────────────────────────────────────────────────

    @Override
    public TrainingResult train(List<Triple> triples, KGEmbeddingConfig config) {
        if (triples == null || triples.isEmpty()) {
            return TrainingResult.failure("No triples provided for training");
        }
        if (training.getAndSet(true)) {
            return TrainingResult.failure("Training already in progress");
        }
        cancelReq.set(false);

        long startTime = System.currentTimeMillis();
        List<Double> lossHistory = new ArrayList<>();

        try {
            // Snapshot warm-start state before buildVocabulary() resets the index maps.
            Map<String, Integer> priorEntityIdx  = new HashMap<>(pendingEntityIndex);
            Map<String, Integer> priorRelIdx     = new HashMap<>(pendingRelationIndex);
            Map<String, INDArray> priorEntityEmb = new HashMap<>(pendingEntityEmbeddings);
            Map<String, INDArray> priorRelEmb    = new HashMap<>(pendingRelationEmbeddings);

            buildVocabulary(triples);
            this.embeddingDim = config.embeddingDim();

            // Build the SameDiff graph (creates trainable variables with random init).
            sd = SameDiff.create();
            buildGraph(config.margin());

            // Overwrite parameter rows from warm-start priors where available.
            initParams(priorEntityIdx, priorEntityEmb, priorRelIdx, priorRelEmb, config);

            // (Re-)initialise Adam updaters for this training run.
            // Mirrors RotatELearner: one GradientUpdater<Adam> per parameter matrix;
            // state length = 2 × param-count (the updater splits it into m and v halves).
            // Params are FLOAT; state is initialised as FLOAT to match.
            adamUpdaters.clear();
            adamIteration = 0;
            Adam adamCfg = new Adam(config.learningRate(), 0.9, 0.999, 1e-8);
            int N = entityToIndex.size();
            int R = relationToIndex.size();
            int D = embeddingDim;
            if (algorithm == KGEmbeddingAlgorithm.ROTATE) {
                adamUpdaters.put(VAR_ENTITY_RE,  newAdamUpdater(adamCfg, 2L * N * D));
                adamUpdaters.put(VAR_ENTITY_IM,  newAdamUpdater(adamCfg, 2L * N * D));
                adamUpdaters.put(VAR_REL_PHASE,  newAdamUpdater(adamCfg, 2L * R * D));
            } else {
                adamUpdaters.put(VAR_ENTITY_EMB, newAdamUpdater(adamCfg, 2L * N * D));
                adamUpdaters.put(VAR_REL_EMB,    newAdamUpdater(adamCfg, 2L * R * D));
            }

            // Training loop
            NegativeSampler sampler = new NegativeSampler(
                    entityToIndex.keySet(), new HashSet<>(triples));
            int totalBatches = (int) Math.ceil((double) triples.size() / config.batchSize());

            for (int epoch = 0; epoch < config.epochs(); epoch++) {
                if (cancelReq.get()) {
                    log.info("[SameDiff-KGE] Cancelled at epoch {}", epoch);
                    return TrainingResult.cancelled(epoch,
                            lossHistory.isEmpty() ? 0.0 : lossHistory.get(lossHistory.size() - 1));
                }

                double epochLoss   = 0.0;
                int    batchesDone = 0;

                List<Triple> shuffled = new ArrayList<>(triples);
                Collections.shuffle(shuffled);

                for (int i = 0; i < shuffled.size(); i += config.batchSize()) {
                    int end  = Math.min(i + config.batchSize(), shuffled.size());
                    List<Triple> batch = shuffled.subList(i, end);
                    List<Triple> negs  = sampler.corrupt(batch, config.negativeSamples());
                    double batchLoss   = trainBatch(batch, negs, config.learningRate(),
                                                    config.negativeSamples());
                    if (Double.isFinite(batchLoss)) epochLoss += batchLoss;
                    batchesDone++;
                }

                if (batchesDone > 0) epochLoss /= batchesDone;
                lossHistory.add(epochLoss);

                if (config.progressCallback() != null) {
                    long elapsed   = System.currentTimeMillis() - startTime;
                    double tps     = (double) (epoch + 1) * triples.size() * 1_000 / elapsed;
                    long remaining = (long) ((config.epochs() - epoch - 1) * elapsed / (epoch + 1));
                    config.progressCallback().accept(new TrainingProgress(
                            epoch + 1, config.epochs(), epochLoss,
                            batchesDone, totalBatches, elapsed, remaining, tps));
                }

                if ((epoch + 1) % 10 == 0 || epoch == 0) {
                    log.info("[SameDiff-KGE] Epoch {}/{}: loss={}", epoch + 1, config.epochs(),
                            String.format("%.4f", epochLoss));
                }
            }

            trained = true;
            long trainingTime = System.currentTimeMillis() - startTime;
            log.info("[SameDiff-KGE] Training complete in {}ms", trainingTime);
            return TrainingResult.success(
                    entityToIndex.size(), relationToIndex.size(), triples.size(),
                    config.epochs(),
                    lossHistory.isEmpty() ? 0.0 : lossHistory.get(lossHistory.size() - 1),
                    lossHistory, trainingTime);

        } catch (Exception e) {
            log.error("[SameDiff-KGE] Training failed", e);
            return TrainingResult.failure(e.getMessage());
        } finally {
            training.set(false);
        }
    }

    /**
     * Builds the SameDiff computation graph.  Called once per {@link #train()} after vocabulary
     * is known so variable shapes ({@code numEntities × dim}) are fixed.
     *
     * <p><b>RotatE</b> (Sun et al. 2019):
     * {@code posScore = sd.graph().rotatE(hRe, hIm, relPhase, tRe, tIm)} — args in that order
     * per {@code SDGraph.rotatE(hRe, hIm, relPhase, tRe, tIm)}.
     * Returns {@code -||...||} (higher = more plausible).
     *
     * <p><b>TransE</b> (Bordes et al. 2013):
     * {@code posScore = sd.graph().transE(head, relation, tail)}.
     * Returns {@code -||head + relation - tail||}.
     *
     * <p><b>Loss</b>:
     * {@code sd.graph().marginRankingLoss(posScore, negScore, margin)} =
     * {@code mean(relu(negScore - posScore + margin))}.
     * Correct sign convention: both scorers return negated distance so
     * higher posScore (smaller pos distance) is good, and the loss penalises
     * cases where the negative score is within {@code margin} of the positive.
     */
    private void buildGraph(double margin) {
        int N = entityToIndex.size();
        int R = relationToIndex.size();
        int D = embeddingDim;

        // Integer gather-index placeholders; shape [-1] = dynamic (batch × negPerPos at call time)
        SDVariable hIdx  = sd.placeHolder(PH_H_IDX,  DataType.INT32, -1);
        SDVariable tIdx  = sd.placeHolder(PH_T_IDX,  DataType.INT32, -1);
        SDVariable rIdx  = sd.placeHolder(PH_R_IDX,  DataType.INT32, -1);
        SDVariable nhIdx = sd.placeHolder(PH_NH_IDX, DataType.INT32, -1);
        SDVariable ntIdx = sd.placeHolder(PH_NT_IDX, DataType.INT32, -1);

        SDVariable loss;

        if (algorithm == KGEmbeddingAlgorithm.ROTATE) {
            double embRange = 6.0 / D;
            SDVariable entityRe = sd.var(VAR_ENTITY_RE,
                    Nd4j.rand(DataType.FLOAT, N, D).muli(2 * embRange).subi(embRange));
            SDVariable entityIm = sd.var(VAR_ENTITY_IM,
                    Nd4j.rand(DataType.FLOAT, N, D).muli(2 * embRange).subi(embRange));
            SDVariable relPhase = sd.var(VAR_REL_PHASE,
                    Nd4j.rand(DataType.FLOAT, R, D).muli(2 * Math.PI).subi(Math.PI));

            // Gather rows for positive pair
            SDVariable hRe = sd.gather(entityRe, hIdx, 0);   // [BK, D]
            SDVariable hIm = sd.gather(entityIm, hIdx, 0);
            SDVariable rPh = sd.gather(relPhase, rIdx, 0);
            SDVariable tRe = sd.gather(entityRe, tIdx, 0);
            SDVariable tIm = sd.gather(entityIm, tIdx, 0);

            // Gather rows for negative pair (same relation as positive)
            SDVariable nhRe = sd.gather(entityRe, nhIdx, 0); // [BK, D]
            SDVariable nhIm = sd.gather(entityIm, nhIdx, 0);
            SDVariable ntRe = sd.gather(entityRe, ntIdx, 0);
            SDVariable ntIm = sd.gather(entityIm, ntIdx, 0);

            // Score using DL4J's built-in RotatE op (arg order: hRe, hIm, relPhase, tRe, tIm)
            SDVariable posScore = sd.graph().rotatE(hRe, hIm, rPh, tRe,  tIm);   // [BK]
            SDVariable negScore = sd.graph().rotatE(nhRe, nhIm, rPh, ntRe, ntIm); // [BK]
            loss = sd.graph().marginRankingLoss(posScore, negScore, margin);       // scalar

        } else { // TRANSE
            double initScale = 6.0 / Math.sqrt(D);
            SDVariable entityEmb = sd.var(VAR_ENTITY_EMB,
                    Nd4j.rand(DataType.FLOAT, N, D).muli(2 * initScale).subi(initScale));
            SDVariable relEmb = sd.var(VAR_REL_EMB,
                    Nd4j.rand(DataType.FLOAT, R, D).muli(2 * initScale).subi(initScale));

            // Gather for positive
            SDVariable hEmb  = sd.gather(entityEmb, hIdx,  0);
            SDVariable rEmb  = sd.gather(relEmb,    rIdx,  0);
            SDVariable tEmb  = sd.gather(entityEmb, tIdx,  0);

            // Gather for negative (same relation)
            SDVariable nhEmb = sd.gather(entityEmb, nhIdx, 0);
            SDVariable ntEmb = sd.gather(entityEmb, ntIdx, 0);

            // Score using DL4J's built-in TransE op (arg order: head, relation, tail)
            SDVariable posScore = sd.graph().transE(hEmb,  rEmb, tEmb);  // [BK]
            SDVariable negScore = sd.graph().transE(nhEmb, rEmb, ntEmb); // [BK]
            loss = sd.graph().marginRankingLoss(posScore, negScore, margin);
        }

        loss.markAsLoss();
        log.debug("[SameDiff-KGE] Graph built: alg={} N={} R={} D={}", algorithm.name(), N, R, D);
    }

    /**
     * Overwrites parameter arrays in the (just-built) SameDiff graph with warm-start prior
     * vectors.  Rows for unknown entities/relations stay as the random init from buildGraph.
     */
    private void initParams(Map<String, Integer> priorEntityIdx,
                            Map<String, INDArray> priorEntityEmb,
                            Map<String, Integer>  priorRelIdx,
                            Map<String, INDArray> priorRelEmb,
                            KGEmbeddingConfig config) {
        int D = config.embeddingDim();
        boolean hasEntityPrior   = !priorEntityEmb.isEmpty();
        boolean hasRelationPrior = !priorRelEmb.isEmpty();

        if (algorithm == KGEmbeddingAlgorithm.ROTATE) {
            if (hasEntityPrior) {
                INDArray reArr = sd.getVariable(VAR_ENTITY_RE).getArr();
                INDArray imArr = sd.getVariable(VAR_ENTITY_IM).getArr();
                int seeded = 0, fresh = 0;
                for (Map.Entry<String, Integer> e : entityToIndex.entrySet()) {
                    INDArray prior = priorEntityEmb.get(e.getKey());
                    // Warm-start vectors are concatenated [real|imag] (2*D cols, per RotatEModel convention)
                    if (prior != null && prior.columns() == 2 * D) {
                        reArr.putRow(e.getValue(), prior.get(NDArrayIndex.all(), NDArrayIndex.interval(0, D)));
                        imArr.putRow(e.getValue(), prior.get(NDArrayIndex.all(), NDArrayIndex.interval(D, 2 * D)));
                        seeded++;
                    } else {
                        fresh++;
                    }
                }
                log.info("[SameDiff-KGE/RotatE] warm-start entities: {} seeded, {} fresh", seeded, fresh);
            }
            if (hasRelationPrior) {
                INDArray rArr = sd.getVariable(VAR_REL_PHASE).getArr();
                int seeded = 0, fresh = 0;
                for (Map.Entry<String, Integer> e : relationToIndex.entrySet()) {
                    INDArray prior = priorRelEmb.get(e.getKey());
                    if (prior != null && prior.columns() == D) {
                        rArr.putRow(e.getValue(), prior);
                        seeded++;
                    } else { fresh++; }
                }
                log.info("[SameDiff-KGE/RotatE] warm-start relations: {} seeded, {} fresh", seeded, fresh);
            }
        } else { // TRANSE
            if (hasEntityPrior) {
                INDArray eArr = sd.getVariable(VAR_ENTITY_EMB).getArr();
                int seeded = 0, fresh = 0;
                for (Map.Entry<String, Integer> e : entityToIndex.entrySet()) {
                    INDArray prior = priorEntityEmb.get(e.getKey());
                    if (prior != null && prior.columns() == D) {
                        eArr.putRow(e.getValue(), prior);
                        seeded++;
                    } else { fresh++; }
                }
                log.info("[SameDiff-KGE/TransE] warm-start entities: {} seeded, {} fresh", seeded, fresh);
            }
            if (hasRelationPrior) {
                INDArray rArr = sd.getVariable(VAR_REL_EMB).getArr();
                int seeded = 0, fresh = 0;
                for (Map.Entry<String, Integer> e : relationToIndex.entrySet()) {
                    INDArray prior = priorRelEmb.get(e.getKey());
                    if (prior != null && prior.columns() == D) {
                        rArr.putRow(e.getValue(), prior);
                        seeded++;
                    } else { fresh++; }
                }
                log.info("[SameDiff-KGE/TransE] warm-start relations: {} seeded, {} fresh", seeded, fresh);
            }
        }
    }

    /**
     * Runs one mini-batch through SameDiff autodiff.
     *
     * <p>The batch is flattened to {@code B × K} (positive, negative) pairs so the gather ops
     * produce parallel {@code [B×K, D]} tensors and
     * {@code sd.graph().marginRankingLoss(posScore, negScore, margin)} averages element-wise.
     *
     * <p>Memory contract:
     * <ul>
     *   <li>Input arrays: two {@code int[B×K]} integer arrays (created here, closed in finally).</li>
     *   <li>SameDiff execution: managed internally; temporaries freed before {@code calculateGradients}
     *       returns.</li>
     *   <li>Gradient arrays: {@code N×D} (sparse, non-zero only on gathered rows); applied with SGD
     *       and closed immediately.</li>
     * </ul>
     */
    private double trainBatch(List<Triple> positives, List<Triple> negatives,
                              double lr, int negPerPos) {
        int B  = positives.size();
        int K  = negPerPos;
        int BK = B * K;
        if (BK == 0) return 0.0;

        // Build flattened index arrays [B*K]: positive i is repeated K times, matched with each negative
        int[] hIdxArr  = new int[BK];
        int[] tIdxArr  = new int[BK];
        int[] rIdxArr  = new int[BK];
        int[] nhIdxArr = new int[BK];
        int[] ntIdxArr = new int[BK];

        int out = 0;
        for (int i = 0; i < B; i++) {
            Triple pos = positives.get(i);
            Integer hi = entityToIndex.get(pos.head());
            Integer ti = entityToIndex.get(pos.tail());
            Integer ri = relationToIndex.get(pos.relation());
            for (int j = 0; j < K; j++, out++) {
                int negOff = i * K + j;
                Triple neg = (negOff < negatives.size()) ? negatives.get(negOff) : pos;
                Integer nhi = entityToIndex.get(neg.head());
                Integer nti = entityToIndex.get(neg.tail());
                hIdxArr[out]  = (hi  != null) ? hi  : 0;
                tIdxArr[out]  = (ti  != null) ? ti  : 0;
                rIdxArr[out]  = (ri  != null) ? ri  : 0;
                nhIdxArr[out] = (nhi != null) ? nhi : 0;
                ntIdxArr[out] = (nti != null) ? nti : 0;
            }
        }

        Map<String, INDArray> inputs = new LinkedHashMap<>();
        inputs.put(PH_H_IDX,  Nd4j.createFromArray(hIdxArr));
        inputs.put(PH_T_IDX,  Nd4j.createFromArray(tIdxArr));
        inputs.put(PH_R_IDX,  Nd4j.createFromArray(rIdxArr));
        inputs.put(PH_NH_IDX, Nd4j.createFromArray(nhIdxArr));
        inputs.put(PH_NT_IDX, Nd4j.createFromArray(ntIdxArr));

        List<String> paramNames = paramVarNames();
        Map<String, INDArray> grads;
        try {
            // calculateGradients() runs forward + backward pass in one call.
            // SameDiff manages its own memory workspace and frees intermediates on return.
            grads = sd.calculateGradients(inputs, paramNames);
        } catch (Exception e) {
            log.warn("[SameDiff-KGE] calculateGradients failed: {}", e.getMessage());
            return 0.0;
        } finally {
            for (INDArray arr : inputs.values()) {
                try { arr.close(); } catch (Exception ignored) {}
            }
        }

        // Apply Adam update (mirrors RotatELearner.applyAdam exactly):
        // GradientUpdater.applyUpdater() rewrites 'update' in-place with the bias-corrected
        // Adam step (advancing its own moment state), then param -= update.
        // Only gathered rows are non-zero, so the update is effectively sparse.
        for (String varName : paramNames) {
            INDArray grad = grads.get(varName);
            if (grad == null) continue;
            try {
                GradientUpdater<Adam> updater = adamUpdaters.get(varName);
                INDArray update = grad.castTo(DataType.FLOAT);  // match param dtype (FLOAT)
                if (updater != null) {
                    updater.applyUpdater(update, adamIteration, 0);
                }
                sd.getVariable(varName).getArr().subi(update);
            } finally {
                try { grad.close(); } catch (Exception ignored) {}
            }
        }
        adamIteration++;

        // Return loss value if SameDiff cached it, else 0
        try {
            Map<String, INDArray> lossOutput = sd.output(inputs, "loss");
            if (lossOutput.containsKey("loss")) return lossOutput.get("loss").getDouble(0);
        } catch (Exception ignored) {}
        return 0.0;
    }

    /**
     * Instantiate an ND4J Adam {@link GradientUpdater} over a fresh, zero-initialised state view.
     * Mirrors {@code RotatELearner.newAdamUpdater}: state length must be {@code 2 × param-count}
     * so the updater can split it into first- and second-moment halves.
     */
    @SuppressWarnings("unchecked")  // IUpdater#instantiate has a raw GradientUpdater return type
    private static GradientUpdater<Adam> newAdamUpdater(Adam config, long stateLen) {
        return config.instantiate(Nd4j.zeros(DataType.FLOAT, 1, stateLen), true);
    }

    /** Names of the trainable parameter variables for the current algorithm. */
    private List<String> paramVarNames() {
        return (algorithm == KGEmbeddingAlgorithm.ROTATE)
                ? List.of(VAR_ENTITY_RE, VAR_ENTITY_IM, VAR_REL_PHASE)
                : List.of(VAR_ENTITY_EMB, VAR_REL_EMB);
    }

    // ─── Vocabulary ──────────────────────────────────────────────────────────

    private void buildVocabulary(List<Triple> triples) {
        entityToIndex.clear();
        relationToIndex.clear();
        indexToEntity.clear();
        indexToRelation.clear();
        Set<String> entities  = new LinkedHashSet<>();
        Set<String> relations = new LinkedHashSet<>();
        for (Triple t : triples) {
            entities.add(t.head());
            entities.add(t.tail());
            relations.add(t.relation());
        }
        int i = 0;
        for (String e : entities)  { entityToIndex.put(e, i++);   indexToEntity.add(e); }
        i = 0;
        for (String r : relations) { relationToIndex.put(r, i++); indexToRelation.add(r); }
    }

    // ─── Embedding access ─────────────────────────────────────────────────────

    @Override
    public INDArray getEntityEmbedding(String entityId) {
        if (!trained) return null;
        Integer idx = entityToIndex.get(entityId);
        if (idx == null) return null;
        if (sd != null) {
            if (algorithm == KGEmbeddingAlgorithm.ROTATE) {
                INDArray re = sd.getVariable(VAR_ENTITY_RE).getArr().getRow(idx).dup();
                INDArray im = sd.getVariable(VAR_ENTITY_IM).getArr().getRow(idx).dup();
                return Nd4j.hstack(re, im);  // [1, 2*dim] concatenated real+imag
            }
            return sd.getVariable(VAR_ENTITY_EMB).getArr().getRow(idx).dup();
        }
        // Fallback: return from pending warm-start embeddings if sd not built yet
        return pendingEntityEmbeddings.get(entityId);
    }

    @Override
    public INDArray getRelationEmbedding(String relationType) {
        if (!trained || sd == null) return null;
        Integer idx = relationToIndex.get(relationType);
        if (idx == null) return null;
        String varName = (algorithm == KGEmbeddingAlgorithm.ROTATE) ? VAR_REL_PHASE : VAR_REL_EMB;
        return sd.getVariable(varName).getArr().getRow(idx).dup();
    }

    @Override
    public Map<String, INDArray> getAllEntityEmbeddings() {
        Map<String, INDArray> result = new HashMap<>();
        for (String id : entityToIndex.keySet()) {
            INDArray emb = getEntityEmbedding(id);
            if (emb != null) result.put(id, emb);
        }
        return result;
    }

    @Override
    public Map<String, INDArray> getAllRelationEmbeddings() {
        Map<String, INDArray> result = new HashMap<>();
        for (String r : relationToIndex.keySet()) {
            INDArray emb = getRelationEmbedding(r);
            if (emb != null) result.put(r, emb);
        }
        return result;
    }

    @Override
    public INDArray getEntityEmbeddingMatrix() {
        if (!trained || sd == null) return null;
        if (algorithm == KGEmbeddingAlgorithm.ROTATE) {
            INDArray re = sd.getVariable(VAR_ENTITY_RE).getArr();
            INDArray im = sd.getVariable(VAR_ENTITY_IM).getArr();
            return Nd4j.hstack(re, im);
        }
        return sd.getVariable(VAR_ENTITY_EMB).getArr().dup();
    }

    @Override
    public INDArray getRelationEmbeddingMatrix() {
        if (!trained || sd == null) return null;
        String varName = (algorithm == KGEmbeddingAlgorithm.ROTATE) ? VAR_REL_PHASE : VAR_REL_EMB;
        return sd.getVariable(varName).getArr().dup();
    }

    // ─── Scoring ──────────────────────────────────────────────────────────────

    /**
     * Scores a triple using pure ND4J arithmetic (same formulae as the DL4J ops).
     * This avoids per-score SameDiff graph executions, which would be expensive
     * for link-prediction sweeps over all entities.
     */
    @Override
    public double scoreTriple(String head, String relation, String tail) {
        INDArray h = getEntityEmbedding(head);
        INDArray r = getRelationEmbedding(relation);
        INDArray t = getEntityEmbedding(tail);
        if (h == null || r == null || t == null) return Double.MAX_VALUE;

        if (algorithm == KGEmbeddingAlgorithm.ROTATE) {
            // h/t are [1, 2*dim]; r is [1, dim] phase angles
            int D = embeddingDim;
            INDArray hRe = h.get(NDArrayIndex.all(), NDArrayIndex.interval(0, D));
            INDArray hIm = h.get(NDArrayIndex.all(), NDArrayIndex.interval(D, 2 * D));
            INDArray tRe = t.get(NDArrayIndex.all(), NDArrayIndex.interval(0, D));
            INDArray tIm = t.get(NDArrayIndex.all(), NDArrayIndex.interval(D, 2 * D));
            INDArray cosR = Transforms.cos(r, true);
            INDArray sinR = Transforms.sin(r, true);
            // dRe = hRe*cos - hIm*sin - tRe;  dIm = hRe*sin + hIm*cos - tIm
            INDArray dRe = hRe.mul(cosR).sub(hIm.mul(sinR)).subi(tRe);
            INDArray dIm = hRe.mul(sinR).add(hIm.mul(cosR)).subi(tIm);
            // dist = sum(sqrt(dRe^2 + dIm^2 + eps)) matches SDGraph.rotatE
            return Transforms.sqrt(dRe.mul(dRe).add(dIm.mul(dIm)).addi(1e-9f), false)
                             .sumNumber().doubleValue();
        }
        // TransE: ||h + r - t|| matches SDGraph.transE
        return h.add(r).subi(t).norm2Number().doubleValue();
    }

    @Override
    public double[] scoreTriples(List<Triple> triples) {
        double[] scores = new double[triples.size()];
        for (int i = 0; i < triples.size(); i++) {
            Triple t = triples.get(i);
            scores[i] = scoreTriple(t.head(), t.relation(), t.tail());
        }
        return scores;
    }

    @Override
    public List<EmbeddingScore> predictTails(String head, String relation, int topK) {
        if (!trained) return Collections.emptyList();
        List<EmbeddingScore> scores = new ArrayList<>();
        for (String candidate : indexToEntity) {
            scores.add(new EmbeddingScore(candidate, scoreTriple(head, relation, candidate)));
        }
        return topK(scores, topK);
    }

    @Override
    public List<EmbeddingScore> predictHeads(String relation, String tail, int topK) {
        if (!trained) return Collections.emptyList();
        List<EmbeddingScore> scores = new ArrayList<>();
        for (String candidate : indexToEntity) {
            scores.add(new EmbeddingScore(candidate, scoreTriple(candidate, relation, tail)));
        }
        return topK(scores, topK);
    }

    @Override
    public List<EmbeddingScore> predictRelations(String head, String tail, int topK) {
        if (!trained) return Collections.emptyList();
        List<EmbeddingScore> scores = new ArrayList<>();
        for (String candidate : indexToRelation) {
            scores.add(new EmbeddingScore(candidate, scoreTriple(head, candidate, tail)));
        }
        return topK(scores, topK);
    }

    private static List<EmbeddingScore> topK(List<EmbeddingScore> scores, int k) {
        scores.sort(Comparator.comparingDouble(EmbeddingScore::score));
        List<EmbeddingScore> result = new ArrayList<>();
        for (int i = 0; i < Math.min(k, scores.size()); i++) {
            result.add(scores.get(i).withRank(i + 1));
        }
        return result;
    }

    @Override
    public List<EmbeddingScore> findSimilarEntities(String entityId, int topK) {
        INDArray target = getEntityEmbedding(entityId);
        if (target == null) return Collections.emptyList();
        List<EmbeddingScore> scores = new ArrayList<>();
        for (String e : indexToEntity) {
            if (e.equals(entityId)) continue;
            INDArray emb = getEntityEmbedding(e);
            if (emb == null) continue;
            double sim = cosineSimilarity(target, emb);
            scores.add(new EmbeddingScore(e, -sim));
        }
        scores.sort(Comparator.comparingDouble(EmbeddingScore::score));
        List<EmbeddingScore> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scores.size()); i++) {
            EmbeddingScore s = scores.get(i);
            result.add(new EmbeddingScore(s.entity(), s.entityType(), -s.score(), i + 1));
        }
        return result;
    }

    @Override
    public List<EmbeddingScore> findSimilarRelations(String relationType, int topK) {
        INDArray target = getRelationEmbedding(relationType);
        if (target == null) return Collections.emptyList();
        List<EmbeddingScore> scores = new ArrayList<>();
        for (String r : indexToRelation) {
            if (r.equals(relationType)) continue;
            INDArray emb = getRelationEmbedding(r);
            if (emb == null) continue;
            double sim = cosineSimilarity(target, emb);
            scores.add(new EmbeddingScore(r, -sim));
        }
        scores.sort(Comparator.comparingDouble(EmbeddingScore::score));
        List<EmbeddingScore> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scores.size()); i++) {
            EmbeddingScore s = scores.get(i);
            result.add(new EmbeddingScore(s.entity(), s.entityType(), -s.score(), i + 1));
        }
        return result;
    }

    @Override
    public double entitySimilarity(String entity1, String entity2) {
        INDArray e1 = getEntityEmbedding(entity1);
        INDArray e2 = getEntityEmbedding(entity2);
        if (e1 == null || e2 == null) return 0.0;
        return cosineSimilarity(e1, e2);
    }

    private double cosineSimilarity(INDArray a, INDArray b) {
        double dot   = a.mul(b).sumNumber().doubleValue();
        double normA = a.norm2Number().doubleValue();
        double normB = b.norm2Number().doubleValue();
        return (normA == 0 || normB == 0) ? 0.0 : dot / (normA * normB);
    }

    // ─── Persistence ──────────────────────────────────────────────────────────

    /**
     * Saves the trained model as a {@code .sdz} sharded ZIP archive via
     * {@link SameDiff#saveShardedOptimized(File, boolean, List)} (updater state excluded)
     * plus a {@code .vocab.json} sidecar for the entity/relation index maps.
     *
     * <p>The {@code requiredOutputs} list passed to {@code saveShardedOptimized} contains
     * the trainable parameter variable names so the embedding tensors are always included
     * in the pruned archive regardless of which output the deployer needs at inference time.
     */
    @Override
    public void saveEmbeddings(Path outputPath) {
        if (!trained || sd == null) {
            throw new IllegalStateException("Model must be trained before saving");
        }
        Path sdzPath   = sdzPathFor(outputPath);
        Path vocabPath = Path.of(sdzPath + ".vocab.json");

        try {
            sd.saveShardedOptimized(sdzPath.toFile(), /*saveUpdaterState*/ false, paramVarNames());
            log.info("[SameDiff-KGE] Saved .sdz to {}", sdzPath);

            Map<String, Object> vocab = new LinkedHashMap<>();
            vocab.put("algorithm",      algorithm.name());
            vocab.put("embeddingDim",   embeddingDim);
            vocab.put("entityToIndex",  entityToIndex);
            vocab.put("indexToEntity",  indexToEntity);
            vocab.put("relationToIndex", relationToIndex);
            vocab.put("indexToRelation", indexToRelation);
            Files.writeString(vocabPath,
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(vocab),
                    StandardCharsets.UTF_8);
            log.info("[SameDiff-KGE] Saved vocab sidecar to {}", vocabPath);

        } catch (IOException e) {
            throw new UncheckedIOException("SameDiffKgeModel.saveEmbeddings failed", e);
        }
    }

    /** Loads from a {@code .sdz} archive + companion {@code .vocab.json} sidecar. */
    @Override
    @SuppressWarnings("unchecked")
    public void loadEmbeddings(Path inputPath) {
        Path sdzPath   = sdzPathFor(inputPath);
        Path vocabPath = Path.of(sdzPath + ".vocab.json");
        try {
            sd = SameDiff.load(sdzPath.toFile(), /*loadUpdaterState*/ false);
            log.info("[SameDiff-KGE] Loaded .sdz from {}", sdzPath);

            Map<String, Object> vocab = new ObjectMapper().readValue(
                    Files.readString(vocabPath, StandardCharsets.UTF_8), Map.class);
            embeddingDim     = (int) vocab.get("embeddingDim");
            entityToIndex    = new HashMap<>((Map<String, Integer>) vocab.get("entityToIndex"));
            indexToEntity    = new ArrayList<>((List<String>)       vocab.get("indexToEntity"));
            relationToIndex  = new HashMap<>((Map<String, Integer>) vocab.get("relationToIndex"));
            indexToRelation  = new ArrayList<>((List<String>)       vocab.get("indexToRelation"));
            trained = true;

        } catch (IOException e) {
            throw new UncheckedIOException("SameDiffKgeModel.loadEmbeddings failed", e);
        }
    }

    private static Path sdzPathFor(Path path) {
        String s = path.toString();
        return s.endsWith(".sdz") ? path : Path.of(s + ".sdz");
    }

    // ─── Warm-start import (called before train()) ────────────────────────────

    /**
     * Stores entity embeddings for warm-start seeding in the next {@link #train()} call.
     * <p>RotatE: each vector must be concatenated {@code [real|imag]} (2×dim columns)
     * as produced by {@link RotatEModel#getEntityEmbedding(String)}.
     * <p>TransE: each vector is plain {@code dim} columns.
     */
    @Override
    public void importEntityEmbeddings(Map<String, INDArray> entityEmbeddings) {
        if (entityEmbeddings == null || entityEmbeddings.isEmpty()) return;
        pendingEntityEmbeddings.clear();
        pendingEntityEmbeddings.putAll(entityEmbeddings);
        pendingEntityIndex.clear();
        int idx = 0;
        for (String entity : entityEmbeddings.keySet()) {
            pendingEntityIndex.put(entity, idx++);
            if (!entityToIndex.containsKey(entity)) {
                entityToIndex.put(entity, entityToIndex.size());
                indexToEntity.add(entity);
            }
        }
        trained = true; // allows getEntityEmbedding() before the first train() call
    }

    /** Stores relation embeddings for warm-start seeding in the next {@link #train()} call. */
    @Override
    public void importRelationEmbeddings(Map<String, INDArray> relationEmbeddings) {
        if (relationEmbeddings == null || relationEmbeddings.isEmpty()) return;
        pendingRelationEmbeddings.clear();
        pendingRelationEmbeddings.putAll(relationEmbeddings);
        pendingRelationIndex.clear();
        int idx = 0;
        for (String rel : relationEmbeddings.keySet()) {
            pendingRelationIndex.put(rel, idx++);
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void close() {
        sd = null; // SameDiff manages its own native memory; GC will clean up
    }
}
