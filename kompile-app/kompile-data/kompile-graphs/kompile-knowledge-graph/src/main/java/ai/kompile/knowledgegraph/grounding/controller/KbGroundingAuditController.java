/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.grounding.controller;

import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.knowledgegraph.audit.FactAuditEvent;
import ai.kompile.knowledgegraph.audit.PinRecord;
import ai.kompile.knowledgegraph.grounding.KbCorrectionService;
import ai.kompile.knowledgegraph.grounding.KbCorrectionService.CorrectionResult;
import ai.kompile.knowledgegraph.reasoning.FactPromotionTracker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the grounding KB audit trail, correction/PIN control surface,
 * and strength-layer-filtered fact queries.
 *
 * <p>Base path: {@code /api/kb-grounding/{factSheetId}}</p>
 */
@RestController
@RequestMapping("/api/kb-grounding/{factSheetId}")
public class KbGroundingAuditController {

    private final KbCorrectionService correctionService;

    @Nullable
    private final FactPromotionTracker promotionTracker;

    @Autowired
    public KbGroundingAuditController(
            KbCorrectionService correctionService,
            @Nullable @Autowired(required = false) FactPromotionTracker promotionTracker) {
        this.correctionService = correctionService;
        this.promotionTracker = promotionTracker;
    }

    /** Backward-compatible constructor (no promotion tracker) for tests and minimal contexts. */
    public KbGroundingAuditController(KbCorrectionService correctionService) {
        this(correctionService, null);
    }

    /**
     * GET /api/kb-grounding/{factSheetId}/audit
     *
     * @param atomKey   optional filter by atom key
     * @param eventType optional filter by event type (ASSERTED, DERIVED, CORRECTED, …)
     * @param limit     maximum events to return (default 200)
     */
    @GetMapping("/audit")
    public ResponseEntity<List<FactAuditEvent>> getAuditTrail(
            @PathVariable long factSheetId,
            @RequestParam(required = false) @Nullable String atomKey,
            @RequestParam(required = false) @Nullable String eventType,
            @RequestParam(defaultValue = "200") int limit) {
        List<FactAuditEvent> events = correctionService.getAuditTrail(factSheetId, atomKey, eventType);
        List<FactAuditEvent> paged = events.size() > limit
                ? events.subList(events.size() - limit, events.size())
                : events;
        return ResponseEntity.ok(paged);
    }

    /**
     * POST /api/kb-grounding/{factSheetId}/corrections
     * Body: { "atomKey": "…", "newValue": 0.0, "reason": "…", "tombstone": false }
     */
    @PostMapping("/corrections")
    public ResponseEntity<CorrectionResult> postCorrection(
            @PathVariable long factSheetId,
            @RequestBody CorrectionRequest request) {
        if (request.atomKey() == null || request.atomKey().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        double value = request.newValue() == null ? 0.0 : request.newValue();
        boolean tombstone = Boolean.TRUE.equals(request.tombstone());
        String actor = request.actor() != null ? request.actor() : "HUMAN:unknown";
        CorrectionResult result = correctionService.correct(
                factSheetId, request.atomKey(), value, actor,
                request.sessionId(), request.reason(), tombstone);
        return ResponseEntity.ok(result);
    }

    /**
     * DELETE /api/kb-grounding/{factSheetId}/corrections/{atomKey}
     * Reverts the PIN for the atom, allowing re-derivation.
     */
    @DeleteMapping("/corrections/{atomKey}")
    public ResponseEntity<Map<String, String>> revertPin(
            @PathVariable long factSheetId,
            @PathVariable String atomKey,
            @RequestParam(required = false, defaultValue = "HUMAN:unknown") String actor) {
        correctionService.revertPin(factSheetId, atomKey, actor);
        return ResponseEntity.ok(Map.of("status", "reverted", "atomKey", atomKey));
    }

    /**
     * GET /api/kb-grounding/{factSheetId}/corrections
     * Returns all active (pinned=true) pins for this fact sheet.
     */
    @GetMapping("/corrections")
    public ResponseEntity<List<PinRecord>> getActivePins(@PathVariable long factSheetId) {
        return ResponseEntity.ok(correctionService.getActivePins(factSheetId));
    }

    /**
     * GET /api/kb-grounding/{factSheetId}/facts?tier=ESTABLISHED
     *
     * <p>Returns up to 500 {@link FactTierRow} entries for the given fact sheet, optionally
     * filtered to a single {@link StrengthBand} tier. Results are drawn from
     * {@link FactPromotionTracker#factsByTierDurable(long, StrengthBand)} and enriched with
     * promotion status, corroboration count, and the latest strength band name.</p>
     *
     * @param factSheetId path variable
     * @param tier        optional {@link StrengthBand} name; if absent all tiers are returned
     */
    @GetMapping("/facts")
    public ResponseEntity<?> getFactsByTier(
            @PathVariable long factSheetId,
            @RequestParam(required = false) @Nullable String tier) {

        if (promotionTracker == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "FactPromotionTracker is not available in this deployment"));
        }

        StrengthBand[] bands;
        if (tier != null) {
            try {
                bands = new StrengthBand[]{ StrengthBand.valueOf(tier) };
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Unknown tier: " + tier
                                + ". Valid values: ESTABLISHED, HIGH, PROBABLE, SPECULATIVE, SUPPRESSED"));
            }
        } else {
            bands = StrengthBand.values();
        }

        List<FactTierRow> rows = new ArrayList<>();
        for (StrengthBand band : bands) {
            if (rows.size() >= 500) break;
            for (InferredFact fact : promotionTracker.factsByTierDurable(factSheetId, band)) {
                if (rows.size() >= 500) break;
                String atomKey = fact.atomKey();
                rows.add(new FactTierRow(
                        atomKey,
                        fact.confidence(),
                        promotionTracker.getLastBand(factSheetId, atomKey).name(),
                        promotionTracker.getPromotionStatus(factSheetId, atomKey),
                        promotionTracker.getCorroborationCount(factSheetId, atomKey)));
            }
        }
        return ResponseEntity.ok(rows);
    }

    /** A single row returned by {@code GET /facts}. */
    public record FactTierRow(
            String atomKey,
            double confidence,
            String band,
            String promotionStatus,
            int corroborationCount) {}

    /** Request payload for POST /corrections. */
    public record CorrectionRequest(
            String atomKey,
            Double newValue,
            String reason,
            Boolean tombstone,
            String actor,
            String sessionId) {}
}
