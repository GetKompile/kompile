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
 * Request body for POST /api/kb-grounding/claim (P1-5 claim dossier endpoint).
 *
 * @param subject     subject entity id
 * @param predicate   predicate / relation type (case-insensitive matching in DossierBuilder)
 * @param object      object entity id
 * @param factSheetId scope to a specific fact sheet; null = global (0L)
 * @param sessionId   optional correlation id echoed in the meta block
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimRequest(
        String subject,
        String predicate,
        String object,
        Long factSheetId,
        String sessionId
) {}
