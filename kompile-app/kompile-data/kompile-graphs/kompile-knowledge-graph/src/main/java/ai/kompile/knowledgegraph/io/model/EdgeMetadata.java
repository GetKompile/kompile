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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.Map;

/**
 * Typed view of the edge {@code metaJson} payload that {@code GraphIOService} hands to
 * {@link ai.kompile.knowledgegraph.service.KnowledgeGraphService#createEdgeWithMetadata}.
 *
 * <p>This is a <em>known-schema</em> contract between the exporter/importer and the store, so it is
 * a typed POJO rather than a {@code Map<String,Object>} + key-by-key extraction: a misspelled key
 * can't silently drop a field, and the schema lives in one place. (Genuinely open-ended bags — node
 * metadata, and the edge's <em>own</em> arbitrary metadata carried in {@link #metadata()} — stay as
 * maps, which is the right tool for an open schema.)</p>
 *
 * <p>It has many optional fields, so it is constructed via a Lombok {@code @Builder}
 * ({@code EdgeMetadata.builder().confidence(..).provenanceType(..).build()}) rather than a positional
 * constructor full of {@code null}s. It stays a {@code record} so Jackson round-trips it through the
 * canonical constructor and the {@code meta.confidence()} read-sites are unchanged. Serialized with
 * {@code NON_NULL} so absent fields are omitted, and read with {@code ignoreUnknown}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder
public record EdgeMetadata(
        Double confidence,
        String provenance,
        String occurredAt,
        Boolean bidirectional,
        String label,
        String sharedEntitiesJson,
        Double similarityScore,
        String description,
        Map<String, Object> metadata,
        String provenanceType
) {
    public static final EdgeMetadata EMPTY = EdgeMetadata.builder().build();

    /** True when no field is set — callers can skip emitting an empty {@code metaJson}. */
    public boolean isEmpty() {
        return confidence == null && provenance == null && occurredAt == null
                && bidirectional == null && label == null && sharedEntitiesJson == null
                && similarityScore == null && description == null
                && (metadata == null || metadata.isEmpty()) && provenanceType == null;
    }
}
