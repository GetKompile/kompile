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
package ai.kompile.core.graphrag.maintenance.model;

import java.time.Instant;
import java.util.Map;

/**
 * A point-in-time health vector for one fact sheet's knowledge graph. Computed store-agnostically over
 * the active node/edge set and persisted as a time series (under {@code data/graph/health/}) so the
 * asset's health is trackable across a project's git history — this is the analyzable half of "graph
 * as an asset".
 *
 * <p>Metrics are deliberately the cheap, O(N+E) ones (counts, density, degree, weak components,
 * orphan/low-confidence rates, ontology conformance). Expensive global metrics (diameter, average path
 * length, clustering coefficient, richer centralities) are intentionally deferred to incremental
 * additions and are not in this record yet.
 *
 * @param factSheetId               the fact sheet whose graph this describes
 * @param computedAt                when the snapshot was computed
 * @param nodeCount                 active (non-stale) nodes
 * @param edgeCount                 active (non-stale) edges with both endpoints present
 * @param nodesByType               active node counts keyed by {@code NodeLevel} name
 * @param density                   undirected edge density {@code 2E / (N(N-1))}, capped at 1.0 (0 when N&lt;2)
 * @param averageDegree             {@code 2E / N} (0 when N=0)
 * @param maxDegree                 highest total degree of any node
 * @param orphanCount               real (non-SOURCE/CUSTOM) nodes with no active edge
 * @param orphanRate                {@code orphanCount / nodeCount} (0 when N=0)
 * @param lowConfidenceNodeCount    active nodes with confidence &lt; 0.5
 * @param lowConfidenceEdgeCount    active edges with confidence &lt; 0.5
 * @param connectedComponentCount   number of weakly-connected components over the active subgraph
 * @param largestComponentFraction  largest component size / nodeCount (1.0 for a fully connected graph; 0 when N=0)
 * @param ontologyBound             whether an ontology governs this graph (drives {@code conformanceScore})
 * @param conformanceScore          fraction (0..1) of ENTITY nodes conforming to the bound ontology; null when unbound
 */
public record GraphHealthSnapshot(
        Long factSheetId,
        Instant computedAt,
        int nodeCount,
        int edgeCount,
        Map<String, Integer> nodesByType,
        double density,
        double averageDegree,
        int maxDegree,
        int orphanCount,
        double orphanRate,
        int lowConfidenceNodeCount,
        int lowConfidenceEdgeCount,
        int connectedComponentCount,
        double largestComponentFraction,
        boolean ontologyBound,
        Double conformanceScore
) {}
