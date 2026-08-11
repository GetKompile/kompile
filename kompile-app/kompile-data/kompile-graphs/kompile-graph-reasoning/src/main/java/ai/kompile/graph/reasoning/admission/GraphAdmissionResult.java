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

/** Read-only graph-branch result with explicit missing/degraded state. */
public record GraphAdmissionResult(
        AdmissionDecision decision,
        double score,
        double margin,
        String reason,
        boolean evaluated,
        boolean degraded,
        String error,
        String reuseTargetId,
        AdmissionEvidenceTrace evidenceTrace) {

    /** Source-compatible constructor for callers that do not provide a matched target. */
    public GraphAdmissionResult(AdmissionDecision decision,
                                double score,
                                double margin,
                                String reason,
                                boolean evaluated,
                                boolean degraded,
                                String error) {
        this(decision, score, margin, reason, evaluated, degraded, error, null,
                AdmissionEvidenceTrace.empty());
    }

    /** Source-compatible constructor for callers that provide a matched target but no trace. */
    public GraphAdmissionResult(AdmissionDecision decision,
                                double score,
                                double margin,
                                String reason,
                                boolean evaluated,
                                boolean degraded,
                                String error,
                                String reuseTargetId) {
        this(decision, score, margin, reason, evaluated, degraded, error, reuseTargetId,
                AdmissionEvidenceTrace.empty());
    }

    public GraphAdmissionResult {
        Objects.requireNonNull(decision, "decision");
        requireUnitInterval(score, "score");
        requireUnitInterval(margin, "margin");
        reason = reason == null || reason.isBlank() ? decision.name() : reason.trim();
        error = error == null || error.isBlank() ? null : error.trim();
        reuseTargetId = reuseTargetId == null || reuseTargetId.isBlank() ? null : reuseTargetId.trim();
        evidenceTrace = evidenceTrace == null ? AdmissionEvidenceTrace.empty() : evidenceTrace;
        if (degraded && error == null) {
            throw new IllegalArgumentException("degraded graph results must include an error");
        }
    }

    public static GraphAdmissionResult notEvaluated() {
        return new GraphAdmissionResult(
                AdmissionDecision.DEFER, 0.0, 0.0,
                "graph evaluation disabled", false, false, null, null,
                AdmissionEvidenceTrace.empty());
    }

    public static GraphAdmissionResult defer(String reason) {
        return new GraphAdmissionResult(
                AdmissionDecision.DEFER, 0.0, 0.0, reason, true, false, null);
    }

    public static GraphAdmissionResult failed(String reason, String error) {
        return new GraphAdmissionResult(
                AdmissionDecision.DEFER, 0.0, 0.0, reason, true, true, error);
    }

    private static void requireUnitInterval(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and in [0,1]: " + value);
        }
    }
}
