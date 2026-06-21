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

import ai.kompile.knowledgegraph.audit.FactAuditEvent;
import ai.kompile.knowledgegraph.audit.PinRecord;
import ai.kompile.knowledgegraph.grounding.KbCorrectionService;
import ai.kompile.knowledgegraph.grounding.KbCorrectionService.CorrectionResult;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the grounding KB audit trail, correction/PIN control surface,
 * and strength-layer-filtered fact queries.
 *
 * <p>Base path: {@code /api/kb-grounding/{factSheetId}}</p>
 *
 * <p>Note: strength-layer filtered fact query (GET /facts?layer=PROBABLE) is deferred —
 * that requires enriching {@link ai.kompile.knowledgegraph.grounding.KbGroundingService}
 * with an allLatest() accessor, which is a separate change.
 * POST corrections and GET audit trail are implemented here.</p>
 */
@RestController
@RequestMapping("/api/kb-grounding/{factSheetId}")
public class KbGroundingAuditController {

    private final KbCorrectionService correctionService;

    public KbGroundingAuditController(KbCorrectionService correctionService) {
        this.correctionService = correctionService;
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
        List<FactAuditEvent> paged = events.size() > limit ? events.subList(events.size() - limit, events.size()) : events;
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

    /** Request payload for POST /corrections. */
    public record CorrectionRequest(
            String atomKey,
            Double newValue,
            String reason,
            Boolean tombstone,
            String actor,
            String sessionId) {}
}
