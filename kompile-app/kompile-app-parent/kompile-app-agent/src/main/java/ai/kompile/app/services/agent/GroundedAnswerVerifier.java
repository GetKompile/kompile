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
package ai.kompile.app.services.agent;

import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Grounded-answer verification loop (grounded-RAG lit-gap rec 1) — Chain-of-Verification with a REAL
 * oracle. After the LLM produces an answer, its factual claims are extracted (via {@link
 * AnswerClaimExtractor}), each is verified INDEPENDENTLY of the draft against the KB
 * ({@link KbGroundingService#verify}) — satisfying CoVe's independence constraint by construction (a
 * KB lookup never sees the draft) — and per-claim SUPPORTED / REFUTED / UNKNOWN verdicts plus a caveat
 * are produced for the answer path to surface.
 *
 * <p>Opt-in via {@code kbAnswerVerificationEnabled} (default false) and inert unless an
 * {@link AnswerClaimExtractor} bean is wired. All failures degrade to "not run" rather than throwing —
 * verification must never break the answer.</p>
 */
@Component
public class GroundedAnswerVerifier {

    private static final Logger log = LoggerFactory.getLogger(GroundedAnswerVerifier.class);

    @Autowired(required = false)
    KbGroundingService groundingService;
    @Autowired(required = false)
    AnswerClaimExtractor claimExtractor;
    @Autowired(required = false)
    KbConfigManager kbConfigManager;

    /** One claim's verdict. */
    public record ClaimVerdict(String atomKey, String status, double confidence, List<String> evidence) {}

    /** The aggregate verification of an answer. */
    public record AnswerVerification(boolean ran, List<ClaimVerdict> verdicts,
                                     int supported, int refuted, int unknown, String caveat) {
        public static AnswerVerification notRun() {
            return new AnswerVerification(false, List.of(), 0, 0, 0, "");
        }
    }

    /** True when verification is enabled AND both required collaborators are present. */
    public boolean isActive() {
        KbConfig c = kbConfigManager != null ? kbConfigManager.current() : KbConfig.defaults();
        return c.isAnswerVerificationEnabled() && groundingService != null && claimExtractor != null;
    }

    /**
     * Verify a chat answer's factual claims against the KB. Returns {@link AnswerVerification#notRun()}
     * when inactive or when no claims are extractable; never throws.
     *
     * @param answerText  the generated answer
     * @param factSheetId the fact sheet the answer is grounded in
     * @param maxClaims   cap on claims to verify (cost guard)
     */
    public AnswerVerification verifyAnswer(String answerText, long factSheetId, int maxClaims) {
        if (!isActive() || answerText == null || answerText.isBlank()) {
            return AnswerVerification.notRun();
        }
        try {
            List<String> atoms = claimExtractor.extractClaimAtoms(answerText, factSheetId, maxClaims);
            if (atoms == null || atoms.isEmpty()) {
                return AnswerVerification.notRun();
            }
            return aggregate(atoms, atom -> groundingService.verify(factSheetId, atom));
        } catch (Exception e) {
            log.debug("GroundedAnswerVerifier: verification aborted — {}", e.getMessage());
            return AnswerVerification.notRun();
        }
    }

    /**
     * Pure per-claim aggregation via a verify function — package-private + static so the loop and its
     * SUPPORTED/REFUTED/UNKNOWN tallying are unit-testable with a lambda (no Spring, no mocks). A verify
     * call that throws or returns null skips that atom.
     */
    static AnswerVerification aggregate(List<String> atoms, Function<String, VerifyResult> verifyFn) {
        List<ClaimVerdict> verdicts = new ArrayList<>(atoms.size());
        int supported = 0, refuted = 0, unknown = 0;
        for (String atom : atoms) {
            VerifyResult r;
            try {
                r = verifyFn.apply(atom);
            } catch (Exception e) {
                continue;
            }
            if (r == null) {
                continue;
            }
            verdicts.add(new ClaimVerdict(atom, r.status().name(), r.confidence(), r.evidence()));
            switch (r.status()) {
                case SUPPORTED -> supported++;
                case REFUTED -> refuted++;
                case UNKNOWN -> unknown++;
            }
        }
        if (verdicts.isEmpty()) {
            return AnswerVerification.notRun();
        }
        return new AnswerVerification(true, verdicts, supported, refuted, unknown,
                buildCaveat(supported, refuted, unknown));
    }

    /**
     * The human caveat. Pure + static so the policy is unit-testable. Empty when every checked claim is
     * supported; otherwise warns about contradicted / unverifiable claims (rec 1's policy: caveat
     * REFUTED, mark UNKNOWN as ungrounded).
     */
    static String buildCaveat(int supported, int refuted, int unknown) {
        if (refuted == 0 && unknown == 0) {
            return supported > 0
                    ? "Grounding check: all " + supported + " checked claim(s) are supported by the knowledge base."
                    : "";
        }
        StringBuilder sb = new StringBuilder("⚠ Grounding check: ");
        if (refuted > 0) {
            sb.append(refuted).append(" claim(s) CONTRADICTED by the knowledge base; ");
        }
        if (unknown > 0) {
            sb.append(unknown).append(" claim(s) could not be verified (treat as ungrounded); ");
        }
        sb.append(supported).append(" supported.");
        return sb.toString();
    }
}
