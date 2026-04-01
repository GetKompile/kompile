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

package ai.kompile.knowledgegraph.embedding.training;

import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

/**
 * Initializes embedding matrices for knowledge graph embedding models.
 */
public class EmbeddingInitializer {

    /**
     * Initializes embeddings using Xavier/Glorot uniform initialization.
     * Values are uniformly distributed in [-limit, limit] where limit = sqrt(6 / dim)
     *
     * @param numEmbeddings Number of embeddings (entities or relations)
     * @param embeddingDim Dimension of each embedding
     * @return Initialized embedding matrix of shape [numEmbeddings, embeddingDim]
     */
    public static INDArray xavierUniform(int numEmbeddings, int embeddingDim) {
        double limit = Math.sqrt(6.0 / embeddingDim);
        return Nd4j.rand(numEmbeddings, embeddingDim).mul(2 * limit).sub(limit);
    }

    /**
     * Initializes embeddings with uniform distribution in [-1/dim, 1/dim].
     * This is the original TransE initialization.
     *
     * @param numEmbeddings Number of embeddings
     * @param embeddingDim Dimension of each embedding
     * @return Initialized embedding matrix
     */
    public static INDArray uniformTransE(int numEmbeddings, int embeddingDim) {
        double limit = 1.0 / embeddingDim;
        return Nd4j.rand(numEmbeddings, embeddingDim).mul(2 * limit).sub(limit);
    }

    /**
     * Initializes embeddings with uniform distribution in [-bound, bound].
     *
     * @param numEmbeddings Number of embeddings
     * @param embeddingDim Dimension of each embedding
     * @param bound The bound for uniform distribution
     * @return Initialized embedding matrix
     */
    public static INDArray uniform(int numEmbeddings, int embeddingDim, double bound) {
        return Nd4j.rand(numEmbeddings, embeddingDim).mul(2 * bound).sub(bound);
    }

    /**
     * Initializes embeddings with uniform distribution for RotatE.
     * Entity embeddings are in [-1, 1] and relation phases are in [0, 2π].
     *
     * @param numEmbeddings Number of embeddings
     * @param embeddingDim Dimension of each embedding
     * @param forRelation If true, initializes as rotation phases [0, 2π]
     * @return Initialized embedding matrix
     */
    public static INDArray uniformRotatE(int numEmbeddings, int embeddingDim, boolean forRelation) {
        if (forRelation) {
            // Relations are phases in [0, 2π]
            return Nd4j.rand(numEmbeddings, embeddingDim).mul(2 * Math.PI);
        } else {
            // Entities are uniform in [-1, 1]
            return Nd4j.rand(numEmbeddings, embeddingDim).mul(2).sub(1);
        }
    }

    /**
     * L2-normalizes each row of the embedding matrix.
     *
     * @param embeddings The embedding matrix to normalize
     * @return The normalized embedding matrix
     */
    public static INDArray normalizeRows(INDArray embeddings) {
        INDArray norms = embeddings.norm2(1);
        // Avoid division by zero
        norms = norms.add(1e-10);
        return embeddings.divColumnVector(norms);
    }

    /**
     * L2-normalizes each row in place.
     *
     * @param embeddings The embedding matrix to normalize in place
     */
    public static void normalizeRowsInPlace(INDArray embeddings) {
        INDArray norms = embeddings.norm2(1);
        norms = norms.add(1e-10);
        embeddings.diviColumnVector(norms);
    }
}
