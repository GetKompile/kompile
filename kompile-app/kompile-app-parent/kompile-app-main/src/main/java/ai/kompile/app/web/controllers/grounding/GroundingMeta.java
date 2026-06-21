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

import java.time.Instant;

/**
 * Common meta-block included in every grounding API response.
 *
 * <p>Per the grounding-api-contract-design.md section 1.1.</p>
 */
public record GroundingMeta(
        Long factSheetId,
        Instant asOf,
        boolean stale,
        long stalenessBudgetMs,
        long kbVersion,
        String sessionId
) {}
