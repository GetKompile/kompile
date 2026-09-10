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
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.graph.reasoning.tms.BeliefReviser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
     * Optional managed KB config — sourced from the KbConfigManager so the
     * {@code kbDerivationMaxAtoms} cap is honoured without hardcoding. Null in plain-Java
     * test contexts; Spring injects via field injection so existing constructors are untouched.
     */
    @Nullable
    @Autowired(required = false)
    private KbConfigManager kbConfigManager;

    /** Return the current config, falling back to defaults when the manager is not wired. */
    private KbConfig kbCfg() {
        return kbConfigManager != null ? kbConfigManager.current() : KbConfig.defaults();
    }

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

        List<Fact> projectedFacts = new ArrayList<>();
        int asserted = 0;

        // Atom cap: read from KbConfig.derivationMaxAtoms (default 5000, configurable via
        // kbDerivationMaxAtoms). 0 or negative means unlimited. Prevents the PSL grounding
        // blowing MAX_GROUND_RULES (500k) on large graphs (9k+ entities × edge fan-out).
        int maxAtoms = kbCfg().getDerivationMaxAtoms();
        int cap = (maxAtoms > 0) ? maxAtoms : Integer.MAX_VALUE;

        // ── Project nodes → unary type atoms ─────────────────────────────────────
        List<GraphNode> nodes = knowledgeGraphService.getNodesInFactSheet(factSheetId);
        for (GraphNode node : nodes) {
            if (asserted >= cap) {
                log.info("GraphToFactStoreProjector: atom cap={} reached after {} nodes; "
                        + "remaining nodes/edges skipped for factSheet={} "
                        + "(raise kbDerivationMaxAtoms to include more)",
                        cap, asserted, factSheetId);
                break;
            }
            if (node.getNodeType() == null || node.getExternalId() == null) {
                continue;
            }
            String atomKey = atomKeyForNode(node);
            projectedFacts.add(Fact.observed(atomKey, SOURCE_ID));
            asserted++;
        }

        // ── Project edges → binary predicate atoms ────────────────────────────────
        List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        // DIAGNOSTIC: capture conf/weight/value of the first few edges so a "0 soft" projection can be
        // traced to the actual stored edge values (raw confidence vs stamped weight) without guessing.
        List<String> sampleEdgeDiag = new ArrayList<>();
        for (GraphEdge edge : edges) {
            if (asserted >= cap) {
                log.info("GraphToFactStoreProjector: atom cap={} reached after {} edges; "
                        + "remaining edges skipped for factSheet={}",
                        cap, asserted, factSheetId);
                break;
            }
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

            if (sampleEdgeDiag.size() < 5) {
                sampleEdgeDiag.add(String.format(Locale.ROOT, "[%s conf=%s weight=%s -> value=%.4f]",
                        predicate, edge.getConfidence(), edge.getWeight(), value));
            }

            String atomKey = predicate + "(" + srcId + ", " + tgtId + ")";
            if (value >= 0.99) {
                projectedFacts.add(Fact.observed(atomKey, SOURCE_ID));
            } else {
                projectedFacts.add(Fact.soft(atomKey, value, SOURCE_ID));
            }
            asserted++;
        }

        var state = kbGroundingService.getState(factSheetId);
        var writeLock = state.lock().writeLock();
        FactStore factStore = state.factStore();
        int retracted;
        writeLock.lock();
        try {
            Set<String> oldProjectionKeys = factStore.allSourceFacts().stream()
                    .filter(fact -> SOURCE_ID.equals(fact.sourceId()))
                    .map(Fact::atomKey)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            retracted = factStore.replaceSourceFacts(SOURCE_ID, projectedFacts);
            Set<String> replacementKeys = projectedFacts.stream().map(Fact::atomKey)
                    .collect(java.util.stream.Collectors.toSet());
            for (String removedKey : oldProjectionKeys) {
                if (!replacementKeys.contains(removedKey) && factStore.factFor(removedKey).isEmpty()) {
                    BeliefReviser.retractAndPurge(removedKey, factStore,
                            state.justificationIndex(), state.inferredFactStore());
                }
            }
            state.concurrentFactStore().markMutation();
            kbGroundingService.markStale(factSheetId);
        } finally {
            writeLock.unlock();
        }
        if (retracted > 0) {
            log.debug("GraphToFactStoreProjector: replaced {} stale graph-projection atoms for factSheet={}",
                    retracted, factSheetId);
        }

        // Compute hard vs soft breakdown so operators can see whether the graph produces
        // mixed-truth inputs (needed for meaningful PSL inference) or all-1.0 hard facts
        // (which produce MAP posteriors of 1.0 everywhere — then nothing new is derived).
        // A graph of pure hard facts produces loss=0 and versionsWritten=0 on all re-runs.
        int hardCount = 0;
        int softCount = 0;
        for (Fact f : factStore.allFacts()) {
            if (f.hard()) {
                hardCount++;
            } else {
                softCount++;
            }
        }
        if (asserted == 0) {
            log.warn("GraphToFactStoreProjector: projected 0 atoms for factSheet={} "
                    + "(nodes={}, edges={}, cap={}) — FactStore will be empty; MAP solve will skip. "
                    + "Causes: graph has no nodes with non-null nodeType/externalId, or cap=0.",
                    factSheetId, nodes.size(), edges.size(), cap == Integer.MAX_VALUE ? "unlimited" : cap);
        } else if (softCount == 0) {
            log.info("GraphToFactStoreProjector: projected {} atoms ({} nodes, {} edges; cap={}) into FactStore "
                    + "for factSheet={} — ALL {} are hard-observed (value=1.0, {} soft). "
                    + "When all atoms are hard/1.0, MAP posteriors are also 1.0, PSL loss=0, "
                    + "and subsequent cascades write 0 new versions (fixed-point already at 1.0). "
                    + "To get non-trivial derivation, edges need confidence < 1.0.",
                    asserted, nodes.size(), edges.size(), cap == Integer.MAX_VALUE ? "unlimited" : cap,
                    factSheetId, hardCount, softCount);
            log.warn("GraphToFactStoreProjector: sample edge values (root-cause of all-hard) for factSheet={}: {}",
                    factSheetId, sampleEdgeDiag);
        } else {
            log.info("GraphToFactStoreProjector: projected {} atoms ({} nodes, {} edges; cap={}) into FactStore "
                    + "for factSheet={} — hard={} soft={}",
                    asserted, nodes.size(), edges.size(), cap == Integer.MAX_VALUE ? "unlimited" : cap,
                    factSheetId, hardCount, softCount);
        }
        return asserted;
    }

    /**
     * Build the PSL/FOL atom key for a node — {@code lowercase(nodeType)(sanitizedExternalId)} —
     * identical to the key used when projecting the graph into the fact store. This is the single
     * source of truth for node→atom-key conversion; the grounding controller reuses it to resolve
     * a UI-supplied node id / external id into the atom key the store is actually keyed by.
     *
     * @return the atom key, or {@code null} if the node has no type or external id
     */
    public static String atomKeyForNode(GraphNode node) {
        if (node == null || node.getNodeType() == null || node.getExternalId() == null) {
            return null;
        }
        return node.getNodeType().name().toLowerCase(Locale.ROOT)
                + "(" + sanitizeAtomArg(node.getExternalId()) + ")";
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
