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

/**
 * Response body for POST /api/kb-grounding/explain.
 *
 * <p>The {@code derivation} field is a JSON string produced by
 * {@code DerivationTree.toJson()} — the controller parses it back into a
 * {@code String} so Jackson can nest it as a raw JSON value. It is returned
 * as a {@code String} here and annotated {@code @JsonRawValue} in the
 * controller response mapping. Callers should treat it as an embedded JSON object.</p>
 */
public record ExplainResponse(
        String atom,
        String verdict,
        double confidence,
        String summary,
        String derivation,
        GroundingMeta meta
) {}
