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
package ai.kompile.knowledgegraph.embedding.adapter;

import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live-store {@link KgEmbeddingGraphAdapter} backed by the vector/matrix graph store. This is what
 * un-orphans the KG-embedding pipeline: triples are extracted from, and trained structural embeddings
 * written back to, the same graph the {@code MatrixGraphRagService} retriever actually queries.
 *
 * <p>Triples are keyed by stable node id (not title), so the model's entity-embedding keys are node
 * ids; {@link #storeEmbeddings} writes each entity vector into the corresponding node's metadata under
 * {@link #KGE_EMBEDDING_KEY} (round-tripped to the vector store via the node document), where the
 * retriever can read it back. Highest priority, so it wins whenever the live store is populated.</p>
 */
@Component
public class MatrixKgEmbeddingGraphAdapter implements KgEmbeddingGraphAdapter {

    private static final Logger log = LoggerFactory.getLogger(MatrixKgEmbeddingGraphAdapter.class);

    /** Node metadata key holding the comma-separated structural (KGE) embedding vector. */
    public static final String KGE_EMBEDDING_KEY = "kgeEmbedding";
    /** Node metadata key holding the algorithm name (TRANSE/ROTATE) used to train the vector. */
    public static final String KGE_ALGORITHM_KEY = "kgeAlgorithm";
    /** Node metadata key holding the embedding version (training-run timestamp). */
    public static final String KGE_VERSION_KEY = "kgeVersion";

    private MatrixGraphStore store;

    @Autowired
    public MatrixKgEmbeddingGraphAdapter(MatrixGraphStore store) {
        this.store = store;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected MatrixKgEmbeddingGraphAdapter() {}

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String storeType() {
        return "matrix";
    }

    @Override
    public boolean hasGraphData(Long factSheetId) {
        for (String graphId : store.listGraphsByFactSheet(factSheetId)) {
            Optional<AdjacencyMatrixGraph> g = store.loadGraph(graphId);
            if (g.isPresent() && g.get().getEdgeCount() > 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Triple> extractTriples(Long factSheetId) {
        List<Triple> triples = new ArrayList<>();
        for (String graphId : store.listGraphsByFactSheet(factSheetId)) {
            AdjacencyMatrixGraph graph = store.loadGraph(graphId).orElse(null);
            if (graph == null) {
                continue;
            }
            // (head=nodeId, relation=edgeType, tail=neighborNodeId) over all typed outgoing edges.
            for (String edgeType : graph.getEdgeTypes()) {
                for (String sourceId : new ArrayList<>(graph.getNodeById().keySet())) {
                    for (Map.Entry<String, Double> neighbor : graph.getNeighbors(sourceId, edgeType)) {
                        triples.add(new Triple(sourceId, edgeType, neighbor.getKey()));
                    }
                }
            }
        }
        log.info("Extracted {} triples from live (matrix) store for fact sheet {}", triples.size(), factSheetId);
        return triples;
    }

    @Override
    public int storeEmbeddings(KGEmbeddingModel model, Long factSheetId, Long version) {
        Map<String, INDArray> entityEmbeddings = model.getAllEntityEmbeddings();
        if (entityEmbeddings == null || entityEmbeddings.isEmpty()) {
            return 0;
        }
        String algorithm = model.getAlgorithm() != null ? model.getAlgorithm().name() : "UNKNOWN";

        int updated = 0;
        for (String graphId : store.listGraphsByFactSheet(factSheetId)) {
            AdjacencyMatrixGraph graph = store.loadGraph(graphId).orElse(null);
            if (graph == null) {
                continue;
            }
            for (String nodeId : new ArrayList<>(graph.getNodeById().keySet())) {
                INDArray vec = entityEmbeddings.get(nodeId);
                if (vec == null) {
                    continue;
                }
                MatrixGraphNode node = graph.getNode(nodeId).orElse(null);
                if (node == null) {
                    continue;
                }
                if (node.getMetadata() == null) {
                    node.setMetadata(new HashMap<>());
                }
                node.getMetadata().put(KGE_EMBEDDING_KEY, encode(vec));
                node.getMetadata().put(KGE_ALGORITHM_KEY, algorithm);
                node.getMetadata().put(KGE_VERSION_KEY, version);
                store.updateNode(graphId, node);
                updated++;
            }
        }
        log.info("Stored {} structural (KGE) entity embeddings into the live (matrix) store for fact sheet {}",
                updated, factSheetId);
        return updated;
    }

    /** Serializes a vector to a comma-separated string for lossless round-trip through node metadata JSON. */
    static String encode(INDArray vec) {
        float[] values = vec.toFloatVector();
        StringBuilder sb = new StringBuilder(values.length * 8);
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    /** Parses a vector previously written by {@link #encode}; returns {@code null} for absent/blank values. */
    public static INDArray decode(Object metadataValue) {
        if (metadataValue == null) {
            return null;
        }
        String s = metadataValue.toString();
        if (s.isBlank()) {
            return null;
        }
        String[] parts = s.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i].trim());
        }
        return Nd4j.create(values);
    }
}
