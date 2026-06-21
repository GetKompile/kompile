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
package ai.kompile.graph.reasoning.embedding;

import ai.kompile.graph.reasoning.embedding.learn.EmbeddingTable;
import ai.kompile.graph.reasoning.psl.PslProgram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bridge between learned entity embeddings and PSL soft-logic programs.
 *
 * <h3>The embeddings-as-evidence design</h3>
 * <p>PSL rules can use any numeric predicate as evidence. This class bridges the gap between
 * the geometry of a learned {@link EmbeddingTable} and the soft-truth atoms of a
 * {@link PslProgram} by observing embedding cosine similarity as PSL atom values. After
 * calling one of the methods here, the program contains atoms such as
 * {@code similar(alice, bob) = 0.92} (observed) that can drive rules like:</p>
 * <pre>
 *   5.0: similar(X, Y) &amp; Label(X) -&gt; Label(Y) ^2
 * </pre>
 * <p>This creates a feedback loop: the PSL engine propagates soft-truth labels over the
 * embedding-similarity structure, and the learned weights on these rules determine how
 * strongly embedding proximity influences label inference.  The bridge is intentionally
 * one-directional — it injects evidence into PSL without modifying the embedding table.</p>
 *
 * <h3>Atom direction</h3>
 * <p>Both {@code (a, b)} and {@code (b, a)} directions are observed for each pair whose
 * cosine meets the threshold. This is appropriate for symmetric similarity predicates and
 * ensures PSL rules can chain {@code similar(X, Y) &amp; similar(Y, Z) -&gt; similar(X, Z)} in
 * either direction.</p>
 *
 * <h3>Self-pairs</h3>
 * <p>Self-pairs ({@code a == b}) are always skipped: the cosine similarity of a vector with
 * itself is 1.0 by definition and adds no relational information.</p>
 *
 * @see EmbeddingTable
 * @see PslProgram
 * @see Embeddings#cosine(double[], double[])
 */
public final class EmbeddingPslEvidence {

    private EmbeddingPslEvidence() {
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Threshold-based similarity evidence
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Observe a PSL similarity atom for every entity pair whose embedding cosine similarity
     * meets or exceeds {@code threshold}.
     *
     * <p>For each qualifying pair {@code (a, b)} with {@code a != b}, two atoms are observed
     * (both directions are symmetric similarity evidence):</p>
     * <pre>
     *   observe predicate(a, b) = cosine(embedding_a, embedding_b)
     *   observe predicate(b, a) = cosine(embedding_a, embedding_b)
     * </pre>
     *
     * <p>Entities without an embedding (i.e. {@link EmbeddingTable#vector(String)} returns
     * {@code null} or has zero magnitude) are silently skipped.</p>
     *
     * @param program   the PSL program to inject evidence into; modified in-place
     * @param table     learned entity embedding table
     * @param predicate PSL predicate name for the similarity atom (e.g. {@code "similar"})
     * @param threshold cosine similarity threshold in {@code [-1, 1]}; typical values 0.5–0.9
     * @return the number of PSL atoms added (each directed pair counts as one; symmetric pairs
     *         as two — i.e. a pair {@code (a, b)} that qualifies contributes 2 to the count)
     */
    public static int addSimilarityEvidence(PslProgram program,
                                            EmbeddingTable table,
                                            String predicate,
                                            double threshold) {
        List<String> ids = entityIdList(table);
        int added = 0;
        for (int i = 0; i < ids.size(); i++) {
            double[] vi = table.vector(ids.get(i));
            if (vi == null || Embeddings.magnitude(vi) == 0.0) continue;
            for (int j = 0; j < ids.size(); j++) {
                if (i == j) continue; // skip self-pairs
                double[] vj = table.vector(ids.get(j));
                if (vj == null || Embeddings.magnitude(vj) == 0.0) continue;
                double cosine = Embeddings.cosine(vi, vj);
                if (cosine >= threshold) {
                    program.observe(predicate, cosine, ids.get(i), ids.get(j));
                    added++;
                }
            }
        }
        return added;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // k-nearest-neighbour evidence
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Observe PSL similarity atoms for each entity's {@code k} nearest neighbours by cosine.
     *
     * <p>For each entity {@code a} in the table, the {@code k} most cosine-similar entities
     * (excluding self) are found, and two directed atoms are observed for each neighbour
     * {@code b} in the k-NN set:</p>
     * <pre>
     *   observe predicate(a, b) = cosine(embedding_a, embedding_b)
     *   observe predicate(b, a) = cosine(embedding_a, embedding_b)
     * </pre>
     *
     * <p>This overload is preferred over the threshold variant when the embedding space has
     * unknown density — the k-NN formulation guarantees that every entity contributes
     * evidence regardless of how tightly clustered the embeddings are.</p>
     *
     * <p>Entities without an embedding are silently skipped.</p>
     *
     * @param program   the PSL program to inject evidence into; modified in-place
     * @param table     learned entity embedding table
     * @param predicate PSL predicate name for the similarity atom (e.g. {@code "similar"})
     * @param k         maximum number of nearest neighbours per entity (must be ≥ 1)
     * @return the number of PSL atoms added (each directed pair counts as one; symmetric
     *         directed pairs counted separately — can be at most {@code 2 * |entities| * k})
     * @throws IllegalArgumentException if {@code k < 1}
     */
    public static int addKnnSimilarityEvidence(PslProgram program,
                                               EmbeddingTable table,
                                               String predicate,
                                               int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1, got " + k);
        }
        List<String> ids = entityIdList(table);
        int added = 0;

        for (String entityId : ids) {
            double[] vi = table.vector(entityId);
            if (vi == null || Embeddings.magnitude(vi) == 0.0) continue;

            // Collect all candidates with their cosine (excluding self)
            List<double[]> candidates = new ArrayList<>(ids.size() - 1);
            for (String otherId : ids) {
                if (otherId.equals(entityId)) continue;
                double[] vj = table.vector(otherId);
                if (vj == null || Embeddings.magnitude(vj) == 0.0) continue;
                double cosine = Embeddings.cosine(vi, vj);
                candidates.add(new double[]{ cosine, ids.indexOf(otherId) }); // [cosine, idx]
            }

            // Sort descending by cosine and take top-k
            candidates.sort((a, b) -> Double.compare(b[0], a[0]));
            int limit = Math.min(k, candidates.size());
            for (int n = 0; n < limit; n++) {
                double cosine = candidates.get(n)[0];
                int    jIdx   = (int) candidates.get(n)[1];
                String otherId = ids.get(jIdx);

                // Both directions
                program.observe(predicate, cosine, entityId, otherId);
                program.observe(predicate, cosine, otherId, entityId);
                added += 2;
            }
        }
        return added;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract the ordered entity-id list from the table's {@code asMap()} snapshot.
     * The table preserves insertion order via {@link java.util.LinkedHashMap}, so the
     * returned list is stable across calls.
     */
    private static List<String> entityIdList(EmbeddingTable table) {
        Map<String, double[]> map = table.asMap();
        return new ArrayList<>(map.keySet());
    }
}
