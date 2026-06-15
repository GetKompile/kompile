/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.domain;

/**
 * How a piece of evidence for an attribution claim was obtained.
 */
public enum EvidenceType {

    /**
     * Directly extracted from source text — the relationship is explicitly stated.
     */
    DIRECT_EXTRACTION,

    /**
     * Inferred from graph structure — the system followed typed edges to
     * derive the connection (e.g. shared entity, hierarchical containment).
     */
    GRAPH_STRUCTURAL,

    /**
     * Temporal proximity — events co-occur within a time window and share
     * entities or context, suggesting a causal or correlative link.
     */
    TEMPORAL_PROXIMITY,

    /**
     * Embedding similarity — vector space proximity between event representations
     * suggests semantic relatedness.
     */
    EMBEDDING_SIMILARITY,

    /**
     * LLM inference — the language model reasoned over retrieved context
     * and proposed or confirmed the causal link.
     */
    LLM_INFERENCE,

    /**
     * Influence propagation — backward propagation from the target event
     * accumulated significant fault/influence score at this node.
     */
    INFLUENCE_PROPAGATION,

    /**
     * Provenance chain — a deterministic derivation chain (W3C PROV-style)
     * connects the evidence to the event.
     */
    PROVENANCE_CHAIN,

    /**
     * Community co-membership — both events belong to the same graph community
     * (Louvain/WCC), suggesting shared thematic context.
     */
    COMMUNITY_CO_MEMBERSHIP,

    /**
     * PageRank centrality — the source node has high centrality relative to
     * the target, indicating structural importance in the causal neighborhood.
     */
    CENTRALITY_SIGNAL
}
