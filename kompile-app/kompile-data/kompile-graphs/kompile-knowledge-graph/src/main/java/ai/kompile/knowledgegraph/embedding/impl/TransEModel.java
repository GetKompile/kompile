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
 * TransE (Translating Embeddings) knowledge graph embedding model.
 *
 * <p>TransE models relations as translations in embedding space:
 * <b>h + r ≈ t</b> for a triple (head, relation, tail).
 *
 * <p>The scoring function is: score(h, r, t) = ||h + r - t||
 * Lower scores indicate more plausible triples.
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
            // Build entity and relation vocabularies
            buildVocabulary(triples);

            this.embeddingDim = config.embeddingDim();

            log.info("Starting TransE training: {} entities, {} relations, {} triples",
                    entityToIndex.size(), relationToIndex.size(), triples.size());

            // Initialize embeddings
            entityEmbeddings = EmbeddingInitializer.uniformTransE(entityToIndex.size(), embeddingDim);
            relationEmbeddings = EmbeddingInitializer.uniformTransE(relationToIndex.size(), embeddingDim);

            // Normalize entity embeddings
            EmbeddingInitializer.normalizeRowsInPlace(entityEmbeddings);

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

                // Normalize entity embeddings after each epoch
                if (config.normalizeEntities()) {
                    EmbeddingInitializer.normalizeRowsInPlace(entityEmbeddings);
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
            INDArray h = entityEmbeddings.getRow(hIdx).dup();
            INDArray r = relationEmbeddings.getRow(rIdx).dup();
            INDArray t = entityEmbeddings.getRow(tIdx).dup();

            // Positive score: ||h + r - t||
            double posScore = scoreVectors(h, r, t);

            // Process negatives for this positive
            for (int j = 0; j < negPerPos; j++) {
                Triple neg = negatives.get(i * negPerPos + j);

                Integer hNegIdx = entityToIndex.get(neg.head());
                Integer tNegIdx = entityToIndex.get(neg.tail());

                if (hNegIdx == null || tNegIdx == null) continue;

                INDArray hNeg = entityEmbeddings.getRow(hNegIdx).dup();
                INDArray tNeg = entityEmbeddings.getRow(tNegIdx).dup();

                // Negative score
                double negScore = scoreVectors(hNeg, r, tNeg);

                // Margin ranking loss: max(0, margin + posScore - negScore)
                double loss = Math.max(0, margin + posScore - negScore);

                if (loss > 0) {
                    totalLoss += loss;

                    // Gradient update (simplified SGD)
                    // d_loss/d_h = (h + r - t) / ||h + r - t|| (for positive)
                    // d_loss/d_t = -(h + r - t) / ||h + r - t|| (for positive)
                    // Opposite signs for negative

                    INDArray diff = h.add(r).sub(t);
                    INDArray diffNorm = diff.div(diff.norm2Number().doubleValue() + 1e-10);

                    INDArray diffNeg = hNeg.add(r).sub(tNeg);
                    INDArray diffNegNorm = diffNeg.div(diffNeg.norm2Number().doubleValue() + 1e-10);

                    // Update positive triple embeddings
                    entityEmbeddings.getRow(hIdx).subi(diffNorm.mul(learningRate));
                    entityEmbeddings.getRow(tIdx).addi(diffNorm.mul(learningRate));
                    relationEmbeddings.getRow(rIdx).subi(diffNorm.mul(learningRate));

                    // Update negative triple embeddings (opposite direction)
                    entityEmbeddings.getRow(hNegIdx).addi(diffNegNorm.mul(learningRate));
                    entityEmbeddings.getRow(tNegIdx).subi(diffNegNorm.mul(learningRate));
                }
            }
        }

        return totalLoss / positives.size();
    }

    /**
     * Computes TransE score: ||h + r - t||_2
     */
    private double scoreVectors(INDArray h, INDArray r, INDArray t) {
        INDArray diff = h.add(r).sub(t);
        return diff.norm2Number().doubleValue();
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
