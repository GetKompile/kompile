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
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds basis-aware {@link Opinion}s and the provenance metadata that travels with a graph
 * edge — Pillar 2 of the confidence-evidence model.
 *
 * <p>This is the single seam that converts {@code (basis, evidence, source-trust)} into an
 * {@code Opinion} plus the {@code _opinion} / {@code _basisType} / {@code _evidencePos} … keys.
 * The same {@link Opinion#fromBetaEvidence} call unifies all three basis classes — only the
 * prior strength {@code W} (from {@link BasisType#priorStrength()}) and the initial evidence
 * vary:</p>
 *
 * <ul>
 *   <li>A single STRUCTURAL observation (W=0.1, pos≈trust) → belief≈0.9, u≈0.1 → ESTABLISHED.</li>
 *   <li>A single LLM_EXTRACTION observation (W=2.0, pos=0.6) → belief≈0.23, u≈0.77 → SPECULATIVE.</li>
 *   <li>ASSERTED → belief=1, u=0 → certain by construction.</li>
 * </ul>
 *
 * <p>The service is stateless; it only computes Opinions and metadata maps. Persisting the
 * edge and merging the metadata into {@code metadataJson} is the caller's responsibility
 * (e.g. {@code EmailGraphExtractor} for {@code From:}-header → {@code person_has_email}).</p>
 */
@Service
public class StructuralFactAssertionService {

    /**
     * Build a basis-aware Opinion from evidence counts.
     *
     * @param basis    the fact's basis class (selects the Beta prior strength W)
     * @param pos      positive evidence weight (e.g. source trust for the first observation)
     * @param neg      negative evidence weight (trust-weighted contradicting sources)
     * @param baseRate prior mean in [0,1]
     * @return the computed Opinion; ASSERTED always returns a fully-certain Opinion
     */
    public Opinion opinionFor(BasisType basis, double pos, double neg, double baseRate) {
        if (basis == BasisType.ASSERTED) {
            // Certain by construction: belief=1, u=0. baseRate=1 (a human asserted it true).
            return new Opinion(1.0, 0.0, 0.0, 1.0);
        }
        return Opinion.fromBetaEvidence(pos, neg, baseRate, basis.priorStrength());
    }

    /**
     * Convenience for a STRUCTURAL assertion from a single observation of the given strength.
     * With {@code W=0.1}, {@code posStrength≈1.0} → belief≈0.91, u≈0.09 (high belief, zero
     * disbelief) — a deductive fact certain from one observation.
     *
     * @param posStrength positive evidence weight (typically the source trust, e.g. 0.95 for From:)
     * @param baseRate    prior mean in [0,1]
     */
    public Opinion structural(double posStrength, double baseRate) {
        return opinionFor(BasisType.STRUCTURAL, posStrength, 0.0, baseRate);
    }

    /**
     * Build the provenance metadata map for an edge: the full Opinion plus the evidence,
     * basis, prior-strength, source-trust, and validity-start keys. Callers merge this into
     * the edge's {@code metadataJson}. {@code _validFrom} is written as epoch-millis to match
     * {@link GraphProvenanceKeys#VALID_FROM} and the slice-1 extraction path.
     *
     * @param basis       the fact's basis class
     * @param opinion     the Opinion computed for the fact
     * @param pos         cumulative positive evidence
     * @param neg         cumulative negative evidence
     * @param sourceTrust trust of the source that produced this observation
     * @param nowEpochMillis assertion timestamp (epoch-millis); pass {@code System.currentTimeMillis()}
     */
    public Map<String, Object> metadataFor(BasisType basis, Opinion opinion,
                                           double pos, double neg,
                                           double sourceTrust, long nowEpochMillis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(GraphProvenanceKeys.OPINION, opinion.toJson());
        m.put(GraphProvenanceKeys.BASIS_TYPE, basis.name());
        m.put(GraphProvenanceKeys.EVIDENCE_POS, pos);
        m.put(GraphProvenanceKeys.EVIDENCE_NEG, neg);
        m.put(GraphProvenanceKeys.PRIOR_STRENGTH, basis.priorStrength());
        m.put(GraphProvenanceKeys.SOURCE_TRUST, sourceTrust);
        m.put(GraphProvenanceKeys.VALID_FROM, nowEpochMillis);
        return m;
    }
}
