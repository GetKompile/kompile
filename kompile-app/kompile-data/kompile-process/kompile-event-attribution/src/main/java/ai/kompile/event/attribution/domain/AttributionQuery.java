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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Set;

/**
 * Encapsulates all parameters for an event attribution query.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttributionQuery {

    /**
     * Node ID of the target event to explain.
     */
    private String targetNodeId;

    /**
     * Optional natural language question (e.g. "Why did the server crash?").
     * When provided, the LLM uses this to guide traversal and explanation.
     */
    private String naturalLanguageQuery;

    /**
     * Fact sheet scope. If set, only considers nodes/edges within this fact sheet.
     */
    private Long factSheetId;

    /**
     * Maximum depth to traverse backward from the target event.
     */
    @Builder.Default
    private int maxDepth = 5;

    /**
     * Maximum number of causal chains to return.
     */
    @Builder.Default
    private int maxChains = 5;

    /**
     * Minimum confidence threshold — chains below this are discarded.
     */
    @Builder.Default
    private double minConfidence = 0.1;

    /**
     * Whether to invoke the LLM for explanation generation and ambiguity resolution.
     */
    @Builder.Default
    private boolean useLlm = true;

    /**
     * Whether to include counterfactual analysis ("what if X hadn't happened?").
     */
    @Builder.Default
    private boolean includeCounterfactuals = false;

    /**
     * Optional temporal bounds — only consider events within this window.
     */
    private Instant temporalStart;
    private Instant temporalEnd;

    /**
     * Restrict to specific causal edge types. If empty/null, all types are considered.
     */
    private Set<CausalEdgeType> allowedCausalTypes;

    /**
     * Restrict to specific evidence types. If empty/null, all types are considered.
     */
    private Set<EvidenceType> requiredEvidenceTypes;
}
