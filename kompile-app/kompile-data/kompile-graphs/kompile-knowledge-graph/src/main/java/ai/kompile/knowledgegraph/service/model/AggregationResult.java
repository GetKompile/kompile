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
import java.util.Map;

/**
 * Result of a hierarchy-aware aggregation: the computed aggregate, a per-subtype breakdown, and
 * the contributing node IDs for evidence tracing.
 */
@Value
@Builder
public class AggregationResult {

    /** The computed aggregate (node count for COUNT). */
    double total;

    /** Nodes whose type matched the requested root type. */
    int matchedNodeCount;

    /** Matched nodes skipped because the numeric attribute was absent or unparsable. */
    int skippedNodeCount;

    /** Per-subtype aggregate; empty unless {@code groupBySubtype} was requested. */
    Map<String, Double> perSubtypeBreakdown;

    /** Node IDs that contributed to the aggregate, for evidence tracing. */
    List<String> contributingNodeIds;

    String graphId;
    String rootType;
    String numericAttribute;
    AggregationType aggregation;
}
