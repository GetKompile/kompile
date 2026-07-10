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

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.util.Map;
import java.util.Objects;

/**
 * Strategy interface for learning node embeddings from a {@link ReasoningGraph}.
 *
 * <p>Implementations (e.g. {@link Node2VecLearner}) train a dense {@code double[]} embedding
 * for each entity using self-supervised objectives derived from the graph structure, and return
 * the result as an {@link EmbeddingTable}. No external labels, no Spring, no ND4J.</p>
 *
 * <p>The {@link #learnInto(MutableReasoningGraph, EmbeddingConfig)} convenience method covers the
 * most common usage pattern: training and writing the learned vectors back into the graph's entity
 * objects so that {@link ai.kompile.graph.reasoning.embedding.Embeddings#cosine Embeddings.cosine}
 * and {@link ai.kompile.graph.reasoning.hybrid.HybridReasoner} can consume them immediately.</p>
 */
public interface EmbeddingLearner {

    /**
     * Train embeddings from the graph structure and return the populated table.
     *
     * @param graph  the source graph; must not be empty
     * @param config hyper-parameters controlling the training run
     * @return a fully-trained {@link EmbeddingTable} with one row per entity
     */
    EmbeddingTable learn(ReasoningGraph graph, EmbeddingConfig config);

    /**
     * Train embeddings and write each entity's learned vector back into {@code graph} by
     * replacing the entity via {@link MutableReasoningGraph#addEntity(GraphEntity)}.
     *
     * <p>The replacement preserves all existing entity properties (type, label, weight, confidence,
     * tags, timestamp, attributes) and only updates the {@link GraphEntity#embedding()} field.
     * After this call {@code graph.entity(id).embedding()} returns the trained vector for every
     * entity, making it immediately consumable by {@link ai.kompile.graph.reasoning.embedding.Embeddings}
     * and {@link ai.kompile.graph.reasoning.hybrid.HybridReasoner}.</p>
     *
     * @param graph  the graph whose entities will be updated in-place
     * @param config hyper-parameters controlling the training run
     */
    default void learnInto(MutableReasoningGraph graph, EmbeddingConfig config) {
        EmbeddingTable table = learn(graph, config);
        Map<String, double[]> vectors = table.asMap();

        for (GraphEntity entity : graph.entities()) {
            double[] vec = vectors.get(entity.id());
            if (vec == null) {
                continue;
            }
            // Rebuild the entity with the learned embedding, preserving all other properties
            GraphEntity updated = GraphEntity.builder(entity.id())
                    .type(entity.type())
                    .label(entity.label())
                    .weight(entity.weight())
                    .confidence(entity.confidence())
                    .tags(entity.tags())
                    .embedding(vec)
                    .timestamp(entity.timestamp())
                    .attributes(entity.attributes())
                    .build();
            graph.addEntity(updated);
        }
    }

    /**
     * Train embeddings into a named {@link UnifiedGraph} vector layer without replacing the
     * graph's primary entity embeddings. This keeps sentence embeddings, imported KGE vectors,
     * and graph-structure embeddings independently selectable by downstream reasoners.
     *
     * @param graph     graph to train over and enrich
     * @param layerName non-blank destination vector-layer name
     * @param config    hyper-parameters controlling the training run
     * @return the learned table that was written to the layer
     */
    default EmbeddingTable learnIntoLayer(UnifiedGraph graph, String layerName,
                                          EmbeddingConfig config) {
        Objects.requireNonNull(graph, "graph");
        if (layerName == null || layerName.isBlank()) {
            throw new IllegalArgumentException("layerName must be non-blank");
        }
        EmbeddingTable table = learn(graph, config);
        table.asMap().forEach((entityId, vector) ->
                graph.putEntityVector(layerName, entityId, vector));
        return table;
    }
}
