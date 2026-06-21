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

import java.util.List;
import java.util.Map;

/**
 * Response body for POST /api/kb-grounding/query.
 */
public record QueryResponse(
        List<BindingRow> bindings,
        int total,
        boolean truncated,
        GroundingMeta meta
) {
    /**
     * One binding row returned by a conjunctive query.
     */
    public record BindingRow(
            Map<String, String> variables,
            double confidence,
            List<String> matchedAtoms
    ) {}
}
