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

import ai.kompile.knowledgegraph.service.GraphForecastService;
import ai.kompile.knowledgegraph.service.model.AggregationType;
import ai.kompile.knowledgegraph.service.model.BucketSize;
import ai.kompile.knowledgegraph.service.model.ForecastRequest;
import ai.kompile.knowledgegraph.service.model.ForecastResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST endpoint for temporal forecasting over the knowledge graph.
 *
 * <p>{@code POST /api/graph/forecast} accepts a JSON request body describing which node
 * type and numeric attribute to aggregate, which time granularity to use for bucketing, and
 * how many future periods to project. It returns a {@link ForecastResult} containing the
 * historical time series, clearly labelled estimates for projected buckets, the projection
 * method used, and an explicit caveat — projections are never represented as certain.
 *
 * <pre>
 * Request (all fields optional except rootType):
 * {
 *   "graphId":           "factsheet_42",       // null = all graphs
 *   "rootType":          "Revenue",            // REQUIRED
 *   "numericAttribute":  "amount",             // required for SUM/AVG/MIN/MAX
 *   "aggregation":       "SUM",               // SUM|COUNT|AVG|MIN|MAX (default SUM)
 *   "bucketSize":        "QUARTER",           // QUARTER|MONTH|YEAR (default QUARTER)
 *   "horizonBuckets":    4,                   // future periods to project (default 4)
 *   "preferredLlmProvider": "claude"          // optional LLM for narrative (skipped if unavailable)
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/graph")
@Slf4j
public class GraphForecastController {

    private final GraphForecastService forecastService;

    @Autowired
    public GraphForecastController(
            @Autowired(required = false) GraphForecastService forecastService) {
        this.forecastService = forecastService;
    }

    /**
     * Temporal forecast over graph nodes.
     *
     * @param requestBody JSON map containing the forecast parameters
     * @return {@link ForecastResult} — always includes the historical series and honesty guards;
     *         projected buckets are empty when history is insufficient
     */
    @PostMapping("/forecast")
    public ResponseEntity<?> forecast(@RequestBody Map<String, Object> requestBody) {
        if (forecastService == null) {
            return ResponseEntity.ok(Map.of(
                    "error", "Forecast service is not available. Ensure the knowledge-graph module is configured.",
                    "available", false
            ));
        }

        String rootType = (String) requestBody.get("rootType");
        if (rootType == null || rootType.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "rootType is required"));
        }

        // aggregation
        String aggregationStr = (String) requestBody.getOrDefault("aggregation", "SUM");
        AggregationType aggregation;
        try {
            aggregation = AggregationType.valueOf(aggregationStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown aggregation '" + aggregationStr + "'. Valid: SUM, COUNT, AVG, MIN, MAX"
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

        // bucket size
        String bucketSizeStr = (String) requestBody.getOrDefault("bucketSize", "QUARTER");
        BucketSize bucketSize;
        try {
            bucketSize = BucketSize.valueOf(bucketSizeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown bucketSize '" + bucketSizeStr + "'. Valid: QUARTER, MONTH, YEAR"
            ));
        }

        // horizon
        int horizonBuckets = 4;
        Object horizonObj = requestBody.get("horizonBuckets");
        if (horizonObj instanceof Number) {
            horizonBuckets = Math.max(1, ((Number) horizonObj).intValue());
        }

        ForecastRequest req = ForecastRequest.builder()
                .graphId((String) requestBody.get("graphId"))
                .rootType(rootType)
                .numericAttribute((String) requestBody.get("numericAttribute"))
                .aggregation(aggregation)
                .bucketSize(bucketSize)
                .horizonBuckets(horizonBuckets)
                .preferredLlmProvider((String) requestBody.get("preferredLlmProvider"))
                .build();

        try {
            ForecastResult result = forecastService.forecast(req);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Forecast failed for rootType={}", rootType, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Forecast failed: " + e.getMessage()
            ));
        }
    }
}
