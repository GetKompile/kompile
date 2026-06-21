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

import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Removes materialized INFERRED edges from the @Primary store.
 *
 * <p><strong>Provenance gate (hard safety rule):</strong> this pruner NEVER touches
 * {@link EdgeProvenance#EXTRACTED} edges — observed truth is immutable from pruning's
 * perspective. Only {@link EdgeProvenance#INFERRED} and {@link EdgeProvenance#AMBIGUOUS}
 * edges are eligible.</p>
 */
@Component
public class InferredFactGraphPruner {

    private static final Logger log = LoggerFactory.getLogger(InferredFactGraphPruner.class);

    private final KnowledgeGraphService knowledgeGraphService;

    public InferredFactGraphPruner(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /** Outcome of a prune run. */
    public record PruneResult(int edgesDeleted, boolean dryRun) {}

    /**
     * Remove INFERRED edges whose atom keys are in the {@code retractedAtomKeys} set.
     *
     * <p>Soft-deletes (marks stale). Provenance-gated: never touches EXTRACTED edges.
     * The match is performed by checking whether the edge's description contains the atom key,
     * which is the convention set during materialization.</p>
     *
     * @param factSheetId       the fact sheet scope
     * @param runId             the hydration run ID (for logging)
     * @param retractedAtomKeys atom keys that were retracted by BeliefReviser this run
     * @param dryRun            when true no writes are performed
     * @return count of edges that were (or would be) soft-deleted
     */
    public PruneResult pruneRetracted(Long factSheetId, String runId,
                                      Set<String> retractedAtomKeys, boolean dryRun) {
        if (retractedAtomKeys == null || retractedAtomKeys.isEmpty()) {
            return new PruneResult(0, dryRun);
        }
        List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        List<String> toDelete = new ArrayList<>();
        for (GraphEdge edge : edges) {
            if (Boolean.TRUE.equals(edge.getStale())) continue;
            if (!isInferredProvenance(edge)) continue; // provenance gate
            String desc = edge.getDescription() != null ? edge.getDescription() : "";
            for (String atomKey : retractedAtomKeys) {
                if (desc.contains(atomKey)) {
                    toDelete.add(edge.getEdgeId());
                    break;
                }
            }
        }
        if (!toDelete.isEmpty()) {
            GraphPruneResult result = knowledgeGraphService.pruneEdges(toDelete, true, dryRun);
            log.info("InferredFactGraphPruner.pruneRetracted: marked {} INFERRED edges stale "
                            + "for factSheet={} runId={} dryRun={}",
                    result.affectedCount(), factSheetId, runId, dryRun);
            return new PruneResult(result.affectedCount(), dryRun);
        }
        return new PruneResult(0, dryRun);
    }

    /**
     * Remove INFERRED edges whose confidence is below {@code confidenceThreshold}.
     *
     * <p>Soft-deletes. Provenance-gated: never touches EXTRACTED edges.</p>
     *
     * @param factSheetId           the fact sheet scope
     * @param confidenceThreshold   edges below this threshold are eligible for removal
     * @param dryRun                when true no writes are performed
     * @return count of edges that were (or would be) soft-deleted
     */
    public PruneResult pruneByConfidence(Long factSheetId, double confidenceThreshold,
                                         boolean dryRun) {
        List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        List<String> toDelete = new ArrayList<>();
        for (GraphEdge edge : edges) {
            if (Boolean.TRUE.equals(edge.getStale())) continue;
            if (!isInferredProvenance(edge)) continue; // provenance gate
            double conf = edge.getConfidence() != null ? edge.getConfidence() : 1.0;
            if (conf < confidenceThreshold) {
                toDelete.add(edge.getEdgeId());
            }
        }
        if (!toDelete.isEmpty()) {
            GraphPruneResult result = knowledgeGraphService.pruneEdges(toDelete, true, dryRun);
            log.info("InferredFactGraphPruner.pruneByConfidence: marked {} INFERRED edges stale "
                            + "(threshold={}) for factSheet={} dryRun={}",
                    result.affectedCount(), confidenceThreshold, factSheetId, dryRun);
            return new PruneResult(result.affectedCount(), dryRun);
        }
        return new PruneResult(0, dryRun);
    }

    /**
     * Provenance gate: returns {@code true} only for INFERRED or AMBIGUOUS edges.
     * EXTRACTED edges are observed truth and must never be pruned.
     */
    private static boolean isInferredProvenance(GraphEdge edge) {
        // Check typed field first (preferred — round-trips cleanly as enum)
        if (edge.getProvenanceType() != null) {
            return edge.getProvenanceType() == EdgeProvenance.INFERRED
                    || edge.getProvenanceType() == EdgeProvenance.AMBIGUOUS;
        }
        // Fall back to free-text provenance field
        String prov = edge.getProvenance();
        if (prov == null) return false;
        return EdgeProvenance.INFERRED.name().equalsIgnoreCase(prov)
                || EdgeProvenance.AMBIGUOUS.name().equalsIgnoreCase(prov);
    }
}
