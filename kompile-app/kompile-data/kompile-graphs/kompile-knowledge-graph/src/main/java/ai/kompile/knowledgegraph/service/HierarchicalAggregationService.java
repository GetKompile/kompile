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
package ai.kompile.knowledgegraph.service;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.model.AggregationRequest;
import ai.kompile.knowledgegraph.service.model.AggregationResult;
import ai.kompile.knowledgegraph.service.model.AggregationType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Hierarchy-aware numeric aggregation over the knowledge graph.
 *
 * <p>Type matching is is-a aware: a node typed {@code RedWine} matches rootType {@code Wine}
 * whenever {@code Wine} appears in its {@code owlInferredTypes} closure (materialized by OWL-RL
 * reasoning). No domain terms are hard-coded — rootType, numericAttribute, and aggregation are
 * caller-supplied.</p>
 */
@Service
public class HierarchicalAggregationService {

    private final KnowledgeGraphService knowledgeGraphService;

    @Autowired
    public HierarchicalAggregationService(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /** Aggregate {@code numericAttribute} over nodes whose type memberships match the root type. */
    public AggregationResult aggregate(AggregationRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.getRootType(), "rootType");
        AggregationType aggregation = request.getAggregation() == null
                ? AggregationType.COUNT : request.getAggregation();

        List<GraphNode> scope =
                NodeAggregationSupport.nodesInScope(knowledgeGraphService, request.getGraphId());
        List<String> contributing = new ArrayList<>();
        Map<String, List<Double>> bySubtype = new LinkedHashMap<>();
        List<Double> values = new ArrayList<>();
        int matched = 0;
        int skipped = 0;

        for (GraphNode node : scope) {
            if (node == null || node.getNodeId() == null
                    || !NodeAggregationSupport.matchesType(node, request.getRootType())) {
                continue;
            }
            matched++;
            double value;
            if (aggregation == AggregationType.COUNT) {
                value = 1.0;
            } else {
                OptionalDouble parsed = NodeAggregationSupport.numericValue(
                        node, request.getNumericAttribute());
                if (parsed.isEmpty()) {
                    skipped++;
                    continue;
                }
                value = parsed.getAsDouble();
            }
            values.add(value);
            contributing.add(node.getNodeId());
            if (request.isGroupBySubtype()) {
                bySubtype.computeIfAbsent(NodeAggregationSupport.subtype(node),
                        ignored -> new ArrayList<>()).add(value);
            }
        }

        Map<String, Double> breakdown = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : bySubtype.entrySet()) {
            breakdown.put(entry.getKey(), fold(aggregation, entry.getValue()));
        }
        return AggregationResult.builder()
                .total(fold(aggregation, values))
                .matchedNodeCount(matched)
                .skippedNodeCount(skipped)
                .perSubtypeBreakdown(breakdown)
                .contributingNodeIds(contributing)
                .graphId(request.getGraphId())
                .rootType(request.getRootType())
                .numericAttribute(request.getNumericAttribute())
                .aggregation(aggregation)
                .build();
    }

    static double fold(AggregationType aggregation, List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        return switch (aggregation) {
            case COUNT -> values.size();
            case SUM -> values.stream().mapToDouble(Double::doubleValue).sum();
            case AVG -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            case MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            case MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        };
    }
}
