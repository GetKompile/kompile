/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.services.agent;

import java.util.List;

/**
 * SPI: extract the factual claims of a chat answer as KB atom keys, for the grounded-answer
 * verification loop (grounded-RAG lit-gap rec 1). An implementation typically runs a focused LLM
 * extraction over the answer and maps the results to atom keys ({@code predicate(subject, object)})
 * understood by {@code KbGroundingService.verify}.
 *
 * <p>Left as an SPI so the verification core ({@code GroundedAnswerVerifier}) stays unit-testable
 * without an LLM and so the extraction strategy can evolve independently. When no implementation bean
 * is present, the verification loop is a graceful no-op.</p>
 */
public interface AnswerClaimExtractor {

    /**
     * Extract up to {@code maxClaims} verifiable claim atom keys from an answer.
     *
     * @param answerText  the generated answer text
     * @param factSheetId the fact sheet the answer is grounded in
     * @param maxClaims   upper bound on claims to extract (cost/latency guard)
     * @return atom keys ({@code predicate(arg, ...)}); may be empty, never {@code null}
     */
    List<String> extractClaimAtoms(String answerText, long factSheetId, int maxClaims);
}
