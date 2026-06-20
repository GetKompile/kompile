/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining.extract;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;

import java.util.List;
import java.util.Map;

/**
 * Decides which graph nodes belong to the same process instance (the "case notion") — the only
 * genuinely domain-specific decision in the pipeline, and the hardest problem in turning a graph into
 * an event log. Forcing a single arbitrary case id causes the well-known convergence/divergence
 * distortions; different strategies trade those off differently.
 *
 * @see ConnectedComponentCorrelation the general default
 */
public interface CaseCorrelation {

    /**
     * Partitions node ids into cases. Returned as {@code caseId → member node ids}; ordering within a
     * case is irrelevant here (the extractor orders events by timestamp afterwards).
     */
    Map<String, List<String>> correlate(List<GraphNode> nodes, List<GraphEdge> edges);

    default String strategyName() {
        return getClass().getSimpleName();
    }
}
