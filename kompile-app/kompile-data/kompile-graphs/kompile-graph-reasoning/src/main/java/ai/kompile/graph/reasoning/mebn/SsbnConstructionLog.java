/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.mebn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full construction log for one SSBN generation pass.
 *
 * <p>Captures per-grounded-node information about:</p>
 * <ul>
 *   <li>Which MFrag instantiated the node</li>
 *   <li>The OV substitution (grounding) that was applied</li>
 *   <li>Outcomes of each context-constraint evaluation for that grounding</li>
 *   <li>The {@link SSBNGenerator.DistributionMode} assigned
 *       (CONTEXTUAL / DEFAULT / FINDING / RECURSIVE)</li>
 *   <li>Nodes that were pruned after construction and the pruning reason</li>
 * </ul>
 *
 * <p>Pass {@code null} instead of an {@code SsbnConstructionLog} to the
 * {@code generateWithLog} variants to get zero-overhead legacy behaviour
 * (the existing {@link SSBNGenerator#generate()} / {@link SSBNGenerator#generateForQuery}
 * paths are unchanged).</p>
 */
public final class SsbnConstructionLog {

    private final List<GroundedNodeLog> groundedNodes = new ArrayList<>();
    private final List<PrunedNodeLog>   prunedNodes   = new ArrayList<>();

    // ─── Package-private mutation API (used only by SSBNGenerator) ──────────

    /** Record the construction decision for one grounded node. */
    void addGroundedNode(GroundedNodeLog entry) {
        groundedNodes.add(entry);
    }

    /** Record a node that was removed by Bayes-Ball pruning. */
    void addPrunedNode(PrunedNodeLog entry) {
        prunedNodes.add(entry);
    }

    // ─── Public read API ─────────────────────────────────────────────────────

    /**
     * All grounded nodes (including finding/default/recursive nodes), in
     * instantiation order.
     */
    public List<GroundedNodeLog> groundedNodes() {
        return Collections.unmodifiableList(groundedNodes);
    }

    /**
     * Nodes removed by Bayes-Ball ancestral-set pruning after the network was
     * fully constructed.  Empty when {@link SSBNGenerator#generateWithLog()} is
     * used (no pruning); populated by
     * {@link SSBNGenerator#generateForQueryWithLog(String)}.
     */
    public List<PrunedNodeLog> prunedNodes() {
        return Collections.unmodifiableList(prunedNodes);
    }

    /** Convenience: log entry for the named node, or {@code null} if not found. */
    public GroundedNodeLog findNode(String nodeKey) {
        return groundedNodes.stream()
                .filter(g -> g.nodeKey().equals(nodeKey))
                .findFirst()
                .orElse(null);
    }
}
