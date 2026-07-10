/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.explain;

/**
 * Identifies which reasoning modality contributed a piece of evidence to a
 * {@link CompositeReasoningTrail}.
 */
public enum ModalityKind {
    /** FOL grounding / KB derivation tree (KbGroundingService). */
    GROUNDING,
    /** Structural + semantic hybrid ranking (HybridReasoner). */
    HYBRID,
    /** Probabilistic Soft Logic / HL-MRF MAP inference (PslReasoningService). */
    PSL,
    /** Multi-Entity Bayesian Network / variable elimination (BayesianNetworkService). */
    MEBN,
    /** Causal attribution chains (EventAttributionService). */
    CAUSAL,
    /** Graph-RAG entity / relationship evidence (MatrixGraphRagService). */
    GRAPH_RAG,
    /** Raw RAG text chunks (source document passages). */
    RAG_CHUNK
}
