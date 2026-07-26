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
package ai.kompile.app.web.controllers;

import ai.kompile.knowledgegraph.service.HierarchicalAggregationService;
import ai.kompile.knowledgegraph.service.model.AggregationRequest;
import ai.kompile.knowledgegraph.service.model.AggregationResult;
import ai.kompile.knowledgegraph.service.model.AggregationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST endpoint for hierarchy-aware numeric aggregation over the knowledge graph.
 * <p>
 * {@code POST /api/graph/aggregate} accepts an {@link AggregationRequest} and returns
 * an {@link AggregationResult} with the computed aggregate, a per-subtype breakdown,
 * and the contributing node IDs for evidence tracing.
 * <p>
 * Type resolution is hierarchy-aware: querying rootType="Wine" will match nodes typed
 * "RedWine" or "WhiteWine" when those subtypes appear in the node's {@code owlInferredTypes}
 * metadata (the is-a closure materialized by OWL-RL reasoning). No domain terms are
 * hard-coded — rootType, numericAttribute, and aggregation are caller-supplied parameters.
 */
@RestController
@RequestMapping("/api/graph")
@Slf4j
public class GraphAggregateController {

    private final HierarchicalAggregationService aggregationService;

    @Autowired
    public GraphAggregateController(
            @Autowired(required = false) HierarchicalAggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    /**
     * Aggregate a numeric attribute over all nodes whose type (or any ancestor type in the
     * is-a hierarchy) matches {@code rootType}.
     *
     * <p>Request body (JSON):
     * <pre>
     * {
     *   "graphId": "factsheet_42",          // optional — null searches all loaded graphs
     *   "rootType": "Wine",                 // required
     *   "numericAttribute": "volume_liters",// required for SUM/AVG/MIN/MAX; ignored for COUNT
     *   "aggregation": "SUM",              // SUM | COUNT | AVG | MIN | MAX
     *   "groupBySubtype": true              // optional, default false
     * }
     * </pre>
     *
     * <p>Response body (JSON):
     * <pre>
     * {
     *   "total": 42.5,
     *   "matchedNodeCount": 3,
     *   "skippedNodeCount": 1,
     *   "perSubtypeBreakdown": { "RedWine": 20.0, "WhiteWine": 22.5 },
     *   "contributingNodeIds": ["node-1", "node-2", "node-3"],
     *   "graphId": null,
     *   "rootType": "Wine",
     *   "numericAttribute": "volume_liters",
     *   "aggregation": "SUM"
     * }
     * </pre>
     */
    @PostMapping("/aggregate")
    public ResponseEntity<?> aggregate(@RequestBody Map<String, Object> requestBody) {
        if (aggregationService == null) {
            return ResponseEntity.ok(Map.of(
                    "error", "Aggregation service is not available. Ensure the knowledge-graph module is configured.",
                    "available", false
            ));
        }

        String rootType = (String) requestBody.get("rootType");
        if (rootType == null || rootType.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "rootType is required"));
        }

        String aggregationStr = (String) requestBody.getOrDefault("aggregation", "COUNT");
        AggregationType aggregation;
        try {
            aggregation = AggregationType.valueOf(aggregationStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown aggregation type '" + aggregationStr + "'. Valid: SUM, COUNT, AVG, MIN, MAX"
            ));
        }

        if (aggregation != AggregationType.COUNT) {
            String attr = (String) requestBody.get("numericAttribute");
            if (attr == null || attr.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "numericAttribute is required for " + aggregation + " aggregation"
                ));
            }
        }

        AggregationRequest req = AggregationRequest.builder()
                .graphId((String) requestBody.get("graphId"))
                .rootType(rootType)
                .numericAttribute((String) requestBody.get("numericAttribute"))
                .aggregation(aggregation)
                .groupBySubtype(Boolean.TRUE.equals(requestBody.get("groupBySubtype")))
                .build();

        try {
            AggregationResult result = aggregationService.aggregate(req);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Aggregation failed for rootType={}", rootType, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Aggregation failed: " + e.getMessage()
            ));
        }
    }
}
