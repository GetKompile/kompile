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

import java.util.List;

/**
 * Package-private bridge that converts a raw {@code double[][]} entity matrix (from a trained
 * {@link RotatELearner}) into an {@link EmbeddingTable}.
 *
 * <p>{@link EmbeddingTable} has no public {@code double[][]} constructor; its package-private
 * SameDiff constructor takes a {@link SameDiffEmbeddingTrainer}. This bridge lives in the same
 * package and wraps the raw array as a minimal {@link SameDiffEmbeddingTrainer} substitute so
 * that {@link RotatELearner.TrainedRotatE#toEmbeddingTable()} can produce a proper table
 * without breaking the {@link EmbeddingTable} encapsulation.</p>
 */
final class RotatEEmbeddingBridge {

    private RotatEEmbeddingBridge() {
    }

    /**
     * Construct an {@link EmbeddingTable} from a raw entity matrix produced by RotatE training.
     *
     * <p>The returned table wraps the real-part entity vectors. Callers that need the imaginary
     * parts or relation phases should retain the {@link RotatELearner.TrainedRotatE} directly.</p>
     *
     * @param entityIds entity id list in insertion order (same order as rows in {@code entityMatrix})
     * @param entityMatrix entity real-part embedding matrix, {@code [numEntities][dim]}
     * @param dim          embedding dimension
     */
    static EmbeddingTable toTable(List<String> entityIds, double[][] entityMatrix, int dim) {
        // Use the plain-Java EmbeddingTable(entityIds, dim, seed=0) constructor, then overwrite
        // the entity rows in-place. EmbeddingTable.vector() returns the LIVE row array, so
        // we simply copy our trained values into each row.
        EmbeddingTable table = new EmbeddingTable(entityIds, dim, 0L);
        for (int i = 0; i < entityIds.size(); i++) {
            double[] row = table.vector(entityIds.get(i));
            if (row != null && i < entityMatrix.length) {
                System.arraycopy(entityMatrix[i], 0, row, 0, dim);
            }
        }
        return table;
    }
}
