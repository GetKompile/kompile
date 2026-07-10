/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining;

import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.mining.convert.ProcessNarrator;

import java.util.List;

/**
 * The fixed-order feature vector a mined suggestion presents to the learned re-ranker — the same
 * multi-signal shape the reasoning lib's {@code synthesis/} scorer was built for, extracted from
 * the suggestion itself so features at TRAINING time (recorded with each accept/dismiss outcome)
 * and at SCORING time (fresh mining runs) come from one definition.
 *
 * <p>Every feature is engineered into [0,1] so logistic regression needs no scaling, and the
 * fitted weights read directly as "what this signal is worth". {@link #NAMES} is persisted with
 * the model; a name mismatch on load means the feature space evolved and the stale model is
 * ignored rather than mis-applied.</p>
 */
public final class SuggestionFeatures {

    /** Feature names, index-aligned with {@link #extract}. */
    public static final List<String> NAMES = List.of(
            "rawConformance",        // fitness × precision, the miner's own quality signal
            "fusedConfidence",       // the multi-modality fused confidence
            "entailedMeanPosterior", // mean posterior of ENTAILED evidence (0 when none)
            "entailedCount01",       // entailed-orderings count, saturating at 10
            "contradictionShare",    // CONTRADICTION entries / all structured evidence
            "causalMeanDependency",  // mean CAUSAL evidence score
            "bayesianMeanPosterior", // mean noisy-OR posterior across activities
            "stepCount01",           // distinct steps, saturating at 12
            "caseCount01",           // supporting cases, saturating at 10
            "roleCoverage");         // steps with a role binding / all steps

    private SuggestionFeatures() {
    }

    public static double[] extract(ProcessSuggestion s) {
        double[] x = new double[NAMES.size()];
        if (s == null) {
            return x;
        }
        x[0] = clamp01(s.getRawConformanceScore() != null ? s.getRawConformanceScore() : 0.0);
        x[1] = clamp01(s.getConfidence());

        int entailedCount = 0;
        double entailedSum = 0.0;
        int contradictionCount = 0;
        int causalCount = 0;
        double causalSum = 0.0;
        int evidenceCount = 0;
        if (s.getStructuredEvidence() != null) {
            for (ProcessSuggestion.StructuredEvidence ev : s.getStructuredEvidence()) {
                evidenceCount++;
                if ("ENTAILED".equals(ev.getType())) {
                    entailedCount++;
                    entailedSum += ev.getScore() != null ? ev.getScore() : 0.0;
                } else if ("CONTRADICTION".equals(ev.getType())) {
                    contradictionCount++;
                } else if ("CAUSAL".equals(ev.getType())) {
                    causalCount++;
                    causalSum += ev.getScore() != null ? clamp01(ev.getScore()) : 0.0;
                }
            }
        }
        x[2] = entailedCount > 0 ? clamp01(entailedSum / entailedCount) : 0.0;
        x[3] = Math.min(1.0, entailedCount / 10.0);
        x[4] = evidenceCount > 0 ? (double) contradictionCount / evidenceCount : 0.0;
        x[5] = causalCount > 0 ? clamp01(causalSum / causalCount) : 0.0;

        if (s.getBayesianPosteriors() != null && !s.getBayesianPosteriors().isEmpty()) {
            x[6] = clamp01(s.getBayesianPosteriors().values().stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0.0));
        }

        List<String> steps = ProcessNarrator.orderedStepNames(s);
        x[7] = Math.min(1.0, steps.size() / 12.0);
        x[8] = Math.min(1.0, ProcessNarrator.caseCount(s) / 10.0);

        int stepTotal = 0;
        int stepsWithRole = 0;
        if (s.getPhases() != null) {
            for (ProcessSuggestion.SuggestedPhase phase : s.getPhases()) {
                if (phase.getSteps() == null) {
                    continue;
                }
                for (ProcessSuggestion.SuggestedStep step : phase.getSteps()) {
                    stepTotal++;
                    if (step.getRoleBinding() != null && !step.getRoleBinding().isBlank()) {
                        stepsWithRole++;
                    }
                }
            }
        }
        x[9] = stepTotal > 0 ? (double) stepsWithRole / stepTotal : 0.0;
        return x;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
