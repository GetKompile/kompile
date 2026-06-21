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
package ai.kompile.graph.reasoning.embedding.learn;

import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Reusable SameDiff-backed mini-batch training substrate for skip-gram-with-negative-sampling
 * (SGNS) node embedding learning.
 *
 * <h3>Purpose and RotatE reuse</h3>
 * <p>This class is intentionally kept general so a future RotatE learner can reuse the core
 * machinery without modification:</p>
 * <ul>
 *   <li>The two trainable matrices ({@code entityW}, {@code contextW}) and the SameDiff graph
 *       are the only training-loop state; a RotatE learner would add a third relation matrix
 *       and swap in a TransE/RotatE distance loss.</li>
 *   <li>{@link #entityMatrix()} and {@link #entityRow(int)} expose the learned weights as plain
 *       {@code double[][]} / {@code double[]} for downstream consumption — no ND4J at call sites.</li>
 *   <li>The manual SGD update ({@code arr.subi(grad.mul(lr))}) can be replaced with Adam or
 *       any IUpdater in a RotatE subclass without touching graph-construction code.</li>
 * </ul>
 *
 * <h3>Mini-batch SameDiff graph</h3>
 * <p>Constructed once at instantiation for a fixed batch size {@code B} and negative-sample
 * count {@code K}. Trainable variables:</p>
 * <ul>
 *   <li>{@code entityW}  — {@code [n, d]} DOUBLE variable (entity / target embeddings)</li>
 *   <li>{@code contextW} — {@code [n, d]} DOUBLE variable (context / output embeddings)</li>
 * </ul>
 * <p>Placeholders (re-bound each call to {@link #fitBatch}):</p>
 * <ul>
 *   <li>{@code centerIdx} — {@code [B]} INT64, center node indices</li>
 *   <li>{@code posIdx}    — {@code [B]} INT64, positive context indices</li>
 *   <li>{@code negIdx}    — {@code [B*K]} INT64, K negatives per sample (row-major)</li>
 * </ul>
 * <p>Loss (batched SGNS objective, summed over the B pairs in the batch):</p>
 * <pre>
 *   eC    = gather(entityW,  centerIdx, axis=0)   → [B, d]
 *   cP    = gather(contextW, posIdx,    axis=0)   → [B, d]
 *   cN    = gather(contextW, negIdx,    axis=0)   → [B*K, d], reshaped to [B, K, d]
 *
 *   scorePos = sum(eC * cP, axis=1)              → [B]
 *   scoreNeg = matmul(cN, eC[:,:,None])          → [B, K, 1] → [B, K]
 *
 *   loss = -sum(logSigmoid(scorePos)) - sum(logSigmoid(-scoreNeg))
 * </pre>
 *
 * <p>Gradients are computed via SameDiff autodiff ({@link SameDiff#calculateGradients}).
 * SGD update: {@code matrix -= lr * ∂loss/∂matrix}. Because only the gathered rows have
 * non-zero gradients, the update is sparse in effect even though the full gradient matrix
 * is materialized.</p>
 *
 * <h3>Reproducibility note</h3>
 * <p>ND4J's internal floating-point reduction order may differ from a pure Java {@code double[]}
 * loop, causing sub-ULP differences that accumulate across training steps. As a result,
 * bit-exact element-wise identity with a plain-Java trainer is not achievable, but two
 * independent runs through this class with the same seed <em>are</em> deterministic.
 * Tests therefore use an approximate cosine-based structural assertion.</p>
 *
 * @see Node2VecLearner
 */
public final class SameDiffEmbeddingTrainer {

    // ── Variable / placeholder names ──────────────────────────────────────────
    private static final String ENTITY_W   = "entityW";
    private static final String CONTEXT_W  = "contextW";
    private static final String CENTER_IDX = "centerIdx";
    private static final String POS_IDX    = "posIdx";
    private static final String NEG_IDX    = "negIdx";
    private static final String LOSS       = "loss";

    /** Default mini-batch size (number of (center, pos, neg[K]) triples per SameDiff call). */
    static final int DEFAULT_BATCH_SIZE = 64;

    // ── Fields ────────────────────────────────────────────────────────────────
    /** Pre-built SameDiff graph for the full {@code batchSize} pairs. */
    private final SameDiff sd;
    /**
     * Pre-built SameDiff graph for a single pair (B=1). Used by {@link #fitPair} so that
     * per-pair gradient computation never over-scales due to padding.
     */
    private final SameDiff sdSingle;

    private final int      n;              // number of entities
    private final int      d;             // embedding dimension
    private final int      k;             // negatives per positive pair
    private final int      batchSize;     // B — pairs per SameDiff call
    private final double   learningRate;

    /** Entity (target) embedding matrix [n, d]. Updated in-place by SGD. */
    private final INDArray entityArr;
    /** Context (output) embedding matrix [n, d]. Updated in-place by SGD. */
    private final INDArray contextArr;

    // Pending pair buffers (flushed every batchSize entries)
    private final int[]    pendingCenter;
    private final int[]    pendingPos;
    private final int[]    pendingNeg;    // length = batchSize * k (row-major)
    private int            pendingCount = 0;

    /**
     * Construct and initialise the trainer using the default batch size
     * ({@value #DEFAULT_BATCH_SIZE}).
     *
     * @param entityIds    ordered list of entity identifiers (defines row→id mapping)
     * @param dim          embedding dimension {@code d}
     * @param negSamples   number of negative samples {@code K} per positive pair
     * @param learningRate SGD step size
     * @param seed         RNG seed; applied to both ND4J global random and local Java RNG
     */
    public SameDiffEmbeddingTrainer(
            List<String> entityIds,
            int dim,
            int negSamples,
            double learningRate,
            long seed) {
        this(entityIds, dim, negSamples, learningRate, seed, DEFAULT_BATCH_SIZE);
    }

    /**
     * Construct and initialise the trainer with a custom batch size.
     *
     * <p>A larger batch size amortises the SameDiff graph-execution overhead but requires
     * more memory. For graphs with thousands of nodes and many epochs, {@code batchSize=128}
     * or {@code 256} is recommended. The default ({@value #DEFAULT_BATCH_SIZE}) is a
     * conservative choice that works for small graphs used in tests.</p>
     *
     * @param entityIds    ordered list of entity identifiers
     * @param dim          embedding dimension
     * @param negSamples   negatives per positive pair
     * @param learningRate SGD step size
     * @param seed         RNG seed
     * @param batchSize    number of pairs per SameDiff forward/backward pass
     */
    public SameDiffEmbeddingTrainer(
            List<String> entityIds,
            int dim,
            int negSamples,
            double learningRate,
            long seed,
            int batchSize) {

        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0, got " + batchSize);
        }
        this.n            = entityIds.size();
        this.d            = dim;
        this.k            = negSamples;
        this.batchSize    = batchSize;
        this.learningRate = learningRate;

        // Seed ND4J global random for deterministic op execution.
        Nd4j.getRandom().setSeed(seed);

        // Initialise entity and context matrices in (-0.5/d, +0.5/d), matching word2vec.
        Random javaRng = new Random(seed);
        double range   = 0.5 / dim;
        double[][] eInit = new double[n][dim];
        double[][] cInit = new double[n][dim];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < dim; j++) {
                eInit[i][j] = (javaRng.nextDouble() - 0.5) * 2.0 * range;
                cInit[i][j] = (javaRng.nextDouble() - 0.5) * 2.0 * range;
            }
        }
        entityArr  = Nd4j.create(eInit).castTo(DataType.DOUBLE);
        contextArr = Nd4j.create(cInit).castTo(DataType.DOUBLE);

        // Pre-allocate pending buffers.
        pendingCenter = new int[batchSize];
        pendingPos    = new int[batchSize];
        pendingNeg    = new int[batchSize * k];

        // Build the static SameDiff computation graphs.
        sd       = buildGraph(batchSize, k);
        sdSingle = buildGraph(1, k);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public training API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Queue one (center, positive-context, negative[K]) training pair.
     *
     * <p>Pairs are accumulated internally and a SameDiff forward/backward pass is triggered
     * automatically when the internal buffer reaches {@code batchSize} entries. Call
     * {@link #flushBatch()} after the last training pair of an epoch to process any
     * remaining buffered pairs (the last partial batch).</p>
     *
     * @param centerIdx index of the center entity
     * @param posIdx    index of the positive context entity
     * @param negIdxs   indices of exactly {@code negSamples} negative entities
     */
    public void queuePair(int centerIdx, int posIdx, int[] negIdxs) {
        pendingCenter[pendingCount] = centerIdx;
        pendingPos[pendingCount]    = posIdx;
        System.arraycopy(negIdxs, 0, pendingNeg, pendingCount * k, k);
        pendingCount++;
        if (pendingCount == batchSize) {
            executeAndApply(batchSize);
            pendingCount = 0;
        }
    }

    /**
     * Flush any remaining buffered pairs (the final partial batch of an epoch).
     *
     * <p>Must be called once after the last {@link #queuePair} of each epoch to ensure all
     * pairs are processed.</p>
     *
     * @return total loss for the flushed pairs, or {@code 0.0} if no pairs were pending
     */
    public double flushBatch() {
        if (pendingCount == 0) {
            return 0.0;
        }
        double loss = executeAndApply(pendingCount);
        pendingCount = 0;
        return loss;
    }

    /**
     * Convenience method: process a single (center, pos, neg[K]) pair immediately,
     * bypassing the internal batch buffer. Useful for direct testing of the trainer.
     *
     * <p>Note: mixing {@code fitPair} and {@code queuePair} on the same trainer is safe
     * but will process pairs out of the order they were submitted.</p>
     *
     * @param centerIdx index of the center entity
     * @param posIdx    index of the positive context entity
     * @param negIdxs   indices of exactly {@code negSamples} negative entities
     * @return the scalar SGNS loss for this pair
     */
    public double fitPair(int centerIdx, int posIdx, int[] negIdxs) {
        // Use the single-pair graph (B=1) to avoid scaling the gradient by batchSize.
        long[] centerLong = new long[]{ centerIdx };
        long[] posLong    = new long[]{ posIdx };
        long[] negLong    = new long[k];
        for (int ki = 0; ki < k; ki++) {
            negLong[ki] = negIdxs[ki];
        }
        return executeSingle(centerLong, posLong, negLong);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Readout
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Return the current entity embedding matrix as a {@code double[][]}, row {@code i} is the
     * embedding for entity at index {@code i}.
     *
     * <p>The returned array is a snapshot copy (not the live INDArray) so it is safe
     * to use after further training steps.</p>
     */
    public double[][] entityMatrix() {
        return entityArr.toDoubleMatrix();
    }

    /**
     * Return row {@code i} of the entity embedding as a {@code double[]} copy.
     */
    public double[] entityRow(int i) {
        return entityArr.getRow(i).toDoubleVector();
    }

    /** Number of entities (rows in both matrices). */
    public int numEntities() {
        return n;
    }

    /** Embedding dimension. */
    public int dim() {
        return d;
    }

    /** Number of negative samples per positive pair. */
    public int negSamples() {
        return k;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence: save / load
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Persist this trainer to {@code dir} so it can be exactly restored later.
     *
     * <p>Two files are written:</p>
     * <ul>
     *   <li>{@code model.fb} — the batch SameDiff graph + live {@code entityW} / {@code contextW}
     *       values, serialised via {@link SameDiff#save(java.io.File, boolean)}
     *       (FlatBuffers; {@code saveUpdaterState=true}).</li>
     *   <li>{@code mapping.json} — hand-rolled JSON sidecar with the {@code entityId ↔ index}
     *       mapping and scalar metadata ({@code dim}, {@code kind="sgns"}).</li>
     * </ul>
     *
     * <p>SGD has no moment arrays; only the weight matrices need to survive.</p>
     *
     * @param entityIds the entity-id list used to construct this trainer (insertion order = row order)
     * @param dir       directory to write checkpoint files into (created if absent)
     */
    public void save(List<String> entityIds, Path dir) {
        try {
            Files.createDirectories(dir);
            // Sync live arrays into the graph before saving
            sd.associateArrayWithVariable(entityArr,  ENTITY_W);
            sd.associateArrayWithVariable(contextArr, CONTEXT_W);
            sd.save(dir.resolve(SameDiffModelIO.MODEL_FILE).toFile(), true);
            String mappingJson = SameDiffModelIO.buildMappingJson("sgns", entityIds, null, d);
            Files.writeString(dir.resolve(SameDiffModelIO.MAPPING_FILE), mappingJson,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("SameDiffEmbeddingTrainer.save failed", e);
        }
    }

    /**
     * Restore a previously saved SGNS trainer from {@code dir}.
     *
     * <p>Returns a {@link SameDiffModelIO.LoadedSgns} containing the entity-id list, embedding
     * dimension, entity matrix, and context matrix. The caller can use this to resume training
     * (by constructing a new trainer and calling {@code associateArrayWithVariable} with the
     * restored arrays) or to extract the learned embeddings for downstream use.</p>
     *
     * @param dir directory containing the checkpoint files written by {@link #save}
     * @return restored SGNS snapshot
     */
    public static SameDiffModelIO.LoadedSgns load(Path dir) {
        return SameDiffModelIO.loadSgns(dir);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal batch execution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Execute the SameDiff graph for the first {@code count} entries in the pending buffers,
     * apply the SGD update, and return the scalar loss.
     *
     * <p>If {@code count < batchSize} (a partial final batch), the remaining slots are padded
     * by repeating the last real entry. Padding ensures we always use the single pre-built
     * graph (fixed shape {@code [batchSize]}), making both forward and backward passes fully
     * deterministic across independent runs.</p>
     */
    private double executeAndApply(int count) {
        int fillFrom = (count > 0) ? count - 1 : 0;
        long[] centerLong = new long[batchSize];
        long[] posLong    = new long[batchSize];
        long[] negLong    = new long[batchSize * k];
        for (int i = 0; i < batchSize; i++) {
            int src = (i < count) ? i : fillFrom;
            centerLong[i] = pendingCenter[src];
            posLong[i]    = pendingPos[src];
            for (int ki = 0; ki < k; ki++) {
                negLong[i * k + ki] = pendingNeg[src * k + ki];
            }
        }
        return executeDirectly(centerLong, posLong, negLong);
    }

    /**
     * Execute the pre-built SameDiff batch graph with the given full-size index arrays,
     * apply the SGD update, and return the scalar loss.
     *
     * @param centerLong center entity indices, length == batchSize
     * @param posLong    positive context indices, length == batchSize
     * @param negLong    negative entity indices, length == batchSize * k
     */
    private double executeDirectly(long[] centerLong, long[] posLong, long[] negLong) {
        return executeOn(sd, centerLong, posLong, negLong);
    }

    /**
     * Execute the pre-built single-pair SameDiff graph (B=1) with the given index arrays,
     * apply the SGD update, and return the scalar loss.
     *
     * @param centerLong center entity indices, length == 1
     * @param posLong    positive context indices, length == 1
     * @param negLong    negative entity indices, length == k
     */
    private double executeSingle(long[] centerLong, long[] posLong, long[] negLong) {
        return executeOn(sdSingle, centerLong, posLong, negLong);
    }

    /**
     * Execute the given SameDiff graph with the provided index arrays,
     * apply SGD, and return loss.
     */
    private double executeOn(SameDiff graph, long[] centerLong, long[] posLong, long[] negLong) {
        // Bind placeholder values.
        Map<String, INDArray> placeholders = new java.util.HashMap<>(4);
        placeholders.put(CENTER_IDX, Nd4j.createFromArray(centerLong));
        placeholders.put(POS_IDX,    Nd4j.createFromArray(posLong));
        placeholders.put(NEG_IDX,    Nd4j.createFromArray(negLong));

        // Associate live weight arrays with the graph variables.
        graph.associateArrayWithVariable(entityArr,  ENTITY_W);
        graph.associateArrayWithVariable(contextArr, CONTEXT_W);

        // Forward pass for loss value.
        INDArray lossArr = graph.outputSingle(placeholders, LOSS);
        double   lossVal = lossArr != null ? lossArr.getDouble(0) : Double.NaN;

        // Backward pass: gradient of loss w.r.t. trainable variables.
        Map<String, INDArray> grads = graph.calculateGradients(
                placeholders, ENTITY_W, CONTEXT_W);

        // SGD update: matrix -= lr * gradient
        INDArray gEntity  = grads.get(ENTITY_W);
        INDArray gContext = grads.get(CONTEXT_W);
        if (gEntity  != null) {
            entityArr.subi(gEntity.castTo(DataType.DOUBLE).mul(learningRate));
        }
        if (gContext != null) {
            contextArr.subi(gContext.castTo(DataType.DOUBLE).mul(learningRate));
        }

        return lossVal;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Graph construction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a SameDiff SGNS computation graph for a batch of {@code B} pairs with
     * {@code K} negatives each.
     *
     * <p>This is the natural extension point for a RotatE learner: replace the
     * logSigmoid SGNS objective with a TransE/RotatE margin-based loss and add a
     * relation-embedding variable. The placeholder-bind → calculateGradients → SGD-apply
     * pattern is identical.</p>
     *
     * @param B number of (center, pos, neg[K]) pairs in the batch
     * @param K number of negatives per positive pair
     */
    private SameDiff buildGraph(int B, int K) {
        SameDiff graph = SameDiff.create();

        // ── Trainable variables ───────────────────────────────────────────────
        SDVariable entityVar  = graph.var(ENTITY_W,  entityArr.dup());
        SDVariable contextVar = graph.var(CONTEXT_W, contextArr.dup());

        // ── Index placeholders ────────────────────────────────────────────────
        SDVariable centerIdxVar = graph.placeHolder(CENTER_IDX, DataType.INT64, B);
        SDVariable posIdxVar    = graph.placeHolder(POS_IDX,    DataType.INT64, B);
        SDVariable negIdxVar    = graph.placeHolder(NEG_IDX,    DataType.INT64, (long) B * K);

        // ── Gather embedding rows ─────────────────────────────────────────────
        // eC:  [B, d]   — entity (target) vectors for center nodes
        // cP:  [B, d]   — context vectors for positive neighbors
        // cNflat: [B*K, d] — context vectors for all negatives (flattened batch)
        SDVariable eC     = graph.gather("eC",     entityVar,  centerIdxVar, 0);  // [B, d]
        SDVariable cP     = graph.gather("cP",     contextVar, posIdxVar,    0);  // [B, d]
        SDVariable cNflat = graph.gather("cNflat", contextVar, negIdxVar,    0);  // [B*K, d]

        // ── Positive scores: sum(eC * cP, axis=1) → [B] ─────────────────────
        SDVariable prodPos  = eC.mul("prodPos", cP);                    // [B, d]
        SDVariable scorePos = prodPos.sum("scorePos", 1);               // [B], keepDims=false

        // ── Negative scores ───────────────────────────────────────────────────
        // Strategy: broadcast-elementwise then reduce.
        //   cNflat [B*K, d] → cN [B, K, d]
        //   eC [B, d] → eCexp [B, 1, d]    (broadcast over K axis)
        //   product [B, K, d], sum(axis=2) → sNK [B, K]
        SDVariable cN    = graph.reshape("cN",    cNflat, (long) B, (long) K, (long) d);
        SDVariable eCexp = graph.reshape("eCexp", eC,     (long) B, 1L,       (long) d);
        SDVariable prodN = cN.mul("prodN", eCexp);           // [B, K, d]
        SDVariable sNK   = prodN.sum("sNK", 2);              // [B, K]

        // ── SGNS loss ─────────────────────────────────────────────────────────
        // loss = -sum(logSigmoid(scorePos)) - sum(logSigmoid(-sNK))
        SDVariable logSigPos = graph.nn().logSigmoid("logSigPos", scorePos);      // [B]
        SDVariable negSNK    = sNK.neg("negSNK");                                 // [B, K]
        SDVariable logSigNeg = graph.nn().logSigmoid("logSigNeg", negSNK);       // [B, K]

        SDVariable sumPos  = logSigPos.sum("sumPos");    // scalar
        SDVariable sumNeg  = logSigNeg.sum("sumNeg");    // scalar

        // loss = -sumPos - sumNeg
        SDVariable negSumPos = sumPos.neg("negSumPos");
        graph.math().sub(LOSS, negSumPos, sumNeg);       // scalar loss

        graph.setLossVariables(LOSS);
        return graph;
    }
}
