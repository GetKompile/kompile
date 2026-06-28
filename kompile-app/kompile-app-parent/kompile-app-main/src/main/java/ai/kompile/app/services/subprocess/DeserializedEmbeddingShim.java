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
package ai.kompile.app.services.subprocess;

import ai.kompile.core.kgembedding.EmbeddingScore;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.kgembedding.TrainingResult;
import ai.kompile.core.kgembedding.Triple;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only {@link KGEmbeddingModel} backed by the deserialized
 * {@code {entities: {id: float[]}, relations: {id: float[]}}} file written by
 * {@link ai.kompile.app.learning.subprocess.LearningSubprocessMain}.
 *
 * <p>This shim exists solely so that
 * {@code adapter.storeEmbeddings(shim, factSheetId, version)} works unchanged
 * after an out-of-process training run. The contract it must honour is:</p>
 * <ul>
 *   <li>{@link #getAllEntityEmbeddings()} — returns the full entity → INDArray map.</li>
 *   <li>{@link #getAllRelationEmbeddings()} — returns the full relation → INDArray map.</li>
 *   <li>{@link #getEntityEmbedding(String)} — lookup by the exact entity-ID string.</li>
 *   <li>{@link #getRelationEmbedding(String)} — lookup by the exact relation-type string.</li>
 *   <li>{@link #getAlgorithm()} — always returns {@code null} (algorithm unknown at shim level;
 *       the adapter's {@code storeEmbeddings} uses {@link #getAlgorithmName()} where needed).</li>
 * </ul>
 *
 * <p>All training-path methods ({@link #train}, {@link #cancelTraining}, etc.) throw
 * {@link UnsupportedOperationException} — this shim is write-back only.</p>
 *
 * <h3>Entity-ID consistency guarantee</h3>
 * The parent launcher serialised the triple list using the exact string IDs returned by
 * the adapter's {@code extractTriples()}, wrote them verbatim to the triples file, and the
 * subprocess built its vocabulary from those same strings. The output file therefore carries
 * the same keys — no translation step is needed here.
 */
public class DeserializedEmbeddingShim implements KGEmbeddingModel {

    private final Map<String, INDArray> entityEmbeddings;
    private final Map<String, INDArray> relationEmbeddings;

    DeserializedEmbeddingShim(Map<String, INDArray> entityEmbeddings,
                               Map<String, INDArray> relationEmbeddings) {
        this.entityEmbeddings   = entityEmbeddings   != null ? entityEmbeddings   : Collections.emptyMap();
        this.relationEmbeddings = relationEmbeddings != null ? relationEmbeddings : Collections.emptyMap();
    }

    // ── model identity ────────────────────────────────────────────────────────

    @Override public String getAlgorithmName() { return "DeserializedSubprocessEmbedding"; }
    @Override public KGEmbeddingAlgorithm getAlgorithm() { return null; } // unknown at shim level

    @Override
    public int getEmbeddingDimension() {
        return entityEmbeddings.values().stream()
                .findFirst()
                .map(a -> (int) a.length())
                .orElse(0);
    }

    @Override public int    getEntityCount()   { return entityEmbeddings.size(); }
    @Override public int    getRelationCount() { return relationEmbeddings.size(); }
    @Override public Set<String> getEntityIds()    { return entityEmbeddings.keySet(); }
    @Override public Set<String> getRelationTypes() { return relationEmbeddings.keySet(); }

    // ── embedding access ──────────────────────────────────────────────────────

    @Override public INDArray getEntityEmbedding(String entityId) {
        return entityEmbeddings.get(entityId);
    }

    @Override public INDArray getRelationEmbedding(String relationType) {
        return relationEmbeddings.get(relationType);
    }

    @Override public Map<String, INDArray> getAllEntityEmbeddings()   { return entityEmbeddings; }
    @Override public Map<String, INDArray> getAllRelationEmbeddings() { return relationEmbeddings; }

    @Override
    public INDArray getEntityEmbeddingMatrix() {
        if (entityEmbeddings.isEmpty()) return null;
        List<INDArray> rows = new ArrayList<>(entityEmbeddings.values());
        return Nd4j.vstack(rows);
    }

    @Override
    public INDArray getRelationEmbeddingMatrix() {
        if (relationEmbeddings.isEmpty()) return null;
        List<INDArray> rows = new ArrayList<>(relationEmbeddings.values());
        return Nd4j.vstack(rows);
    }

    // ── unsupported training-path methods ────────────────────────────────────

    @Override
    public TrainingResult train(List<Triple> triples, KGEmbeddingConfig config) {
        throw new UnsupportedOperationException("DeserializedEmbeddingShim is read-only");
    }

    @Override public boolean isTraining()    { return false; }
    @Override public void cancelTraining()   { /* no-op */ }
    @Override public boolean isTrained()     { return true; }

    // ── unsupported scoring methods ───────────────────────────────────────────

    @Override public double scoreTriple(String h, String r, String t) {
        throw new UnsupportedOperationException("DeserializedEmbeddingShim does not support scoring");
    }

    @Override public double[] scoreTriples(List<Triple> triples) {
        throw new UnsupportedOperationException("DeserializedEmbeddingShim does not support scoring");
    }

    @Override public List<EmbeddingScore> predictTails(String h, String r, int k) {
        throw new UnsupportedOperationException("DeserializedEmbeddingShim does not support prediction");
    }

    @Override public List<EmbeddingScore> predictHeads(String r, String t, int k) {
        throw new UnsupportedOperationException("DeserializedEmbeddingShim does not support prediction");
    }

    @Override public List<EmbeddingScore> predictRelations(String h, String t, int k) {
        throw new UnsupportedOperationException("DeserializedEmbeddingShim does not support prediction");
    }

    @Override public List<EmbeddingScore> findSimilarEntities(String entityId, int topK) {
        throw new UnsupportedOperationException("DeserializedEmbeddingShim does not support similarity");
    }

    @Override public List<EmbeddingScore> findSimilarRelations(String relationType, int topK) {
        throw new UnsupportedOperationException("DeserializedEmbeddingShim does not support similarity");
    }

    @Override public double entitySimilarity(String e1, String e2) {
        throw new UnsupportedOperationException("DeserializedEmbeddingShim does not support similarity");
    }

    // ── persistence (no-op for a shim) ───────────────────────────────────────

    @Override public void saveEmbeddings(Path outputPath) {
        throw new UnsupportedOperationException("DeserializedEmbeddingShim cannot re-save");
    }

    @Override public void loadEmbeddings(Path inputPath) {
        throw new UnsupportedOperationException("DeserializedEmbeddingShim cannot load");
    }

    @Override public void importEntityEmbeddings(Map<String, INDArray> embeddings) {
        throw new UnsupportedOperationException("DeserializedEmbeddingShim is read-only");
    }

    @Override public void importRelationEmbeddings(Map<String, INDArray> embeddings) {
        throw new UnsupportedOperationException("DeserializedEmbeddingShim is read-only");
    }
}
