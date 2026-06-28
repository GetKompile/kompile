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

import ai.kompile.core.kgembedding.*;
import ai.kompile.knowledgegraph.embedding.training.EmbeddingInitializer;
import ai.kompile.knowledgegraph.embedding.training.NegativeSampler;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.memory.conf.WorkspaceConfiguration;
import org.nd4j.linalg.api.memory.enums.AllocationPolicy;
import org.nd4j.linalg.api.memory.enums.LearningPolicy;
import org.nd4j.linalg.api.memory.enums.ResetPolicy;
import org.nd4j.linalg.api.memory.enums.SpillPolicy;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RotatE (Rotation-based Embeddings) knowledge graph embedding model.
 *
 * <p>RotatE models relations as rotations in complex space:
 * <b>h ∘ r ≈ t</b> where ∘ denotes the Hadamard (element-wise) product
 * in complex space.
 *
 * <p>Relations are represented as rotation angles: r = e^(iθ) = cos(θ) + i*sin(θ)
 *
 * <p>The scoring function is: score(h, r, t) = ||h ∘ r - t||
 * Lower scores indicate more plausible triples.
 *
 * <p>RotatE can model:
 * - Symmetric relations: θ = 0 or π
 * - Antisymmetric relations: any θ ≠ 0, π
 * - Inverse relations: θ_r2 = -θ_r1
 * - Composition relations: θ_r3 = θ_r1 + θ_r2
 *
 * <p>Reference: Sun et al., "RotatE: Knowledge Graph Embedding by
 * Relational Rotation in Complex Space", ICLR 2019.
 */
