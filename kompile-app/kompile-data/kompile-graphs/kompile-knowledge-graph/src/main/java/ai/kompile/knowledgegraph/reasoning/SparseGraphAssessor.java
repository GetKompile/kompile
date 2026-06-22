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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.sparse.SparseEvidenceHelper;
import ai.kompile.graph.reasoning.sparse.SparsityMetrics;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Production consumer of the infra-free {@link SparsityMetrics} / {@link SparseEvidenceHelper} pair:
 * decides whether a fact sheet's graph is structurally sparse and, from that, produces the correct
 * <em>open-world</em> {@link Opinion} for a fact that is ABSENT from the graph.
 *
 * <p><b>Why this matters.</b> For a sparse graph (e.g. an Excel/CSV import where most cells are empty),
 * mere absence of an edge must be represented as <em>uncertainty</em> — a vacuous Opinion whose
 * expectation is the domain base rate — never as disbelief. That is the open-world assumption. For a
 * dense graph the closed-world assumption holds and absence is disbelief. Verifiers already return a
 * bare {@code UNKNOWN} for an absent atom; this turns that into a calibrated, base-rate-aware Opinion.</p>
 *
 * <p>The structural decision is delegated to {@link SparsityMetrics}; this class only bridges the live
 * graph store to it (via {@link KnowledgeGraphReasoningAdapter}) and selects the open- vs closed-world
 * representation. The decision helpers are static + package-visible so they are unit-testable against a
 * hand-built {@link ReasoningGraph} without a Spring context.</p>
 */
@Service
public class SparseGraphAssessor {

    /** Bound on the reasoning-graph projection so assessment stays cheap on large fact sheets. */
    private static final int MAX_NODES = 5000;

    private final KnowledgeGraphService graphService;

    public SparseGraphAssessor(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    /** Structural sparsity metrics for the fact sheet's graph (projected onto a {@link ReasoningGraph}). */
    public SparsityMetrics assess(long factSheetId) {
        List<GraphNode> nodes = graphService.getNodesInFactSheet(factSheetId);
        List<String> seedIds = new ArrayList<>();
        if (nodes != null) {
            for (GraphNode n : nodes) {
                if (n != null && n.getNodeId() != null) {
                    seedIds.add(n.getNodeId());
                }
            }
        }
        ReasoningGraph graph = new KnowledgeGraphReasoningAdapter(graphService)
                .maxNodes(MAX_NODES)
                .subgraph(seedIds);
        return SparsityMetrics.compute(graph);
    }

    /** True when the fact sheet's graph is structurally sparse (open-world reasoning applies). */
    public boolean isSparse(long factSheetId) {
        return isSparse(assess(factSheetId));
    }

    /**
     * Open-world Opinion for a fact ABSENT from the fact sheet's graph: a vacuous, base-rate-aware
     * Opinion when the graph is sparse; closed-world disbelief when it is dense.
     *
     * @param baseRate domain prior probability the absent proposition is true, in [0, 1]
     */
    public Opinion absentFactOpinion(long factSheetId, double baseRate) {
        return absentFactOpinion(assess(factSheetId), baseRate);
    }

    // ── Pure decision logic (unit-testable against a hand-built graph, no Spring/store) ──────────

    /** Sparse iff the graph is trivially small (no closed world to assume) or {@link SparsityMetrics} says so. */
    static boolean isSparse(SparsityMetrics metrics) {
        return metrics.nodeCount <= 1 || metrics.isLikelySparse();
    }

    /** Open-world vacuous (sparse) vs closed-world disbelief (dense) for an absent fact. */
    static Opinion absentFactOpinion(SparsityMetrics metrics, double baseRate) {
        return isSparse(metrics)
                ? SparseEvidenceHelper.opinionForAbsentInSparse(baseRate)
                : Opinion.fromObservedValue(0.0);
    }
}
