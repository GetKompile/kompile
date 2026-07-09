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
package ai.kompile.graph.reasoning.explain;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.fol.EntailmentRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic open-question analysis over reasoning traces.
 *
 * <p>The renderer answers "what happened?" and the attribution index answers "what can I cite?".
 * This analyzer answers "what should a caller or LLM verify next?" by returning bounded,
 * step-id-addressable gaps that point back into the same trace IDs used by
 * {@link ReasoningTraceRenderer}.</p>
 */
public final class ReasoningTraceGapAnalyzer {

    public static final int DEFAULT_MAX_GAPS = 12;
    private static final double LOW_CONFIDENCE = 0.60;
    private static final double VERY_LOW_CONFIDENCE = 0.40;
    private static final double MODALITY_DISAGREEMENT = 0.35;
    /** Opinion uncertainty threshold for UNCERTAIN_OPINION gap. */
    private static final double OPINION_UNCERTAIN_THRESHOLD = 0.6;
    /** Opinion belief/disbelief thresholds for CONTESTED_EVIDENCE gap. */
    private static final double OPINION_CONTEST_THRESHOLD = 0.3;
    /** Pairwise conflict() threshold for sibling-opinion CONTESTED_EVIDENCE gap. */
    private static final double OPINION_CONFLICT_THRESHOLD = 0.25;
    private static final Set<String> UNCERTAIN_TOKENS = Set.of(
            "unknown", "fallback", "n/a", "unavailable", "no signal", "could not",
            "not available", "unclear", "missing", "failed");

    private ReasoningTraceGapAnalyzer() {
    }

    /** One actionable clarification or trace-quality gap tied to citeable step IDs. */
    public record TraceGap(
            String gapType,
            String severity,
            String question,
            String reason,
            List<String> relatedStepIds) {
        public TraceGap {
            gapType = clean(gapType);
            severity = clean(severity);
            question = clean(question);
            reason = clean(reason);
            relatedStepIds = relatedStepIds == null ? List.of() : List.copyOf(relatedStepIds);
        }
    }

    public static List<TraceGap> traceGaps(ReasoningTrail trail) {
        return traceGaps(trail, DEFAULT_MAX_GAPS);
    }

    public static List<TraceGap> traceGaps(ReasoningTrail trail, int maxGaps) {
        if (trail == null || maxGaps <= 0) {
            return List.of();
        }
        List<TraceGap> out = new ArrayList<>();
        analyzeAttributions(ReasoningTraceRenderer.attributionIndex(trail), out, maxGaps, false);
        addEntailmentSupportGaps(trail, out, maxGaps);
        return List.copyOf(out);
    }

    public static List<TraceGap> openQuestions(ReasoningTrail trail) {
        return traceGaps(trail);
    }

    public static List<TraceGap> traceGaps(ReasoningTrace trace) {
        return traceGaps(trace, DEFAULT_MAX_GAPS);
    }

    public static List<TraceGap> traceGaps(ReasoningTrace trace, int maxGaps) {
        if (trace == null || maxGaps <= 0) {
            return List.of();
        }
        boolean rootIsBaseEvidence = trace.conclusion().isLeaf()
                && (trace.conclusion().kind() == ReasoningTrace.StepKind.FACT
                || trace.conclusion().kind() == ReasoningTrace.StepKind.ASSUMPTION);
        List<TraceGap> out = new ArrayList<>();
        // Pass the root Step so addConfidenceGap can suppress LOW_CONFIDENCE for opinion-carrying steps
        analyzeAttributions(ReasoningTraceRenderer.attributionIndex(trace), out, maxGaps,
                rootIsBaseEvidence, trace.conclusion());
        // E3: opinion-based gap analysis over all steps in the trace
        addOpinionGaps(trace.steps(), out, maxGaps);
        return List.copyOf(out);
    }

    public static List<TraceGap> openQuestions(ReasoningTrace trace) {
        return traceGaps(trace);
    }

    public static List<TraceGap> traceGaps(CompositeReasoningTrail trail) {
        return traceGaps(trail, DEFAULT_MAX_GAPS);
    }

    public static List<TraceGap> traceGaps(CompositeReasoningTrail trail, int maxGaps) {
        if (trail == null || maxGaps <= 0) {
            return List.of();
        }
        List<TraceGap> out = new ArrayList<>();
        analyzeAttributions(ReasoningTraceRenderer.attributionIndex(trail), out, maxGaps, false);
        addCompositeGaps(trail, out, maxGaps);
        return List.copyOf(out);
    }

    public static List<TraceGap> openQuestions(CompositeReasoningTrail trail) {
        return traceGaps(trail);
    }

