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

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/** Resolves sparse graph embeddings through relations, endpoints, and bounded neighborhoods. */
public final class GraphEmbeddingResolver {

    private static final double HOP_DECAY = 0.65;

    private GraphEmbeddingResolver() {
    }

    public enum Origin {
        DIRECT_ENTITY,
        DIRECT_RELATION,
        RELATION_ENDPOINTS,
        NEIGHBORHOOD,
        NONE
    }

    public record Resolved(double[] vector, Origin origin, int supportCount, int hops) {
        public Resolved {
            vector = vector == null ? null : vector.clone();
            origin = origin == null ? Origin.NONE : origin;
            supportCount = Math.max(0, supportCount);
            hops = Math.max(0, hops);
        }

        public static Resolved empty() {
            return new Resolved(null, Origin.NONE, 0, 0);
        }

        public boolean present() {
            return usable(vector);
        }
    }

    /** Resolve an entity or relation id, using at most two graph hops for sparse fallback. */
    public static Resolved resolve(ReasoningGraph graph, String graphObjectId) {
        return resolve(graph, graphObjectId, 2);
    }

    /** Resolve an entity or relation id with a configurable neighborhood radius. */
    public static Resolved resolve(ReasoningGraph graph, String graphObjectId, int maxHops) {
        Objects.requireNonNull(graph, "graph");
        if (graphObjectId == null || graphObjectId.isBlank()) {
            return Resolved.empty();
        }

        Optional<GraphEntity> entity = graph.entity(graphObjectId);
        if (entity.isPresent()) {
            if (entity.get().hasEmbedding() && usable(entity.get().embedding())) {
                return new Resolved(entity.get().embedding(), Origin.DIRECT_ENTITY, 1, 0);
            }
            return resolveEntityNeighborhood(graph, graphObjectId, Math.max(0, maxHops));
        }

        GraphRelation relation = null;
        for (GraphRelation candidate : graph.relations()) {
            if (graphObjectId.equals(candidate.id())) {
                relation = candidate;
                break;
            }
        }
        if (relation == null) {
            return Resolved.empty();
        }
        if (relation.hasEmbedding() && usable(relation.embedding())) {
            return new Resolved(relation.embedding(), Origin.DIRECT_RELATION, 1, 0);
        }

        List<WeightedVector> endpoints = new ArrayList<>(2);
        addDirectEntityVector(graph, relation.sourceId(), 1.0, 0, endpoints);
        addDirectEntityVector(graph, relation.targetId(), 1.0, 0, endpoints);
        Resolved endpointCentroid = centroid(endpoints, Origin.RELATION_ENDPOINTS);
        if (endpointCentroid.present()) {
            return endpointCentroid;
        }

        List<WeightedVector> inferredEndpoints = new ArrayList<>(2);
        for (String endpointId : List.of(relation.sourceId(), relation.targetId())) {
            Resolved resolved = resolveEntityNeighborhood(graph, endpointId, Math.max(0, maxHops));
            if (resolved.present()) {
                inferredEndpoints.add(new WeightedVector(
                        resolved.vector(), 1.0, resolved.hops(), resolved.supportCount()));
            }
        }
        return centroid(inferredEndpoints, Origin.NEIGHBORHOOD);
    }

    private static Resolved resolveEntityNeighborhood(ReasoningGraph graph, String entityId,
                                                      int maxHops) {
        if (maxHops <= 0 || graph.entity(entityId).isEmpty()) {
            return Resolved.empty();
        }
        Queue<NodeDepth> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        List<WeightedVector> vectors = new ArrayList<>();
        queue.add(new NodeDepth(entityId, 0));
        visited.add(entityId);

        while (!queue.isEmpty()) {
            NodeDepth current = queue.remove();
            if (current.depth() >= maxHops) {
                continue;
            }
            int neighborDepth = current.depth() + 1;
            for (GraphRelation relation : graph.relationsOf(current.entityId())) {
                double edgeWeight = Math.max(0.01, Math.min(1.0, relation.weight()));
                double weight = edgeWeight * Math.pow(HOP_DECAY, neighborDepth - 1);
                if (relation.hasEmbedding() && usable(relation.embedding())) {
                    vectors.add(new WeightedVector(relation.embedding(), weight,
                            neighborDepth, 1));
                }
                String neighborId = current.entityId().equals(relation.sourceId())
                        ? relation.targetId() : relation.sourceId();
                addDirectEntityVector(graph, neighborId, weight, neighborDepth, vectors);
                if (visited.add(neighborId) && neighborDepth < maxHops) {
                    queue.add(new NodeDepth(neighborId, neighborDepth));
                }
            }
        }
        return centroid(vectors, Origin.NEIGHBORHOOD);
    }

    private static void addDirectEntityVector(ReasoningGraph graph, String entityId,
                                              double weight, int hops,
                                              List<WeightedVector> vectors) {
        graph.entity(entityId).filter(GraphEntity::hasEmbedding).ifPresent(entity -> {
            if (usable(entity.embedding())) {
                vectors.add(new WeightedVector(entity.embedding(), weight, hops, 1));
            }
        });
    }

    private static Resolved centroid(List<WeightedVector> vectors, Origin origin) {
        if (vectors.isEmpty()) {
            return Resolved.empty();
        }
        Map<Integer, Integer> dimensionSupport = new HashMap<>();
        for (WeightedVector vector : vectors) {
            if (usable(vector.vector())) {
                dimensionSupport.merge(vector.vector().length, vector.supportCount(), Integer::sum);
            }
        }
        int dimension = dimensionSupport.entrySet().stream()
                .max(Map.Entry.<Integer, Integer>comparingByValue()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(0);
        if (dimension == 0) {
            return Resolved.empty();
        }

        double[] sum = new double[dimension];
        double totalWeight = 0.0;
        int support = 0;
        int maxDepth = 0;
        for (WeightedVector weighted : vectors) {
            if (weighted.vector().length != dimension || !usable(weighted.vector())) {
                continue;
            }
            double[] normalized = Embeddings.normalize(weighted.vector());
            for (int i = 0; i < dimension; i++) {
                sum[i] += normalized[i] * weighted.weight();
            }
            totalWeight += weighted.weight();
            support += weighted.supportCount();
            maxDepth = Math.max(maxDepth, weighted.hops());
        }
        if (totalWeight == 0.0 || Embeddings.magnitude(sum) == 0.0) {
            return Resolved.empty();
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= totalWeight;
        }
        return new Resolved(Embeddings.normalize(sum), origin, support, maxDepth);
    }

    private static boolean usable(double[] vector) {
        double magnitude = Embeddings.magnitude(vector);
        return Double.isFinite(magnitude) && magnitude > 0.0;
    }

    private record NodeDepth(String entityId, int depth) {
    }

    private record WeightedVector(double[] vector, double weight, int hops, int supportCount) {
    }
}
