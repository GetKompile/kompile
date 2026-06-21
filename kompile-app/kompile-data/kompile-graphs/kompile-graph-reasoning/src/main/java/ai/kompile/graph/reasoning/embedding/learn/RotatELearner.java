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

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.GradientUpdater;
import org.nd4j.linalg.learning.config.Adam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * RotatE knowledge-graph embedding learner backed by ND4J SameDiff autodiff.
 *
 * <h3>Model</h3>
 * <p>RotatE (Sun et al., 2019) represents each entity as a complex vector in {@code C^d}:
 * a real part {@code h_re ∈ R^d} and an imaginary part {@code h_im ∈ R^d}. Each relation
 * is a phase vector {@code θ_r ∈ R^d}; the relation matrix is interpreted as a
 * per-dimension unit-modulus rotation {@code e^{iθ} = (cosθ, sinθ)} on the unit circle.
 * For a triple {@code (h, r, t)}, the rotated head is:</p>
 * <pre>
 *   re' = h_re · cosθ − h_im · sinθ
 *   im' = h_re · sinθ + h_im · cosθ
 * </pre>
 * <p>The distance is the L2 norm of the difference over both real and imaginary parts:</p>
 * <pre>
 *   d(h, r, t) = ||(re' − t_re, im' − t_im)||₂   (over 2·d components)
 * </pre>
 * <p>Lower distance = more plausible. This matches RotatE paper Eq. 1–2.</p>
 *
 * <h3>Loss</h3>
 * <p>Self-adversarial negative-sampling loss with <em>uniform</em> weights
 * (Sun et al. §3.1, simplified to {@code p_i = 1/K}):</p>
 * <pre>
 *   L = −log σ(γ − d_pos) − (1/K) · Σ_{i=1}^{K} log σ(d_neg_i − γ)
 * </pre>
 * <p>Uniform weights are used instead of the self-adversarial softmax
 * ({@code p_i = softmax(α·score_i)}) because the stop-gradient on the weights is
 * difficult to express in SameDiff, and uniform weights provide sufficient signal for
 * the true&gt;corrupt correctness gate.</p>
 *
 * <h3>Optimizer</h3>
 * <p>Adam (Kingma &amp; Ba, 2015) with {@code β₁=0.9}, {@code β₂=0.999}, {@code ε=1e-8}.
 * SameDiff autodiff computes the gradient; the Adam moment update is applied by ND4J's
 * fused {@link org.nd4j.linalg.learning.AdamUpdater} (one updater per parameter matrix).
 * Adam is used instead of SGD because RotatE distances start
 * far from the margin γ (random init), and SGD diverges under this condition while
 * Adam's adaptive step sizes handle the large initial gradients stably.</p>
 *
 * <h3>Initialisation</h3>
 * <ul>
 *   <li>Entity real/imaginary: uniform in {@code (−initScale, +initScale)} where
 *       {@code initScale = γ / (2·sqrt(dim))} — calibrated so the initial expected L2
 *       distance {@code ≈ γ / 2}, placing the model in a productive loss region.</li>
 *   <li>Relation phases: uniform in {@code (−π, π]}; not re-normalised during training.</li>
 *   <li>Entity moduli are NOT constrained to 1 (modulus carries information per paper §4.1).</li>
 * </ul>
 *
 * <h3>Numerical stability</h3>
 * <p>A small epsilon ({@code ε=1e-8}) is added inside the {@code sqrt} to avoid
 * infinite gradients at distance zero ({@code ∂√x/∂x = 1/(2√x) → ∞} when {@code x=0}).</p>
 *
 * <h3>SameDiff graph</h3>
 * <p>A single static SameDiff graph is pre-built for a fixed batch of {@code B} triples with
 * {@code K} negative corruptions each. Trainable variables:</p>
 * <ul>
 *   <li>{@code entityRe} — {@code [numEntities, dim]} DOUBLE (real parts)</li>
 *   <li>{@code entityIm} — {@code [numEntities, dim]} DOUBLE (imaginary parts)</li>
 *   <li>{@code relPhase} — {@code [numRelations, dim]} DOUBLE (phases θ)</li>
 * </ul>
 * <p>Gradients via {@link SameDiff#calculateGradients}; Adam update via ND4J
 * {@link org.nd4j.linalg.learning.AdamUpdater}.</p>
 *
 * <h3>Reproducibility</h3>
 * <p>ND4J SameDiff shares workspace/JIT state within a JVM, so per-element values may vary
 * slightly between sequential runs. The structural invariant (true triple scores lower distance
 * than corrupted triple) is preserved across runs. Both {@link Nd4j#getRandom()} and a local
 * {@link Random} are seeded from {@link RotatEConfig#seed()}.</p>
 *
 * <p>References:
 * <ul>
 *   <li>Sun, Z., Deng, Z.-H., Nie, J.-Y., Tang, J. (2019). RotatE: Knowledge Graph Embedding
 *       by Relational Rotation in Complex Space. ICLR 2019. https://arxiv.org/abs/1902.10197</li>
 *   <li>Kingma, D., Ba, J. (2015). Adam: A Method for Stochastic Optimization.
 *       ICLR 2015. https://arxiv.org/abs/1412.6980</li>
 * </ul>
 * </p>
 *
 * @see RotatEConfig
 * @see SameDiffEmbeddingTrainer
 */
public final class RotatELearner implements EmbeddingLearner {

    // ── SameDiff variable / placeholder names ─────────────────────────────────
    private static final String ENTITY_RE = "entityRe";
    private static final String ENTITY_IM = "entityIm";
    private static final String REL_PHASE  = "relPhase";
    private static final String HEAD_IDX   = "headIdx";
    private static final String REL_IDX    = "relIdx";
    private static final String TAIL_IDX   = "tailIdx";
    private static final String NEG_IDX    = "negIdx";
    private static final String LOSS       = "loss";

    // ── Adam hyper-parameters ─────────────────────────────────────────────────
    private static final double ADAM_BETA1   = 0.9;
    private static final double ADAM_BETA2   = 0.999;
    private static final double ADAM_EPS     = 1e-8;

    // ── Gradient stability ────────────────────────────────────────────────────
    /** Small epsilon added inside sqrt to avoid infinite gradient at distance = 0. */
    private static final double SQRT_EPS = 1e-8;

    // ── Fields ────────────────────────────────────────────────────────────────
    private final RotatEConfig config;

    /**
     * Constructs a learner with the given hyper-parameters.
     *
     * @param config RotatE training configuration
     */
    public RotatELearner(RotatEConfig config) {
        this.config = config;
    }

    /** Constructs a learner with {@link RotatEConfig#defaults()}. */
    public RotatELearner() {
        this(RotatEConfig.defaults());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EmbeddingLearner contract
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Adapts the node2vec {@link EmbeddingConfig} to RotatE by extracting the overlapping
     * fields (dim, negSamples, epochs, learningRate, seed) and using this learner's
     * {@link RotatEConfig} for RotatE-specific settings (margin, initRange, batchSize).
     * Returns entity real-part vectors as the canonical embedding.</p>
     */
    @Override
    public EmbeddingTable learn(ReasoningGraph graph, EmbeddingConfig config) {
        RotatEConfig rc = new RotatEConfig(
                config.dim(),
                this.config.margin(),
                config.negSamples(),
                config.epochs(),
                config.learningRate(),
                this.config.initRange(),
                config.seed(),
                this.config.batchSize());
        TrainedRotatE trained = trainRotatE(graph, rc);
        return trained.toEmbeddingTable();
    }

    /**
     * Train RotatE and return the full trained model (entity + relation embeddings).
     *
     * @param graph  source graph; must have at least one relation
     * @param rc     RotatE hyper-parameters
     * @return trained model with scoring capability
     */
    public TrainedRotatE train(ReasoningGraph graph, RotatEConfig rc) {
        return trainRotatE(graph, rc);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static scoring utility (pure Java — no SameDiff)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * RotatE distance for a triple given pre-trained embeddings — LOWER = more plausible.
     *
     * <p>Computes {@code d(h, r, t) = ||(h_re·cosθ − h_im·sinθ − t_re,
     *                                    h_re·sinθ + h_im·cosθ − t_im)||₂}.</p>
     *
     * @param hRe   head real part,      length {@code dim}
     * @param hIm   head imaginary part, length {@code dim}
     * @param theta relation phase,      length {@code dim}
     * @param tRe   tail real part,      length {@code dim}
     * @param tIm   tail imaginary part, length {@code dim}
     * @return non-negative L2 distance
     */
    public static double score(double[] hRe, double[] hIm, double[] theta,
                               double[] tRe, double[] tIm) {
        int d = hRe.length;
        double sumSq = 0.0;
        for (int i = 0; i < d; i++) {
            double cos = Math.cos(theta[i]);
            double sin = Math.sin(theta[i]);
            double rotRe = hRe[i] * cos - hIm[i] * sin - tRe[i];
            double rotIm = hRe[i] * sin + hIm[i] * cos - tIm[i];
            sumSq += rotRe * rotRe + rotIm * rotIm;
        }
        return Math.sqrt(sumSq);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core training loop
    // ─────────────────────────────────────────────────────────────────────────

    private TrainedRotatE trainRotatE(ReasoningGraph graph, RotatEConfig rc) {
        // ─── 1. Index entities ───────────────────────────────────────────────
        List<String> entityIds = new ArrayList<>(graph.entityCount());
        for (GraphEntity e : graph.entities()) {
            entityIds.add(e.id());
        }
        if (entityIds.isEmpty()) {
            throw new IllegalArgumentException("graph has no entities");
        }
        Map<String, Integer> entityIndex = new LinkedHashMap<>(entityIds.size() * 2);
        for (int i = 0; i < entityIds.size(); i++) {
            entityIndex.put(entityIds.get(i), i);
        }
        int numEntities = entityIds.size();

        // ─── 2. Index relation types ─────────────────────────────────────────
        List<String> relTypes = new ArrayList<>();
        Map<String, Integer> relIndex = new LinkedHashMap<>();
        for (GraphRelation rel : graph.relations()) {
            String t = rel.type();
            if (!relIndex.containsKey(t)) {
                relIndex.put(t, relTypes.size());
                relTypes.add(t);
            }
        }
        if (relTypes.isEmpty()) {
            throw new IllegalArgumentException("graph has no relations");
        }
        int numRelations = relTypes.size();

        // ─── 3. Collect triples ──────────────────────────────────────────────
        List<int[]> triples = new ArrayList<>(graph.relationCount());
        for (GraphRelation rel : graph.relations()) {
            Integer h = entityIndex.get(rel.sourceId());
            Integer t = entityIndex.get(rel.targetId());
            Integer r = relIndex.get(rel.type());
            if (h != null && t != null && r != null) {
                triples.add(new int[]{h, r, t});
            }
        }
        if (triples.isEmpty()) {
            throw new IllegalArgumentException("graph has no indexable triples");
        }

        // ─── 4. Negative-sampling table (uniform) ────────────────────────────
        double[] uniform = new double[numEntities];
        for (int i = 0; i < numEntities; i++) {
            uniform[i] = 1.0;
        }
        AliasTable negSampler = AliasTable.build(uniform);

        // ─── 5. Initialise embedding arrays ─────────────────────────────────
        Nd4j.getRandom().setSeed(rc.seed());
        Random javaRng = new Random(rc.seed());
        int dim = rc.dim();

        // Calibrated init scale: expected ||h_complex|| ≈ γ/2 so distances start near γ/2.
        // With uniform init in (-s, s), E[||complex vector||] ≈ s * sqrt(2*dim) * (2/sqrt(π)) ≈ s * 1.13 * sqrt(2*dim).
        // Target: s * sqrt(2*dim) ≈ γ/2 → s ≈ γ / (2 * sqrt(2*dim)).
        // Simpler: set initScale = γ / (2 * sqrt(dim)) which works empirically.
        double initScale = rc.initRange() > 0.0 ? rc.initRange()
                : rc.margin() / (2.0 * Math.sqrt(dim));

        double[][] eReInit    = new double[numEntities][dim];
        double[][] eImInit    = new double[numEntities][dim];
        for (int i = 0; i < numEntities; i++) {
            for (int j = 0; j < dim; j++) {
                eReInit[i][j] = (javaRng.nextDouble() * 2.0 - 1.0) * initScale;
                eImInit[i][j] = (javaRng.nextDouble() * 2.0 - 1.0) * initScale;
            }
        }

        // Relation phases: uniform in (-π, π]
        double[][] relPhaseInit = new double[numRelations][dim];
        for (int i = 0; i < numRelations; i++) {
            for (int j = 0; j < dim; j++) {
                relPhaseInit[i][j] = (javaRng.nextDouble() * 2.0 - 1.0) * Math.PI;
            }
        }

        INDArray entityReArr = Nd4j.create(eReInit).castTo(DataType.DOUBLE);
        INDArray entityImArr = Nd4j.create(eImInit).castTo(DataType.DOUBLE);
        INDArray relPhaseArr = Nd4j.create(relPhaseInit).castTo(DataType.DOUBLE);

        // ─── 6. Build SameDiff graph ─────────────────────────────────────────
        int B = rc.batchSize();
        int K = rc.negSamples();
        SameDiff sd = buildGraph(B, K, dim, rc.margin(),
                entityReArr, entityImArr, relPhaseArr);

        // ─── 7. Adam optimizers (one ND4J AdamUpdater per parameter matrix) ───
        // Each updater owns a flat 2·N state view (m‖v) and applies the fused
        // native Adam op, so the first/second-moment math is no longer hand-rolled.
        Adam adamConfig = new Adam(rc.learningRate(), ADAM_BETA1, ADAM_BETA2, ADAM_EPS);
        GradientUpdater<Adam> adamRe    = newAdamUpdater(adamConfig, 2L * numEntities  * dim);
        GradientUpdater<Adam> adamIm    = newAdamUpdater(adamConfig, 2L * numEntities  * dim);
        GradientUpdater<Adam> adamPhase = newAdamUpdater(adamConfig, 2L * numRelations * dim);

        // ─── 8. Training loop ────────────────────────────────────────────────
        Random trainRng = new Random(rc.seed() + 1L);
        List<int[]> shuffled = new ArrayList<>(triples);

        long[] batchHead = new long[B];
        long[] batchRel  = new long[B];
        long[] batchTail = new long[B];
        long[] batchNeg  = new long[B * K];
        int pendingCount  = 0;
        int iteration     = 0;  // 0-based Adam step; AdamUpdater uses (iteration+1) as the bias-correction power

        for (int epoch = 0; epoch < rc.epochs(); epoch++) {
            Collections.shuffle(shuffled, trainRng);

            for (int[] triple : shuffled) {
                // Corrupt tail negative samples
                int startNeg = pendingCount * K;
                for (int ki = 0; ki < K; ki++) {
                    int neg;
                    do {
                        neg = negSampler.sample(trainRng);
                    } while (neg == triple[2]);
                    batchNeg[startNeg + ki] = neg;
                }
                batchHead[pendingCount] = triple[0];
                batchRel[pendingCount]  = triple[1];
                batchTail[pendingCount] = triple[2];
                pendingCount++;

                if (pendingCount == B) {
                    executeAndUpdateAdam(sd, entityReArr, entityImArr, relPhaseArr,
                            adamRe, adamIm, adamPhase,
                            batchHead, batchRel, batchTail, batchNeg,
                            iteration++);
                    pendingCount = 0;
                }
            }

            // Flush partial batch (pad with last entry)
            if (pendingCount > 0) {
                int fillFrom = pendingCount - 1;
                for (int i = pendingCount; i < B; i++) {
                    batchHead[i] = batchHead[fillFrom];
                    batchRel[i]  = batchRel[fillFrom];
                    batchTail[i] = batchTail[fillFrom];
                    System.arraycopy(batchNeg, fillFrom * K, batchNeg, i * K, K);
                }
                executeAndUpdateAdam(sd, entityReArr, entityImArr, relPhaseArr,
                        adamRe, adamIm, adamPhase,
                        batchHead, batchRel, batchTail, batchNeg,
                        iteration++);
                pendingCount = 0;
            }
        }

        return new TrainedRotatE(entityIds, relTypes, dim, entityReArr, entityImArr, relPhaseArr);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Adam parameter update
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Instantiate an ND4J Adam {@link GradientUpdater} over a fresh, zero-initialised state view.
     *
     * @param config   the shared Adam hyper-parameter config
     * @param stateLen state-view length; must be {@code 2 × parameter-count} (the updater splits
     *                 it into the first- and second-moment halves)
     * @return a ready Adam updater
     */
    @SuppressWarnings("unchecked")  // IUpdater#instantiate is declared with a raw GradientUpdater return
    private static GradientUpdater<Adam> newAdamUpdater(Adam config, long stateLen) {
        return config.instantiate(Nd4j.zeros(DataType.DOUBLE, 1, stateLen), true);
    }

    /**
     * Execute one SameDiff forward+backward pass, then apply an Adam step to every
     * parameter matrix via its {@link GradientUpdater}.
     *
     * <p>Each updater carries the first/second-moment state and applies the fused native
     * Adam op (Kingma &amp; Ba, 2015), which realises the standard rule:</p>
     * <pre>
     *   m_t = β₁·m_{t-1} + (1−β₁)·g
     *   v_t = β₂·v_{t-1} + (1−β₂)·g²
     *   p_t = p_{t-1} − lr · (m_t/(1−β₁^τ)) / (√(v_t/(1−β₂^τ)) + ε),   τ = iteration+1
     * </pre>
     */
    private void executeAndUpdateAdam(
            SameDiff sd,
            INDArray entityReArr, INDArray entityImArr, INDArray relPhaseArr,
            GradientUpdater<Adam> adamRe, GradientUpdater<Adam> adamIm, GradientUpdater<Adam> adamPhase,
            long[] headIdx, long[] relIdx, long[] tailIdx, long[] negIdx,
            int iteration) {

        // Bind live arrays to graph variables
        sd.associateArrayWithVariable(entityReArr, ENTITY_RE);
        sd.associateArrayWithVariable(entityImArr, ENTITY_IM);
        sd.associateArrayWithVariable(relPhaseArr, REL_PHASE);

        // Bind placeholders
        Map<String, INDArray> placeholders = new HashMap<>(6);
        placeholders.put(HEAD_IDX, Nd4j.createFromArray(headIdx));
        placeholders.put(REL_IDX,  Nd4j.createFromArray(relIdx));
        placeholders.put(TAIL_IDX, Nd4j.createFromArray(tailIdx));
        placeholders.put(NEG_IDX,  Nd4j.createFromArray(negIdx));

        // Forward pass (we only need the loss for diagnostics; skip storing it)
        sd.outputSingle(placeholders, LOSS);

        // Backward pass
        Map<String, INDArray> grads = sd.calculateGradients(
                placeholders, ENTITY_RE, ENTITY_IM, REL_PHASE);

        applyAdam(adamRe,    entityReArr, grads.get(ENTITY_RE),  iteration);
        applyAdam(adamIm,    entityImArr, grads.get(ENTITY_IM),  iteration);
        applyAdam(adamPhase, relPhaseArr, grads.get(REL_PHASE),  iteration);
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
        updater.applyUpdater(update, iteration, 0);
        param.subi(update);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SameDiff graph construction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build the RotatE SameDiff computation graph for a batch of {@code B} triples with
     * {@code K} negative tail corruptions each.
     *
     * <h4>Forward graph (abbreviated; see {@link RotatELearner} class javadoc for full detail)</h4>
     * <pre>
     *   Gather h_re, h_im, θ, t_re, t_im, n_re, n_im from embedding variables.
     *   Rotation: re' = h_re·cos(θ) − h_im·sin(θ);  im' = h_re·sin(θ) + h_im·cos(θ)
     *   dPos = sqrt(sum((re'−t_re)²+(im'−t_im)², axis=1) + ε)      [B]
     *   dNeg = sqrt(sum((re'−n_re)²+(im'−n_im)², axis=2) + ε)      [B, K]
     *   loss = mean(-logSigmoid(γ−dPos)) + mean(-logSigmoid(dNeg−γ))
     * </pre>
     *
     * <p>All operations are SameDiff-differentiable; {@link SameDiff#calculateGradients}
     * computes {@code ∂loss/∂entityRe}, {@code ∂loss/∂entityIm}, {@code ∂loss/∂relPhase}.</p>
     */
    private static SameDiff buildGraph(int B, int K, int dim, double margin,
                                       INDArray entityReArr, INDArray entityImArr,
                                       INDArray relPhaseArr) {
        SameDiff sd = SameDiff.create();

        // ── Trainable variables ───────────────────────────────────────────────
        SDVariable entityRe = sd.var(ENTITY_RE, entityReArr.dup());
        SDVariable entityIm = sd.var(ENTITY_IM, entityImArr.dup());
        SDVariable relPhase = sd.var(REL_PHASE, relPhaseArr.dup());

        // ── Placeholders ──────────────────────────────────────────────────────
        SDVariable headIdxVar = sd.placeHolder(HEAD_IDX, DataType.INT64, B);
        SDVariable relIdxVar  = sd.placeHolder(REL_IDX,  DataType.INT64, B);
        SDVariable tailIdxVar = sd.placeHolder(TAIL_IDX, DataType.INT64, B);
        SDVariable negIdxVar  = sd.placeHolder(NEG_IDX,  DataType.INT64, (long) B * K);

        // ── Gather ────────────────────────────────────────────────────────────
        SDVariable hRe     = sd.gather("hRe",     entityRe, headIdxVar, 0);   // [B, d]
        SDVariable hIm     = sd.gather("hIm",     entityIm, headIdxVar, 0);   // [B, d]
        SDVariable theta   = sd.gather("theta",   relPhase, relIdxVar,  0);   // [B, d]
        SDVariable tRe     = sd.gather("tRe",     entityRe, tailIdxVar, 0);   // [B, d]
        SDVariable tIm     = sd.gather("tIm",     entityIm, tailIdxVar, 0);   // [B, d]
        SDVariable nReFlat = sd.gather("nReFlat", entityRe, negIdxVar,  0);   // [B*K, d]
        SDVariable nImFlat = sd.gather("nImFlat", entityIm, negIdxVar,  0);   // [B*K, d]
        SDVariable nRe = sd.reshape("nRe", nReFlat, (long) B, (long) K, (long) dim);  // [B, K, d]
        SDVariable nIm = sd.reshape("nIm", nImFlat, (long) B, (long) K, (long) dim);  // [B, K, d]

        // ── Trig (element-wise cos/sin of phase) ──────────────────────────────
        SDVariable cosT = sd.math().cos("cosT", theta);   // [B, d]
        SDVariable sinT = sd.math().sin("sinT", theta);   // [B, d]

        // ── Positive triple: rotated head minus tail ──────────────────────────
        // re' = h_re·cos - h_im·sin  → [B, d]
        // im' = h_re·sin + h_im·cos  → [B, d]
        SDVariable hRe_cos = hRe.mul("hRe_cos", cosT);
        SDVariable hIm_sin = hIm.mul("hIm_sin", sinT);
        SDVariable hRe_sin = hRe.mul("hRe_sin", sinT);
        SDVariable hIm_cos = hIm.mul("hIm_cos", cosT);

        SDVariable rotRePos = hRe_cos.sub("rotRePos", hIm_sin).sub("rotRePosT", tRe);  // [B, d]
        SDVariable rotImPos = hRe_sin.add("rotImPos", hIm_cos).sub("rotImPosT", tIm);  // [B, d]

        // dPos = sqrt(sum(rotRe²+rotIm², axis=1) + ε)  → [B]
        SDVariable distSqPosElem = rotRePos.mul("rrpSq", rotRePos)
                                           .add("dspTmp", rotImPos.mul("ripSq", rotImPos));  // [B, d]
        SDVariable distSqPos = distSqPosElem.sum("dsp", 1);   // [B]
        SDVariable sqrtEps = sd.constant("sqrtEps", Nd4j.scalar(DataType.DOUBLE, SQRT_EPS));
        SDVariable dPos = sd.math().sqrt("dPos", distSqPos.add("dspEps", sqrtEps));   // [B]

        // ── Negative triples: broadcast over K ───────────────────────────────
        // Reshape [B, d] → [B, 1, d] to broadcast against [B, K, d]
        SDVariable hRe3  = sd.reshape("hRe3",  hRe_cos, (long) B, 1L, (long) dim);
        SDVariable hIm3  = sd.reshape("hIm3",  hIm_sin, (long) B, 1L, (long) dim);
        SDVariable hRe3s = sd.reshape("hRe3s", hRe_sin, (long) B, 1L, (long) dim);
        SDVariable hIm3c = sd.reshape("hIm3c", hIm_cos, (long) B, 1L, (long) dim);

        SDVariable rotReNeg = hRe3.sub("rotReNeg",  hIm3).sub("rotReNegN",  nRe);   // [B, K, d]
        SDVariable rotImNeg = hRe3s.add("rotImNeg", hIm3c).sub("rotImNegN", nIm);  // [B, K, d]

        // dNeg = sqrt(sum(rotRe²+rotIm², axis=2) + ε)  → [B, K]
        SDVariable distSqNegElem = rotReNeg.mul("rrnSq", rotReNeg)
                                           .add("dsnTmp", rotImNeg.mul("rinSq", rotImNeg));  // [B, K, d]
        SDVariable distSqNeg = distSqNegElem.sum("dsn", 2);   // [B, K]
        SDVariable dNeg = sd.math().sqrt("dNeg", distSqNeg.add("dsnEps", sqrtEps));  // [B, K]

        // ── Loss ──────────────────────────────────────────────────────────────
        // lPos = -logSigmoid(γ - dPos)   → [B]
        // lNeg = -logSigmoid(dNeg - γ)   → [B, K]
        // loss = mean(lPos) + mean(lNeg)  → scalar
        SDVariable gamma = sd.constant("gamma", Nd4j.scalar(DataType.DOUBLE, margin));

        SDVariable gammaMinusDPos = gamma.sub("gammaMinusDPos", dPos);
        SDVariable lPos = sd.nn().logSigmoid("lSigPos", gammaMinusDPos).neg("negLPos");

        SDVariable dNegMinusGamma = dNeg.sub("dNegMinusGamma", gamma);
        SDVariable lNeg = sd.nn().logSigmoid("lSigNeg", dNegMinusGamma).neg("negLNeg");

        SDVariable meanPos = lPos.mean("meanPos");
        SDVariable meanNeg = lNeg.mean("meanNeg");
        sd.math().add(LOSS, meanPos, meanNeg);

        sd.setLossVariables(LOSS);
        return sd;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result holder
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Holds the trained RotatE embedding arrays for entities and relations.
     *
     * <p>Entity embeddings are complex: {@link #entityRe(int)} and {@link #entityIm(int)} return
     * the real and imaginary parts of entity {@code i}. Relation embeddings are phases:
     * {@link #relPhase(int)} returns the phase vector for relation type at index {@code r}.</p>
     */
    public static final class TrainedRotatE {

        private final List<String> entityIds;
        private final List<String> relTypes;
        private final int          dim;
        private final double[][]   entityRe;
        private final double[][]   entityIm;
        private final double[][]   relPhase;

        private TrainedRotatE(List<String> entityIds, List<String> relTypes, int dim,
                              INDArray entityReArr, INDArray entityImArr, INDArray relPhaseArr) {
            this.entityIds = new ArrayList<>(entityIds);
            this.relTypes  = new ArrayList<>(relTypes);
            this.dim       = dim;
            this.entityRe  = entityReArr.toDoubleMatrix();
            this.entityIm  = entityImArr.toDoubleMatrix();
            this.relPhase  = relPhaseArr.toDoubleMatrix();
        }

        /**
         * Package-private factory for persistence: reconstruct a {@link TrainedRotatE} from raw
         * INDArrays restored off disk. Called by {@link SameDiffModelIO} and
         * {@link RotatEPersistenceBridge}.
         */
        static TrainedRotatE fromArrays(List<String> entityIds, List<String> relTypes, int dim,
                                         INDArray entityReArr, INDArray entityImArr,
                                         INDArray relPhaseArr) {
            return new TrainedRotatE(entityIds, relTypes, dim, entityReArr, entityImArr, relPhaseArr);
        }

        /** Number of entities. */
        public int numEntities() {
            return entityIds.size();
        }

        /** Number of distinct relation types. */
        public int numRelations() {
            return relTypes.size();
        }

        /** Embedding dimension {@code d}. */
        public int dim() {
            return dim;
        }

        /** Entity id list in insertion order (index = row in embedding matrices). */
        public List<String> entityIds() {
            return Collections.unmodifiableList(entityIds);
        }

        /** Relation type list in first-seen order (index = row in phase matrix). */
        public List<String> relTypes() {
            return Collections.unmodifiableList(relTypes);
        }

        /** Real part of entity {@code i}'s complex embedding. */
        public double[] entityRe(int i) {
            return entityRe[i];
        }

        /** Imaginary part of entity {@code i}'s complex embedding. */
        public double[] entityIm(int i) {
            return entityIm[i];
        }

        /** Phase vector for relation type at index {@code r}. */
        public double[] relPhase(int r) {
            return relPhase[r];
        }

        /**
         * RotatE distance for a triple by string ids — LOWER = more plausible.
         *
         * @param headId   head entity id
         * @param relType  relation type string
         * @param tailId   tail entity id
         * @return non-negative distance, or {@link Double#NaN} if any id is unknown
         */
        public double score(String headId, String relType, String tailId) {
            int h = entityIds.indexOf(headId);
            int r = relTypes.indexOf(relType);
            int t = entityIds.indexOf(tailId);
            if (h < 0 || r < 0 || t < 0) {
                return Double.NaN;
            }
            return RotatELearner.score(entityRe[h], entityIm[h], relPhase[r],
                    entityRe[t], entityIm[t]);
        }

        /**
         * Build an {@link EmbeddingTable} from the real-part entity vectors.
         *
         * <p>The real parts are used as the canonical node embedding for downstream
         * similarity tasks ({@link ai.kompile.graph.reasoning.embedding.Embeddings#cosine},
         * {@link ai.kompile.graph.reasoning.hybrid.HybridReasoner}). The imaginary parts
         * and relation phases are available directly on this object for link prediction.</p>
         */
        public EmbeddingTable toEmbeddingTable() {
            return RotatEEmbeddingBridge.toTable(entityIds, entityRe, dim);
        }
    }
}
