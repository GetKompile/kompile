/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.grounding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * Request body for POST /api/kb-grounding/query.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryRequest(
        List<ConjunctEntry> conjuncts,
        Long factSheetId,
        Instant asOf,
        int maxResults,
        double minConfidence,
        String sessionId
) {
    /**
     * One atom pattern in the conjunctive query.
     *
     * @param predicate the predicate name (e.g. "worksFor")
     * @param args      arguments; use "?" prefix for variables, bare string for constants
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConjunctEntry(String predicate, List<String> args) {}
}
