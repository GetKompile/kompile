/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.grounding;

import ai.kompile.graph.reasoning.confidence.StrengthBand;

import java.util.List;
import java.util.Optional;

/**
 * Wraps any graph-derived artifact element T with KB-grounding evidence,
 * a calibrated confidence score, and a link to the reasoning trail.
 *
 * @param element             the wrapped domain object
 * @param verifyResult        KB verdict for the primary atom
 * @param calibratedConfidence [0,1] from StrengthCalibrator
 * @param band                ESTABLISHED/HIGH/PROBABLE/SPECULATIVE/SUPPRESSED
 * @param atomKey             the canonical atom key passed to KbVerifier
 * @param trailRef            "runId:<runId>" — resolved lazily; null when EAGER
 * @param derivationTree      eagerly built when mode=EAGER; null in LAZY mode
 * @param generatorId         "INDUCTIVE_MINER", "DECLARE_MINER", "GRAPH_RAG", etc.
 */
public record GroundedElement<T>(
    T element,
    VerifyResult verifyResult,
    double calibratedConfidence,
    StrengthBand band,
    String atomKey,
    String trailRef,
    DerivationTree derivationTree,
    String generatorId
) {
    public GroundedElement {
        if (verifyResult == null) throw new IllegalArgumentException("verifyResult must not be null");
        if (calibratedConfidence < 0 || calibratedConfidence > 1)
            throw new IllegalArgumentException("calibratedConfidence must be in [0,1]: " + calibratedConfidence);
        if (band == null) throw new IllegalArgumentException("band must not be null");
    }

    /** True when the KB actively supports this element. */
    public boolean isVerified() {
        return verifyResult.status() == VerifyResult.Status.SUPPORTED;
    }

    /** True when the KB actively contradicts this element. */
    public boolean isRefuted() {
        return verifyResult.status() == VerifyResult.Status.REFUTED;
    }

    /** Evidence list from the KB. */
    public List<String> evidence() {
        return verifyResult.evidence();
    }

    /** True when a DerivationTree was built eagerly. */
    public boolean hasDerivationTree() {
        return derivationTree != null;
    }

    /** Convenience: derivationTree wrapped in Optional. */
    public Optional<DerivationTree> derivationTreeOpt() {
        return Optional.ofNullable(derivationTree);
    }
}
