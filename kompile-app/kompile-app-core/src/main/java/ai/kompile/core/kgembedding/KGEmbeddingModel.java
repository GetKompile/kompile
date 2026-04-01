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

import org.nd4j.linalg.api.ndarray.INDArray;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interface for knowledge graph embedding models.
 *
 * <p>Knowledge graph embedding models learn low-dimensional vector representations
 * of entities and relations in a knowledge graph. These embeddings can be used for:
 * <ul>
 *   <li>Link prediction (predicting missing edges)</li>
 *   <li>Entity similarity</li>
 *   <li>Relation similarity</li>
 *   <li>Knowledge graph completion</li>
 *   <li>GraphRAG (retrieval augmented generation using graph structure)</li>
 * </ul>
 *
 * <p>Supported algorithms:
 * <ul>
 *   <li><b>TransE</b>: Translational model where h + r ≈ t</li>
 *   <li><b>RotatE</b>: Rotational model in complex space where h ∘ r ≈ t</li>
 * </ul>
 */
public interface KGEmbeddingModel extends AutoCloseable {

    // ═══════════════════════════════════════════════════════════════════════════
    // MODEL INFORMATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the algorithm name (e.g., "TransE", "RotatE").
     */
    String getAlgorithmName();

    /**
     * Returns the algorithm type enum.
     */
    KGEmbeddingAlgorithm getAlgorithm();

    /**
     * Returns the embedding dimension.
     */
    int getEmbeddingDimension();

    /**
     * Returns the number of entities with embeddings.
     */
    int getEntityCount();

    /**
     * Returns the number of relations with embeddings.
     */
    int getRelationCount();

    /**
     * Returns the set of all entity IDs.
     */
    Set<String> getEntityIds();

    /**
     * Returns the set of all relation types.
     */
    Set<String> getRelationTypes();

    // ═══════════════════════════════════════════════════════════════════════════
    // TRAINING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Trains the embedding model on a list of triples.
     *
     * @param triples The training triples
     * @param config Training configuration
     * @return Training result with metrics
     */
    TrainingResult train(List<Triple> triples, KGEmbeddingConfig config);

    /**
     * Returns true if the model is currently training.
     */
    boolean isTraining();

    /**
     * Cancels the current training.
     */
    void cancelTraining();

    /**
     * Returns true if the model has been trained.
     */
    boolean isTrained();

    // ═══════════════════════════════════════════════════════════════════════════
    // EMBEDDING ACCESS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gets the embedding vector for an entity.
     *
     * @param entityId The entity ID
     * @return The embedding vector, or null if not found
     */
    INDArray getEntityEmbedding(String entityId);

    /**
     * Gets the embedding vector for a relation.
     *
     * @param relationType The relation type
     * @return The embedding vector, or null if not found
     */
    INDArray getRelationEmbedding(String relationType);

    /**
     * Gets all entity embeddings as a map.
     *
     * @return Map from entity ID to embedding vector
     */
    Map<String, INDArray> getAllEntityEmbeddings();

    /**
     * Gets all relation embeddings as a map.
     *
     * @return Map from relation type to embedding vector
     */
    Map<String, INDArray> getAllRelationEmbeddings();

    /**
     * Gets entity embeddings as a matrix.
     *
     * @return Matrix of shape [numEntities, embeddingDim]
     */
    INDArray getEntityEmbeddingMatrix();

    /**
     * Gets relation embeddings as a matrix.
     *
     * @return Matrix of shape [numRelations, embeddingDim]
     */
    INDArray getRelationEmbeddingMatrix();

    // ═══════════════════════════════════════════════════════════════════════════
    // SCORING & LINK PREDICTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Scores a single triple. Lower scores indicate more plausible triples.
     *
     * @param head The head entity
     * @param relation The relation type
     * @param tail The tail entity
     * @return The score (lower = more plausible)
     */
    double scoreTriple(String head, String relation, String tail);

    /**
     * Scores a triple.
     *
     * @param triple The triple to score
     * @return The score (lower = more plausible)
     */
    default double scoreTriple(Triple triple) {
        return scoreTriple(triple.head(), triple.relation(), triple.tail());
    }

    /**
     * Scores multiple triples in batch.
     *
     * @param triples The triples to score
     * @return Array of scores
     */
    double[] scoreTriples(List<Triple> triples);

    /**
     * Predicts the most likely tail entities given head and relation.
     *
     * @param head The head entity
     * @param relation The relation type
     * @param topK Number of predictions to return
     * @return List of scored predictions, sorted by score (best first)
     */
    List<EmbeddingScore> predictTails(String head, String relation, int topK);

    /**
     * Predicts the most likely head entities given relation and tail.
     *
     * @param relation The relation type
     * @param tail The tail entity
     * @param topK Number of predictions to return
     * @return List of scored predictions, sorted by score (best first)
     */
    List<EmbeddingScore> predictHeads(String relation, String tail, int topK);

    /**
     * Predicts the most likely relations given head and tail.
     *
     * @param head The head entity
     * @param tail The tail entity
     * @param topK Number of predictions to return
     * @return List of scored predictions, sorted by score (best first)
     */
    List<EmbeddingScore> predictRelations(String head, String tail, int topK);

    // ═══════════════════════════════════════════════════════════════════════════
    // SIMILARITY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Finds the most similar entities to the given entity.
     *
     * @param entityId The entity to find similar entities for
     * @param topK Number of results to return
     * @return List of similar entities with similarity scores
     */
    List<EmbeddingScore> findSimilarEntities(String entityId, int topK);

    /**
     * Finds the most similar relations to the given relation.
     *
     * @param relationType The relation to find similar relations for
     * @param topK Number of results to return
     * @return List of similar relations with similarity scores
     */
    List<EmbeddingScore> findSimilarRelations(String relationType, int topK);

    /**
     * Computes cosine similarity between two entity embeddings.
     *
     * @param entity1 First entity ID
     * @param entity2 Second entity ID
     * @return Cosine similarity (-1 to 1)
     */
    double entitySimilarity(String entity1, String entity2);

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Saves the embeddings to a file.
     *
     * @param outputPath Path to save embeddings
     */
    void saveEmbeddings(Path outputPath);

    /**
     * Loads embeddings from a file.
     *
     * @param inputPath Path to load embeddings from
     */
    void loadEmbeddings(Path inputPath);

    /**
     * Imports entity embeddings from an external source.
     * Useful for loading embeddings stored in the database.
     *
     * @param entityEmbeddings Map from entity ID to embedding vector
     */
    void importEntityEmbeddings(Map<String, INDArray> entityEmbeddings);

    /**
     * Imports relation embeddings from an external source.
     * Useful for loading embeddings stored in the database.
     *
     * @param relationEmbeddings Map from relation type to embedding vector
     */
    void importRelationEmbeddings(Map<String, INDArray> relationEmbeddings);

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Closes the model and releases any resources.
     */
    @Override
    default void close() throws Exception {
        // Default no-op
    }
}
