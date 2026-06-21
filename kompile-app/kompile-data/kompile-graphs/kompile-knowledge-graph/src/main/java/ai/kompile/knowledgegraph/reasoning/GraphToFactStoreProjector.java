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

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Projects a crawled fact-sheet graph into the PSL {@link FactStore} so the
 * {@link IncrementalReasoningOrchestrator} MAP solver has data to work with.
 *
 * <h3>What is projected</h3>
 * <ul>
 *   <li><b>Nodes → unary type atoms</b>: each {@link GraphNode} with a non-null
 *       {@link ai.kompile.knowledgegraph.domain.NodeLevel} produces
 *       {@code lowercase(nodeType)(externalId)} — e.g.
 *       {@code entity(doc-abc)} for an ENTITY-level node.  The {@code externalId} is
 *       used (not the internal UUID) so PSL rules can refer to stable domain identifiers.</li>
 *   <li><b>Edges → binary predicate atoms</b>: each {@link GraphEdge} produces
 *       {@code lowercase(edgeType)(sourceExternalId, targetExternalId)}.  The edge
 *       {@link GraphEdge#getWeight()} or {@link GraphEdge#getConfidence()} is used as the
 *       soft-truth value (1.0 if null).</li>
 * </ul>
 *
 * <h3>Idempotence</h3>
 * <p>{@link FactStore#assertFact} uses revision semantics — re-projecting replaces
 * the old fact with the new one (same atom key), so calling {@code project} twice
 * does not duplicate atoms.</p>
 *
 * <h3>Null-safety</h3>
 * <p>When {@code knowledgeGraphService} is {@code null} (plain-Java test context),
 * {@link #project(long)} is a no-op returning 0. The existing 17 tests
 * (which supply facts directly into the FactStore) continue to pass unmodified.</p>
 */
@Service
@Slf4j
public class GraphToFactStoreProjector {

    /** Source-id label used for all PSL facts projected from the graph. */
    private static final String SOURCE_ID = "graph-projection";

    @Nullable
    private final KnowledgeGraphService knowledgeGraphService;

    private final KbGroundingService kbGroundingService;

    /**
     * Primary Spring constructor: both dependencies are injected.
     *
     * @param knowledgeGraphService the live graph store (the {@code @Primary} matrix/vector store)
     * @param kbGroundingService    the grounding service that owns the per-factSheet FactStores
     */
    public GraphToFactStoreProjector(KnowledgeGraphService knowledgeGraphService,
                                     KbGroundingService kbGroundingService) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.kbGroundingService = kbGroundingService;
    }

    /**
     * Project all nodes and edges for {@code factSheetId} into that fact sheet's
     * {@link FactStore}.
     *
     * <p>This must be called <em>before</em> the MAP solve inside
     * {@link IncrementalReasoningOrchestrator#runFullReground} so that a freshly
     * crawled graph is reflected in the next PSL inference run.</p>
     *
     * @param factSheetId the fact sheet whose graph to project
     * @return the number of PSL atoms asserted (0 if the graph service is null)
     */
    public int project(long factSheetId) {
        if (knowledgeGraphService == null) {
            log.debug("GraphToFactStoreProjector: knowledgeGraphService is null — no-op for factSheet={}",
                    factSheetId);
            return 0;
        }

        FactStore factStore = kbGroundingService.getState(factSheetId).factStore();
        int asserted = 0;

        // ── Project nodes → unary type atoms ─────────────────────────────────────
        List<GraphNode> nodes = knowledgeGraphService.getNodesInFactSheet(factSheetId);
        for (GraphNode node : nodes) {
            if (node.getNodeType() == null || node.getExternalId() == null) {
                continue;
            }
            String predicate = node.getNodeType().name().toLowerCase(Locale.ROOT);
            String externalId = node.getExternalId();
            // Sanitize externalId for use as a PSL atom argument (remove parens/commas)
            String safeId = sanitizeAtomArg(externalId);
            String atomKey = predicate + "(" + safeId + ")";
            factStore.assertFact(Fact.observed(atomKey, SOURCE_ID));
            asserted++;
        }

        // ── Project edges → binary predicate atoms ────────────────────────────────
        List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        for (GraphEdge edge : edges) {
            if (edge.getEdgeType() == null
                    || edge.getSourceNode() == null
                    || edge.getTargetNode() == null) {
                continue;
            }
            String srcId = sanitizeAtomArg(edge.getSourceNode().getExternalId());
            String tgtId = sanitizeAtomArg(edge.getTargetNode().getExternalId());
            if (srcId == null || tgtId == null) continue;

            // Prefer the semantic relationType if present; fall back to structural EdgeType
            String predicate;
            if (edge.getRelationType() != null && !edge.getRelationType().isBlank()) {
                predicate = edge.getRelationType().toLowerCase(Locale.ROOT);
            } else {
                predicate = edge.getEdgeType().name().toLowerCase(Locale.ROOT);
            }

            // Edge soft-truth value: prefer confidence, fall back to weight, default 1.0
            double value = 1.0;
            if (edge.getConfidence() != null && edge.getConfidence() >= 0.0 && edge.getConfidence() <= 1.0) {
                value = edge.getConfidence();
            } else if (edge.getWeight() != null && edge.getWeight() >= 0.0 && edge.getWeight() <= 1.0) {
                value = edge.getWeight();
            }

            String atomKey = predicate + "(" + srcId + ", " + tgtId + ")";
            if (value >= 0.99) {
                factStore.assertFact(Fact.observed(atomKey, SOURCE_ID));
            } else {
                factStore.assertFact(Fact.soft(atomKey, value, SOURCE_ID));
            }
            asserted++;
        }

        log.debug("GraphToFactStoreProjector: projected {} atoms into FactStore for factSheet={}",
                asserted, factSheetId);
        return asserted;
    }

    /**
     * Sanitize a raw external ID for use as a PSL atom argument.
     * PSL atom keys use the form {@code predicate(arg1, arg2)}, so commas, parentheses
     * and whitespace inside argument strings would break the parser. We replace them
     * with underscores and trim.
     */
    private static String sanitizeAtomArg(String raw) {
        if (raw == null) return null;
        // Replace characters that would confuse PSL atom-key parsing
        return raw.trim()
                  .replace('(', '_')
                  .replace(')', '_')
                  .replace(',', '_')
                  .replace(' ', '_');
    }
}
