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
import ai.kompile.knowledgegraph.persistence.dual.InferredFactRow;
import ai.kompile.knowledgegraph.persistence.dual.InferredFactRowRepository;
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

import java.time.Instant;
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

    @Nullable
    private final InferredFactRowRepository factRepo;

    @Autowired
    public KbGroundingAuditController(
            KbCorrectionService correctionService,
            @Nullable @Autowired(required = false) FactPromotionTracker promotionTracker,
            @Nullable @Autowired(required = false) InferredFactRowRepository factRepo) {
        this.correctionService = correctionService;
        this.promotionTracker = promotionTracker;
        this.factRepo = factRepo;
    }

    /** Backward-compatible constructor (no promotion tracker, no repo) for tests and minimal contexts. */
    public KbGroundingAuditController(KbCorrectionService correctionService) {
        this(correctionService, null, null);
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
     * GET /api/kb-grounding/{factSheetId}/facts?tier=ESTABLISHED&validFrom=&validTo=
     *
     * <p>Returns up to 500 {@link FactTierRow} entries for the given fact sheet, optionally
     * filtered to a single {@link StrengthBand} tier and/or a validity-time window.
     * Results are drawn from
     * {@link FactPromotionTracker#factsByTierDurable(long, StrengthBand)} and enriched with
     * promotion status, corroboration count, the latest strength band name, and
     * temporal validity bounds sourced from the DB row ({@code inferredAt} → {@code validFrom})
     * and any {@code _validTo} key embedded in the provenance JSON.</p>
     *
     * <p>Temporal filtering: a row is included when its validity window overlaps the requested
     * range. Rows whose {@code validFrom}/{@code validTo} are both null are always included unless
     * the caller explicitly sets {@code excludeUndated=true}.</p>
     *
     * @param factSheetId   path variable
     * @param tier          optional {@link StrengthBand} name; if absent all tiers are returned
     * @param validFrom     optional filter — epoch millis; row's inferredAt must be &gt;= this
     * @param validTo       optional filter — epoch millis; row's validTo (or unbounded) must overlap
     * @param excludeUndated when {@code true}, rows without any temporal metadata are excluded
     */
    @GetMapping("/facts")
    public ResponseEntity<?> getFactsByTier(
            @PathVariable long factSheetId,
            @RequestParam(required = false) @Nullable String tier,
            @RequestParam(required = false) @Nullable Long validFrom,
            @RequestParam(required = false) @Nullable Long validTo,
            @RequestParam(defaultValue = "false") boolean excludeUndated) {

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

        // Build a lookup map of atomKey → InferredFactRow for temporal metadata (when repo is available)
        Map<String, InferredFactRow> rowByAtom = buildRowLookup(factSheetId);

        List<FactTierRow> rows = new ArrayList<>();
        for (StrengthBand band : bands) {
            if (rows.size() >= 500) break;
            for (InferredFact fact : promotionTracker.factsByTierDurable(factSheetId, band)) {
                if (rows.size() >= 500) break;
                String atomKey = fact.atomKey();

                // Extract temporal bounds from the persisted row
                InferredFactRow dbRow = rowByAtom.get(atomKey);
                Long rowValidFrom = extractValidFrom(fact, dbRow);
                Long rowValidTo   = extractValidTo(dbRow);

                // Apply temporal filter
                if (!matchesTemporalFilter(rowValidFrom, rowValidTo, validFrom, validTo, excludeUndated)) {
                    continue;
                }

                rows.add(new FactTierRow(
                        atomKey,
                        fact.confidence(),
                        promotionTracker.getLastBand(factSheetId, atomKey).name(),
                        promotionTracker.getPromotionStatus(factSheetId, atomKey),
                        promotionTracker.getCorroborationCount(factSheetId, atomKey),
                        rowValidFrom,
                        rowValidTo));
            }
        }
        return ResponseEntity.ok(rows);
    }

    // ── Temporal helpers ─────────────────────────────────────────────────────────

    /**
     * Build a map of atomKey → latest {@link InferredFactRow} for the given fact sheet.
     * Returns an empty map when no JPA repository is available.
     */
    private Map<String, InferredFactRow> buildRowLookup(long factSheetId) {
        if (factRepo == null) {
            return Map.of();
        }
        try {
            Map<String, InferredFactRow> lookup = new java.util.HashMap<>();
            for (InferredFactRow r : factRepo.findLatestByFactSheetId(factSheetId)) {
                lookup.put(r.getAtomKey(), r);
            }
            return lookup;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Extract {@code validFrom} as epoch millis.
     * Primary source: {@link InferredFactRow#getInferredAt()} (the wall-clock time the fact was
     * inferred — the earliest moment we know the fact was valid).
     * Falls back to {@link InferredFact#inferredAt()} when the DB row is absent.
     */
    private static Long extractValidFrom(InferredFact fact, @Nullable InferredFactRow dbRow) {
        if (dbRow != null && dbRow.getInferredAt() != null) {
            return dbRow.getInferredAt().toEpochMilli();
        }
        // Fallback: use the InferredFact record's own inferredAt (always non-null)
        Instant ts = fact.inferredAt();
        return ts != null ? ts.toEpochMilli() : null;
    }

    /**
     * Extract {@code validTo} as epoch millis from the {@code _validTo} key embedded in
     * {@link InferredFactRow#getProvenanceJson()}. Returns {@code null} when the key is absent
     * (fact is still valid / unbounded).
     */
    private static Long extractValidTo(@Nullable InferredFactRow dbRow) {
        if (dbRow == null || dbRow.getProvenanceJson() == null) {
            return null;
        }
        // Fast substring search — avoid pulling in Jackson just for one numeric field.
        // Format written by InferredFact.toJson() won't contain _validTo (it's a graph-node key),
        // but KbCorrectionService / ContradictionDetector may embed it in a wrapper JSON written
        // to the provenance column. Parse defensively.
        String json = dbRow.getProvenanceJson();
        int idx = json.indexOf("\"_validTo\"");
        if (idx < 0) {
            idx = json.indexOf("\"validTo\"");
        }
        if (idx < 0) {
            return null;
        }
        try {
            int colon = json.indexOf(':', idx);
            if (colon < 0) return null;
            int start = colon + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
            String numStr = json.substring(start, end).trim();
            if (numStr.isEmpty()) return null;
            return Long.parseLong(numStr);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Returns {@code true} when the row's validity window overlaps the requested filter range.
     *
     * <ul>
     *   <li>If neither {@code filterFrom} nor {@code filterTo} is set, all rows pass.</li>
     *   <li>Rows with null temporal metadata pass unless {@code excludeUndated=true}.</li>
     *   <li>Overlap check: row's [validFrom, validTo] ∩ [filterFrom, filterTo] ≠ ∅
     *       (treating null validTo as +∞ and null validFrom as −∞).</li>
     * </ul>
     */
    private static boolean matchesTemporalFilter(
            @Nullable Long rowValidFrom,
            @Nullable Long rowValidTo,
            @Nullable Long filterFrom,
            @Nullable Long filterTo,
            boolean excludeUndated) {

        // No filter active — accept everything
        if (filterFrom == null && filterTo == null) {
            return true;
        }

        // Row has no temporal data
        if (rowValidFrom == null && rowValidTo == null) {
            return !excludeUndated;
        }

        // Overlap check: row ends before filter starts, or row starts after filter ends
        if (filterTo != null && rowValidFrom != null && rowValidFrom > filterTo) {
            return false;
        }
        if (filterFrom != null && rowValidTo != null && rowValidTo < filterFrom) {
            return false;
        }
        return true;
    }

    /**
     * A single row returned by {@code GET /facts}.
     *
     * @param atomKey            canonical atom key
     * @param confidence         soft-truth confidence score in [0,1]
     * @param band               persisted {@link StrengthBand} name
     * @param promotionStatus    "NONE" or "PROMOTED"
     * @param corroborationCount number of independent corroborations
     * @param validFrom          epoch millis when this fact became valid (= inferredAt). Null = unknown.
     * @param validTo            epoch millis when this fact ceased to be valid. Null = still valid / unbounded.
     */
    public record FactTierRow(
            String atomKey,
            double confidence,
            String band,
            String promotionStatus,
            int corroborationCount,
            Long validFrom,
            Long validTo) {}

    /** Request payload for POST /corrections. */
    public record CorrectionRequest(
            String atomKey,
            Double newValue,
            String reason,
            Boolean tombstone,
            String actor,
            String sessionId) {}
}
