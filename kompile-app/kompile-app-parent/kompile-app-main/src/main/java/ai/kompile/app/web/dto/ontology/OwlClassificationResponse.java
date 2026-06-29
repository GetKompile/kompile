/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.dto.ontology;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

/**
 * Response DTO for {@code POST /api/graph-ontology/classify?factSheetId={id}} — the on-demand OWL
 * classification (realization) run. Reports how many entities were classified up the is-a hierarchy
 * and how many has-a transitive-closure edges were materialized back into the graph.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OwlClassificationResponse {

    /** The fact sheet that was classified. */
    long factSheetId;

    /** Whether an ontology was bound/auto-provisioned for the run. */
    boolean ontologyBound;

    /** Display name of the ontology classified against, or {@code null} when none. */
    String ontologyName;

    /** Instance-level inferred is-a type assertions produced by the run. */
    int inferredTypeCount;

    /** Inferred has-a transitive-closure relations produced by the run. */
    int inferredRelationCount;

    /** Entity nodes whose {@code owlInferredTypes} metadata was written. */
    int entitiesClassified;

    /** New {@code INFERRED} has-a edges written back to the graph. */
    int edgesMaterialized;

    /** {@code true} if the reasoner found no inconsistencies. */
    boolean consistent;

    /** {@code true} when the reasoner actually ran (an ontology was bound/provisioned). */
    boolean reasonerActive;

    /** No-op response for a fact sheet with no ontology and nothing to provision (e.g. empty graph). */
    public static OwlClassificationResponse unbound(long factSheetId) {
        return OwlClassificationResponse.builder()
                .factSheetId(factSheetId)
                .ontologyBound(false)
                .reasonerActive(false)
                .build();
    }
}
