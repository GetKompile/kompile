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

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.knowledgegraph.persistence.dual.InferredFactRow;
import ai.kompile.knowledgegraph.persistence.dual.InferredFactRowRepository;
import ai.kompile.knowledgegraph.reasoning.FactPromotionTracker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for browsing the full Subjective Logic {@link Opinion} attached to each
 * inferred fact in a grounding knowledge base.
 *
 * <p>Complements {@link KbGroundingAuditController} which returns a lighter {@code FactTierRow}
 * (confidence + band + promotionStatus + corroborationCount only).  This controller adds the
 * full opinion tuple (belief / disbelief / uncertainty / expectation / baseRate) so callers can
 * filter and sort on epistemic dimensions that the tier alone does not expose.</p>
 *
 * <p>Base path: {@code /api/kb-grounding/{factSheetId}}</p>
 *
 * <h3>Opinion derivation strategy</h3>
 * <ol>
 *   <li>If the {@link InferredFactRow} has non-null {@code evidencePos} and {@code evidenceNeg}
 *       columns (Beta-distribution accumulation path), the opinion is computed via
 *       {@link Opinion#fromBetaEvidence(double, double)}.</li>
 *   <li>Otherwise, if the row's {@code provenanceJson} contains the {@code "_opinion"} key,
 *       the embedded JSON blob is parsed via {@link Opinion#fromJson(String)}.</li>
 *   <li>As a last resort the opinion is approximated from the scalar confidence via
 *       {@link Opinion#fromSoftTruth(double)}.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/kb-grounding/{factSheetId}")
public class KbOpinionBrowserController {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;

    /** JSON key under which a serialized {@link Opinion} may be embedded in provenanceJson. */
    private static final String OPINION_JSON_KEY = "\"_opinion\"";

    @Nullable
    private final FactPromotionTracker promotionTracker;

    @Nullable
    private final InferredFactRowRepository factRepo;

    @Autowired
    public KbOpinionBrowserController(
            @Nullable @Autowired(required = false) FactPromotionTracker promotionTracker,
            @Nullable @Autowired(required = false) InferredFactRowRepository factRepo) {
        this.promotionTracker = promotionTracker;
        this.factRepo = factRepo;
    }

    /**
     * GET /api/kb-grounding/{factSheetId}/opinions
     *
     * <p>Returns a list of {@link FactOpinionRow} entries for the given fact sheet, each
     * carrying the full Subjective Logic opinion (belief, disbelief, uncertainty, expectation,
     * baseRate) alongside the standard tier metadata.</p>
     *
     * @param factSheetId     path variable — the fact sheet to query
     * @param tier            optional {@link StrengthBand} name filter
     *                        (ESTABLISHED / HIGH / PROBABLE / SPECULATIVE / SUPPRESSED)
     * @param q               optional case-insensitive substring filter on atomKey, subject,
     *                        or predicate (supports corpus-level text search — D5)
     * @param maxUncertainty  optional upper bound on opinion.uncertainty() (inclusive, 0..1)
     * @param minExpectation  optional lower bound on opinion.expectation() (inclusive, 0..1)
     * @param basisType       optional {@link ai.kompile.knowledgegraph.confidence.BasisType} name
     *                        filter (STRUCTURAL / LLM_EXTRACTION / PSL_INFERENCE / MEBN_INFERENCE /
     *                        CORROBORATION / ASSERTED) — D4
     * @param limit           maximum number of rows to return (default 200, capped at 1000)
     */
    @GetMapping("/opinions")
    public ResponseEntity<?> getOpinions(
            @PathVariable long factSheetId,
            @RequestParam(required = false) @Nullable String tier,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable Double maxUncertainty,
            @RequestParam(required = false) @Nullable Double minExpectation,
            @RequestParam(required = false) @Nullable String basisType,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {

        if (promotionTracker == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "FactPromotionTracker is not available in this deployment"));
        }

        // Resolve the bands to iterate
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

        // Cap the limit
        int effectiveLimit = Math.min(Math.max(1, limit), MAX_LIMIT);

        // Build the text filter (lower-cased once) — D5: matches atomKey, subject, or predicate
        String qLower = (q != null && !q.isBlank()) ? q.toLowerCase() : null;

        // D4: basis-type filter (null = all)
        String basisTypeLower = (basisType != null && !basisType.isBlank())
                ? basisType.trim().toUpperCase() : null;

        List<FactOpinionRow> rows = new ArrayList<>();

        for (StrengthBand band : bands) {
            if (rows.size() >= effectiveLimit) break;

            List<InferredFact> facts = promotionTracker.factsByTierDurable(factSheetId, band);
            for (InferredFact fact : facts) {
                if (rows.size() >= effectiveLimit) break;

                String atomKey = fact.atomKey();

                // Text filter — match on full atomKey string (covers predicate and arguments)
                if (qLower != null && !atomKey.toLowerCase().contains(qLower)) {
                    continue;
                }

                // Derive the full Opinion and basisType for this fact
                ResolvedFactDetail detail = resolveDetail(factSheetId, atomKey, fact.confidence());
                Opinion opinion = detail.opinion();
                String resolvedBasisType = detail.basisType();

                // D4: basisType filter
                if (basisTypeLower != null && !basisTypeLower.equals(resolvedBasisType)) {
                    continue;
                }

                // Uncertainty filter
                if (maxUncertainty != null && opinion.uncertainty() > maxUncertainty) {
                    continue;
                }

                // Expectation filter
                if (minExpectation != null && opinion.expectation() < minExpectation) {
                    continue;
                }

                String bandName = promotionTracker.getLastBand(factSheetId, atomKey).name();
                String promotionStatus = promotionTracker.getPromotionStatus(factSheetId, atomKey);
                int corroborationCount = promotionTracker.getCorroborationCount(factSheetId, atomKey);

                rows.add(new FactOpinionRow(
                        atomKey,
                        fact.confidence(),
                        bandName,
                        promotionStatus,
                        corroborationCount,
                        opinion.belief(),
                        opinion.disbelief(),
                        opinion.uncertainty(),
                        opinion.expectation(),
                        opinion.baseRate(),
                        resolvedBasisType));
            }
        }

        return ResponseEntity.ok(rows);
    }

    /**
     * GET /api/kb-grounding/{factSheetId}/band-summary
     *
     * <p>Returns a {@code Map<String, Long>} of bandName → atom count for the given fact sheet.
     * Counts are derived from {@link FactPromotionTracker#bandCounts(long)} (in-memory, no DB call).</p>
     *
     * @param factSheetId the fact sheet to aggregate
     */
    @GetMapping("/band-summary")
    public ResponseEntity<?> getBandSummary(@PathVariable long factSheetId) {

        if (promotionTracker == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "FactPromotionTracker is not available in this deployment"));
        }

        Map<StrengthBand, Integer> counts = promotionTracker.bandCounts(factSheetId);

        // Convert to a stable-ordered Map<String, Long> (ESTABLISHED first = highest tier)
        Map<String, Long> result = new LinkedHashMap<>();
        for (StrengthBand band : StrengthBand.values()) {
            result.put(band.name(), counts.getOrDefault(band, 0).longValue());
        }

        return ResponseEntity.ok(result);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** JSON key under which a serialized basisType may be embedded in provenanceJson. */
    private static final String BASIS_TYPE_JSON_KEY = "\"_basisType\"";

    /** Default basisType string used when the provenance JSON has no _basisType entry. */
    private static final String DEFAULT_BASIS_TYPE = "LLM_EXTRACTION";

    /**
     * Resolved fact detail: the best-available {@link Opinion} and the {@code _basisType} string.
     */
    private record ResolvedFactDetail(Opinion opinion, String basisType) {}

    /**
     * Resolve the best-available {@link Opinion} and {@code _basisType} for the given triple.
     *
     * <ol>
     *   <li>Beta-distribution evidence columns ({@code evidencePos}/{@code evidenceNeg}) — most
     *       accurate when the fact has gone through the Trust-accumulation path.  The
     *       {@code _basisType} is read from {@code provenanceJson} in the same row.</li>
     *   <li>Embedded {@code "_opinion"} JSON blob in {@code provenanceJson} — present when the
     *       LLM extraction path serialized a full opinion at write time.</li>
     *   <li>Scalar soft-truth fallback via {@link Opinion#fromSoftTruth(double)};
     *       {@code basisType} defaults to {@value #DEFAULT_BASIS_TYPE}.</li>
     * </ol>
     */
    private ResolvedFactDetail resolveDetail(long factSheetId, String atomKey, double confidence) {
        if (factRepo != null) {
            try {
                Optional<InferredFactRow> rowOpt =
                        factRepo.findTopByFactSheetIdAndAtomKeyOrderByVersionDesc(factSheetId, atomKey);
                if (rowOpt.isPresent()) {
                    InferredFactRow row = rowOpt.get();

                    // Read basisType from provenanceJson (present in all post-D4 rows)
                    String provJson = row.getProvenanceJson();
                    String basisType = extractStringValue(provJson, BASIS_TYPE_JSON_KEY);
                    if (basisType == null) {
                        basisType = DEFAULT_BASIS_TYPE;
                    }

                    // Strategy 1: Beta-distribution evidence accumulators
                    if (row.getEvidencePos() != null && row.getEvidenceNeg() != null) {
                        return new ResolvedFactDetail(
                                Opinion.fromBetaEvidence(row.getEvidencePos(), row.getEvidenceNeg()),
                                basisType);
                    }

                    // Strategy 2: embedded _opinion blob in provenanceJson
                    if (provJson != null && provJson.contains(OPINION_JSON_KEY)) {
                        String opinionJson = extractOpinionJson(provJson);
                        if (opinionJson != null) {
                            try {
                                return new ResolvedFactDetail(Opinion.fromJson(opinionJson), basisType);
                            } catch (Exception ignored) {
                                // Malformed blob — fall through to scalar fallback
                            }
                        }
                    }

                    // Strategy 3: scalar fallback (but basisType still read from provenanceJson)
                    return new ResolvedFactDetail(Opinion.fromSoftTruth(confidence), basisType);
                }
            } catch (Exception ignored) {
                // DB unavailable or row missing — fall through to scalar fallback
            }
        }

        // Strategy 3: scalar soft-truth approximation (no DB row available)
        return new ResolvedFactDetail(Opinion.fromSoftTruth(confidence), DEFAULT_BASIS_TYPE);
    }

    /**
     * Extract the JSON object value for the {@code "_opinion"} key from a provenance JSON string.
     *
     * <p>The provenance JSON is produced by {@link ai.kompile.graph.reasoning.fol.InferredFact#toJson()}
     * and may embed the opinion as a nested object under the {@code "_opinion"} key, e.g.:
     * {@code {"atomKey":"…", "_opinion":{"belief":0.9,"disbelief":0.05,"uncertainty":0.05,"baseRate":0.5}}}.</p>
     *
     * @param provenanceJson the full provenance JSON string
     * @return the nested opinion JSON object string, or {@code null} if not present / not parseable
     */
    @Nullable
    private static String extractOpinionJson(String provenanceJson) {
        int keyIdx = provenanceJson.indexOf(OPINION_JSON_KEY);
        if (keyIdx < 0) return null;

        // Advance past the key and the separating colon
        int colonIdx = provenanceJson.indexOf(':', keyIdx + OPINION_JSON_KEY.length());
        if (colonIdx < 0) return null;

        // Skip whitespace after the colon
        int start = colonIdx + 1;
        while (start < provenanceJson.length() && Character.isWhitespace(provenanceJson.charAt(start))) {
            start++;
        }
        if (start >= provenanceJson.length() || provenanceJson.charAt(start) != '{') return null;

        // Find the matching closing brace (handles nested objects)
        int depth = 0;
        int end = start;
        while (end < provenanceJson.length()) {
            char c = provenanceJson.charAt(end);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end++;
                    break;
                }
            }
            end++;
        }
        if (depth != 0) return null; // unbalanced braces

        return provenanceJson.substring(start, end);
    }

    /**
     * Extract the string value for a given JSON key (e.g. {@code "\"_basisType\""}) from a flat
     * JSON string.  Returns the unquoted value for a string field, or {@code null} if not found.
     *
     * <p>Used to read simple string fields (like {@code _basisType}) without pulling in Jackson.</p>
     *
     * @param json  the JSON string to search; may be {@code null}
     * @param jsonKey the quoted key to search for, e.g. {@code "\"_basisType\""}
     * @return the unquoted string value, or {@code null} if the key is absent or not a string
     */
    @Nullable
    static String extractStringValue(@Nullable String json, String jsonKey) {
        if (json == null || json.isEmpty()) return null;
        int keyIdx = json.indexOf(jsonKey);
        if (keyIdx < 0) return null;

        // Advance past key and separating colon
        int colonIdx = json.indexOf(':', keyIdx + jsonKey.length());
        if (colonIdx < 0) return null;

        // Skip whitespace after the colon
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '"') return null;

        // Find closing quote (respecting simple escapes)
        int vs = start + 1;
        StringBuilder sb = new StringBuilder();
        while (vs < json.length()) {
            char c = json.charAt(vs);
            if (c == '\\' && vs + 1 < json.length()) {
                sb.append(json.charAt(vs + 1));
                vs += 2;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
            vs++;
        }
        return sb.toString();
    }

    // ── Response DTO ──────────────────────────────────────────────────────────

    /**
     * A single row returned by {@code GET /opinions}.
     *
     * <p>The {@code belief}, {@code disbelief}, {@code uncertainty}, {@code expectation}, and
     * {@code baseRate} fields come from the resolved {@link Opinion}.  They are all non-null
     * because the scalar-fallback strategy guarantees an opinion is always produced.</p>
     *
     * <p>{@code basisType} is read from the {@code _basisType} key in {@code provenanceJson}
     * (D4).  It defaults to {@code "LLM_EXTRACTION"} when the row has no explicit basis type
     * (pre-D4 rows, or rows from the soft-truth fallback path).</p>
     */
    public record FactOpinionRow(
            String atomKey,
            double confidence,
            String band,
            String promotionStatus,
            int corroborationCount,
            Double belief,
            Double disbelief,
            Double uncertainty,
            Double expectation,
            Double baseRate,
            String basisType) {}
}
