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
import ai.kompile.knowledgegraph.service.model.AggregationType;
import ai.kompile.knowledgegraph.service.model.BucketSize;
import ai.kompile.knowledgegraph.service.model.ForecastRequest;
import ai.kompile.knowledgegraph.service.model.ForecastResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * Temporal forecasting over the knowledge graph: bucket matched nodes by observation date,
 * aggregate per bucket, and project future buckets with a least-squares linear trend.
 *
 * <p>Honesty guards are structural: projected buckets are separate from history and flagged as
 * estimates, the projection method is named, the caveat is always present, and no projection is
 * produced from fewer than two historical buckets.</p>
 */
@Service
public class GraphForecastService {

    static final String PROJECTION_METHOD = "least-squares-linear-trend";
    static final String CAVEAT = "Projected buckets are extrapolations of the historical trend, "
            + "not observations; they assume the trend continues and carry no uncertainty bounds.";

    private final KnowledgeGraphService knowledgeGraphService;

    @Autowired
    public GraphForecastService(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /** Bucket, aggregate, and project. Always returns the historical series and the caveat. */
    public ForecastResult forecast(ForecastRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.getRootType(), "rootType");
        AggregationType aggregation = request.getAggregation() == null
                ? AggregationType.SUM : request.getAggregation();
        BucketSize bucketSize = request.getBucketSize() == null
                ? BucketSize.QUARTER : request.getBucketSize();

        List<GraphNode> scope =
                NodeAggregationSupport.nodesInScope(knowledgeGraphService, request.getGraphId());
        TreeMap<Long, List<Double>> byBucket = new TreeMap<>();
        int matched = 0;
        int skipped = 0;
        for (GraphNode node : scope) {
            if (node == null || !NodeAggregationSupport.matchesType(node, request.getRootType())) {
                continue;
            }
            matched++;
            Optional<LocalDate> date = NodeAggregationSupport.observationDate(node);
            if (date.isEmpty()) {
                skipped++;
                continue;
            }
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
            byBucket.computeIfAbsent(bucketKey(date.get(), bucketSize),
                    ignored -> new ArrayList<>()).add(value);
        }

        List<ForecastResult.Bucket> history = new ArrayList<>();
        for (Map.Entry<Long, List<Double>> entry : byBucket.entrySet()) {
            history.add(new ForecastResult.Bucket(
                    bucketLabel(entry.getKey(), bucketSize),
                    HierarchicalAggregationService.fold(aggregation, entry.getValue()),
                    false,
                    entry.getValue().size()));
        }

        List<ForecastResult.Bucket> projected = new ArrayList<>();
        int horizon = Math.max(1, request.getHorizonBuckets());
        if (history.size() >= 2) {
            double[] trend = leastSquares(history);
            long lastKey = byBucket.lastKey();
            for (int step = 1; step <= horizon; step++) {
                long key = advance(lastKey, step, bucketSize);
                double value = trend[0] + trend[1] * (history.size() - 1 + step);
                projected.add(new ForecastResult.Bucket(
                        bucketLabel(key, bucketSize), value, true, 0));
            }
        }

        return ForecastResult.builder()
                .history(history)
                .projected(projected)
                .projectionMethod(PROJECTION_METHOD)
                .caveat(CAVEAT)
                .narrative(null)
                .matchedNodeCount(matched)
                .skippedNodeCount(skipped)
                .graphId(request.getGraphId())
                .rootType(request.getRootType())
                .numericAttribute(request.getNumericAttribute())
                .aggregation(aggregation)
                .bucketSize(bucketSize)
                .build();
    }

    /** Ordinal bucket key: months/quarters/years since year zero — sortable and advanceable. */
    private static long bucketKey(LocalDate date, BucketSize bucketSize) {
        return switch (bucketSize) {
            case MONTH -> date.getYear() * 12L + (date.getMonthValue() - 1);
            case QUARTER -> date.getYear() * 4L + ((date.getMonthValue() - 1) / 3);
            case YEAR -> date.getYear();
        };
    }

    private static long advance(long key, int steps, BucketSize bucketSize) {
        return key + steps;
    }

    private static String bucketLabel(long key, BucketSize bucketSize) {
        return switch (bucketSize) {
            case MONTH -> String.format("%04d-%02d", key / 12, (key % 12) + 1);
            case QUARTER -> String.format("%04d-Q%d", key / 4, (key % 4) + 1);
            case YEAR -> String.format("%04d", key);
        };
    }

    /** Least squares fit y = a + b*x over the history series (x = bucket index). */
    private static double[] leastSquares(List<ForecastResult.Bucket> history) {
        int n = history.size();
        double sumX = 0;
        double sumY = 0;
        double sumXy = 0;
        double sumXx = 0;
        for (int i = 0; i < n; i++) {
            double y = history.get(i).getValue();
            sumX += i;
            sumY += y;
            sumXy += i * y;
            sumXx += (double) i * i;
        }
        double denominator = n * sumXx - sumX * sumX;
        double slope = denominator == 0.0 ? 0.0 : (n * sumXy - sumX * sumY) / denominator;
        double intercept = (sumY - slope * sumX) / n;
        return new double[] {intercept, slope};
    }
}