public class RotatEModel implements KGEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(RotatEModel.class);

    /**
     * Workspace configuration for per-batch native memory scoping in RotatE.
     *
     * <p>Each call to {@code trainBatch} allocates many transient {@link INDArray}s
     * ({@code dup()} copies, cosine/sine intermediates, gradient terms). Without a
     * workspace these accumulate in off-heap native memory until the next GC cycle,
     * causing the same per-batch leak that TransE exhibited before its fix.
     * This workspace bounds all transient allocations and resets them at the end of
     * each batch, matching the TransE pattern exactly.
     *
     * <p>Persistent embeddings ({@code entityRealEmbeddings}, {@code entityImagEmbeddings},
     * {@code relationPhaseAngles}) live outside the workspace; in-place {@code .subi}/{@code .addi}
     * calls write through {@code .getRow()} views, which is safe across the workspace boundary.</p>
     */
    private static final WorkspaceConfiguration ROTATE_BATCH_WS_CONFIG =
            WorkspaceConfiguration.builder()
                    .initialSize(512 * 1024 * 1024L)  // 512 MB: a full batch's per-triple allocs must FIT (RotatE ~2× TransE); spill isn't reclaimed by BLOCK_LEFT → climbs to OOM
                    .policyAllocation(AllocationPolicy.OVERALLOCATE)
                    .overallocationLimit(0.3)         // 30% headroom (valid for BLOCK_LEFT; cyclic reset would require an integral multiple)
                    .policySpill(SpillPolicy.REALLOCATE)
                    .policyLearning(LearningPolicy.FIRST_LOOP)
                    .policyReset(ResetPolicy.BLOCK_LEFT)  // per-batch try-with-resources: reset to start on close (DL4J-standard; NOT cyclic ENDOFBUFFER_REACHED)
                    .build();

    // Complex embeddings: stored as real and imaginary parts
    private INDArray entityRealEmbeddings;   // [numEntities, embeddingDim]
    private INDArray entityImagEmbeddings;   // [numEntities, embeddingDim]
    private INDArray relationPhaseAngles;     // [numRelations, embeddingDim] - θ values

    // Index mappings
    private Map<String, Integer> entityToIndex;
    private Map<String, Integer> relationToIndex;
    private List<String> indexToEntity;
    private List<String> indexToRelation;

    // State
    private int embeddingDim;
    private final AtomicBoolean training = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private boolean trained = false;

    // Embedding range parameter (γ/dim from paper)
    private double embeddingRange;

    /**
     * Creates an empty RotatE model.
     */
    public RotatEModel() {
        this.entityToIndex = new HashMap<>();
        this.relationToIndex = new HashMap<>();
        this.indexToEntity = new ArrayList<>();
        this.indexToRelation = new ArrayList<>();
    }

    @Override
    public String getAlgorithmName() {
        return "RotatE";
    }

    @Override
    public KGEmbeddingAlgorithm getAlgorithm() {
        return KGEmbeddingAlgorithm.ROTATE;
    }

    @Override
    public int getEmbeddingDimension() {
        return embeddingDim;
    }

    @Override
    public int getEntityCount() {
        return entityToIndex.size();
    }

    @Override
    public int getRelationCount() {
        return relationToIndex.size();
    }

    @Override
    public Set<String> getEntityIds() {
        return Collections.unmodifiableSet(entityToIndex.keySet());
    }

    @Override
    public Set<String> getRelationTypes() {
        return Collections.unmodifiableSet(relationToIndex.keySet());
    }

    @Override
    public TrainingResult train(List<Triple> triples, KGEmbeddingConfig config) {
        if (triples == null || triples.isEmpty()) {
            return TrainingResult.failure("No triples provided for training");
        }

        if (training.getAndSet(true)) {
            return TrainingResult.failure("Training already in progress");
        }

        cancelRequested.set(false);
        long startTime = System.currentTimeMillis();
        List<Double> lossHistory = new ArrayList<>();

        try {
            // Snapshot any prior imported embeddings BEFORE buildVocabulary clears the index maps.
            // importEntityEmbeddings / importRelationEmbeddings call clear() on those maps and set the
            // embedding matrices; buildVocabulary also clears them. Save them FIRST.
            //
            // RotatE-specific representation:
            //   entity warm-start  — two separate matrices: entityRealEmbeddings [n×dim] +
            //                        entityImagEmbeddings [n×dim].  The public API
            //                        (importEntityEmbeddings / getEntityEmbedding) concatenates them
            //                        into a single [1×2*dim] vector, so dim-match must check
            //                        columns == 2*embeddingDim after the current embeddingDim is known.
            //   relation warm-start — one matrix: relationPhaseAngles [n×dim].  columns == embeddingDim.
            Map<String, Integer> priorEntityIndex    = new HashMap<>(entityToIndex);
            INDArray priorEntityReal  = entityRealEmbeddings;   // null on cold start
            INDArray priorEntityImag  = entityImagEmbeddings;   // null on cold start
            Map<String, Integer> priorRelationIndex  = new HashMap<>(relationToIndex);
            INDArray priorPhaseAngles = relationPhaseAngles;    // null on cold start

            // Build entity and relation vocabularies
            buildVocabulary(triples);

            this.embeddingDim = config.embeddingDim();
            // Embedding range: γ/dim (γ is margin)
            this.embeddingRange = config.margin() / embeddingDim;

            // ── Warm-start vs cold-start initialization ──────────────────────────────────────
            // Prior entity vectors were imported as concatenated [real|imag] (2*dim cols each).
            // dim-match: priorEntityReal.columns() must equal embeddingDim (split inside importEntityEmbeddings).
            boolean hasPriorEntity   = priorEntityReal != null && !priorEntityIndex.isEmpty()
                    && (int) priorEntityReal.columns() == embeddingDim;
            // Prior phase vectors are [1×dim] each.
            boolean hasPriorRelation = priorPhaseAngles != null && !priorRelationIndex.isEmpty()
                    && (int) priorPhaseAngles.columns() == embeddingDim;

            if (priorEntityReal != null && !hasPriorEntity) {
                log.warn("RotatE warm-start: entity dim mismatch (priorReal.cols={} config={}×2) — cold-starting entities",
                        priorEntityReal.columns(), embeddingDim);
            }
            if (priorPhaseAngles != null && !hasPriorRelation) {
                log.warn("RotatE warm-start: relation dim mismatch (priorPhase.cols={} config={}) — cold-starting relations",
                        priorPhaseAngles.columns(), embeddingDim);
            }

            log.info("Starting RotatE training: {} entities, {} relations, {} triples (warmStart={})",
                    entityToIndex.size(), relationToIndex.size(), triples.size(), hasPriorEntity);

            // ── Initialize entity embeddings ──────────────────────────────────────────────────
            if (hasPriorEntity) {
                // Warm-start: allocate fresh matrices for the (possibly larger) current vocabulary,
                // then copy known entity rows from the prior real/imag matrices.
                int numEntitiesNew = entityToIndex.size();
                entityRealEmbeddings = Nd4j.rand(numEntitiesNew, embeddingDim)
                        .muli(2 * embeddingRange).subi(embeddingRange);
                entityImagEmbeddings = Nd4j.rand(numEntitiesNew, embeddingDim)
                        .muli(2 * embeddingRange).subi(embeddingRange);
                int seeded = 0, fresh = 0;
                for (Map.Entry<String, Integer> entry : entityToIndex.entrySet()) {
                    Integer priorIdx = priorEntityIndex.get(entry.getKey());
                    if (priorIdx != null && priorIdx < priorEntityReal.rows()) {
                        entityRealEmbeddings.putRow(entry.getValue(), priorEntityReal.getRow(priorIdx));
                        entityImagEmbeddings.putRow(entry.getValue(), priorEntityImag.getRow(priorIdx));
                        seeded++;
                    } else {
                        fresh++;
                    }
                }
                log.info("RotatE warm-start: {} entities seeded from prior (real+imag), {} new (random-init)",
                        seeded, fresh);
            } else {
                // Cold start: initializeEmbeddings() also sets relationPhaseAngles, so handle both here.
                initializeEmbeddings();
            }

            // ── Initialize relation phase angles ─────────────────────────────────────────────
            // Note: initializeEmbeddings() was called above ONLY in the cold-start (hasPriorEntity=false)
            // branch. In the warm-start branch we need to initialize relations independently.
            if (hasPriorEntity) {
                // Always allocate a fresh relation matrix for the current vocab, then seed known relations.
                int numRelationsNew = relationToIndex.size();
                relationPhaseAngles = Nd4j.rand(numRelationsNew, embeddingDim)
                        .muli(2 * Math.PI).subi(Math.PI);
                if (hasPriorRelation) {
                    int seeded = 0, fresh = 0;
                    for (Map.Entry<String, Integer> entry : relationToIndex.entrySet()) {
                        Integer priorIdx = priorRelationIndex.get(entry.getKey());
                        if (priorIdx != null && priorIdx < priorPhaseAngles.rows()) {
                            relationPhaseAngles.putRow(entry.getValue(), priorPhaseAngles.getRow(priorIdx));
                            seeded++;
                        } else {
                            fresh++;
                        }
                    }
                    log.info("RotatE warm-start: {} relations seeded from prior phase angles, {} new (random-init)",
                            seeded, fresh);
                }
                // hasPriorEntity=true, hasPriorRelation=false: full random init for relations (already done above).
            } else if (hasPriorRelation) {
                // Cold-start entities but warm-start relations (unlikely path; handle for completeness).
                // initializeEmbeddings() already set relationPhaseAngles randomly; overwrite known relations.
                int seeded = 0, fresh = 0;
                for (Map.Entry<String, Integer> entry : relationToIndex.entrySet()) {
                    Integer priorIdx = priorRelationIndex.get(entry.getKey());
                    if (priorIdx != null && priorIdx < priorPhaseAngles.rows()) {
                        relationPhaseAngles.putRow(entry.getValue(), priorPhaseAngles.getRow(priorIdx));
                        seeded++;
                    } else {
                        fresh++;
                    }
                }
                log.info("RotatE warm-start (entity cold, relation warm): {} relations seeded, {} new",
                        seeded, fresh);
            }
            // hasPriorEntity=false, hasPriorRelation=false: full cold start; initializeEmbeddings() handled both.

            // Create negative sampler
            NegativeSampler sampler = new NegativeSampler(entityToIndex.keySet(), new HashSet<>(triples));

            // Training loop
            int totalBatches = (int) Math.ceil((double) triples.size() / config.batchSize());

            for (int epoch = 0; epoch < config.epochs(); epoch++) {
                if (cancelRequested.get()) {
                    log.info("Training cancelled at epoch {}", epoch);
                    return TrainingResult.cancelled(epoch, lossHistory.isEmpty() ? 0 : lossHistory.get(lossHistory.size() - 1));
                }

                double epochLoss = 0.0;
                int batchesCompleted = 0;

                // Shuffle triples
                List<Triple> shuffled = new ArrayList<>(triples);
                Collections.shuffle(shuffled);

                // Process batches
                for (int i = 0; i < shuffled.size(); i += config.batchSize()) {
                    int end = Math.min(i + config.batchSize(), shuffled.size());
                    List<Triple> batch = shuffled.subList(i, end);

                    // Generate negative samples
                    List<Triple> negatives = sampler.corrupt(batch, config.negativeSamples());

                    // Wrap trainBatch in a MemoryWorkspace so all transient INDArrays
                    // (dup copies, cos/sin intermediates, gradient terms) are freed at the
                    // end of each batch rather than accumulating in off-heap native memory.
                    // Persistent embedding matrices live outside the workspace and are
                    // updated in-place via .getRow() — safe across the workspace boundary.
                    double batchLoss;
                    try (MemoryWorkspace ws = Nd4j.getWorkspaceManager()
                            .getAndActivateWorkspace(ROTATE_BATCH_WS_CONFIG, "ROTATE_BATCH")) {
                        batchLoss = trainBatch(batch, negatives, config.learningRate(), config.margin());
                    }
                    epochLoss += batchLoss;
                    batchesCompleted++;
                }

                epochLoss /= batchesCompleted;
                lossHistory.add(epochLoss);

                // Progress callback
                if (config.progressCallback() != null) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    double triplesPerSec = (double) (epoch + 1) * triples.size() * 1000 / elapsed;
                    long remaining = (long) ((config.epochs() - epoch - 1) * elapsed / (epoch + 1));

                    config.progressCallback().accept(new TrainingProgress(
                            epoch + 1,
                            config.epochs(),
                            epochLoss,
                            batchesCompleted,
                            totalBatches,
                            elapsed,
                            remaining,
                            triplesPerSec
                    ));
                }

                if ((epoch + 1) % 10 == 0 || epoch == 0) {
                    log.info("Epoch {}/{}: loss = {}", epoch + 1, config.epochs(), String.format("%.4f", epochLoss));
                }
            }

            trained = true;
            long trainingTime = System.currentTimeMillis() - startTime;

            log.info("RotatE training completed in {}ms", trainingTime);

            return TrainingResult.success(
                    entityToIndex.size(),
                    relationToIndex.size(),
                    triples.size(),
                    config.epochs(),
                    lossHistory.get(lossHistory.size() - 1),
                    lossHistory,
                    trainingTime
            );

        } catch (Exception e) {
            log.error("RotatE training failed", e);
            return TrainingResult.failure(e.getMessage());
        } finally {
            training.set(false);
        }
    }

    /**
     * Builds entity and relation vocabularies from triples.
     */
    private void buildVocabulary(List<Triple> triples) {
        entityToIndex.clear();
        relationToIndex.clear();
        indexToEntity.clear();
        indexToRelation.clear();

        Set<String> entities = new LinkedHashSet<>();
        Set<String> relations = new LinkedHashSet<>();

        for (Triple t : triples) {
            entities.add(t.head());
            entities.add(t.tail());
            relations.add(t.relation());
        }

        int idx = 0;
        for (String entity : entities) {
            entityToIndex.put(entity, idx++);
            indexToEntity.add(entity);
        }

        idx = 0;
        for (String relation : relations) {
            relationToIndex.put(relation, idx++);
            indexToRelation.add(relation);
        }
    }

    /**
     * Initializes complex entity embeddings and relation phase angles.
     */
    private void initializeEmbeddings() {
        int numEntities = entityToIndex.size();
        int numRelations = relationToIndex.size();

        // Initialize entity embeddings uniformly in [-range, range]
        entityRealEmbeddings = Nd4j.rand(numEntities, embeddingDim).muli(2 * embeddingRange).subi(embeddingRange);
        entityImagEmbeddings = Nd4j.rand(numEntities, embeddingDim).muli(2 * embeddingRange).subi(embeddingRange);

        // Initialize relation phase angles uniformly in [-π, π]
        relationPhaseAngles = Nd4j.rand(numRelations, embeddingDim).muli(2 * Math.PI).subi(Math.PI);
    }

    /**
     * Trains on a batch of positive and negative triples.
     *
     * <p>Replaces the old scalar per-triple loop while preserving its <em>exact</em>
     * sequential-SGD semantics.  Pre-allocated workspace buffers (one set per batch
     * call, reused across all pairs) eliminate the ~20 transient INDArray allocations
     * per pair that the old implementation produced.
     *
     * <p><b>Sequential-SGD invariants (mirrors the original code exactly):</b>
     * <ul>
     *   <li>For positive {@code i}: {@code hReal, hImag, tReal, tImag, theta, cosTheta,
     *       sinTheta, hrReal, hrImag} and the derived positive score/gradient values
     *       are snapped from the live embeddings at the start of the outer iteration.</li>
     *   <li>For negative {@code j}: {@code hNegReal, hNegImag, tNegReal, tNegImag} are
     *       read live from the (possibly already updated) embedding matrices.</li>
     *   <li>Gradient updates are applied immediately to the live embedding matrices
     *       before the next pair is processed — identical to the original loop.</li>
     * </ul>
     *
     * @return totalLoss / positives.size()
     */
    private double trainBatch(List<Triple> positives, List<Triple> negatives,
                              double learningRate, double margin) {
        double totalLoss = 0.0;
        int negPerPos = negatives.isEmpty() ? 0 : negatives.size() / positives.size();
        if (negPerPos == 0) return 0.0;

        // Pre-allocate reusable workspace buffers — one set per batch call, reused
        // across all (pos, neg) loop iterations.  Zero INDArray allocations inside
        // either loop body.
        //
        // SAFETY RULE: only two-arg in-place ops are used.
        //   a.assign(b)  — copies b into a (a is mutated, b is unchanged)
        //   a.muli(b)    — a *= b  in-place (a is mutated, b is unchanged)
        //   a.addi(b)    — a += b  in-place
        //   a.subi(b)    — a -= b  in-place
        //   a.divi(b)    — a /= b  in-place
        //   a.negi()     — a = -a  in-place
        //
        // The three-arg form opi(b, result) is BANNED — it writes to result AND
        // mutates `this`, corrupting workspace buffers that must survive across iterations.
        //
        // Safe idiom for "result = a * b" while preserving a:
        //   result.assign(a).muli(b);
        //
        // Outer-loop workspace (snapped once per positive triple from live embeddings):
        //   hR, hI      — positive head real/imag
        //   tR, tI      — positive tail real/imag
        //   cosT, sinT  — cos/sin of the snapped relation phase angle
        //   hrR, hrI    — h ∘ r  (positive rotation product)
        //   dPR, dPI    — positive residual: hrR-tR, hrI-tI
        //   posMag      — per-dim magnitude sqrt(dPR^2+dPI^2+eps)
        //   gR, gI      — positive gradient direction: dPR/posMag, dPI/posMag
        //   dhR, dhI    — positive head update direction
        //   dtR         — relation phase update direction
        //   scrA, scrB  — two independent scratch buffers (avoid aliasing in 3-term exprs)
        // Inner-loop workspace (overwritten each j from live reads):
        //   hnR, hnI    — negative head real/imag (live read)
        //   tnR, tnI    — negative tail real/imag (live read)
        //   hrNR, hrNI  — hNeg ∘ r
        //   dNR, dNI    — negative residual: hrNeg-tNeg
        //   negMag      — per-dim negative magnitude
        //   gnR, gnI    — negative gradient direction
        //   dhNR, dhNI  — negative head update direction
        //   upd         — scaled update vector: lr * weight * direction (reused per component)
        int dim = embeddingDim;
        // outer-loop workspace
        INDArray hR     = Nd4j.create(dim);
        INDArray hI     = Nd4j.create(dim);
        INDArray tR     = Nd4j.create(dim);
        INDArray tI     = Nd4j.create(dim);
        INDArray cosT   = Nd4j.create(dim);
        INDArray sinT   = Nd4j.create(dim);
        INDArray hrR    = Nd4j.create(dim);
        INDArray hrI    = Nd4j.create(dim);
        INDArray dPR    = Nd4j.create(dim);
        INDArray dPI    = Nd4j.create(dim);
        INDArray posMag = Nd4j.create(dim);
        INDArray gR     = Nd4j.create(dim);
        INDArray gI     = Nd4j.create(dim);
        INDArray dhR    = Nd4j.create(dim);
        INDArray dhI    = Nd4j.create(dim);
        INDArray dtR    = Nd4j.create(dim);
        INDArray scrA   = Nd4j.create(dim);
        INDArray scrB   = Nd4j.create(dim);
        // inner-loop workspace
        INDArray hnR    = Nd4j.create(dim);
        INDArray hnI    = Nd4j.create(dim);
        INDArray tnR    = Nd4j.create(dim);
        INDArray tnI    = Nd4j.create(dim);
        INDArray hrNR   = Nd4j.create(dim);
        INDArray hrNI   = Nd4j.create(dim);
        INDArray dNR    = Nd4j.create(dim);
        INDArray dNI    = Nd4j.create(dim);
        INDArray negMag = Nd4j.create(dim);
        INDArray gnR    = Nd4j.create(dim);
        INDArray gnI    = Nd4j.create(dim);
        INDArray dhNR   = Nd4j.create(dim);
        INDArray dhNI   = Nd4j.create(dim);
        INDArray upd    = Nd4j.create(dim);

        for (int i = 0; i < positives.size(); i++) {
            Triple pos = positives.get(i);
            Integer hIdx = entityToIndex.get(pos.head());
            Integer rIdx = relationToIndex.get(pos.relation());
            Integer tIdx = entityToIndex.get(pos.tail());
            if (hIdx == null || rIdx == null || tIdx == null) continue;

            // Snapshot outer-loop embeddings via assign() — zero allocation, no dup.
            hR.assign(entityRealEmbeddings.getRow(hIdx));
            hI.assign(entityImagEmbeddings.getRow(hIdx));
            tR.assign(entityRealEmbeddings.getRow(tIdx));
            tI.assign(entityImagEmbeddings.getRow(tIdx));

            // cosT = cos(theta),  sinT = sin(theta).
            // Transforms.cos(row, true) returns a NEW copy of cos(row), which we
            // assign into the pre-allocated cosT buffer — the relation row is untouched.
            cosT.assign(Transforms.cos(relationPhaseAngles.getRow(rIdx), true));
            sinT.assign(Transforms.sin(relationPhaseAngles.getRow(rIdx), true));

            // hrR = hR*cosT - hI*sinT
            hrR.assign(hR).muli(cosT);          // hrR = hR * cosT
            scrA.assign(hI).muli(sinT);          // scrA = hI * sinT
            hrR.subi(scrA);                      // hrR = hR*cosT - hI*sinT

            // hrI = hR*sinT + hI*cosT
            hrI.assign(hR).muli(sinT);           // hrI = hR * sinT
            scrA.assign(hI).muli(cosT);          // scrA = hI * cosT
            hrI.addi(scrA);                      // hrI = hR*sinT + hI*cosT

            // dPR = hrR - tR,  dPI = hrI - tI
            dPR.assign(hrR).subi(tR);
            dPI.assign(hrI).subi(tI);

            // posMag = sqrt(dPR^2 + dPI^2 + eps)  per-dim
            posMag.assign(dPR).muli(dPR);        // posMag = dPR^2
            scrA.assign(dPI).muli(dPI);           // scrA = dPI^2
            posMag.addi(scrA).addi(1e-10);        // posMag = dPR^2 + dPI^2 + eps
            Transforms.sqrt(posMag, false);       // posMag = sqrt(...)  (in-place, no new alloc)

            double posScore = posMag.sumNumber().doubleValue();

            // gR = dPR / posMag,  gI = dPI / posMag
            gR.assign(dPR).divi(posMag);
            gI.assign(dPI).divi(posMag);

            // dhR = gR*cosT + gI*sinT
            dhR.assign(gR).muli(cosT);           // dhR = gR * cosT
            scrA.assign(gI).muli(sinT);           // scrA = gI * sinT
            dhR.addi(scrA);                      // dhR = gR*cosT + gI*sinT

            // dhI = -(gR*sinT - gI*cosT)
            dhI.assign(gR).muli(sinT);           // dhI = gR * sinT
            scrA.assign(gI).muli(cosT);           // scrA = gI * cosT
            dhI.subi(scrA).negi();               // dhI = -(gR*sinT - gI*cosT)

            // dtR = (-hR*sinT - hI*cosT)*gR + (hR*cosT - hI*sinT)*gI
            // part1 = (-hR*sinT - hI*cosT) * gR
            dtR.assign(hR).muli(sinT).negi();    // dtR = -hR*sinT
            scrA.assign(hI).muli(cosT).negi();    // scrA = -hI*cosT
            dtR.addi(scrA);                      // dtR = -hR*sinT - hI*cosT  = part1/gR
            dtR.muli(gR);                        // dtR = part1

            // part2 = (hR*cosT - hI*sinT) * gI
            scrA.assign(hR).muli(cosT);           // scrA = hR*cosT
            scrB.assign(hI).muli(sinT);           // scrB = hI*sinT
            scrA.subi(scrB);                     // scrA = hR*cosT - hI*sinT
            scrA.muli(gI);                       // scrA = part2
            dtR.addi(scrA);                      // dtR = part1 + part2

            for (int j = 0; j < negPerPos; j++) {
                Triple neg = negatives.get(i * negPerPos + j);
                Integer hNegIdx = entityToIndex.get(neg.head());
                Integer tNegIdx = entityToIndex.get(neg.tail());
                if (hNegIdx == null || tNegIdx == null) continue;

                // Read hNeg, tNeg LIVE (sequential SGD: may reflect prior updates).
                hnR.assign(entityRealEmbeddings.getRow(hNegIdx));
                hnI.assign(entityImagEmbeddings.getRow(hNegIdx));
                tnR.assign(entityRealEmbeddings.getRow(tNegIdx));
                tnI.assign(entityImagEmbeddings.getRow(tNegIdx));

                // hrNR = hnR*cosT - hnI*sinT
                hrNR.assign(hnR).muli(cosT);
                scrA.assign(hnI).muli(sinT);
                hrNR.subi(scrA);

                // hrNI = hnR*sinT + hnI*cosT
                hrNI.assign(hnR).muli(sinT);
                scrA.assign(hnI).muli(cosT);
                hrNI.addi(scrA);

                // dNR = hrNR - tnR,  dNI = hrNI - tnI
                dNR.assign(hrNR).subi(tnR);
                dNI.assign(hrNI).subi(tnI);

                // negMag = sqrt(dNR^2 + dNI^2 + eps)  per-dim
                negMag.assign(dNR).muli(dNR);
                scrA.assign(dNI).muli(dNI);
                negMag.addi(scrA).addi(1e-10);
                Transforms.sqrt(negMag, false);

                double negScore = negMag.sumNumber().doubleValue();

                double posLoss = -Math.log(sigmoid(margin - posScore) + 1e-10);
                double negLoss = -Math.log(sigmoid(negScore - margin) + 1e-10);
                double loss = posLoss + negLoss;

                if (Double.isFinite(loss)) {
                    totalLoss += loss;

                    double sigPos = sigmoid(margin - posScore);
                    double sigNeg = sigmoid(negScore - margin);
                    double lwp = learningRate * sigPos * (1.0 - sigPos);
                    double lwn = learningRate * sigNeg * (1.0 - sigNeg);

                    // gnR = dNR / negMag,  gnI = dNI / negMag
                    gnR.assign(dNR).divi(negMag);
                    gnI.assign(dNI).divi(negMag);

                    // dhNR = gnR*cosT + gnI*sinT
                    dhNR.assign(gnR).muli(cosT);
                    scrA.assign(gnI).muli(sinT);
                    dhNR.addi(scrA);

                    // dhNI = -(gnR*sinT - gnI*cosT)
                    dhNI.assign(gnR).muli(sinT);
                    scrA.assign(gnI).muli(cosT);
                    dhNI.subi(scrA).negi();

                    // Apply updates (immediate sequential SGD).
                    // upd = direction * lr_weight, then subtract/add into the live matrix row.

                    // Positive head:  entityReal[h] -= dhR * lwp
                    upd.assign(dhR).muli(lwp);
                    entityRealEmbeddings.getRow(hIdx).subi(upd);
                    upd.assign(dhI).muli(lwp);
                    entityImagEmbeddings.getRow(hIdx).subi(upd);

                    // Positive tail:  entityReal[t] += gR * lwp
                    upd.assign(gR).muli(lwp);
                    entityRealEmbeddings.getRow(tIdx).addi(upd);
                    upd.assign(gI).muli(lwp);
                    entityImagEmbeddings.getRow(tIdx).addi(upd);

                    // Relation phase: phases[r] -= dtR * lwp
                    upd.assign(dtR).muli(lwp);
                    relationPhaseAngles.getRow(rIdx).subi(upd);

                    // Negative head:  entityReal[hNeg] += dhNR * lwn
                    upd.assign(dhNR).muli(lwn);
                    entityRealEmbeddings.getRow(hNegIdx).addi(upd);
                    upd.assign(dhNI).muli(lwn);
                    entityImagEmbeddings.getRow(hNegIdx).addi(upd);

                    // Negative tail:  entityReal[tNeg] -= gnR * lwn
                    upd.assign(gnR).muli(lwn);
                    entityRealEmbeddings.getRow(tNegIdx).subi(upd);
                    upd.assign(gnI).muli(lwn);
                    entityImagEmbeddings.getRow(tNegIdx).subi(upd);
                }
            }
        }

        return totalLoss / positives.size();
    }
    /**
     * Computes the L2 distance between two complex vectors.
     */
    private double computeComplexDistance(INDArray aReal, INDArray aImag,
                                           INDArray bReal, INDArray bImag) {
        INDArray diffReal = aReal.sub(bReal);
        INDArray diffImag = aImag.sub(bImag);
        INDArray magnitude = Transforms.sqrt(diffReal.mul(diffReal).add(diffImag.mul(diffImag)));
        return magnitude.sumNumber().doubleValue();
    }

    /**
     * Sigmoid activation function.
     */
    private double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    @Override
    public boolean isTraining() {
        return training.get();
    }

    @Override
    public void cancelTraining() {
        cancelRequested.set(true);
    }

    @Override
    public boolean isTrained() {
        return trained;
    }

    @Override
    public INDArray getEntityEmbedding(String entityId) {
        Integer idx = entityToIndex.get(entityId);
        if (idx == null || entityRealEmbeddings == null) return null;

        // Return concatenated [real, imaginary] embedding
        INDArray real = entityRealEmbeddings.getRow(idx);
        INDArray imag = entityImagEmbeddings.getRow(idx);
        return Nd4j.hstack(real, imag);
    }

    @Override
    public INDArray getRelationEmbedding(String relationType) {
        Integer idx = relationToIndex.get(relationType);
        if (idx == null || relationPhaseAngles == null) return null;

        // Return phase angles as the relation embedding
        return relationPhaseAngles.getRow(idx).dup();
    }

    @Override
    public Map<String, INDArray> getAllEntityEmbeddings() {
        Map<String, INDArray> result = new HashMap<>();
        for (Map.Entry<String, Integer> entry : entityToIndex.entrySet()) {
            result.put(entry.getKey(), getEntityEmbedding(entry.getKey()));
        }
        return result;
    }

    @Override
    public Map<String, INDArray> getAllRelationEmbeddings() {
        Map<String, INDArray> result = new HashMap<>();
        for (Map.Entry<String, Integer> entry : relationToIndex.entrySet()) {
            result.put(entry.getKey(), getRelationEmbedding(entry.getKey()));
        }
        return result;
    }

    @Override
    public INDArray getEntityEmbeddingMatrix() {
        if (entityRealEmbeddings == null) return null;
        // Return concatenated [real | imaginary] matrix
        return Nd4j.hstack(entityRealEmbeddings, entityImagEmbeddings);
    }

    @Override
    public INDArray getRelationEmbeddingMatrix() {
        return relationPhaseAngles != null ? relationPhaseAngles.dup() : null;
    }

    @Override
    public double scoreTriple(String head, String relation, String tail) {
        Integer hIdx = entityToIndex.get(head);
        Integer rIdx = relationToIndex.get(relation);
        Integer tIdx = entityToIndex.get(tail);

        if (hIdx == null || rIdx == null || tIdx == null) {
            return Double.MAX_VALUE;
        }

        INDArray hReal = entityRealEmbeddings.getRow(hIdx);
        INDArray hImag = entityImagEmbeddings.getRow(hIdx);
        INDArray tReal = entityRealEmbeddings.getRow(tIdx);
        INDArray tImag = entityImagEmbeddings.getRow(tIdx);
        INDArray theta = relationPhaseAngles.getRow(rIdx);

        INDArray cosTheta = Transforms.cos(theta);
        INDArray sinTheta = Transforms.sin(theta);

        INDArray hrReal = hReal.mul(cosTheta).sub(hImag.mul(sinTheta));
        INDArray hrImag = hReal.mul(sinTheta).add(hImag.mul(cosTheta));

        return computeComplexDistance(hrReal, hrImag, tReal, tImag);
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
        Integer hIdx = entityToIndex.get(head);
        Integer rIdx = relationToIndex.get(relation);

        if (hIdx == null || rIdx == null) {
            return Collections.emptyList();
        }

        INDArray hReal = entityRealEmbeddings.getRow(hIdx);
        INDArray hImag = entityImagEmbeddings.getRow(hIdx);
        INDArray theta = relationPhaseAngles.getRow(rIdx);

        INDArray cosTheta = Transforms.cos(theta);
        INDArray sinTheta = Transforms.sin(theta);

        INDArray hrReal = hReal.mul(cosTheta).sub(hImag.mul(sinTheta));
        INDArray hrImag = hReal.mul(sinTheta).add(hImag.mul(cosTheta));

        List<EmbeddingScore> scores = new ArrayList<>();
        for (int i = 0; i < indexToEntity.size(); i++) {
            INDArray tReal = entityRealEmbeddings.getRow(i);
            INDArray tImag = entityImagEmbeddings.getRow(i);
            double score = computeComplexDistance(hrReal, hrImag, tReal, tImag);
            scores.add(new EmbeddingScore(indexToEntity.get(i), score));
        }

        scores.sort(Comparator.comparingDouble(EmbeddingScore::score));

        List<EmbeddingScore> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scores.size()); i++) {
            result.add(scores.get(i).withRank(i + 1));
        }
        return result;
    }

    @Override
    public List<EmbeddingScore> predictHeads(String relation, String tail, int topK) {
        Integer rIdx = relationToIndex.get(relation);
        Integer tIdx = entityToIndex.get(tail);

        if (rIdx == null || tIdx == null) {
            return Collections.emptyList();
        }

        INDArray tReal = entityRealEmbeddings.getRow(tIdx);
        INDArray tImag = entityImagEmbeddings.getRow(tIdx);
        INDArray theta = relationPhaseAngles.getRow(rIdx);

        // Inverse rotation: multiply by conjugate of r = e^(-iθ)
        INDArray cosNegTheta = Transforms.cos(theta.neg());
        INDArray sinNegTheta = Transforms.sin(theta.neg());

        // Expected head: t ∘ r^(-1) = t ∘ e^(-iθ)
        INDArray expectedReal = tReal.mul(cosNegTheta).sub(tImag.mul(sinNegTheta));
        INDArray expectedImag = tReal.mul(sinNegTheta).add(tImag.mul(cosNegTheta));

        List<EmbeddingScore> scores = new ArrayList<>();
        for (int i = 0; i < indexToEntity.size(); i++) {
            INDArray hReal = entityRealEmbeddings.getRow(i);
            INDArray hImag = entityImagEmbeddings.getRow(i);
            double score = computeComplexDistance(hReal, hImag, expectedReal, expectedImag);
            scores.add(new EmbeddingScore(indexToEntity.get(i), score));
        }

        scores.sort(Comparator.comparingDouble(EmbeddingScore::score));

        List<EmbeddingScore> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scores.size()); i++) {
            result.add(scores.get(i).withRank(i + 1));
        }
        return result;
    }

    @Override
    public List<EmbeddingScore> predictRelations(String head, String tail, int topK) {
        Integer hIdx = entityToIndex.get(head);
        Integer tIdx = entityToIndex.get(tail);

        if (hIdx == null || tIdx == null) {
            return Collections.emptyList();
        }

        INDArray hReal = entityRealEmbeddings.getRow(hIdx);
        INDArray hImag = entityImagEmbeddings.getRow(hIdx);
        INDArray tReal = entityRealEmbeddings.getRow(tIdx);
        INDArray tImag = entityImagEmbeddings.getRow(tIdx);

        List<EmbeddingScore> scores = new ArrayList<>();
        for (int i = 0; i < indexToRelation.size(); i++) {
            INDArray theta = relationPhaseAngles.getRow(i);
            INDArray cosTheta = Transforms.cos(theta);
            INDArray sinTheta = Transforms.sin(theta);

            INDArray hrReal = hReal.mul(cosTheta).sub(hImag.mul(sinTheta));
            INDArray hrImag = hReal.mul(sinTheta).add(hImag.mul(cosTheta));

            double score = computeComplexDistance(hrReal, hrImag, tReal, tImag);
            scores.add(new EmbeddingScore(indexToRelation.get(i), score));
        }

        scores.sort(Comparator.comparingDouble(EmbeddingScore::score));

        List<EmbeddingScore> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scores.size()); i++) {
            result.add(scores.get(i).withRank(i + 1));
        }
        return result;
    }

    @Override
    public List<EmbeddingScore> findSimilarEntities(String entityId, int topK) {
        Integer idx = entityToIndex.get(entityId);
        if (idx == null) {
            return Collections.emptyList();
        }

        INDArray targetReal = entityRealEmbeddings.getRow(idx);
        INDArray targetImag = entityImagEmbeddings.getRow(idx);

        List<EmbeddingScore> scores = new ArrayList<>();
        for (int i = 0; i < indexToEntity.size(); i++) {
            String entity = indexToEntity.get(i);
            if (entity.equals(entityId)) continue;

            INDArray real = entityRealEmbeddings.getRow(i);
            INDArray imag = entityImagEmbeddings.getRow(i);

            // Compute complex cosine similarity
            double similarity = complexCosineSimilarity(targetReal, targetImag, real, imag);
            scores.add(new EmbeddingScore(entity, -similarity));
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
        Integer idx = relationToIndex.get(relationType);
        if (idx == null) {
            return Collections.emptyList();
        }

        INDArray targetTheta = relationPhaseAngles.getRow(idx);

        List<EmbeddingScore> scores = new ArrayList<>();
        for (int i = 0; i < indexToRelation.size(); i++) {
            String relation = indexToRelation.get(i);
            if (relation.equals(relationType)) continue;

            INDArray theta = relationPhaseAngles.getRow(i);

            // Compute cosine similarity of phase angles
            double similarity = cosineSimilarity(targetTheta, theta);
            scores.add(new EmbeddingScore(relation, -similarity));
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
        Integer idx1 = entityToIndex.get(entity1);
        Integer idx2 = entityToIndex.get(entity2);

        if (idx1 == null || idx2 == null) {
            return 0.0;
        }

        INDArray real1 = entityRealEmbeddings.getRow(idx1);
        INDArray imag1 = entityImagEmbeddings.getRow(idx1);
        INDArray real2 = entityRealEmbeddings.getRow(idx2);
        INDArray imag2 = entityImagEmbeddings.getRow(idx2);

        return complexCosineSimilarity(real1, imag1, real2, imag2);
    }

    /**
     * Computes cosine similarity between two complex vectors.
     */
    private double complexCosineSimilarity(INDArray aReal, INDArray aImag,
                                            INDArray bReal, INDArray bImag) {
        // Complex dot product: Re(a ∘ conj(b)) = aReal*bReal + aImag*bImag
        double realDot = aReal.mul(bReal).add(aImag.mul(bImag)).sumNumber().doubleValue();

        // Magnitudes
        double normA = Math.sqrt(aReal.mul(aReal).add(aImag.mul(aImag)).sumNumber().doubleValue());
        double normB = Math.sqrt(bReal.mul(bReal).add(bImag.mul(bImag)).sumNumber().doubleValue());

        if (normA == 0 || normB == 0) return 0;
        return realDot / (normA * normB);
    }

    /**
     * Computes cosine similarity between two real vectors.
     */
    private double cosineSimilarity(INDArray a, INDArray b) {
        double dot = a.mul(b).sumNumber().doubleValue();
        double normA = a.norm2Number().doubleValue();
        double normB = b.norm2Number().doubleValue();
        if (normA == 0 || normB == 0) return 0;
        return dot / (normA * normB);
    }

    @Override
    public void saveEmbeddings(Path outputPath) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(outputPath.toFile()))) {
            oos.writeInt(embeddingDim);
            oos.writeDouble(embeddingRange);
            oos.writeObject(entityToIndex);
            oos.writeObject(relationToIndex);
            oos.writeObject(indexToEntity);
            oos.writeObject(indexToRelation);

            // Save NDArrays as byte arrays
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Nd4j.write(entityRealEmbeddings, new DataOutputStream(baos));
            oos.writeObject(baos.toByteArray());

            baos = new ByteArrayOutputStream();
            Nd4j.write(entityImagEmbeddings, new DataOutputStream(baos));
            oos.writeObject(baos.toByteArray());

            baos = new ByteArrayOutputStream();
            Nd4j.write(relationPhaseAngles, new DataOutputStream(baos));
            oos.writeObject(baos.toByteArray());

            log.info("Saved RotatE embeddings to {}", outputPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save embeddings", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void loadEmbeddings(Path inputPath) {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(inputPath.toFile()))) {
            embeddingDim = ois.readInt();
            embeddingRange = ois.readDouble();
            entityToIndex = (Map<String, Integer>) ois.readObject();
            relationToIndex = (Map<String, Integer>) ois.readObject();
            indexToEntity = (List<String>) ois.readObject();
            indexToRelation = (List<String>) ois.readObject();

            byte[] entityRealBytes = (byte[]) ois.readObject();
            entityRealEmbeddings = Nd4j.read(new DataInputStream(new ByteArrayInputStream(entityRealBytes)));

            byte[] entityImagBytes = (byte[]) ois.readObject();
            entityImagEmbeddings = Nd4j.read(new DataInputStream(new ByteArrayInputStream(entityImagBytes)));

            byte[] relationBytes = (byte[]) ois.readObject();
            relationPhaseAngles = Nd4j.read(new DataInputStream(new ByteArrayInputStream(relationBytes)));

            trained = true;
            log.info("Loaded RotatE embeddings from {}", inputPath);
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to load embeddings", e);
        }
    }

    @Override
    public void importEntityEmbeddings(Map<String, INDArray> entityEmbeddings) {
        if (entityEmbeddings.isEmpty()) return;

        // Assume embeddings are concatenated [real | imaginary]
        int fullDim = entityEmbeddings.values().iterator().next().columns();
        this.embeddingDim = fullDim / 2;
        this.embeddingRange = 6.0 / embeddingDim; // Default margin

        // Build index
        entityToIndex.clear();
        indexToEntity.clear();
        int idx = 0;
        for (String entity : entityEmbeddings.keySet()) {
            entityToIndex.put(entity, idx++);
            indexToEntity.add(entity);
        }

        // Build embedding matrices
        this.entityRealEmbeddings = Nd4j.zeros(entityToIndex.size(), embeddingDim);
        this.entityImagEmbeddings = Nd4j.zeros(entityToIndex.size(), embeddingDim);

        for (Map.Entry<String, INDArray> entry : entityEmbeddings.entrySet()) {
            int i = entityToIndex.get(entry.getKey());
            INDArray emb = entry.getValue();
            this.entityRealEmbeddings.putRow(i, emb.get(NDArrayIndex.all(), NDArrayIndex.interval(0, embeddingDim)));
            this.entityImagEmbeddings.putRow(i, emb.get(NDArrayIndex.all(), NDArrayIndex.interval(embeddingDim, fullDim)));
        }

        trained = true;
    }

    @Override
    public void importRelationEmbeddings(Map<String, INDArray> relationEmbeddings) {
        if (relationEmbeddings.isEmpty()) return;

        int dim = relationEmbeddings.values().iterator().next().columns();
        if (this.embeddingDim == 0) {
            this.embeddingDim = dim;
        }

        relationToIndex.clear();
        indexToRelation.clear();
        int idx = 0;
        for (String relation : relationEmbeddings.keySet()) {
            relationToIndex.put(relation, idx++);
            indexToRelation.add(relation);
        }

        this.relationPhaseAngles = Nd4j.zeros(relationToIndex.size(), dim);
        for (Map.Entry<String, INDArray> entry : relationEmbeddings.entrySet()) {
            int i = relationToIndex.get(entry.getKey());
            this.relationPhaseAngles.putRow(i, entry.getValue());
        }
    }

    @Override
    public void close() throws Exception {
        if (entityRealEmbeddings != null) {
            entityRealEmbeddings.close();
        }
        if (entityImagEmbeddings != null) {
            entityImagEmbeddings.close();
        }
        if (relationPhaseAngles != null) {
            relationPhaseAngles.close();
        }
    }

    // Inner class for NDArray indexing (if not available)
    private static class NDArrayIndex {
        public static org.nd4j.linalg.indexing.INDArrayIndex all() {
            return org.nd4j.linalg.indexing.NDArrayIndex.all();
        }
        public static org.nd4j.linalg.indexing.INDArrayIndex interval(int begin, int end) {
            return org.nd4j.linalg.indexing.NDArrayIndex.interval(begin, end);
        }
    }
}