    /** Overload without a matching root Step — used by trail and composite path. */
    private static void analyzeAttributions(List<ReasoningTraceRenderer.AttributionStep> attributions,
                                            List<TraceGap> out,
                                            int maxGaps,
                                            boolean rootIsBaseEvidence) {
        analyzeAttributions(attributions, out, maxGaps, rootIsBaseEvidence, null);
    }

    private static void analyzeAttributions(List<ReasoningTraceRenderer.AttributionStep> attributions,
                                            List<TraceGap> out,
                                            int maxGaps,
                                            boolean rootIsBaseEvidence,
                                            ReasoningTrace.Step rootStep) {
        if (attributions == null || attributions.isEmpty()) {
            addIfRoom(out, maxGaps, new TraceGap(
                    "MISSING_TRACE",
                    "HIGH",
                    "Which reasoning steps produced this conclusion?",
                    "No attribution steps were available for the trace.",
                    List.of()));
            return;
        }

        ReasoningTraceRenderer.AttributionStep root = attributions.get(0);
        addConfidenceGapForStep(root, rootStep, out, maxGaps);

        List<ReasoningTraceRenderer.AttributionStep> support = attributions.stream()
                .filter(step -> !"trace.root".equals(step.stepId()))
                .toList();
        if (support.isEmpty() && !rootIsBaseEvidence) {
            addIfRoom(out, maxGaps, new TraceGap(
                    "MISSING_SUPPORT",
                    "HIGH",
                    "What evidence, rule, or source directly supports this conclusion?",
                    "The trace exposes a conclusion but no supporting evidence or derivation steps.",
                    List.of(root.stepId())));
        } else if (support.size() == 1) {
            addIfRoom(out, maxGaps, new TraceGap(
                    "SINGLE_SUPPORT",
                    "LOW",
                    "Can this conclusion be corroborated by another independent evidence path?",
                    "Only one supporting trace step is exposed.",
                    List.of(root.stepId(), support.get(0).stepId())));
        }

        List<String> missingProvenance = support.stream()
                .filter(ReasoningTraceGapAnalyzer::requiresProvenance)
                .filter(step -> isBlank(step.source()))
                .map(ReasoningTraceRenderer.AttributionStep::stepId)
                .limit(5)
                .toList();
        if (!missingProvenance.isEmpty()) {
            addIfRoom(out, maxGaps, new TraceGap(
                    "MISSING_PROVENANCE",
                    "MEDIUM",
                    "Which source produced or supports these trace steps?",
                    "One or more support steps do not expose source provenance.",
                    missingProvenance));
        }

        List<String> uncertainSteps = attributions.stream()
                .filter(step -> !"trace.root".equals(step.stepId()))
                .filter(ReasoningTraceGapAnalyzer::looksUncertain)
                .map(ReasoningTraceRenderer.AttributionStep::stepId)
                .limit(5)
                .toList();
        if (!uncertainSteps.isEmpty()) {
            addIfRoom(out, maxGaps, new TraceGap(
                    "UNCERTAIN_EVIDENCE",
                    "MEDIUM",
                    "Can the uncertain evidence be replaced with a concrete source or finding?",
                    "Some support text is marked as unavailable, missing, failed, or otherwise uncertain.",
                    uncertainSteps));
        }
    }

    private static void addConfidenceGap(ReasoningTraceRenderer.AttributionStep root,
                                         List<TraceGap> out,
                                         int maxGaps) {
        addConfidenceGapForStep(root, null, out, maxGaps);
    }

    /**
     * Add a confidence-based gap for a single attribution step. When the corresponding trace step
     * carries an {@link Opinion}, opinion-specific gap types take precedence over LOW_CONFIDENCE
     * (those will be emitted by {@link #addOpinionGaps} instead), so this method only fires
     * LOW_CONFIDENCE for scalar-only steps.
     *
     * @param matchingTraceStep the {@link ReasoningTrace.Step} that backs {@code root}, or null if
     *                          this is a trail-level call without a corresponding Step object
     */
    private static void addConfidenceGapForStep(ReasoningTraceRenderer.AttributionStep root,
                                                 ReasoningTrace.Step matchingTraceStep,
                                                 List<TraceGap> out,
                                                 int maxGaps) {
        if (root == null) {
            return;
        }
        if (Double.isNaN(root.confidence())) {
            addIfRoom(out, maxGaps, new TraceGap(
                    "MISSING_CONFIDENCE",
                    "MEDIUM",
                    "What confidence score should be attached to this conclusion?",
                    "The root trace step does not expose a numeric confidence.",
                    List.of(root.stepId())));
            return;
        }
        // If the step carries an Opinion, skip scalar LOW_CONFIDENCE — opinion gaps cover it
        if (matchingTraceStep != null && matchingTraceStep.opinion() != null) {
            return;
        }
        if (root.confidence() < LOW_CONFIDENCE) {
            String severity = root.confidence() < VERY_LOW_CONFIDENCE ? "HIGH" : "MEDIUM";
            addIfRoom(out, maxGaps, new TraceGap(
                    "LOW_CONFIDENCE",
                    severity,
                    "What additional evidence would raise confidence in this conclusion?",
                    "The root confidence is " + format(root.confidence()) + ".",
                    List.of(root.stepId())));
        }
    }

