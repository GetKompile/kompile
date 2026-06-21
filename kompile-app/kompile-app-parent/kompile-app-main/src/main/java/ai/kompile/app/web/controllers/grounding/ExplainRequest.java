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

/**
 * Request body for POST /api/kb-grounding/explain.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExplainRequest(
        String atom,
        Long factSheetId,
        int depth,
        String sessionId
) {}
