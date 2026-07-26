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
 * Request body for {@code POST /api/kb-grounding/retract}.
 *
 * @param factSheetId the fact sheet to retract from (required)
 * @param atomKey     the canonical atom key to retract (required), e.g. {@code "trusts(Alice, Bob)"}
 * @param mode        optional retraction mode:
 *                    <ul>
 *                      <li>{@code "retract"} (default) — TMS retraction + dependency analysis;
 *                          background cascade re-reasons asynchronously.</li>
 *                      <li>{@code "revise"} — TMS retraction + synchronous propagation of
 *                          sole-dependent removals before returning; background cascade still fires.</li>
 *                    </ul>
 */
public record RetractRequest(
        Long factSheetId,
        String atomKey,
        String mode
) {}
