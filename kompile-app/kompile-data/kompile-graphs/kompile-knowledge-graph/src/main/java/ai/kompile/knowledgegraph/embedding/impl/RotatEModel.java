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
            // Build entity and relation vocabularies
            buildVocabulary(triples);

            this.embeddingDim = config.embeddingDim();
            // Embedding range: γ/dim (γ is margin)
            this.embeddingRange = config.margin() / embeddingDim;

            log.info("Starting RotatE training: {} entities, {} relations, {} triples",
                    entityToIndex.size(), relationToIndex.size(), triples.size());

            // Initialize complex embeddings
            initializeEmbeddings();

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

                    // Compute loss and update
                    double batchLoss = trainBatch(batch, negatives, config.learningRate(), config.margin());
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
     * @return The average loss for this batch
     */
    private double trainBatch(List<Triple> positives, List<Triple> negatives,
                              double learningRate, double margin) {
        double totalLoss = 0.0;
        int negPerPos = negatives.size() / positives.size();

        for (int i = 0; i < positives.size(); i++) {
            Triple pos = positives.get(i);

            Integer hIdx = entityToIndex.get(pos.head());
            Integer rIdx = relationToIndex.get(pos.relation());
            Integer tIdx = entityToIndex.get(pos.tail());

            if (hIdx == null || rIdx == null || tIdx == null) continue;

            // Get embeddings
            INDArray hReal = entityRealEmbeddings.getRow(hIdx).dup();
            INDArray hImag = entityImagEmbeddings.getRow(hIdx).dup();
            INDArray tReal = entityRealEmbeddings.getRow(tIdx).dup();
            INDArray tImag = entityImagEmbeddings.getRow(tIdx).dup();
            INDArray theta = relationPhaseAngles.getRow(rIdx).dup();

            // Compute h ∘ r in complex space
            // (h_re + i*h_im) * (cos(θ) + i*sin(θ)) =
            // (h_re*cos(θ) - h_im*sin(θ)) + i*(h_re*sin(θ) + h_im*cos(θ))
            INDArray cosTheta = Transforms.cos(theta);
            INDArray sinTheta = Transforms.sin(theta);

            INDArray hrReal = hReal.mul(cosTheta).sub(hImag.mul(sinTheta));
            INDArray hrImag = hReal.mul(sinTheta).add(hImag.mul(cosTheta));

            // Positive score: ||h ∘ r - t||
            double posScore = computeComplexDistance(hrReal, hrImag, tReal, tImag);

            // Process negatives for this positive
            for (int j = 0; j < negPerPos; j++) {
                Triple neg = negatives.get(i * negPerPos + j);

                Integer hNegIdx = entityToIndex.get(neg.head());
                Integer tNegIdx = entityToIndex.get(neg.tail());

                if (hNegIdx == null || tNegIdx == null) continue;

                INDArray hNegReal = entityRealEmbeddings.getRow(hNegIdx).dup();
                INDArray hNegImag = entityImagEmbeddings.getRow(hNegIdx).dup();
                INDArray tNegReal = entityRealEmbeddings.getRow(tNegIdx).dup();
                INDArray tNegImag = entityImagEmbeddings.getRow(tNegIdx).dup();

                // Compute h_neg ∘ r
                INDArray hrNegReal = hNegReal.mul(cosTheta).sub(hNegImag.mul(sinTheta));
                INDArray hrNegImag = hNegReal.mul(sinTheta).add(hNegImag.mul(cosTheta));

                // Negative score
                double negScore = computeComplexDistance(hrNegReal, hrNegImag, tNegReal, tNegImag);

                // Self-adversarial negative sampling loss
                // L = -log σ(γ - ||h ∘ r - t||) - Σ log σ(||h' ∘ r - t'|| - γ)
                double posLoss = -Math.log(sigmoid(margin - posScore) + 1e-10);
                double negLoss = -Math.log(sigmoid(negScore - margin) + 1e-10);
                double loss = posLoss + negLoss;

                if (Double.isFinite(loss)) {
                    totalLoss += loss;

                    // Gradient updates using simplified SGD
                    updateGradients(hIdx, rIdx, tIdx, hNegIdx, tNegIdx,
                            hReal, hImag, hrReal, hrImag, tReal, tImag,
                            hNegReal, hNegImag, hrNegReal, hrNegImag, tNegReal, tNegImag,
                            theta, cosTheta, sinTheta,
                            posScore, negScore, margin, learningRate);
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
     * Updates embeddings using gradient descent.
     */
    private void updateGradients(int hIdx, int rIdx, int tIdx, int hNegIdx, int tNegIdx,
                                  INDArray hReal, INDArray hImag,
                                  INDArray hrReal, INDArray hrImag,
                                  INDArray tReal, INDArray tImag,
                                  INDArray hNegReal, INDArray hNegImag,
                                  INDArray hrNegReal, INDArray hrNegImag,
                                  INDArray tNegReal, INDArray tNegImag,
                                  INDArray theta, INDArray cosTheta, INDArray sinTheta,
                                  double posScore, double negScore,
                                  double margin, double lr) {

        // Gradient of positive score w.r.t. embeddings
        // d/d_h ||h ∘ r - t|| = (h ∘ r - t) * conj(r) / ||h ∘ r - t||
        INDArray diffReal = hrReal.sub(tReal);
        INDArray diffImag = hrImag.sub(tImag);
        INDArray magnitude = Transforms.sqrt(diffReal.mul(diffReal).add(diffImag.mul(diffImag)).add(1e-10));

        INDArray gradReal = diffReal.div(magnitude);
        INDArray gradImag = diffImag.div(magnitude);

        double sigPos = sigmoid(margin - posScore);
        double gradWeight = sigPos * (1 - sigPos);

        // Update positive head
        INDArray dhReal = gradReal.mul(cosTheta).add(gradImag.mul(sinTheta)).mul(lr * gradWeight);
        INDArray dhImag = gradReal.mul(sinTheta).sub(gradImag.mul(cosTheta)).neg().mul(lr * gradWeight);

        entityRealEmbeddings.getRow(hIdx).subi(dhReal);
        entityImagEmbeddings.getRow(hIdx).subi(dhImag);

        // Update positive tail (opposite direction)
        entityRealEmbeddings.getRow(tIdx).addi(gradReal.mul(lr * gradWeight));
        entityImagEmbeddings.getRow(tIdx).addi(gradImag.mul(lr * gradWeight));

        // Update relation phase angles
        // d/d_θ = (h_re*(-sin(θ)) - h_im*cos(θ)) * grad_real +
        //         (h_re*cos(θ) + h_im*(-sin(θ))) * grad_imag
        INDArray dTheta = hReal.mul(sinTheta).neg().sub(hImag.mul(cosTheta)).mul(gradReal)
                .add(hReal.mul(cosTheta).sub(hImag.mul(sinTheta)).mul(gradImag))
                .mul(lr * gradWeight);
        relationPhaseAngles.getRow(rIdx).subi(dTheta);

        // Gradient of negative score (opposite direction)
        INDArray diffNegReal = hrNegReal.sub(tNegReal);
        INDArray diffNegImag = hrNegImag.sub(tNegImag);
        INDArray magNeg = Transforms.sqrt(diffNegReal.mul(diffNegReal).add(diffNegImag.mul(diffNegImag)).add(1e-10));

        INDArray gradNegReal = diffNegReal.div(magNeg);
        INDArray gradNegImag = diffNegImag.div(magNeg);

        double sigNeg = sigmoid(negScore - margin);
        double gradNegWeight = sigNeg * (1 - sigNeg);

        // Update negative head (push apart)
        INDArray dhNegReal = gradNegReal.mul(cosTheta).add(gradNegImag.mul(sinTheta)).mul(lr * gradNegWeight);
        INDArray dhNegImag = gradNegReal.mul(sinTheta).sub(gradNegImag.mul(cosTheta)).neg().mul(lr * gradNegWeight);

        entityRealEmbeddings.getRow(hNegIdx).addi(dhNegReal);
        entityImagEmbeddings.getRow(hNegIdx).addi(dhNegImag);

        // Update negative tail
        entityRealEmbeddings.getRow(tNegIdx).subi(gradNegReal.mul(lr * gradNegWeight));
        entityImagEmbeddings.getRow(tNegIdx).subi(gradNegImag.mul(lr * gradNegWeight));
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