    /**
     * E3 opinion-based gap analysis. For each step that carries a subjective-logic {@link Opinion}:
     * <ul>
     *   <li><b>UNCERTAIN_OPINION</b> — uncertainty ≥ 0.6: fires INSTEAD of LOW_CONFIDENCE for
     *       opinion-carrying steps, signalling "evidence is thin".</li>
     *   <li><b>CONTESTED_EVIDENCE</b> — either (belief ≥ 0.3 AND disbelief ≥ 0.3) OR pairwise
     *       conflict() ≥ 0.25 among sibling premise opinions: "sources actively disagree".</li>
     * </ul>
     * These are checked at the root/leaf level and across sibling premises.
     */
    private static void addOpinionGaps(List<ReasoningTrace.Step> steps, List<TraceGap> out, int maxGaps) {
        if (steps == null || steps.isEmpty()) return;
        for (ReasoningTrace.Step step : steps) {
            Opinion op = step.opinion();
            if (op == null) continue;

            String sid = "trace.step"; // step IDs not available here; use generic id

            // UNCERTAIN_OPINION: thin evidence
            if (op.uncertainty() >= OPINION_UNCERTAIN_THRESHOLD) {
                addIfRoom(out, maxGaps, new TraceGap(
                        "UNCERTAIN_OPINION",
                        "MEDIUM",
                        "What additional evidence would reduce uncertainty about this conclusion?",
                        "Evidence is thin: uncertainty=" + format(op.uncertainty()) + " ≥ " + OPINION_UNCERTAIN_THRESHOLD
                                + " for step \"" + truncate(step.conclusion()) + "\".",
                        List.of(sid)));
                continue; // UNCERTAIN_OPINION takes precedence; don't also emit CONTESTED for same step
            }

            // CONTESTED_EVIDENCE: sources actively disagree (intra-step)
            if (op.belief() >= OPINION_CONTEST_THRESHOLD && op.disbelief() >= OPINION_CONTEST_THRESHOLD) {
                addIfRoom(out, maxGaps, new TraceGap(
                        "CONTESTED_EVIDENCE",
                        "MEDIUM",
                        "Why do sources disagree about this conclusion?",
                        "Sources actively disagree: belief=" + format(op.belief())
                                + ", disbelief=" + format(op.disbelief())
                                + " for step \"" + truncate(step.conclusion()) + "\".",
                        List.of(sid)));
            }
        }

        // Check sibling premise opinions for pairwise conflict
        for (ReasoningTrace.Step step : steps) {
            List<ReasoningTrace.Step> premises = step.premises();
            if (premises.size() < 2) continue;
            outer:
            for (int i = 0; i < premises.size(); i++) {
                Opinion oi = premises.get(i).opinion();
                if (oi == null) continue;
                for (int j = i + 1; j < premises.size(); j++) {
                    Opinion oj = premises.get(j).opinion();
                    if (oj == null) continue;
                    if (oi.conflict(oj) >= OPINION_CONFLICT_THRESHOLD) {
                        addIfRoom(out, maxGaps, new TraceGap(
                                "CONTESTED_EVIDENCE",
                                "MEDIUM",
                                "Why do supporting sources disagree with each other?",
                                "Sibling premises conflict: conflict()=" + format(oi.conflict(oj))
                                        + " ≥ " + OPINION_CONFLICT_THRESHOLD
                                        + " under step \"" + truncate(step.conclusion()) + "\".",
                                List.of("trace.step")));
                        break outer;
                    }
                }
            }
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    private static void addEntailmentSupportGaps(ReasoningTrail trail,
                                                 List<TraceGap> out,
                                                 int maxGaps) {
        List<String> unsupportedEntailments = new ArrayList<>();
        for (int i = 0; i < trail.entailments().size(); i++) {
            EntailmentRecord entailment = trail.entailments().get(i);
            if (entailment.supportingFindingKeys().isEmpty()) {
                unsupportedEntailments.add("trace.entailment." + i);
            }
        }
        if (!unsupportedEntailments.isEmpty()) {
            addIfRoom(out, maxGaps, new TraceGap(
                    "MISSING_SUPPORT_FINDINGS",
                    "MEDIUM",
                    "Which findings or grounded facts support these entailments?",
                    "At least one entailment has no supporting finding keys.",
                    unsupportedEntailments.stream().limit(5).toList()));
        }
    }

    private static void addCompositeGaps(CompositeReasoningTrail trail,
                                         List<TraceGap> out,
                                         int maxGaps) {
        long activeCount = trail.activeModalityCount();
        if (trail.modalities().isEmpty()) {
            addIfRoom(out, maxGaps, new TraceGap(
                    "MISSING_MODALITIES",
                    "HIGH",
                    "Which reasoning modalities were evaluated for this conclusion?",
                    "The fused trace does not contain any modality evidence.",
                    List.of("trace.root")));
            return;
        }
        if (activeCount == 0) {
            addIfRoom(out, maxGaps, new TraceGap(
                    "NO_ACTIVE_MODALITY",
                    "HIGH",
                    "Which reasoning modality produced a usable confidence signal?",
                    "All modalities reported no usable signal.",
                    modalityIds(trail, false)));
        } else if (activeCount < 2) {
            addIfRoom(out, maxGaps, new TraceGap(
                    "LOW_MODALITY_CORROBORATION",
                    "LOW",
                    "Can this fused conclusion be corroborated by another active modality?",
                    "Fewer than two modalities produced a usable confidence signal.",
                    activeModalityIds(trail)));
        }

        List<String> noSignal = modalityIds(trail, false);
        if (!noSignal.isEmpty()) {
            addIfRoom(out, maxGaps, new TraceGap(
                    "NO_SIGNAL_MODALITY",
                    "MEDIUM",
                    "Why did these modalities produce no confidence signal?",
                    "One or more modalities ran without returning a positive confidence signal.",
                    noSignal.stream().limit(5).toList()));
        }

        List<String> missingDetails = new ArrayList<>();
        for (int i = 0; i < trail.modalities().size(); i++) {
            ModalityEvidence modality = trail.modalities().get(i);
            if (modality.hasSignal() && modality.details().isEmpty()) {
                missingDetails.add("trace.modality." + i);
            }
        }
        if (!missingDetails.isEmpty()) {
            addIfRoom(out, maxGaps, new TraceGap(
                    "MISSING_MODALITY_DETAIL",
                    "MEDIUM",
                    "Which fine-grained evidence lines produced these modality scores?",
                    "At least one active modality exposes a confidence without detail lines.",
                    missingDetails.stream().limit(5).toList()));
        }

        addModalityDisagreementGap(trail, out, maxGaps);
    }

    private static void addModalityDisagreementGap(CompositeReasoningTrail trail,
                                                   List<TraceGap> out,
                                                   int maxGaps) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < trail.modalities().size(); i++) {
            ModalityEvidence modality = trail.modalities().get(i);
            if (!modality.hasSignal()) {
                continue;
            }
            min = Math.min(min, modality.confidence());
            max = Math.max(max, modality.confidence());
            ids.add("trace.modality." + i);
        }
        if (ids.size() > 1 && max - min >= MODALITY_DISAGREEMENT) {
            addIfRoom(out, maxGaps, new TraceGap(
                    "MODALITY_DISAGREEMENT",
                    "MEDIUM",
                    "Why do the active modalities disagree on confidence?",
                    "Active modality confidence scores differ by " + format(max - min) + ".",
                    ids.stream().limit(5).toList()));
        }
    }

