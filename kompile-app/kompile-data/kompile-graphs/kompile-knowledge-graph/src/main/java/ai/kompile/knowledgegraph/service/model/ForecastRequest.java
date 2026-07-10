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

/**
 * Temporal forecast request: bucket matched nodes by time, aggregate per bucket, and project
 * a bounded number of future buckets.
 *
 * @see ai.kompile.knowledgegraph.service.GraphForecastService
 */
@Value
@Builder
public class ForecastRequest {

    /** Graph scope, e.g. {@code "factsheet_42"}; null = all graphs. */
    String graphId;

    /** Required: nodes whose type memberships (is-a closure included) match this type. */
    String rootType;

    /** Numeric metadata attribute; required for SUM/AVG/MIN/MAX, ignored for COUNT. */
    String numericAttribute;

    /** Per-bucket aggregation. */
    AggregationType aggregation;

    /** Time-bucket granularity. */
    BucketSize bucketSize;

    /** Number of future buckets to project. */
    int horizonBuckets;

    /**
     * Optional LLM provider for a narrative; the knowledge-graph module itself never calls a
     * model — a host-level decorator may honor this hint.
     */
    String preferredLlmProvider;
}
