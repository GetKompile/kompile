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
        // In-place: allocate once, scale/shift without transient copies.
        INDArray out = Nd4j.rand(numEmbeddings, embeddingDim);
        out.muli(2 * limit);
        out.subi(limit);
        return out;
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
        // In-place: allocate once, scale/shift without transient copies.
        INDArray out = Nd4j.rand(numEmbeddings, embeddingDim);
        out.muli(2 * limit);
        out.subi(limit);
        return out;
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
        // In-place: allocate once, scale/shift without transient copies.
        INDArray out = Nd4j.rand(numEmbeddings, embeddingDim);
        out.muli(2 * bound);
        out.subi(bound);
        return out;
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
        INDArray out = Nd4j.rand(numEmbeddings, embeddingDim);
        if (forRelation) {
            // Relations are phases in [0, 2π] — in-place: allocate once, scale without transient copy.
            out.muli(2 * Math.PI);
        } else {
            // Entities are uniform in [-1, 1] — in-place: allocate once, scale/shift without transient copies.
            out.muli(2);
            out.subi(1);
        }
        return out;
    }

    /**
     * L2-normalizes each row of the embedding matrix.
     *
     * @param embeddings The embedding matrix to normalize
     * @return The normalized embedding matrix
     */
    public static INDArray normalizeRows(INDArray embeddings) {
        INDArray norms = embeddings.norm2(1);
        // Avoid division by zero — addi is in-place so no second INDArray is allocated.
        norms.addi(1e-10);
        return embeddings.divColumnVector(norms);
    }

    /**
     * L2-normalizes each row in place.
     *
     * <p>Avoids the native-memory orphan produced by the old {@code norms = norms.add(eps)}
     * pattern: that call created a NEW {@link INDArray} and left the original {@code norms}
     * (from {@code norm2(1)}) unreachable without {@code close()}.  The replacement uses
     * {@code addi(eps)} so the addition happens in-place on the single {@code norms} array.
     *
     * @param embeddings The embedding matrix to normalize in place
     */
    public static void normalizeRowsInPlace(INDArray embeddings) {
        INDArray norms = embeddings.norm2(1);
        norms.addi(1e-10);   // in-place: no second INDArray created, no orphaned allocation
        embeddings.diviColumnVector(norms);
    }
}
