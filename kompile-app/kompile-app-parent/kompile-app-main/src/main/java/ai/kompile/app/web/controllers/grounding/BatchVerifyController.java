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

import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch-verify endpoint — POST /api/kb/verify/batch.
 *
 * <p>Replaces N×1 verify calls with a single request, returning a map of
 * {@code atomKey → VerifyResultSummary} for the UI strength-overlay panel.</p>
 *
 * <p>Lives in package {@code ai.kompile.app.web.controllers.grounding}, which is already
 * covered by {@code GlobalExceptionHandler.basePackages}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/kb/verify")
public class BatchVerifyController {

    private static final int MAX_BATCH_SIZE = 200;

    private final KbGroundingService groundingService;

    @Autowired
    public BatchVerifyController(KbGroundingService groundingService) {
        this.groundingService = groundingService;
    }

    /**
     * Batch-verify a list of atom keys against a single fact-sheet scope.
     *
     * <p>Returns a map {@code {atomKey: {verdict, confidence, evidenceCount, timestamp}}}
     * so the UI can overlay strength badges for all atoms in one round-trip.</p>
     */
    @PostMapping("/batch")
    public ResponseEntity<BatchVerifyResponse> batchVerify(@RequestBody BatchVerifyRequest req) {
        if (req.atomKeys() == null || req.atomKeys().isEmpty()) {
            throw new IllegalArgumentException("atomKeys must not be empty");
        }
        if (req.atomKeys().size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "batch size must not exceed " + MAX_BATCH_SIZE + "; got " + req.atomKeys().size());
        }

        long factSheetId = req.factSheetId() != null ? req.factSheetId() : 0L;
        double threshold = req.minConfidence() != null && req.minConfidence() > 0.0
                ? req.minConfidence() : 0.0;

        Map<String, VerifyResultSummary> results = new LinkedHashMap<>();
        for (String atomKey : req.atomKeys()) {
            if (atomKey == null || atomKey.isBlank()) {
                continue; // skip blank entries silently
            }
            VerifyResult vr = threshold > 0.0
                    ? groundingService.verify(factSheetId, atomKey, threshold)
                    : groundingService.verify(factSheetId, atomKey);

            results.put(atomKey, new VerifyResultSummary(
                    vr.status().name(),
                    vr.confidence(),
                    vr.evidence().size(),
                    Instant.now()
            ));
        }

        log.debug("POST /api/kb/verify/batch factSheet={} count={} results={}",
                factSheetId, req.atomKeys().size(), results.size());

        return ResponseEntity.ok(new BatchVerifyResponse(
                results,
                results.size(),
                factSheetId,
                Instant.now()
        ));
    }

    // ── Request / Response DTOs ──────────────────────────────────────────────────

    /**
     * Request body for POST /api/kb/verify/batch.
     *
     * @param factSheetId  fact-sheet scope; null = global (0L)
     * @param atomKeys     list of atom keys to verify
     * @param minConfidence optional confidence threshold; 0 = use default
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BatchVerifyRequest(
            Long factSheetId,
            List<String> atomKeys,
            Double minConfidence
    ) {}

    /**
     * Top-level response containing the per-atom map.
     *
     * @param results     atom key → summary
     * @param totalCount  number of entries returned (= non-blank input keys)
     * @param factSheetId the scope used
     * @param timestamp   server-side evaluation time
     */
    public record BatchVerifyResponse(
            Map<String, VerifyResultSummary> results,
            int totalCount,
            long factSheetId,
            Instant timestamp
    ) {}

    /**
     * Per-atom verify summary — compact form for the UI overlay.
     *
     * @param verdict       SUPPORTED | REFUTED | UNKNOWN
     * @param confidence    calibrated confidence in [0,1]
     * @param evidenceCount number of evidence items backing this verdict
     * @param evaluatedAt   time the verdict was produced
     */
    public record VerifyResultSummary(
            String verdict,
            double confidence,
            int evidenceCount,
            Instant evaluatedAt
    ) {}
}
