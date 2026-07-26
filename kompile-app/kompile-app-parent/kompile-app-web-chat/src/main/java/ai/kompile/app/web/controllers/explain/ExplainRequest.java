/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.explain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Request body for POST /api/explain.
 *
 * <p>The router uses {@code target} and the optional {@code mode} hint to decide which
 * reasoning engine to invoke:</p>
 * <ul>
 *   <li>atom-key form (e.g. {@code isEmployedBy(Alice,Acme)}) → GROUNDING</li>
 *   <li>{@code causal:<target>} prefix → CAUSAL attribution</li>
 *   <li>bare entity id (no parentheses, no prefix) → HYBRID structural+semantic</li>
 *   <li>{@code mode} override (GROUNDING|HYBRID|CAUSAL) forces a specific engine</li>
 * </ul>
 *
 * @param target      the thing to explain — atom key, entity id, or prefixed causal target
 * @param factSheetId the fact-sheet / named-graph scope; null = global (0L)
 * @param depth       derivation depth cap; 0 uses the lib default (5)
 * @param mode        optional engine override: GROUNDING, HYBRID, CAUSAL
 * @param sessionId   pass-through for audit/correlation
 * @param format      optional response format: {@code json} (default) | {@code prov-n} | {@code prov-json}.
 *                    When {@code prov-n} or {@code prov-json} is requested the controller
 *                    renders the {@link ai.kompile.graph.reasoning.explain.ReasoningTrace}
 *                    via {@link ai.kompile.graph.reasoning.explain.ProvSerializer} and returns
 *                    the PROV document as {@code text/plain} (PROV-N) or
 *                    {@code application/json} (PROV-JSON). Default behaviour is unchanged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExplainRequest(
        String target,
        Long factSheetId,
        int depth,
        String mode,
        String sessionId,
        String format
) {}
