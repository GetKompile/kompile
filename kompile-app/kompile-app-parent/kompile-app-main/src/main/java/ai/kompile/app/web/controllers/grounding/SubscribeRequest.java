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

import java.util.List;

/**
 * Request body for {@code POST /api/kb-grounding/subscribe}.
 *
 * @param factSheetId the fact-sheet to scope the subscription to; null → 0 (global)
 * @param predicates  predicate names to match; null or empty = all predicates on this fact sheet
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubscribeRequest(
        Long factSheetId,
        List<String> predicates
) {}
