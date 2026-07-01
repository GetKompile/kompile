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
package ai.kompile.graph.reasoning.embedding.kge;

import ai.kompile.graph.reasoning.embedding.EmbeddingPslEvidence;
import ai.kompile.graph.reasoning.embedding.learn.EmbeddingTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Public-API bridge that constructs an {@link EmbeddingTable} from a KGE entity vector map.
 *
 * <h3>When to use</h3>
 * <p>For RotatE models, use {@link ai.kompile.graph.reasoning.embedding.learn.RotatELearner.TrainedRotatE#toEmbeddingTable()}
 * directly — it is already wired and uses the real-part entity vectors.
 * This class provides the equivalent for <em>other</em> KGE model families (GNN, DistMult,
 * TransE, ComplEx, etc.) where the caller supplies a pre-built {@code Map<String, double[]>}
 * of entity vectors without a {@code TrainedRotatE} wrapper.</p>
 *
 * <h3>EmbeddingTable encapsulation</h3>
 * <p>{@link EmbeddingTable} has no public {@code double[][]} or {@code Map} constructor.
 * However, {@link EmbeddingTable#vector(String)} returns the <em>live backing row</em>, so
 * this bridge creates a randomly-initialised table of the correct shape and then overwrites
 * each row in-place — exactly the same mechanism used by
 * {@link ai.kompile.graph.reasoning.embedding.learn.RotatEEmbeddingBridge} (package-private).</p>
 *
 * <h3>Downstream use</h3>
 * <p>Once built, the table can be passed directly to
 * {@link EmbeddingPslEvidence#addSimilarityEvidence(ai.kompile.graph.reasoning.psl.PslProgram, EmbeddingTable, String, double)}
 * or
 * {@link EmbeddingPslEvidence#addKnnSimilarityEvidence(ai.kompile.graph.reasoning.psl.PslProgram, EmbeddingTable, String, int)}
 * to populate a PSL program with embedding-cosine evidence atoms.</p>
 *
 * <pre>
 *   Map&lt;String, double[]&gt; gnnVectors = ...;          // from your GNN
 *   EmbeddingTable table = KgeEmbeddingTableBridge.fromVectorMap(gnnVectors);
 *   EmbeddingPslEvidence.addKnnSimilarityEvidence(program, table, "KnnSim", 5);
 * </pre>
 *
 * @see EmbeddingTable
 * @see EmbeddingPslEvidence
 */
public final class KgeEmbeddingTableBridge {

    private KgeEmbeddingTableBridge() {}

    /**
     * Build an {@link EmbeddingTable} from a map of entity ids to embedding vectors.
     *
     * <p>All vectors in the map must have the same length; an
     * {@link IllegalArgumentException} is thrown if they differ.  Entities with a
     * {@code null} or zero-length vector are silently skipped — they will have an
     * all-zero row in the resulting table and will be excluded by
     * {@link EmbeddingPslEvidence}'s magnitude guard.</p>
     *
     * <p>Insertion order of the map's key set becomes the entity index order in the table.
     * Use a {@link java.util.LinkedHashMap} when order matters.</p>
     *
     * @param entityVectors map from entity id to embedding vector; must not be null or empty
     * @return a fully-populated {@link EmbeddingTable}
     * @throws IllegalArgumentException if the map is empty, or if vectors have inconsistent lengths
     */
    public static EmbeddingTable fromVectorMap(Map<String, double[]> entityVectors) {
        Objects.requireNonNull(entityVectors, "entityVectors must not be null");
        if (entityVectors.isEmpty()) {
            throw new IllegalArgumentException("entityVectors must not be empty");
        }

        // Determine consistent dimension from the first non-null, non-empty vector
        int dim = -1;
        for (double[] v : entityVectors.values()) {
            if (v != null && v.length > 0) {
                dim = v.length;
                break;
            }
        }
        if (dim < 0) {
            throw new IllegalArgumentException("No valid (non-null, non-empty) vector found in entityVectors");
        }
        // Validate all other vectors for consistent dimension
        for (Map.Entry<String, double[]> entry : entityVectors.entrySet()) {
            double[] v = entry.getValue();
            if (v != null && v.length > 0 && v.length != dim) {
                throw new IllegalArgumentException(
                        "Inconsistent vector lengths: expected " + dim
                                + " but entity '" + entry.getKey() + "' has length " + v.length);
            }
        }

        List<String> entityIds = new ArrayList<>(entityVectors.keySet());
        // Create table with random init (seed=0 for reproducibility), then overwrite rows in-place
        EmbeddingTable table = new EmbeddingTable(entityIds, dim, 0L);
        for (String entityId : entityIds) {
            double[] src = entityVectors.get(entityId);
            if (src == null || src.length == 0) continue; // leave as random-init (zero magnitude → skipped by cosine)
            double[] row = table.vector(entityId);         // live backing row
            if (row != null) {
                System.arraycopy(src, 0, row, 0, dim);
            }
        }
        return table;
    }
}
