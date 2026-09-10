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
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.GradientUpdater;
import org.nd4j.linalg.learning.config.Adam;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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
public final class SameDiffEmbeddingTrainer implements AutoCloseable {

    // ── Variable / placeholder names ──────────────────────────────────────────
    private static final String ENTITY_W   = "entityW";
    private static final String CONTEXT_W  = "contextW";
    private static final String CENTER_IDX = "centerIdx";
    private static final String POS_IDX    = "posIdx";
    private static final String NEG_IDX    = "negIdx";
    private static final String LOSS       = "loss";

    // ── Adam hyper-parameters ─────────────────────────────────────────────────
    private static final double ADAM_BETA1 = 0.9;
    private static final double ADAM_BETA2 = 0.999;
    private static final double ADAM_EPS   = 1e-8;

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

    /** Entity (target) embedding matrix [n, d]. Updated in-place by Adam. */
    private final INDArray entityArr;
    /** Context (output) embedding matrix [n, d]. Updated in-place by Adam. */
    private final INDArray contextArr;

    /** Root storage for the first/second Adam moments of {@link #entityArr}. */
    private final INDArray adamEntityState;
    /** Root storage for the first/second Adam moments of {@link #contextArr}. */
    private final INDArray adamContextState;

    // ── Adam updaters (one per parameter matrix) ──────────────────────────────
    /** Adam updater carrying first/second-moment state for {@code entityArr}. */
    private final GradientUpdater<Adam> adamEntity;
    /** Adam updater carrying first/second-moment state for {@code contextArr}. */
    private final GradientUpdater<Adam> adamContext;
    /** 0-based Adam step counter; passed to the updater for bias-correction. */
    private int adamIteration = 0;

    // Pending pair buffers (flushed every batchSize entries)
    private final int[]    pendingCenter;
    private final int[]    pendingPos;
    private final int[]    pendingNeg;    // length = batchSize * k (row-major)
    private int            pendingCount = 0;

    /** Set once teardown starts; all subsequent training/readout operations are rejected. */
    private volatile boolean closed;
    /** Set only after every graph and dedicated state array has been released successfully. */
    private boolean resourcesClosed;

    /** Package-private test seam for counting caller-owned SameDiff results released per step. */
    private static final AtomicLong EXECUTION_RESULT_CLOSES = new AtomicLong();

    static void resetExecutionResultCloseCountForTests() {
        EXECUTION_RESULT_CLOSES.set(0L);
    }

    static long executionResultCloseCountForTests() {
        return EXECUTION_RESULT_CLOSES.get();
    }

    interface ArrayAllocatorForTests {
        INDArray create(double[][] values);
        INDArray zeros(DataType dataType, long rows, long columns);
        INDArray createFromArray(long[] values);
    }

    private static final ArrayAllocatorForTests DEFAULT_ARRAY_ALLOCATOR = new ArrayAllocatorForTests() {
        @Override
        public INDArray create(double[][] values) {
            return Nd4j.create(values);
        }

        @Override
        public INDArray zeros(DataType dataType, long rows, long columns) {
            return Nd4j.zeros(dataType, rows, columns);
        }

        @Override
        public INDArray createFromArray(long[] values) {
            return Nd4j.createFromArray(values);
        }
    };

    private static volatile ArrayAllocatorForTests arrayAllocator = DEFAULT_ARRAY_ALLOCATOR;

    static void setArrayAllocatorForTests(ArrayAllocatorForTests allocator) {
        arrayAllocator = allocator == null ? DEFAULT_ARRAY_ALLOCATOR : allocator;
    }

    static void resetArrayAllocatorForTests() {
        arrayAllocator = DEFAULT_ARRAY_ALLOCATOR;
    }

