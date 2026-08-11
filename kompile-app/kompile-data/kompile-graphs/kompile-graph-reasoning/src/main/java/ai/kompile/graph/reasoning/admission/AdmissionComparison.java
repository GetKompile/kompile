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
package ai.kompile.graph.reasoning.admission;

import java.util.Objects;

/**
 * Correlated result for one candidate evaluated by both admission branches.
 *
 * <p>In LLM_ONLY and SHADOW_COMPARE the staged model identity action remains authoritative. In
 * GRAPH_POLICY the crawl adapter replaces it with the operational result: ALLOW preserves the staged
 * CREATE/REUSE action, DENY becomes REJECT, and REVIEW becomes DEFER.</p>
 */
public record AdmissionComparison(
        String decisionGroupId,
        String candidateId,
        String canonicalKey,
        String snapshotId,
        String policyVersion,
        String ballotFingerprint,
        AdmissionMode mode,
        AdmissionDecision llmDecision,
        GraphAdmissionResult graphResult,
        AdmissionDecision authoritativeDecision,
        long graphDurationNanos) {

    public AdmissionComparison {
        requireText(decisionGroupId, "decisionGroupId");
        requireText(candidateId, "candidateId");
        requireText(canonicalKey, "canonicalKey");
        requireText(snapshotId, "snapshotId");
        requireText(policyVersion, "policyVersion");
        requireText(ballotFingerprint, "ballotFingerprint");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(llmDecision, "llmDecision");
        Objects.requireNonNull(graphResult, "graphResult");
        Objects.requireNonNull(authoritativeDecision, "authoritativeDecision");
        if (mode != AdmissionMode.GRAPH_POLICY && authoritativeDecision != llmDecision) {
            throw new IllegalArgumentException(
                    "LLM must remain authoritative outside GRAPH_POLICY mode");
        }
        if (graphDurationNanos < 0L) {
            throw new IllegalArgumentException("graphDurationNanos must not be negative");
        }
    }

    /** Whether a usable graph result agrees with the authoritative LLM action. */
    public boolean agrees() {
        return graphResult.evaluated()
                && !graphResult.degraded()
                && graphResult.decision() == llmDecision;
    }

    /** Whether this comparison contains a usable graph result. */
    public boolean graphAvailable() {
        return graphResult.evaluated() && !graphResult.degraded();
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
