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
import org.bytedeco.javacpp.Pointer;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TransE (Translating Embeddings) knowledge graph embedding model.
 *
 * <p>TransE models relations as translations in embedding space:
 * <b>h + r ≈ t</b> for a triple (head, relation, tail).
 *
 * <p>The scoring function is: score(h, r, t) = ||h + r - t||
 * Lower scores indicate more plausible triples.
 *
 * <p><b>Memory design</b>: the hot training inner loop in {@link #trainBatch} runs
 * entirely in pure-Java {@code float[]} arithmetic so that ZERO ND4J native objects
 * ({@code OpaqueNDArray*} C++ peers) are created per iteration.  The previous
 * {@code INDArray.divi/addi/subi/muli/assign} approach created one
 * {@code OpaqueNDArray*} per call, tracked by JavaCPP's {@code Pointer.physicalBytes()}.
 * At batchSize=256 × negativeSamples=10 × ~15 ops/pair = 38,400 tracked native objects
 * per batch — never reclaimed until GC fired — this grew {@code physicalBytes} to the
 * JavaCPP cap (82 GB on the real graph) and crashed the subprocess.
 *
 * <p>The persistent embedding matrices are still {@link INDArray} (ND4J manages their
 * off-heap buffers).  Training math runs on {@code float[][]} mirrors of those matrices
 * (allocated ONCE per {@link #train} call); the mirrors are synced back into the INDArray
 * matrices once per epoch — reads via the flat {@link DataBuffer#asFloat()} and writes via
 * {@link DataBuffer#put(int, float)} straight into the existing off-heap buffer, so NO
 * intermediate {@code INDArray} / {@code OpaqueNDArray} object is created during a sync
 * either (vs tens of thousands per batch in the old per-op code).
 *
 * <p>Reference: Bordes et al., "Translating Embeddings for Modeling
 * Multi-relational Data", NeurIPS 2013.
 */
public class TransEModel implements KGEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(TransEModel.class);

    // Embeddings
    private INDArray entityEmbeddings;   // [numEntities, embeddingDim]
    private INDArray relationEmbeddings; // [numRelations, embeddingDim]

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

    /**
     * Creates an empty TransE model.
     */
    public TransEModel() {
        this.entityToIndex = new HashMap<>();
        this.relationToIndex = new HashMap<>();
        this.indexToEntity = new ArrayList<>();
        this.indexToRelation = new ArrayList<>();
    }

    @Override
    public String getAlgorithmName() {
        return "TransE";
    }

    @Override
    public KGEmbeddingAlgorithm getAlgorithm() {
        return KGEmbeddingAlgorithm.TRANSE;
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
            // importEntityEmbeddings / importRelationEmbeddings call clear() on the index maps and
            // set entityEmbeddings; buildVocabulary also clears them. So we must save the prior
            // index-to-entity mapping and the prior INDArray matrix BEFORE the vocab rebuild.
            Map<String, Integer> priorEntityIndex  = new HashMap<>(entityToIndex);
            INDArray priorEntityMatrix  = entityEmbeddings;   // null on cold start
            Map<String, Integer> priorRelationIndex = new HashMap<>(relationToIndex);
            INDArray priorRelationMatrix = relationEmbeddings; // null on cold start

            // Build entity and relation vocabularies
            buildVocabulary(triples);

            this.embeddingDim = config.embeddingDim();

            // ── Warm-start vs cold-start initialization ──────────────────────────────
            // Warm-start: prior embeddings were loaded via importEntityEmbeddings(). If the
            // persisted dim matches the current config, seed known entities from the prior
            // matrix and random-init only new entities/relations. If the dim has changed (config
            // was modified since the last run), fall back to full random init and log a warning.
            boolean hasPriorEntity   = priorEntityMatrix != null && !priorEntityIndex.isEmpty()
                    && (int) priorEntityMatrix.columns() == embeddingDim;
            boolean hasPriorRelation = priorRelationMatrix != null && !priorRelationIndex.isEmpty()
                    && (int) priorRelationMatrix.columns() == embeddingDim;

            if (priorEntityMatrix != null && !hasPriorEntity) {
                log.warn("TransE warm-start: dim mismatch (persisted={} config={}) — cold-starting",
                        priorEntityMatrix.columns(), embeddingDim);
            }

            log.info("Starting TransE training: {} entities, {} relations, {} triples (warmStart={})",
                    entityToIndex.size(), relationToIndex.size(), triples.size(), hasPriorEntity);
            logMem("after-buildVocabulary(entities=" + entityToIndex.size()
                    + ",relations=" + relationToIndex.size() + ",dim=" + embeddingDim + ")");

            // Initialize entity embeddings
            if (hasPriorEntity) {
                // Warm-start: allocate a fresh matrix for the (possibly larger) current vocabulary,
                // then copy known entity rows from the prior matrix; random-init new entities only.
                int numEntitiesNew = entityToIndex.size();
                entityEmbeddings = EmbeddingInitializer.uniformTransE(numEntitiesNew, embeddingDim);
                int seeded = 0, fresh = 0;
                for (Map.Entry<String, Integer> entry : entityToIndex.entrySet()) {
                    Integer priorIdx = priorEntityIndex.get(entry.getKey());
                    if (priorIdx != null) {
                        // Known entity: copy the prior row verbatim
                        entityEmbeddings.putRow(entry.getValue(), priorEntityMatrix.getRow(priorIdx));
                        seeded++;
                    } else {
                        // New entity: keep the random row already set by uniformTransE
                        fresh++;
                    }
                }
                log.info("TransE warm-start: {} entities seeded from prior, {} new (random-init)",
                        seeded, fresh);
            } else {
                // Cold start: full random init
                entityEmbeddings = EmbeddingInitializer.uniformTransE(entityToIndex.size(), embeddingDim);
            }

            // Initialize relation embeddings
            if (hasPriorRelation) {
                int numRelationsNew = relationToIndex.size();
                relationEmbeddings = EmbeddingInitializer.uniformTransE(numRelationsNew, embeddingDim);
                int seeded = 0, fresh = 0;
                for (Map.Entry<String, Integer> entry : relationToIndex.entrySet()) {
                    Integer priorIdx = priorRelationIndex.get(entry.getKey());
                    if (priorIdx != null) {
                        relationEmbeddings.putRow(entry.getValue(), priorRelationMatrix.getRow(priorIdx));
                        seeded++;
                    } else {
                        fresh++;
                    }
                }
                log.info("TransE warm-start: {} relations seeded from prior, {} new (random-init)",
                        seeded, fresh);
            } else {
                relationEmbeddings = EmbeddingInitializer.uniformTransE(relationToIndex.size(), embeddingDim);
            }

            // Normalize entity embeddings (always — prior vectors may not be normalised after the
            // last epoch's L2-normalization step was applied to the INDArray but not written back
            // through the metadata encode/decode round-trip at full float32 precision)
            EmbeddingInitializer.normalizeRowsInPlace(entityEmbeddings);
            // Expected off-heap here ≈ (entities+relations)×dim×4 bytes. If off-heap is far larger than
            // that, the embedding count/dim is inflated; if RSS is large but off-heap is small, the bloat
            // is JVM heap (e.g. the triples list / vocabulary), not ND4J.
            logMem("after-embedding-init");

            // Create negative sampler
            NegativeSampler sampler = new NegativeSampler(entityToIndex.keySet(), new HashSet<>(triples));

            // Training loop
            int totalBatches = (int) Math.ceil((double) triples.size() / config.batchSize());

            // Flat float[][] mirror of the embedding matrices.
            // All hot-path math in trainBatch runs in pure Java over these arrays, avoiding
            // ND4J op dispatch (and the OpaqueNDArray* C++ object created per op) entirely.
            // After each epoch the mirror is synced BACK to the INDArray matrices so that
            // normalizeRowsInPlace() and the final embedding export still work via ND4J.
            int numEntities  = entityToIndex.size();
            int numRelations = relationToIndex.size();
            float[][] entityMirror   = new float[numEntities][embeddingDim];
            float[][] relationMirror = new float[numRelations][embeddingDim];
            copyFromINDArray(entityEmbeddings,   entityMirror,   numEntities,  embeddingDim);
            copyFromINDArray(relationEmbeddings, relationMirror, numRelations, embeddingDim);

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

                for (int i = 0; i < shuffled.size(); i += config.batchSize()) {
                    int end = Math.min(i + config.batchSize(), shuffled.size());
                    List<Triple> batch = shuffled.subList(i, end);

                    // Generate negative samples
                    List<Triple> negatives = sampler.corrupt(batch, config.negativeSamples());

                    // Pure-Java inner loop: ZERO ND4J native objects created.
                    double batchLoss = trainBatch(batch, negatives, config.learningRate(),
                            config.margin(), entityMirror, relationMirror);
                    epochLoss += batchLoss;
                    batchesCompleted++;
                    if (batchesCompleted % 25 == 0) {
                        logMem("epoch=" + epoch + " batch=" + batchesCompleted);
                    }
                }

                // Sync mirrors → INDArray matrices so normalizeRowsInPlace works on the latest values.
                copyToINDArray(entityMirror,   entityEmbeddings,   numEntities,  embeddingDim);
                copyToINDArray(relationMirror, relationEmbeddings, numRelations, embeddingDim);

                // Normalize entity embeddings after each epoch.
                // normalizeRowsInPlace operates on the INDArray; after the call, sync the
                // updated values back into the float[][] mirror so the next epoch's inner
                // loop sees normalized values.
                if (config.normalizeEntities()) {
                    EmbeddingInitializer.normalizeRowsInPlace(entityEmbeddings);
                    copyFromINDArray(entityEmbeddings, entityMirror, numEntities, embeddingDim);
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

            log.info("TransE training completed in {}ms", trainingTime);

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
            log.error("TransE training failed", e);
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
     * Trains on a batch of positive and negative triples using pure-Java {@code float[]}
     * arithmetic — ZERO ND4J native objects ({@code OpaqueNDArray*} C++ peers) are created
     * anywhere inside this method.
     *
     * <p>The root cause of the OOM was that every {@code INDArray.divi/addi/subi/muli/assign}
     * call dispatches through {@code NativeOpExecutioner.exec()} →
     * {@code OpaqueNDArray.fromINDArray()} → {@code Nd4jCpu.create(Native Method)} →
     * {@code Pointer.init()}.  That adds the new {@code OpaqueNDArray*} to JavaCPP's
     * {@code Pointer.physicalBytes()} tracking.  At batchSize=256 × negativeSamples=10 ×
     * ~15 ops per (pos,neg) pair = 38,400 tracked native objects per batch, never freed
     * until GC ran — this grew {@code physicalBytes} to the 82 GB cap within 70 s.
     *
     * <p>All math is now done on {@code float[][]} mirrors of the embedding matrices.  The
     * mirrors are kept in sync with the {@link INDArray} matrices across epochs (see
     * {@link #train}) so that ND4J's {@code normalizeRowsInPlace} and final export still
     * work correctly.
     *
     * <p><b>Sequential-SGD semantics preserved:</b>
     * <ul>
     *   <li>For positive {@code i}: E[h], R[r], E[t] are snapped into temporary
     *       {@code float[]} scratch arrays at the start of the outer iteration.</li>
     *   <li>For negative {@code j}: E[hNeg], E[tNeg] are read live from
     *       {@code entityMirror} (which reflects all updates applied so far).</li>
     *   <li>Gradient updates are applied immediately to {@code entityMirror} /
     *       {@code relationMirror} before the next pair is processed.</li>
     * </ul>
     *
     * @param positives      positive triples for this batch
     * @param negatives      pre-generated negatives (size = positives × negPerPos)
     * @param learningRate   SGD step size
     * @param margin         margin for the ranking loss
     * @param entityMirror   float[][] mirror of entityEmbeddings [numEntities][dim]
     * @param relationMirror float[][] mirror of relationEmbeddings [numRelations][dim]
     * @return totalLoss / positives.size()
     */
    private double trainBatch(List<Triple> positives, List<Triple> negatives,
                              double learningRate, double margin,
                              float[][] entityMirror, float[][] relationMirror) {
        double totalLoss = 0.0;
        int negPerPos = negatives.isEmpty() ? 0 : negatives.size() / positives.size();
        if (negPerPos == 0) return 0.0;

        final int dim = embeddingDim;
        // Thread-local scratch arrays: allocated ONCE here, reused for the entire batch.
        // Pure Java arrays → no ND4J / JavaCPP involvement.
        float[] diff        = new float[dim];  // E[h] + R[r] - E[t]
        float[] diffNorm    = new float[dim];  // diff / (||diff|| + eps)
        float[] rSnap       = new float[dim];  // snapshot of R[r] (snapped at outer-i start)
        float[] diffNeg     = new float[dim];  // E[hNeg] + R[r] - E[tNeg]
        float[] diffNegNorm = new float[dim];  // diffNeg / (||diffNeg|| + eps)

        for (int i = 0; i < positives.size(); i++) {
            Triple pos = positives.get(i);

            Integer hIdx = entityToIndex.get(pos.head());
            Integer rIdx = relationToIndex.get(pos.relation());
            Integer tIdx = entityToIndex.get(pos.tail());
            if (hIdx == null || rIdx == null || tIdx == null) continue;

            // Snap R[r] and compute diff = E[h] + R[r] - E[t]
            float[] hRow = entityMirror[hIdx];
            float[] rRow = relationMirror[rIdx];
            float[] tRow = entityMirror[tIdx];
            System.arraycopy(rRow, 0, rSnap, 0, dim);  // rSnap = R[r]
            double posScore = 0.0;
            for (int d = 0; d < dim; d++) {
                diff[d] = hRow[d] + rSnap[d] - tRow[d];
                posScore += diff[d] * diff[d];
            }
            posScore = Math.sqrt(posScore);

            // diffNorm = diff / (posScore + eps)
            double posScoreEps = posScore + 1e-10;
            for (int d = 0; d < dim; d++) {
                diffNorm[d] = (float) (diff[d] / posScoreEps);
            }

            for (int j = 0; j < negPerPos; j++) {
                Triple neg = negatives.get(i * negPerPos + j);

                Integer hNegIdx = entityToIndex.get(neg.head());
                Integer tNegIdx = entityToIndex.get(neg.tail());
                if (hNegIdx == null || tNegIdx == null) continue;

                // diffNeg = E[hNeg] + rSnap - E[tNeg]  (E[hNeg]/E[tNeg] read live — sequential SGD)
                float[] hNegRow = entityMirror[hNegIdx];
                float[] tNegRow = entityMirror[tNegIdx];
                double negScore = 0.0;
                for (int d = 0; d < dim; d++) {
                    diffNeg[d] = hNegRow[d] + rSnap[d] - tNegRow[d];
                    negScore += diffNeg[d] * diffNeg[d];
                }
                negScore = Math.sqrt(negScore);

                double loss = Math.max(0.0, margin + posScore - negScore);
                if (loss > 0.0) {
                    totalLoss += loss;

                    // diffNegNorm = diffNeg / (negScore + eps)
                    double negScoreEps = negScore + 1e-10;
                    for (int d = 0; d < dim; d++) {
                        diffNegNorm[d] = (float) (diffNeg[d] / negScoreEps);
                    }

                    // Apply updates to mirrors (sequential SGD):
                    //   E[h]    -= lr * diffNorm
                    //   E[t]    += lr * diffNorm
                    //   R[r]    -= lr * diffNorm
                    //   E[hNeg] += lr * diffNegNorm
                    //   E[tNeg] -= lr * diffNegNorm
                    float[] hMut    = entityMirror[hIdx];
                    float[] tMut    = entityMirror[tIdx];
                    float[] rMut    = relationMirror[rIdx];
                    float[] hNegMut = entityMirror[hNegIdx];
                    float[] tNegMut = entityMirror[tNegIdx];
                    for (int d = 0; d < dim; d++) {
                        float dn  = (float) (learningRate * diffNorm[d]);
                        float dnn = (float) (learningRate * diffNegNorm[d]);
                        hMut[d]    -= dn;
                        tMut[d]    += dn;
                        rMut[d]    -= dn;
                        hNegMut[d] += dnn;
                        tNegMut[d] -= dnn;
                    }
                }
            }
        }

        return totalLoss / positives.size();
    }

    // ── float[][] ↔ INDArray sync helpers ────────────────────────────────────

    /**
     * Copies rows from an {@link INDArray} matrix into a pre-allocated {@code float[][]} mirror.
     *
     * <p>Reads all values via the flat DataBuffer (one array-backed call) to avoid creating
     * per-row INDArray view objects.
     */
    private static void copyFromINDArray(INDArray matrix, float[][] mirror, int rows, int cols) {
        // Read the entire matrix as one flat float array (single DataBuffer access).
        float[] flat = matrix.data().asFloat();
        for (int r = 0; r < rows; r++) {
            System.arraycopy(flat, r * cols, mirror[r], 0, cols);
        }
    }

    /**
     * Writes a {@code float[][]} mirror back into an {@link INDArray} matrix.
     *
     * <p>Uses {@link DataBuffer#put(int, float)} to write scalar values directly into the
     * underlying off-heap buffer — ZERO intermediate {@link INDArray} objects created,
     * ZERO native-object churn tracked by JavaCPP.
     */
    private static void copyToINDArray(float[][] mirror, INDArray matrix, int rows, int cols) {
        DataBuffer buf = matrix.data();
        for (int r = 0; r < rows; r++) {
            int base = r * cols;
            float[] row = mirror[r];
            for (int c = 0; c < cols; c++) {
                buf.put(base + c, row[c]);
            }
        }
    }

    /**
     * Computes TransE score: ||h + r - t||_2
     */
    private double scoreVectors(INDArray h, INDArray r, INDArray t) {
        INDArray diff = h.add(r).sub(t);
        return diff.norm2Number().doubleValue();
    }

    /**
     * Log a native-memory snapshot (JavaCPP off-heap vs total RSS vs JVM heap) at a labelled training
     * phase, so the OOM source can be pinpointed: ND4J off-heap should be only ~(entities+relations)×dim×4
     * bytes here, so a large off-heap value means an inflated embedding count/dim, while a large RSS with
     * small off-heap means the JVM heap (triples/vocabulary) is the culprit. Never throws.
     */
    private void logMem(String phase) {
        try {
            long rssMb = Pointer.physicalBytes() >> 20;
            long offHeapMb = Pointer.totalBytes() >> 20;
            long capMb = Pointer.maxPhysicalBytes() >> 20;
            Runtime rt = Runtime.getRuntime();
            long heapUsedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
            // WARN level on purpose: the learning subprocess's slf4j suppresses INFO, so an INFO probe
            // never reaches the captured stderr. WARN is captured (same level the CUDA-probe notice uses).
            log.warn("[NATIVE-MEM] phase={} rssMB={} offHeapMB={} capMB={} heapUsedMB={}",
                    phase, rssMb, offHeapMb, capMb, heapUsedMb);
        } catch (Throwable ignored) {
            // Instrumentation must never destabilise training.
        }
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
        if (idx == null || entityEmbeddings == null) return null;
        return entityEmbeddings.getRow(idx).dup();
    }

    @Override
    public INDArray getRelationEmbedding(String relationType) {
        Integer idx = relationToIndex.get(relationType);
        if (idx == null || relationEmbeddings == null) return null;
        return relationEmbeddings.getRow(idx).dup();
    }

    @Override
    public Map<String, INDArray> getAllEntityEmbeddings() {
        Map<String, INDArray> result = new HashMap<>();
        for (Map.Entry<String, Integer> entry : entityToIndex.entrySet()) {
            result.put(entry.getKey(), entityEmbeddings.getRow(entry.getValue()).dup());
        }
        return result;
    }

    @Override
    public Map<String, INDArray> getAllRelationEmbeddings() {
        Map<String, INDArray> result = new HashMap<>();
        for (Map.Entry<String, Integer> entry : relationToIndex.entrySet()) {
            result.put(entry.getKey(), relationEmbeddings.getRow(entry.getValue()).dup());
        }
        return result;
    }

    @Override
    public INDArray getEntityEmbeddingMatrix() {
        return entityEmbeddings != null ? entityEmbeddings.dup() : null;
    }

    @Override
    public INDArray getRelationEmbeddingMatrix() {
        return relationEmbeddings != null ? relationEmbeddings.dup() : null;
    }

    @Override
    public double scoreTriple(String head, String relation, String tail) {
        INDArray h = getEntityEmbedding(head);
        INDArray r = getRelationEmbedding(relation);
        INDArray t = getEntityEmbedding(tail);

        if (h == null || r == null || t == null) {
            return Double.MAX_VALUE;
        }

        return scoreVectors(h, r, t);
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
        INDArray h = getEntityEmbedding(head);
        INDArray r = getRelationEmbedding(relation);

        if (h == null || r == null) {
            return Collections.emptyList();
        }

        // h + r is the expected tail position
        INDArray expected = h.add(r);

        // Score all entities as tails
        List<EmbeddingScore> scores = new ArrayList<>();
        for (int i = 0; i < indexToEntity.size(); i++) {
            INDArray t = entityEmbeddings.getRow(i);
            double score = expected.sub(t).norm2Number().doubleValue();
            scores.add(new EmbeddingScore(indexToEntity.get(i), score));
        }

        // Sort by score (ascending) and take top-k
        scores.sort(Comparator.comparingDouble(EmbeddingScore::score));

        List<EmbeddingScore> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scores.size()); i++) {
            result.add(scores.get(i).withRank(i + 1));
        }
        return result;
    }

    @Override
    public List<EmbeddingScore> predictHeads(String relation, String tail, int topK) {
        INDArray r = getRelationEmbedding(relation);
        INDArray t = getEntityEmbedding(tail);

        if (r == null || t == null) {
            return Collections.emptyList();
        }

        // Expected head: t - r
        INDArray expected = t.sub(r);

        List<EmbeddingScore> scores = new ArrayList<>();
        for (int i = 0; i < indexToEntity.size(); i++) {
            INDArray h = entityEmbeddings.getRow(i);
            double score = expected.sub(h).norm2Number().doubleValue();
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
        INDArray h = getEntityEmbedding(head);
        INDArray t = getEntityEmbedding(tail);

        if (h == null || t == null) {
            return Collections.emptyList();
        }

        // Expected relation: t - h
        INDArray expected = t.sub(h);

        List<EmbeddingScore> scores = new ArrayList<>();
        for (int i = 0; i < indexToRelation.size(); i++) {
            INDArray r = relationEmbeddings.getRow(i);
            double score = expected.sub(r).norm2Number().doubleValue();
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
        INDArray target = getEntityEmbedding(entityId);
        if (target == null) {
            return Collections.emptyList();
        }

        List<EmbeddingScore> scores = new ArrayList<>();
        for (int i = 0; i < indexToEntity.size(); i++) {
            String entity = indexToEntity.get(i);
            if (entity.equals(entityId)) continue;

            INDArray emb = entityEmbeddings.getRow(i);
            double similarity = cosineSimilarity(target, emb);
            scores.add(new EmbeddingScore(entity, -similarity)); // Negate for sorting
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
        if (target == null) {
            return Collections.emptyList();
        }

        List<EmbeddingScore> scores = new ArrayList<>();
        for (int i = 0; i < indexToRelation.size(); i++) {
            String relation = indexToRelation.get(i);
            if (relation.equals(relationType)) continue;

            INDArray emb = relationEmbeddings.getRow(i);
            double similarity = cosineSimilarity(target, emb);
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
        INDArray e1 = getEntityEmbedding(entity1);
        INDArray e2 = getEntityEmbedding(entity2);

        if (e1 == null || e2 == null) {
            return 0.0;
        }

        return cosineSimilarity(e1, e2);
    }

    /**
     * Computes cosine similarity between two vectors.
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
            oos.writeObject(entityToIndex);
            oos.writeObject(relationToIndex);
            oos.writeObject(indexToEntity);
            oos.writeObject(indexToRelation);

            // Save NDArrays as byte arrays
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Nd4j.write(entityEmbeddings, new DataOutputStream(baos));
            oos.writeObject(baos.toByteArray());

            baos = new ByteArrayOutputStream();
            Nd4j.write(relationEmbeddings, new DataOutputStream(baos));
            oos.writeObject(baos.toByteArray());

            log.info("Saved TransE embeddings to {}", outputPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save embeddings", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void loadEmbeddings(Path inputPath) {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(inputPath.toFile()))) {
            embeddingDim = ois.readInt();
            entityToIndex = (Map<String, Integer>) ois.readObject();
            relationToIndex = (Map<String, Integer>) ois.readObject();
            indexToEntity = (List<String>) ois.readObject();
            indexToRelation = (List<String>) ois.readObject();

            byte[] entityBytes = (byte[]) ois.readObject();
            entityEmbeddings = Nd4j.read(new DataInputStream(new ByteArrayInputStream(entityBytes)));

            byte[] relationBytes = (byte[]) ois.readObject();
            relationEmbeddings = Nd4j.read(new DataInputStream(new ByteArrayInputStream(relationBytes)));

            trained = true;
            log.info("Loaded TransE embeddings from {}", inputPath);
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to load embeddings", e);
        }
    }

    @Override
    public void importEntityEmbeddings(Map<String, INDArray> entityEmbeddings) {
        if (entityEmbeddings.isEmpty()) return;

        // Infer dimension from first embedding
        int dim = entityEmbeddings.values().iterator().next().columns();
        this.embeddingDim = dim;

        // Build index
        entityToIndex.clear();
        indexToEntity.clear();
        int idx = 0;
        for (String entity : entityEmbeddings.keySet()) {
            entityToIndex.put(entity, idx++);
            indexToEntity.add(entity);
        }

        // Build embedding matrix
        this.entityEmbeddings = Nd4j.zeros(entityToIndex.size(), dim);
        for (Map.Entry<String, INDArray> entry : entityEmbeddings.entrySet()) {
            int i = entityToIndex.get(entry.getKey());
            this.entityEmbeddings.putRow(i, entry.getValue());
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

        this.relationEmbeddings = Nd4j.zeros(relationToIndex.size(), dim);
        for (Map.Entry<String, INDArray> entry : relationEmbeddings.entrySet()) {
            int i = relationToIndex.get(entry.getKey());
            this.relationEmbeddings.putRow(i, entry.getValue());
        }
    }

    @Override
    public void close() throws Exception {
        if (entityEmbeddings != null) {
            entityEmbeddings.close();
        }
        if (relationEmbeddings != null) {
            relationEmbeddings.close();
        }
    }
}
