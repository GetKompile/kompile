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

/**
 * Response body for {@code POST /api/kb-grounding/retract}.
 *
 * <p>Shape is intentionally compatible with {@link AssertResponse} so clients that handle
 * assert-and-retract via the same code path can share parsing logic.</p>
 *
 * @param status                {@code "RETRACTED"} on success, {@code "NOT_FOUND"} if the atom
 *                              was absent from the fact store (idempotent — still HTTP 200)
 * @param atomKey               the atom key that was retracted (echo back for correlation)
 * @param mode                  the retraction mode that was applied ({@code "retract"} or {@code "revise"})
 * @param dependentAtomsUnsupported atoms that lost ALL support due to this retraction (may be empty)
 * @param dependentAtomsWeakened    atoms that lost ONE of several supports (weakened but not removed)
 * @param cascadeTriggered      {@code true} when a background re-reason cascade was scheduled
 * @param meta                  standard grounding meta-block (factSheetId, kbVersion, stale, sessionId, …)
 */
public record RetractResponse(
        String status,
        String atomKey,
        String mode,
        List<String> dependentAtomsUnsupported,
        List<String> dependentAtomsWeakened,
        boolean cascadeTriggered,
        GroundingMeta meta
) {}
