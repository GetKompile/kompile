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

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortableEdge(
        String fromExternalId,
        String toExternalId,
        String edgeType,
        Double weight,
        String description,
        String provenance,
        Double confidence,
        String occurredAt
) {
    /**
     * Compact constructor for backwards-compatible creation without provenance/confidence/occurredAt.
     */
    public PortableEdge(String fromExternalId, String toExternalId, String edgeType,
                        Double weight, String description) {
        this(fromExternalId, toExternalId, edgeType, weight, description, null, null, null);
    }

    /**
     * Compact constructor for backwards-compatible creation without occurredAt.
     */
    public PortableEdge(String fromExternalId, String toExternalId, String edgeType,
                        Double weight, String description, String provenance, Double confidence) {
        this(fromExternalId, toExternalId, edgeType, weight, description, provenance, confidence, null);
    }
}
