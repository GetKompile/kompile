/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.io.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Format-agnostic edge payload used by importers/exporters.
 *
 * <p>The first nine components are the core relationship + quality fields. The trailing six
 * ({@code factSheetId}, {@code bidirectional}, {@code label}, {@code sharedEntitiesJson},
 * {@code similarityScore}, {@code metadataJson}) are restored for faithful round-trips — they
 * are persisted on {@code GraphEdge} but were previously dropped on export, breaking
 * SHARED_ENTITY payloads (M-4), bidirectional traversal semantics (M-3), per-edge fact-sheet
 * scope (M-1), and edge metadata (M-7). Interop formats that don't carry them leave them null
 * (honored by {@code @JsonInclude(NON_NULL)}).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortableEdge(
        String fromExternalId,
        String toExternalId,
        String edgeType,
        Double weight,
        String description,
        String provenance,
        Double confidence,
        String occurredAt,
        String relationType,
        Long factSheetId,
        Boolean bidirectional,
        String label,
        String sharedEntitiesJson,
        Double similarityScore,
        String metadataJson,
        String provenanceType
) {
    /**
     * Compact constructor for backwards-compatible creation without provenance/confidence/occurredAt.
     */
    public PortableEdge(String fromExternalId, String toExternalId, String edgeType,
                        Double weight, String description) {
        this(fromExternalId, toExternalId, edgeType, weight, description, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    /**
     * Compact constructor for backwards-compatible creation without occurredAt/relationType.
     */
    public PortableEdge(String fromExternalId, String toExternalId, String edgeType,
                        Double weight, String description, String provenance, Double confidence) {
        this(fromExternalId, toExternalId, edgeType, weight, description, provenance, confidence, null, null,
                null, null, null, null, null, null, null);
    }

    /**
     * Compact constructor for backwards-compatible creation without relationType (pre-relationType files).
     */
    public PortableEdge(String fromExternalId, String toExternalId, String edgeType,
                        Double weight, String description, String provenance, Double confidence, String occurredAt) {
        this(fromExternalId, toExternalId, edgeType, weight, description, provenance, confidence, occurredAt, null,
                null, null, null, null, null, null, null);
    }

    /**
     * Compact constructor for the core relationship + quality fields, without the extended
     * edge fields (factSheetId/bidirectional/label/sharedEntitiesJson/similarityScore/metadataJson/provenanceType).
     */
    public PortableEdge(String fromExternalId, String toExternalId, String edgeType,
                        Double weight, String description, String provenance, Double confidence,
                        String occurredAt, String relationType) {
        this(fromExternalId, toExternalId, edgeType, weight, description, provenance, confidence, occurredAt,
                relationType, null, null, null, null, null, null, null);
    }

    /**
     * Compact constructor for the full extended field set <em>without</em> the typed
     * {@code provenanceType} (M-10) — keeps pre-M-10 15-arg call sites (e.g. format importers) valid.
     */
    public PortableEdge(String fromExternalId, String toExternalId, String edgeType,
                        Double weight, String description, String provenance, Double confidence,
                        String occurredAt, String relationType, Long factSheetId, Boolean bidirectional,
                        String label, String sharedEntitiesJson, Double similarityScore, String metadataJson) {
        this(fromExternalId, toExternalId, edgeType, weight, description, provenance, confidence, occurredAt,
                relationType, factSheetId, bidirectional, label, sharedEntitiesJson, similarityScore,
                metadataJson, null);
    }
}
