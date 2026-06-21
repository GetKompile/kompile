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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Holds the entity embedding matrix and the corresponding context (output) matrix for skip-gram
 * training, together with the bidirectional entity-id &harr; index mapping.
 *
 * <p>The embedding matrix {@code entity[i][d]} and context matrix {@code context[i][d]} follow the
 * word2vec convention: entities are trained as <em>target</em> vectors (updated by the center-word
 * gradient) and context vectors are updated by the neighbor gradient. After training only the
 * entity matrix is exposed via {@link #vector(String)} and {@link #asMap()}; the context matrix is
 * internal to the training loop.</p>
 *
 * <p>Initialization (plain-Java constructor): each entry is drawn uniformly from
 * {@code (-0.5/dim, +0.5/dim)}, matching the original word2vec initialization.</p>
 *
 * <p>SameDiff constructor: receives a fully-trained {@link SameDiffEmbeddingTrainer} and reads out
 * its entity matrix as {@code double[][]} rows, one per entity id in insertion order.</p>
 */
public final class EmbeddingTable {

    private final Map<String, Integer> entityIndex;   // entityId → row index
    private final List<String>         indexEntity;   // row index → entityId
    private final double[][]           entity;        // entity (target) embeddings
    private final double[][]           context;       // context (output) embeddings
    private final int                  dim;

    /**
     * Construct and randomly initialise an embedding table.
     *
     * @param entityIds ordered entity identifiers; insertion order is the index order
     * @param dim       embedding dimension
     * @param seed      RNG seed for reproducible initialisation
     */
    public EmbeddingTable(List<String> entityIds, int dim, long seed) {
        if (dim <= 0) {
            throw new IllegalArgumentException("dim must be positive, got " + dim);
        }
        this.dim = dim;

        int n = entityIds.size();
        entityIndex = new LinkedHashMap<>(n * 2);
        indexEntity = new ArrayList<>(entityIds);
        for (int i = 0; i < n; i++) {
            entityIndex.put(entityIds.get(i), i);
        }

        entity  = new double[n][dim];
        context = new double[n][dim];

        // Initialise entries uniformly in (-0.5/dim, +0.5/dim)
        Random rng = new Random(seed);
        double range = 0.5 / dim;
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < dim; d++) {
                entity[i][d]  = (rng.nextDouble() - 0.5) * 2.0 * range;
                context[i][d] = (rng.nextDouble() - 0.5) * 2.0 * range;
            }
        }
    }

    /**
     * Construct an {@link EmbeddingTable} from a fully-trained {@link SameDiffEmbeddingTrainer}.
     *
     * <p>The entity rows are read out of the trainer's ND4J array as plain {@code double[]} copies.
     * The context matrix is not exposed externally and is set to zeros here (it is only used
     * inside the training loop, which the trainer owns).</p>
     *
     * @param entityIds ordered entity identifiers; must match the order used to construct the trainer
     * @param trainer   a trainer that has completed at least one epoch of SameDiff training
     */
    EmbeddingTable(List<String> entityIds, SameDiffEmbeddingTrainer trainer) {
        int n = entityIds.size();
        this.dim = trainer.dim();

        entityIndex = new LinkedHashMap<>(n * 2);
        indexEntity = new ArrayList<>(entityIds);
        for (int i = 0; i < n; i++) {
            entityIndex.put(entityIds.get(i), i);
        }

        // Read entity rows from the trained ND4J matrix.
        double[][] trained = trainer.entityMatrix();
        entity  = new double[n][dim];
        context = new double[n][dim];   // context not needed externally; leave as zeros
        for (int i = 0; i < n; i++) {
            System.arraycopy(trained[i], 0, entity[i], 0, dim);
        }
    }

    /**
     * The entity (target) embedding for the given entity id, or {@code null} if unknown.
     * The returned array is the live row — callers that modify it change the table in-place
     * (intentional: the trainer does exactly this during SGD updates).
     */
    public double[] vector(String entityId) {
        Integer idx = entityIndex.get(entityId);
        return idx == null ? null : entity[idx];
    }

    /**
     * The context embedding for the given entity id, or {@code null} if unknown.
     * Used internally by the training loop; not part of the public output.
     */
    double[] contextVector(String entityId) {
        Integer idx = entityIndex.get(entityId);
        return idx == null ? null : context[idx];
    }

    /** Return the entity-id at a given row index (used for sampling by index). */
    String entityAt(int index) {
        return indexEntity.get(index);
    }

    /** Number of entities. */
    public int size() {
        return indexEntity.size();
    }

    /** Embedding dimension. */
    public int dim() {
        return dim;
    }

    /**
     * Snapshot of the learned entity embeddings as an unmodifiable map.
     * The {@code double[]} arrays are the live table rows — do not modify.
     */
    public Map<String, double[]> asMap() {
        Map<String, double[]> out = new LinkedHashMap<>(entityIndex.size() * 2);
        for (Map.Entry<String, Integer> e : entityIndex.entrySet()) {
            out.put(e.getKey(), entity[e.getValue()]);
        }
        return Collections.unmodifiableMap(out);
    }
}