    /**
     * Construct and initialise the trainer using the default batch size
     * ({@value #DEFAULT_BATCH_SIZE}).
     *
     * @param entityIds    ordered list of entity identifiers (defines row→id mapping)
     * @param dim          embedding dimension {@code d}
     * @param negSamples   number of negative samples {@code K} per positive pair
     * @param learningRate Adam learning rate
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
     * @param learningRate Adam learning rate
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

        INDArray entityRoot = null;
        INDArray contextRoot = null;
        INDArray entityState = null;
        INDArray contextState = null;
        GradientUpdater<Adam> entityUpdater = null;
        GradientUpdater<Adam> contextUpdater = null;
        SameDiff graph = null;
        SameDiff singleGraph = null;
        int[] pendingCenterBuffer = null;
        int[] pendingPosBuffer = null;
        int[] pendingNegBuffer = null;
        try {
            entityRoot = createOwnedDoubleMatrix(eInit);
            contextRoot = createOwnedDoubleMatrix(cInit);

            // Initialise one Adam updater per parameter matrix.
            // State length = 2 × (n × d): the updater splits it into the first- and second-moment halves.
            Adam adamConfig = new Adam(learningRate, ADAM_BETA1, ADAM_BETA2, ADAM_EPS);
            entityState  = arrayAllocator.zeros(DataType.DOUBLE, 1, 2L * n * d);
            contextState = arrayAllocator.zeros(DataType.DOUBLE, 1, 2L * n * d);
            entityUpdater  = newAdamUpdater(adamConfig, entityState);
            contextUpdater = newAdamUpdater(adamConfig, contextState);

            // Pre-allocate pending buffers.
            pendingCenterBuffer = new int[batchSize];
            pendingPosBuffer    = new int[batchSize];
            pendingNegBuffer    = new int[batchSize * k];

            // Build the static SameDiff computation graphs only after all owned roots exist.
            graph       = buildGraph(batchSize, k, entityRoot, contextRoot);
            singleGraph = buildGraph(1, k, entityRoot, contextRoot);
        } catch (RuntimeException | Error e) {
            RuntimeException cleanupFailure = null;
            cleanupFailure = appendFailure(cleanupFailure, closeGraphSafely(graph));
            cleanupFailure = appendFailure(cleanupFailure, closeGraphSafely(singleGraph));
            cleanupFailure = appendFailure(cleanupFailure, closeOwnedArraySafely(entityRoot));
            cleanupFailure = appendFailure(cleanupFailure, closeOwnedArraySafely(contextRoot));
            cleanupFailure = appendFailure(cleanupFailure, closeOwnedArraySafely(entityState));
            cleanupFailure = appendFailure(cleanupFailure, closeOwnedArraySafely(contextState));
            if (cleanupFailure != null) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }

        // Publish fields only after the complete resource set has been constructed.  A failure
        // above therefore cannot leave a partially initialised trainer without cleanup.
        entityArr = entityRoot;
        contextArr = contextRoot;
        adamEntityState = entityState;
        adamContextState = contextState;
        adamEntity = entityUpdater;
        adamContext = contextUpdater;
        pendingCenter = pendingCenterBuffer;
        pendingPos = pendingPosBuffer;
        pendingNeg = pendingNegBuffer;
        sd = graph;
        sdSingle = singleGraph;
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
        ensureOpen();
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
        ensureOpen();
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
        ensureOpen();
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
        ensureOpen();
        return entityArr.toDoubleMatrix();
    }

    /**
     * Return row {@code i} of the entity embedding as a {@code double[]} copy.
     */
    public double[] entityRow(int i) {
        ensureOpen();
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

    /**
     * Release the SameDiff graphs, their shared trainable arrays, and the dedicated Adam state.
     *
     * <p>The two graphs intentionally share {@link #entityArr} and {@link #contextArr}; those
     * arrays are released by the graph owners first; a guarded fallback verifies and releases
     * either root only if the local SameDiff close path left it open.  The SameDiff close
     * implementation is identity-aware and skips an already released shared buffer when the
     * second graph is closed.  Adam's root state arrays are not graph-owned and are released
     * explicitly.</p>
     *
     * <p>Teardown is deterministic and idempotent.  The returned {@link EmbeddingTable} remains
     * usable because it contains Java copies made before this method is called.</p>
     */
    @Override
    public synchronized void close() {
        if (resourcesClosed) {
            return;
        }
        closed = true;

        RuntimeException failure = null;
        boolean graphsClosed = true;
        try {
            closeGraphAndFunctions(sd);
        } catch (RuntimeException e) {
            graphsClosed = false;
            failure = e;
        }
        try {
            closeGraphAndFunctions(sdSingle);
        } catch (RuntimeException e) {
            graphsClosed = false;
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (graphsClosed) {
            // SameDiff normally releases these graph-owned roots.  Keep a guarded fallback for
            // the local close implementation's logged-but-swallowed buffer-close failures.
            try {
                closeOwnedArray(entityArr);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            try {
                closeOwnedArray(contextArr);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        try {
            closeOwnedArray(adamEntityState);
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            closeOwnedArray(adamContextState);
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
        resourcesClosed = true;
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
        ensureOpen();
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
     * apply the Adam update, and return the scalar loss.
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
     * apply the Adam update, and return the scalar loss.
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
     * apply the Adam update, and return the scalar loss.
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
     * apply Adam, and return loss.
     */
    private double executeOn(SameDiff graph, long[] centerLong, long[] posLong, long[] negLong) {
        // Bind placeholder values inside the protected region.  If a later allocation fails,
        // finally still sees and releases every earlier placeholder root.
        INDArray centerArray = null;
        INDArray posArray = null;
        INDArray negArray = null;
        INDArray lossArr = null;
        Map<String, INDArray> grads = null;
        Throwable operationFailure = null;
        try {
            centerArray = arrayAllocator.createFromArray(centerLong);
            posArray = arrayAllocator.createFromArray(posLong);
            negArray = arrayAllocator.createFromArray(negLong);
            Map<String, INDArray> placeholders = new HashMap<>(4);
            placeholders.put(CENTER_IDX, centerArray);
            placeholders.put(POS_IDX,    posArray);
            placeholders.put(NEG_IDX,    negArray);

            // Associate live weight arrays with the graph variables.
            graph.associateArrayWithVariable(entityArr,  ENTITY_W);
            graph.associateArrayWithVariable(contextArr, CONTEXT_W);

            // outputSingle returns an independent caller-owned output copy on the standard
            // SameDiff execution path; release it after reading the scalar loss.
            lossArr = graph.outputSingle(placeholders, LOSS);
            double lossVal = lossArr != null ? lossArr.getDouble(0) : Double.NaN;

            // calculateGradients returns caller-owned gradient output copies.  They remain live
            // through both Adam updates and are released in the finally block below.
            grads = graph.calculateGradients(placeholders, ENTITY_W, CONTEXT_W);

            // Adam update: one step per parameter matrix.
            INDArray gEntity  = grads.get(ENTITY_W);
            INDArray gContext = grads.get(CONTEXT_W);
            applyAdam(adamEntity,  entityArr,  gEntity,  adamIteration);
            applyAdam(adamContext, contextArr, gContext, adamIteration);
            adamIteration++;

            return lossVal;
        } catch (RuntimeException | Error e) {
            operationFailure = e;
            throw e;
        } finally {
            cleanupPlaceholders(graph, centerArray, posArray, negArray,
                    lossArr, grads, operationFailure,
                    entityArr, contextArr, adamEntityState, adamContextState);
        }
    }

    private static void cleanupPlaceholders(SameDiff graph,
                                            INDArray centerArray,
                                            INDArray posArray,
                                            INDArray negArray,
                                            INDArray lossArray,
                                            Map<String, INDArray> gradients,
                                            Throwable operationFailure,
                                            INDArray... protectedRoots) {
        RuntimeException cleanupFailure = closeExecutionResults(
                lossArray, gradients, null,
                concatProtectedRoots(protectedRoots, centerArray, posArray, negArray));
        try {
            graph.clearPlaceholders(false);
        } catch (RuntimeException e) {
            cleanupFailure = appendFailure(cleanupFailure, e);
        }
        cleanupFailure = appendFailure(cleanupFailure, closeOwnedArraySafely(centerArray));
        cleanupFailure = appendFailure(cleanupFailure, closeOwnedArraySafely(posArray));
        cleanupFailure = appendFailure(cleanupFailure, closeOwnedArraySafely(negArray));
        if (cleanupFailure != null) {
            if (operationFailure != null) {
                operationFailure.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
        }
    }

    private static INDArray[] concatProtectedRoots(INDArray[] roots, INDArray... additional) {
        INDArray[] all = new INDArray[roots.length + additional.length];
        System.arraycopy(roots, 0, all, 0, roots.length);
        System.arraycopy(additional, 0, all, roots.length, additional.length);
        return all;
    }

    private static INDArray createOwnedDoubleMatrix(double[][] values) {
        INDArray source = null;
        try {
            source = arrayAllocator.create(values);
            INDArray result = source.castTo(DataType.DOUBLE);
            if (result != source) {
                closeOwnedArray(source);
            }
            return result;
        } catch (RuntimeException | Error e) {
            if (source != null) {
                try {
                    closeOwnedArray(source);
                } catch (RuntimeException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw e;
        }
    }

    /**
     * Release standard-path output/gradient copies without changing closeability or ownership of
     * views.  Identity and DataBuffer de-duplication protects aliases and shared parameter roots.
     */
    private static RuntimeException closeExecutionResults(
            INDArray lossArray,
            Map<String, INDArray> gradients,
            INDArray additionalResult,
            INDArray... protectedRoots) {
        Set<INDArray> protectedArrays = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<DataBuffer> protectedBuffers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (INDArray root : protectedRoots) {
            if (root == null || !protectedArrays.add(root)) {
                continue;
            }
            DataBuffer data = dataBufferOf(root);
            if (data != null) {
                protectedBuffers.add(data);
            }
        }

        Set<INDArray> seenArrays = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<DataBuffer> seenBuffers = Collections.newSetFromMap(new IdentityHashMap<>());
        RuntimeException failure = null;
        failure = appendFailure(failure, closeExecutionResult(lossArray,
                protectedArrays, protectedBuffers, seenArrays, seenBuffers));
        if (gradients != null) {
            for (INDArray gradient : gradients.values()) {
                failure = appendFailure(failure, closeExecutionResult(gradient,
                        protectedArrays, protectedBuffers, seenArrays, seenBuffers));
            }
        }
        failure = appendFailure(failure, closeExecutionResult(additionalResult,
                protectedArrays, protectedBuffers, seenArrays, seenBuffers));
        return failure;
    }

    private static RuntimeException closeExecutionResult(
            INDArray array,
            Set<INDArray> protectedArrays,
            Set<DataBuffer> protectedBuffers,
            Set<INDArray> seenArrays,
            Set<DataBuffer> seenBuffers) {
        if (array == null || protectedArrays.contains(array) || !seenArrays.add(array)
                || array.wasClosed()) {
            return null;
        }
        DataBuffer data = dataBufferOf(array);
        if (data != null && (data.wasClosed() || protectedBuffers.contains(data)
                || !seenBuffers.add(data))) {
            return null;
        }
        // Returned outputs are already caller-owned.  Do not force-close a borrowed view.
        if (!array.closeable()) {
            return null;
        }
        try {
            array.close();
            if (array.wasClosed()) {
                EXECUTION_RESULT_CLOSES.incrementAndGet();
            }
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static DataBuffer dataBufferOf(INDArray array) {
        try {
            return array == null ? null : array.data();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static RuntimeException closeOwnedArraySafely(INDArray array) {
        try {
            closeOwnedArray(array);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static RuntimeException closeGraphSafely(SameDiff graph) {
        if (graph == null) {
            return null;
        }
        try {
            closeGraphAndFunctions(graph);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static RuntimeException appendFailure(RuntimeException first, RuntimeException next) {
        if (next == null) {
            return first;
        }
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Adam helpers (mirrors RotatELearner.newAdamUpdater / applyAdam)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Instantiate an ND4J Adam {@link GradientUpdater} over a fresh, zero-initialised state view.
     *
     * @param config the shared Adam hyper-parameter config
     * @param state  trainer-owned root state array; the updater keeps first/second-moment views
     * @return a ready Adam updater
     */
    @SuppressWarnings("unchecked")  // IUpdater#instantiate is declared with a raw GradientUpdater return
    private static GradientUpdater<Adam> newAdamUpdater(Adam config, INDArray state) {
        try {
            return config.instantiate(state, true);
        } catch (RuntimeException | Error e) {
            try {
                closeOwnedArray(state);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    /**
     * Apply one Adam step to a single parameter matrix: the {@link GradientUpdater} rewrites
     * {@code grad} in place with the bias-corrected update (advancing its own moment state),
     * then {@code param −= update}.
     *
     * @param updater   the parameter matrix's Adam updater (holds the moment state)
     * @param param     the parameter array to update in place
     * @param grad      gradient for this step; consumed in place ({@code null} ⇒ no-op)
     * @param iteration 0-based Adam step (the op uses {@code iteration+1} for bias correction)
     */
    private static void applyAdam(GradientUpdater<Adam> updater, INDArray param,
                                  INDArray grad, int iteration) {
        if (grad == null) {
            return;
        }
        // Match the DOUBLE state view (no-op when grad is already DOUBLE).
        INDArray update = grad.castTo(DataType.DOUBLE);
        try {
            updater.applyUpdater(update, iteration, 0);
            param.subi(update);
        } finally {
            // castTo returns the input for DOUBLE gradients, otherwise it creates an owned array.
            if (update != grad) {
                closeOwnedArray(update);
            }
        }
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
    private SameDiff buildGraph(int B, int K, INDArray entityRoot, INDArray contextRoot) {
        SameDiff graph = SameDiff.create();
        try {

        // ── Trainable variables ───────────────────────────────────────────────
        // Both graphs deliberately bind the trainer-owned arrays.  Duplicating here would leave
        // an untracked graph-initialisation matrix behind when associateArrayWithVariable rebinds
        // the live arrays on the first training step.
        SDVariable entityVar  = graph.var(ENTITY_W,  entityRoot);
        SDVariable contextVar = graph.var(CONTEXT_W, contextRoot);

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
        } catch (RuntimeException | Error e) {
            try {
                closeGraphAndFunctions(graph);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SameDiffEmbeddingTrainer is closed");
        }
    }

    /**
     * SameDiff keeps the backward graph as a separate SameDiff instance. Close it before the
     * parent graph so its execution session and graph-owned intermediates are not leaked.
     */
    private static void closeGraphAndFunctions(SameDiff graph) {
        RuntimeException failure = null;
        SameDiff gradient = null;
        try {
            gradient = graph.getFunction("grad");
        } catch (RuntimeException e) {
            failure = e;
        }
        if (gradient != null && gradient != graph) {
            try {
                gradient.close();
            } catch (RuntimeException e) {
                failure = appendFailure(failure, e);
            }
        }
        try {
            graph.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Close only arrays whose ownership is unambiguous and exclusive to this trainer. */
    private static void closeOwnedArray(INDArray array) {
        if (array == null || array.wasClosed()) {
            return;
        }
        if (!array.closeable()) {
            array.setCloseable(true);
        }
        if (!array.wasClosed()) {
            array.close();
        }
    }
}
