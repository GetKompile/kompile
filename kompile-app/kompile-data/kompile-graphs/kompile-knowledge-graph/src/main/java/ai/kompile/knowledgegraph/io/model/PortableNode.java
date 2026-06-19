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

import java.util.Map;

/**
 * Format-agnostic node payload used by importers/exporters. Maps cleanly to
 * {@link ai.kompile.knowledgegraph.domain.GraphNode} fields without dragging JPA into the
 * format layer.
 *
 * <p>The scoping/quality fields ({@code factSheetId}, {@code namedGraphId},
 * {@code confidence}, {@code occurredAt}) are required for <em>faithful</em>
 * round-trips — e.g. when a graph is serialized into a project and rehydrated
 * after a {@code git clone}. Interop formats that don't carry them simply leave
 * them null (honored by {@code @JsonInclude(NON_NULL)}).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortableNode(
        String externalId,
        String title,
        String description,
        String nodeType,
        Map<String, Object> metadata,
        Long factSheetId,
        String namedGraphId,
        Double confidence,
        String occurredAt
) {
    /**
     * Backwards-compatible constructor for interop formats that only carry the
     * core fields (no scoping/quality). Delegates to the canonical constructor
     * with the extended fields null.
     */
    public PortableNode(String externalId, String title, String description,
                        String nodeType, Map<String, Object> metadata) {
        this(externalId, title, description, nodeType, metadata, null, null, null, null);
    }
}
