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

/**
 * Basis class for a fact's evidence — Pillar 2 of the confidence-evidence model.
 *
 * <p>Certainty dynamics are <em>per-basis</em>, not governed by one global prior strength.
 * The basis determines the Beta prior strength {@code W} fed to
 * {@link ai.kompile.graph.reasoning.confidence.Opinion#fromBetaEvidence(double, double, double, double)}:
 *
 * <ul>
 *   <li><b>DEDUCTIVE / STRUCTURAL</b> — derivable from domain structure (a {@code From:} header,
 *       a reporting line, a GTIN-14 barcode). Reaches high belief from a <em>single</em>
 *       observation because {@code W} is small (0.1). No slow climb.</li>
 *   <li><b>CORROBORATIVE / INFERRED</b> — LLM-extracted relations, PSL inferences, MEBN
 *       posteriors, re-observations. Start LOW and accumulate via the Beta path ({@code W=2.0}).
 *       This is the only class where "how many observations before certain" applies.</li>
 *   <li><b>ASSERTED</b> — human corrections. Certain by construction ({@code W=0});
 *       not subject to Beta accumulation.</li>
 * </ul>
 *
 * <p>The enum names mirror the {@code _basisType} provenance string values documented on
 * {@link ai.kompile.knowledgegraph.domain.GraphProvenanceKeys#BASIS_TYPE}.</p>
 */
public enum BasisType {

    /** Derivable from domain structure; near-certain from one observation. */
    STRUCTURAL(0.1),

    /** LLM-extracted relation from unstructured text; accumulates via corroboration. */
    LLM_EXTRACTION(2.0),

    /** PSL soft-logic inference; accumulates. */
    PSL_INFERENCE(2.0),

    /** MEBN / Bayesian posterior; accumulates. */
    MEBN_INFERENCE(2.0),

    /** A corroborating re-observation of an existing fact; accumulates. */
    CORROBORATION(2.0),

    /** Human assertion / correction; certain until reverted, not Beta-accumulated. */
    ASSERTED(0.0);

    private final double priorStrength;

    BasisType(double priorStrength) {
        this.priorStrength = priorStrength;
    }

    /**
     * The Beta prior strength {@code W} for this basis, used in
     * {@code Opinion.fromBetaEvidence(pos, neg, baseRate, W)}. Smaller W means a single
     * observation moves belief further (structural); larger W demands corroboration.
     */
    public double priorStrength() {
        return priorStrength;
    }

    /** True for the structural/deductive class — high belief from a single observation. */
    public boolean isDeductive() {
        return this == STRUCTURAL;
    }

    /** True for classes that earn confidence through corroboration (the Beta-climb path). */
    public boolean isCorroborative() {
        return this == LLM_EXTRACTION || this == PSL_INFERENCE
                || this == MEBN_INFERENCE || this == CORROBORATION;
    }

    /**
     * Case-insensitive parse of a {@code _basisType} provenance string.
     * Unknown or blank input falls back to {@link #LLM_EXTRACTION} — the safe corroborative
     * default (treat an unlabelled fact as needing corroboration, never as deductive).
     */
    public static BasisType fromString(String s) {
        if (s == null || s.isBlank()) {
            return LLM_EXTRACTION;
        }
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LLM_EXTRACTION;
        }
    }
}
