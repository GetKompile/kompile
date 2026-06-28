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

import ai.kompile.knowledgegraph.reasoning.TraceHumanizer;
import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import ai.kompile.graph.reasoning.fol.grounding.GroundedElement;
import ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Verify-element endpoint — POST /api/grounding/verify-element.
 *
 * <p>Given a domain element + its canonical atom key, runs KB verification, calibrates the
 * confidence via {@link PlattCalibrator} (identity defaults until calibration data is available),
 * and returns a {@link GroundedElementResponse} carrying the verdict, calibrated strength, band,
 * derivation tree, and a trail reference.</p>
 *
 * <p>This is the insertion point described in domain-object-grounding-design.md §7 —
 * wired surgically so generators can wrap any artifact element without changing the generator
 * logic. The endpoint also feeds the {@code StrengthBadgeComponent} in the UI.</p>
 *
 * <p>Lives in package {@code ai.kompile.app.web.controllers.grounding}, which is already
 * covered by {@code GlobalExceptionHandler.basePackages}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/grounding")
public class VerifyElementController {

    private final KbGroundingService groundingService;
    private final PlattCalibrator calibrator;

    /**
     * Optional: humanizes atom keys + rule strings in the derivation tree so the
     * {@code StrengthBadge}/{@code ReasoningTrail} UI shows entity titles, not raw PSL.
     * Null-safe — falls back to the raw tree JSON in plain-lib test contexts.
     */
    @Nullable
    @Autowired(required = false)
    private TraceHumanizer traceHumanizer;

    @Autowired
    public VerifyElementController(KbGroundingService groundingService) {
        this.groundingService = groundingService;
        this.calibrator = new PlattCalibrator();
    }

    /**
     * Verify a single domain element against the KB.
     *
     * <p>Runs {@code KbGroundingService.verify} + {@code explain} and applies Platt-scaling
     * calibration with {@link StrengthCalibrator.SignalType#OBSERVED} defaults. Returns a
     * {@link GroundedElementResponse} compatible with the UI {@code StrengthBadgeComponent}.</p>
     */
    @PostMapping("/verify-element")
    public ResponseEntity<GroundedElementResponse> verifyElement(
            @RequestBody VerifyElementRequest req) {

        if (req.atomKey() == null || req.atomKey().isBlank()) {
            throw new IllegalArgumentException("atomKey must not be blank");
        }

        long factSheetId = req.factSheetId() != null ? req.factSheetId() : 0L;
        String generatorId = req.generatorId() != null ? req.generatorId() : "UNKNOWN";

        // 1. KB verify
        VerifyResult verifyResult = groundingService.verify(factSheetId, req.atomKey());

        // 2. Calibrate confidence via Platt scaling
        double calibratedConfidence = calibrator.calibrate(
                verifyResult.confidence(),
                StrengthCalibrator.SignalType.OBSERVED,
                verifyResult
        );

        // 3. Map to StrengthBand
        StrengthBand band = StrengthBand.fromScalar(calibratedConfidence);

        // 4. Eagerly build derivation tree
        int depth = req.depth() > 0 ? req.depth() : DerivationTree.DEFAULT_MAX_DEPTH;
        DerivationTree tree = groundingService.explain(factSheetId, req.atomKey(), depth);

        // 5. Build trail reference (lazy — not yet stored durably; Phase 2 will add persistence)
        String trailRef = "runId:phase1-" + Instant.now().toEpochMilli();

        // 6. Wrap in GroundedElement<String> (element = the element string representation)
        String elementKey = req.element() != null ? req.element() : req.atomKey();
        GroundedElement<String> grounded = new GroundedElement<>(
                elementKey,
                verifyResult,
                calibratedConfidence,
                band,
                req.atomKey(),
                trailRef,
                tree,
                generatorId
        );

        log.debug("POST /api/grounding/verify-element atom='{}' factSheet={} verdict={} band={}",
                req.atomKey(), factSheetId, verifyResult.status(), band);

        // Humanize the derivation tree (atom keys → entity titles, PSL rules → readable form)
        // when available, matching the /api/kb-grounding/explain endpoint. Falls back to raw JSON.
        String treeJson;
        if (tree == null) {
            treeJson = null;
        } else if (traceHumanizer != null) {
            List<String> treeRules = new ArrayList<>();
            collectRuleStrings(tree, treeRules);
            treeJson = tree.toJsonWithTitlesAndRules(
                    traceHumanizer.buildAtomKeyToTitle(tree.allAtomKeys()),
                    traceHumanizer.buildRuleMap(treeRules));
        } else {
            treeJson = tree.toJson();
        }

        return ResponseEntity.ok(GroundedElementResponse.from(grounded, treeJson, factSheetId));
    }

    /** Collect all non-null ruleApplied strings from a derivation tree (BFS). */
    private static void collectRuleStrings(DerivationTree tree, List<String> acc) {
        if (tree.ruleApplied() != null) acc.add(tree.ruleApplied());
        for (DerivationTree child : tree.children()) {
            collectRuleStrings(child, acc);
        }
    }

    // ── Request / Response DTOs ──────────────────────────────────────────────────

    /**
     * Request body for POST /api/grounding/verify-element.
     *
     * @param element      a string representation of the domain object (e.g. step name); optional
     * @param atomKey      the canonical FOL atom key for KB lookup (required)
     * @param factSheetId  fact-sheet scope; null = global (0L)
     * @param depth        derivation depth cap; 0 = lib default (5)
     * @param generatorId  the generator that produced this element (INDUCTIVE_MINER, etc.)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerifyElementRequest(
            String element,
            String atomKey,
            Long factSheetId,
            int depth,
            String generatorId
    ) {}

    /**
     * Response body for POST /api/grounding/verify-element.
     *
     * <p>Mirrors {@link GroundedElement} as a JSON-serializable record without the generic
     * type parameter, so Jackson can serialize it directly.</p>
     *
     * @param element              string representation of the domain element
     * @param atomKey              the canonical atom key
     * @param verdict              SUPPORTED | REFUTED | UNKNOWN
     * @param confidence           raw KB confidence
     * @param calibratedConfidence Platt-scaled confidence in [0,1]
     * @param strengthBand         ESTABLISHED | HIGH | PROBABLE | SPECULATIVE | SUPPRESSED
     * @param trailRef             "runId:<id>" — lazy trail reference
     * @param derivationTreeJson   JSON derivation tree (eager)
     * @param evidenceCount        number of supporting evidence items
     * @param generatorId          the generator that produced the element
     * @param factSheetId          the scope that was used
     * @param evaluatedAt          server timestamp
     */
    public record GroundedElementResponse(
            String element,
            String atomKey,
            String verdict,
            double confidence,
            double calibratedConfidence,
            String strengthBand,
            String trailRef,
            String derivationTreeJson,
            int evidenceCount,
            String generatorId,
            long factSheetId,
            Instant evaluatedAt
    ) {
        static GroundedElementResponse from(GroundedElement<String> g,
                                            String derivationTreeJson,
                                            long factSheetId) {
            return new GroundedElementResponse(
                    g.element(),
                    g.atomKey(),
                    g.verifyResult().status().name(),
                    g.verifyResult().confidence(),
                    g.calibratedConfidence(),
                    g.band().name(),
                    g.trailRef(),
                    derivationTreeJson,
                    g.evidence().size(),
                    g.generatorId(),
                    factSheetId,
                    Instant.now()
            );
        }
    }
}
