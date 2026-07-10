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

import ai.kompile.graph.reasoning.explain.CompositeReasoningTrail;
import ai.kompile.graph.reasoning.explain.ProvSerializer;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.explain.ReasoningTrail;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
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

    @Nullable
    private final FusedReasonerService fusedReasonerService;

    @Autowired
    public ExplainController(ExplainOrchestrator orchestrator,
                             KbGroundingService groundingService,
                             @Nullable FusedReasonerService fusedReasonerService) {
        this.orchestrator = orchestrator;
        this.groundingService = groundingService;
        this.fusedReasonerService = fusedReasonerService;
    }

    /**
     * Unified explain — POST /api/explain.
     *
     * <p>Returns a {@link ExplainResponse} with the full {@link ReasoningTrail} plus
     * flat summary fields for quick frontend overlay rendering.</p>
     *
     * <p>When {@code format} is {@code prov-n} or {@code prov-json}, renders the
     * {@link ReasoningTrace} via {@link ProvSerializer} and returns the PROV document
     * as {@code text/plain} (PROV-N) or {@code application/json} (PROV-JSON).
     * Default {@code json} behaviour is unchanged.</p>
     *
     * @param req the explain request; {@code target} is required
     */
    @PostMapping
    public ResponseEntity<?> explain(@RequestBody ExplainRequest req) {
        if (req.target() == null || req.target().isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }

        long factSheetId = req.factSheetId() != null ? req.factSheetId() : 0L;

        ReasoningTrail trail = orchestrator.explain(
                req.target(), factSheetId, req.depth(), req.mode());

        // P1-8a: PROV export — when format=prov-n or prov-json, render via ProvSerializer
        String format = req.format();
        if ("prov-n".equalsIgnoreCase(format) || "prov-json".equalsIgnoreCase(format)) {
            ReasoningTrace trace = trail.toReasoningTrace();
            if ("prov-n".equalsIgnoreCase(format)) {
                String provN = ProvSerializer.toProvN(trace);
                log.debug("POST /api/explain format=prov-n target='{}' bytes={}", req.target(), provN.length());
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(provN);
            } else {
                String provJson = ProvSerializer.toProvJson(trace);
                log.debug("POST /api/explain format=prov-json target='{}' bytes={}", req.target(), provJson.length());
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(provJson);
            }
        }

        // For GROUNDING mode, attach the verdict from the verify call
        String verdict = deriveVerdict(trail);

        ExplainResponse response = ExplainResponse.fromTrailWithVerdict(trail, verdict);
        log.debug("POST /api/explain target='{}' mode={} confidence={}",
                req.target(), trail.inferenceMode(), trail.confidence());
        return ResponseEntity.ok(response);
    }

    /**
     * Fused multi-modal explain — POST /api/explain/fused.
     *
     * <p>Runs ALL applicable reasoning engines concurrently (GROUNDING where the target is
     * an atom key, HYBRID, PSL, MEBN, CAUSAL, and graph-RAG) and returns a single
     * {@link FusedExplainResponse} carrying one {@link ai.kompile.graph.reasoning.explain.ModalityEvidence}
     * per engine plus a blended fused confidence.</p>
     *
     * <p>Requires {@link FusedReasonerService} to be wired (it is a Spring bean in app-main,
     * so this is always the case in the full application context).  Returns HTTP 503 when
     * the service is not available (e.g. in minimal test contexts).</p>
     *
     * <p>When {@code format} is {@code prov-n} or {@code prov-json}, renders the fused
     * {@link ReasoningTrace} via {@link ProvSerializer}.  Default JSON is unchanged.</p>
     */
    @PostMapping("/fused")
    public ResponseEntity<?> explainFused(@RequestBody ExplainRequest req) {
        if (req.target() == null || req.target().isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        if (fusedReasonerService == null) {
            return ResponseEntity.status(503).build();
        }

        long factSheetId = req.factSheetId() != null ? req.factSheetId() : 0L;
        CompositeReasoningTrail trail = fusedReasonerService.explainAll(
                req.target(), factSheetId, req.depth(), req.target());

        // P1-8a: PROV export on fused endpoint
        String format = req.format();
        if ("prov-n".equalsIgnoreCase(format) || "prov-json".equalsIgnoreCase(format)) {
            ReasoningTrace trace = trail.toReasoningTrace();
            if ("prov-n".equalsIgnoreCase(format)) {
                String provN = ProvSerializer.toProvN(trace);
                log.debug("POST /api/explain/fused format=prov-n target='{}' bytes={}", req.target(), provN.length());
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(provN);
            } else {
                String provJson = ProvSerializer.toProvJson(trace);
                log.debug("POST /api/explain/fused format=prov-json target='{}' bytes={}", req.target(), provJson.length());
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(provJson);
            }
        }

        FusedExplainResponse response = FusedExplainResponse.fromTrail(trail);
        log.debug("POST /api/explain/fused target='{}' modalities={} fusedConf={}",
                req.target(), response.modalityCount(), response.fusedConfidence());
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
