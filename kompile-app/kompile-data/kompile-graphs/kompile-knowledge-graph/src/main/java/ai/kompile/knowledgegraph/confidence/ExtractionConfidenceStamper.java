/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.confidence;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Single seam for stamping Opinion + provenance metadata onto every graph edge at extraction time.
 *
 * <p>This component eliminates the five inconsistent confidence paths that previously spread across
 * {@code GraphExtractionOrchestrator}, {@code GraphPersistenceHelper}, and the email/LLM extractors.
 * All graph-extraction persistence now routes through {@link #stampEdgeConfidence} so that:</p>
 * <ul>
 *   <li>No edge is persisted with a bare 1.0 default (which pins the PSL MAP gradient to zero).</li>
 *   <li>Every edge carries {@code _opinion}/{@code _basisType}/{@code _sourceTrust}/{@code _evidencePos}/
 *       {@code _validFrom} under the reserved {@link GraphProvenanceKeys} keys.</li>
 *   <li>All tunable trust and prior-strength values come from {@link KbConfig} via
 *       {@link KbConfigManager#current()} — no hard-coded literals, no {@code @Value} bindings.</li>
 * </ul>
 *
 * <p>Collaborators ({@link SourceTrustResolver}, {@link KbConfigManager}) are injected with
 * {@code required=false}: when either is absent (plain-Java tests, subprocess slices) the stamper
 * falls back to the raw {@code rawConfidence} so existing callers never break.</p>
 *
 * <h3>Supported sourceType strings (forwarded to {@link SourceTrustResolver#trustFor})</h3>
 * <ul>
 *   <li>{@code "LLM_EXTRACTION"} — inline LLM single-/multi-chunk extraction</li>
 *   <li>{@code "STRUCTURAL"} — Tika/GraphConstructor structural edges (doc→entity CONTAINS, inferred relations)</li>
 *   <li>{@code "email-from"}, {@code "email-to-cc"}, etc. — email-specific source types</li>
 * </ul>
 */
@Component
public class ExtractionConfidenceStamper {

    private static final Logger log = LoggerFactory.getLogger(ExtractionConfidenceStamper.class);

    @Autowired(required = false)
    private SourceTrustResolver sourceTrustResolver;

    @Autowired(required = false)
    private KbConfigManager kbConfigManager;

    /** Spring constructor (collaborators injected by field). */
    public ExtractionConfidenceStamper() {
    }

    /** Explicit constructor for tests / wiring without Spring. */
    public ExtractionConfidenceStamper(SourceTrustResolver sourceTrustResolver,
                                       KbConfigManager kbConfigManager) {
        this.sourceTrustResolver = sourceTrustResolver;
        this.kbConfigManager = kbConfigManager;
    }

    /**
     * Stamp confidence-evidence metadata onto a mutable edge-metadata map.
     *
     * <p>The method:
     * <ol>
     *   <li>Resolves source trust via {@link SourceTrustResolver#trustFor(String)}.</li>
     *   <li>Selects the Beta prior strength {@code W} for the basis class from {@link KbConfig}:
     *       {@link BasisType#LLM_EXTRACTION} uses {@code kbEvidencePriorStrength} (default 2.0);
     *       {@link BasisType#STRUCTURAL} uses {@code kbStructuralPriorStrength} (default 0.1).</li>
     *   <li>If {@code rawConfidence} is non-null and finite, it is used as the positive evidence
     *       weight directly (honouring an LLM-calibrated score without overwriting it).</li>
     *   <li>If {@code rawConfidence} is null, the positive evidence is set to {@code sourceTrust}
     *       (one unit of trust-weighted evidence from a single observation).</li>
     *   <li>Writes {@code _opinion}, {@code _basisType}, {@code _sourceTrust}, {@code _evidencePos},
     *       {@code _evidenceNeg}, {@code _priorStrength}, {@code _corroborationCount}, and
     *       {@code _validFrom} into {@code metadata} (all under the keys defined on
     *       {@link GraphProvenanceKeys}).</li>
     * </ol>
     *
     * <p>When either collaborator is absent the method writes nothing and returns without
     * throwing — callers retain their own fallback confidence value.</p>
     *
     * @param metadata      the edge metadata map to stamp (mutated in place)
     * @param sourceType    source/basis type string forwarded to {@link SourceTrustResolver}
     *                      (e.g. {@code "LLM_EXTRACTION"}, {@code "STRUCTURAL"})
     * @param rawConfidence the LLM-returned confidence score, or {@code null} if absent
     * @return the effective confidence to use for the edge weight scalar
     *         (either the Opinion expectation or the raw fallback)
     */
    public double stampEdgeConfidence(Map<String, Object> metadata,
                                      String sourceType,
                                      Double rawConfidence) {
        if (sourceTrustResolver == null || kbConfigManager == null) {
            // Collaborators absent — fall back gracefully; return raw confidence or 0.5
            log.debug("ExtractionConfidenceStamper: collaborators absent, skipping Opinion stamp (sourceType={})",
                    sourceType);
            return rawConfidence != null && Double.isFinite(rawConfidence) ? rawConfidence : 0.5;
        }

        try {
            KbConfig cfg = kbConfigManager.current();
            BasisType basisType = resolveBasisType(sourceType);
            double sourceTrust = sourceTrustResolver.trustFor(sourceType);

            // Positive evidence: honour the LLM's calibrated score when provided;
            // otherwise seed with one trust-unit (the first observation).
            double pos = (rawConfidence != null && Double.isFinite(rawConfidence))
                    ? rawConfidence : sourceTrust;
            double neg = 0.0;
            double baseRate = 0.5;
            double W = priorStrengthFor(basisType, cfg);

            Opinion opinion = Opinion.fromBetaEvidence(pos, neg, baseRate, W);
            double effectiveConfidence = opinion.expectation();

            metadata.put(GraphProvenanceKeys.OPINION, opinion.toJson());
            metadata.put(GraphProvenanceKeys.BASIS_TYPE, basisType.name());
            metadata.put(GraphProvenanceKeys.SOURCE_TRUST, sourceTrust);
            metadata.put(GraphProvenanceKeys.EVIDENCE_POS, pos);
            metadata.put(GraphProvenanceKeys.EVIDENCE_NEG, neg);
            metadata.put(GraphProvenanceKeys.PRIOR_STRENGTH, W);
            metadata.put(GraphProvenanceKeys.CORROBORATION_COUNT, 1);
            metadata.put(GraphProvenanceKeys.VALID_FROM, System.currentTimeMillis());

            return effectiveConfidence;
        } catch (Exception e) {
            log.warn("ExtractionConfidenceStamper: failed to stamp Opinion for sourceType={}: {}",
                    sourceType, e.getMessage());
            return rawConfidence != null && Double.isFinite(rawConfidence) ? rawConfidence : 0.5;
        }
    }

    // ── Package-private helpers for testability ──────────────────────────────────

    /**
     * Map a source-type string to a {@link BasisType}. Returns {@link BasisType#STRUCTURAL} for
     * structural/Tika paths and {@link BasisType#LLM_EXTRACTION} for everything else (the safe
     * corroborative default — treats unknown sources as needing corroboration).
     */
    BasisType resolveBasisType(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return BasisType.LLM_EXTRACTION;
        }
        switch (sourceType.trim().toLowerCase()) {
            case "structural":
            case "graph_constructor":
            case "graph-constructor":
            case "tika":
            case "tika_structural":
            case "tika-structural":
                return BasisType.STRUCTURAL;
            default:
                return BasisType.LLM_EXTRACTION;
        }
    }

    /** Select the Beta prior strength W from the managed KbConfig for a given basis class. */
    private double priorStrengthFor(BasisType basisType, KbConfig cfg) {
        return switch (basisType) {
            case STRUCTURAL -> cfg.getStructuralPriorStrength();
            case ASSERTED -> cfg.getAssertedPriorStrength();
            default -> cfg.getEvidencePriorStrength();
        };
    }
}
