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
import java.util.Map;

/**
 * A single piece of evidence supporting (or weakening) a causal attribution claim.
 * Each evidence record is tied to one hop in an {@link AttributionChain} and
 * carries enough metadata for traceability and auditability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttributionEvidence {

    /**
     * How this evidence was obtained.
     */
    private EvidenceType evidenceType;

    /**
     * Numeric strength of this evidence (0.0 to 1.0).
     */
    private double strength;

    /**
     * The graph edge ID that this evidence relates to, if applicable.
     */
    private String edgeId;

    /**
     * The source node ID that produced this evidence.
     */
    private String sourceNodeId;

    /**
     * Human-readable summary of the evidence.
     * For LLM-produced evidence, this is the model's reasoning excerpt.
     */
    private String summary;

    /**
     * The raw text snippet from the source document that supports the claim.
     */
    private String sourceSnippet;

    /**
     * Document/source identifier for citation.
     */
    private String sourceReference;

    /**
     * When this evidence was collected/generated.
     */
    private Instant collectedAt;

    /**
     * Additional metadata (e.g. LLM model name, propagation iteration count,
     * embedding distance value).
     */
    private Map<String, Object> metadata;
}