    private static List<String> modalityIds(CompositeReasoningTrail trail, boolean active) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < trail.modalities().size(); i++) {
            if (trail.modalities().get(i).hasSignal() == active) {
                ids.add("trace.modality." + i);
            }
        }
        return ids;
    }

    private static List<String> activeModalityIds(CompositeReasoningTrail trail) {
        return modalityIds(trail, true);
    }

    private static boolean requiresProvenance(ReasoningTraceRenderer.AttributionStep step) {
        if (step == null || "trace.root".equals(step.stepId())) {
            return false;
        }
        String kind = step.kind();
        return ReasoningTrace.StepKind.FACT.name().equals(kind)
                || ReasoningTrace.StepKind.INFERENCE.name().equals(kind)
                || ReasoningTrace.StepKind.RULE.name().equals(kind);
    }

    private static boolean looksUncertain(ReasoningTraceRenderer.AttributionStep step) {
        String text = (clean(step.conclusion()) + " " + clean(step.operation()))
                .toLowerCase(Locale.ROOT);
        return UNCERTAIN_TOKENS.stream().anyMatch(text::contains);
    }

    private static void addIfRoom(List<TraceGap> out, int maxGaps, TraceGap gap) {
        if (out.size() < maxGaps) {
            out.add(gap);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String format(double value) {
        if (Double.isNaN(value)) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
