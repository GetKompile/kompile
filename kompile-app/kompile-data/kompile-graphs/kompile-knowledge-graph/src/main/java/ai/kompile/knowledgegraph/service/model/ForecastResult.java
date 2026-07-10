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
package ai.kompile.knowledgegraph.service.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Temporal forecast result: the historical series, clearly labelled estimates for projected
 * buckets, the projection method, and an explicit caveat — projections are never represented
 * as certain.
 */
@Value
@Builder
public class ForecastResult {

    /** One time bucket: observed history ({@code estimate=false}) or projection ({@code true}). */
    @Value
    public static class Bucket {
        /** Bucket label, e.g. {@code 2026-07}, {@code 2026-Q3}, {@code 2026}. */
        String label;
        /** Aggregated (history) or projected (estimate) value. */
        double value;
        /** True for projected buckets — estimates, never observations. */
        boolean estimate;
        /** Observations contributing to a history bucket; 0 for projections. */
        int sampleCount;
    }

    /** Observed history, oldest first. */
    List<Bucket> history;

    /** Projected buckets, first future bucket first; empty when history is insufficient. */
    List<Bucket> projected;

    /** Projection method, e.g. {@code least-squares-linear-trend}. */
    String projectionMethod;

    /** Always present: the honesty guard describing why projections are estimates. */
    String caveat;

    /** Optional narrative supplied by a host-level decorator; null in the plain module. */
    String narrative;

    /** Nodes whose type matched the requested root type. */
    int matchedNodeCount;

    /** Matched nodes skipped for missing/unparsable timestamps or numeric values. */
    int skippedNodeCount;

    String graphId;
    String rootType;
    String numericAttribute;
    AggregationType aggregation;
    BucketSize bucketSize;
}
