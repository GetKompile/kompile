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

import ai.kompile.graph.reasoning.explain.ReasoningTrail;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified explain endpoint — POST /api/explain.
 *
 * <p>Auto-routes by target type to the correct reasoning engine via
 * {@link ExplainOrchestrator}:</p>
 * <ul>
 *   <li>Atom key (contains parentheses) → GROUNDING (KB derivation tree)</li>
 *   <li>Entity id (no parentheses, no prefix) → HYBRID (structural + semantic)</li>
 *   <li>{@code causal:<target>} prefix → CAUSAL (attribution chains)</li>
 * </ul>
 *
 * <p>The {@code mode} field in the request body overrides auto-detection. Accepted values:
 * {@code GROUNDING}, {@code HYBRID}, {@code CAUSAL}, {@code PSL} (HL-MRF soft logic),
 * {@code MEBN} (multi-entity Bayesian network / variable elimination).</p>
 *
 * <p>This controller is in package {@code ai.kompile.app.web.controllers.explain},
 * which is registered in {@code GlobalExceptionHandler.basePackages} so structured
 * error bodies are returned on failures instead of opaque HTTP 500s.</p>
 *
 * <p>All existing per-engine endpoints ({@code /api/kb-grounding/explain},
 * {@code /api/attribution/*}) stay intact — this is an additive unified facade.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/explain")
public class ExplainController {

    private final ExplainOrchestrator orchestrator;
    private final KbGroundingService groundingService;

    @Autowired
    public ExplainController(ExplainOrchestrator orchestrator,
                             KbGroundingService groundingService) {
        this.orchestrator = orchestrator;
        this.groundingService = groundingService;
    }

    /**
     * Unified explain — POST /api/explain.
     *
     * <p>Returns a {@link ExplainResponse} with the full {@link ReasoningTrail} plus
     * flat summary fields for quick frontend overlay rendering.</p>
     *
     * @param req the explain request; {@code target} is required
     */
    @PostMapping
    public ResponseEntity<ExplainResponse> explain(@RequestBody ExplainRequest req) {
        if (req.target() == null || req.target().isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }

        long factSheetId = req.factSheetId() != null ? req.factSheetId() : 0L;

        ReasoningTrail trail = orchestrator.explain(
                req.target(), factSheetId, req.depth(), req.mode());

        // For GROUNDING mode, attach the verdict from the verify call
        String verdict = deriveVerdict(trail);

        ExplainResponse response = ExplainResponse.fromTrailWithVerdict(trail, verdict);
        log.debug("POST /api/explain target='{}' mode={} confidence={}",
                req.target(), trail.inferenceMode(), trail.confidence());
        return ResponseEntity.ok(response);
    }

    /**
     * Extract a flat verdict string from a GROUNDING-mode trail.
     * For HYBRID/CAUSAL modes there is no binary verdict, returns null.
     */
    private String deriveVerdict(ReasoningTrail trail) {
        if (!"GROUNDING".equals(trail.inferenceMode())) {
            return null;
        }
        // Re-derive verdict from confidence + trail content
        // The trail was built from verify() + explain(); map confidence back to status
        if (trail.confidence() <= 0.0 && trail.evidence().isEmpty()) {
            return VerifyResult.Status.UNKNOWN.name();
        }
        // Refuted: NL summary contains "refuted" or confidence is very low with evidence
        String summary = trail.naturalLanguageSummary();
        if (summary != null && summary.toLowerCase().contains("refuted")) {
            return VerifyResult.Status.REFUTED.name();
        }
        if (trail.confidence() > 0.0) {
            return VerifyResult.Status.SUPPORTED.name();
        }
        return VerifyResult.Status.UNKNOWN.name();
    }
}
