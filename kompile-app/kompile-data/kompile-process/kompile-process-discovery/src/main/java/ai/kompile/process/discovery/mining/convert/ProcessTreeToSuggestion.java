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

package ai.kompile.process.discovery.mining.convert;

import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.fol.grounding.GroundedElement;
import ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestion.StructuredEvidence;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedPhase;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedStep;
import ai.kompile.process.discovery.mining.conformance.ConformanceChecker;
import ai.kompile.process.discovery.mining.conformance.ConformanceResult;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import ai.kompile.process.discovery.mining.tree.ProcessTreeNode;
import ai.kompile.process.discovery.mining.tree.ProcessTreeNode.Operator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Flattens a discovered {@link ProcessTree} into the existing {@link ProcessSuggestion} shape, so a
 * mined process travels through the exact same accept→{@code ProcessDefinition} path and UI as the
 * legacy heuristic suggestions — no downstream changes required.
 *
 * <p>The two-level suggestion model (phases → steps) is produced by treating a top-level sequence's
 * children as phases (otherwise the whole tree is one phase), and the activity leaves within each
 * block as steps. Each step carries the graph node ids and earliest timestamp of the events that
 * produced its activity, preserving provenance back into the knowledge graph.
 *
 * <p>A KB-grounded overload {@link #convertGrounded} is available when a {@link KbGroundingService}
 * is wired: each step's atom key {@code activity("<name>")} is verified against the KB, calibrated
 * via {@link PlattCalibrator} with {@link StrengthCalibrator.SignalType#INDUCTIVE_MINER_FM}, and
 * wrapped in a {@link GroundedElement}. The aggregate calibrated confidence replaces the bare
 * {@code fitness × precision} score on the suggestion.
 */
public final class ProcessTreeToSuggestion {

    private ProcessTreeToSuggestion() {
    }

    /**
     * Convert without KB grounding (backward-compatible static entry point).
     * Confidence = {@code max(0.1, fitness × precision)}.
     */
    public static ProcessSuggestion convert(ProcessTree tree, EventLog log, String processName) {
        return convertGrounded(tree, log, processName, null, null, null);
    }

    /**
     * Convert with KB grounding: each step's atom is verified against the KB, calibrated, and
     * wrapped in a {@link GroundedElement}. The aggregate calibrated confidence replaces the
     * bare {@code fitness × precision} score.
     *
     * @param tree        the discovered process tree
     * @param log         the event log used to mine the tree
     * @param processName display name
     * @param kbGrounding the KB grounding service (may be null — falls back to ungrounded)
     * @param calibrator  the strength calibrator (may be null — a fresh PlattCalibrator is used)
     * @param factSheetId the fact sheet to verify atoms against (ignored when kbGrounding is null)
     */
    public static ProcessSuggestion convertGrounded(
            ProcessTree tree,
            EventLog log,
            String processName,
            KbGroundingService kbGrounding,
            StrengthCalibrator calibrator,
            Long factSheetId) {
        // Resolve calibrator — use a fresh PlattCalibrator when not provided
        StrengthCalibrator cal = (calibrator != null) ? calibrator : new PlattCalibrator();

        Map<String, List<String>> labelToNodeIds = new LinkedHashMap<>();
        Map<String, LocalDateTime> labelToEarliest = new LinkedHashMap<>();
        List<String> allNodeIds = new ArrayList<>();
        for (Trace t : log.traces()) {
            for (Event e : t.events()) {
                if (e.graphNodeId() != null) {
                    labelToNodeIds.computeIfAbsent(e.activity(), k -> new ArrayList<>()).add(e.graphNodeId());
                    allNodeIds.add(e.graphNodeId());
                }
                if (e.timestamp() != null) {
                    labelToEarliest.merge(e.activity(), e.timestamp(), (a, b) -> a.isBefore(b) ? a : b);
                }
            }
        }

        ProcessTreeNode root = tree.root();
        List<ProcessTreeNode> phaseRoots =
                (root.operator() == Operator.SEQUENCE) ? root.children() : List.of(root);

        List<SuggestedPhase> phases = new ArrayList<>();
        // Collect all grounded steps across phases for aggregate confidence
        List<GroundedElement<SuggestedStep>> allGroundedSteps = new ArrayList<>();
        int idx = 1;
        for (ProcessTreeNode phaseRoot : phaseRoots) {
            List<SuggestedStep> steps = new ArrayList<>();
            LocalDateTime phaseEarliest = null;
            LocalDateTime phaseLatest = null;
            for (ProcessTreeNode leaf : orderedActivities(phaseRoot)) {
                String label = leaf.activity();
                LocalDateTime when = labelToEarliest.get(label);
                SuggestedStep step = SuggestedStep.builder()
                        .name(label)
                        .stepType(inferStepType(label))
                        .description("Discovered activity \"" + label + "\"")
                        .graphNodeIds(new ArrayList<>(labelToNodeIds.getOrDefault(label, List.of())))
                        .occurredAt(when)
                        .build();
                steps.add(step);

                // KB grounding: verify activity("<label>") against the KB
                if (kbGrounding != null && factSheetId != null) {
                    String atomKey = "activity(\"" + label + "\")";
                    VerifyResult vr = kbGrounding.verify(factSheetId, atomKey);
                    // Raw score: fitness×precision at step level (the conformance is per-tree,
                    // not per-step, so we use the tree conformance as a proxy)
                    ConformanceResult stepConf = ConformanceChecker.check(tree, log);
                    double rawScore = stepConf.fitness() * stepConf.precision();
                    double calibrated = cal.calibrate(rawScore, StrengthCalibrator.SignalType.INDUCTIVE_MINER_FM, vr);
                    StrengthBand band = StrengthBand.fromScalar(calibrated);
                    GroundedElement<SuggestedStep> ge = new GroundedElement<>(
                            step, vr, calibrated, band, atomKey,
                            null, null, "INDUCTIVE_MINER");
                    allGroundedSteps.add(ge);
                }

                if (when != null) {
                    if (phaseEarliest == null || when.isBefore(phaseEarliest)) {
                        phaseEarliest = when;
                    }
                    if (phaseLatest == null || when.isAfter(phaseLatest)) {
                        phaseLatest = when;
                    }
                }
            }
            if (steps.isEmpty()) {
                continue;
            }
            phases.add(SuggestedPhase.builder()
                    .name(describePhase(phaseRoot, idx))
                    .description(phaseRoot.toString())
                    .steps(steps)
                    .earliestOccurrence(phaseEarliest)
                    .latestOccurrence(phaseLatest)
                    .build());
            idx++;
        }

        List<String> distinctNodeIds = new ArrayList<>(new LinkedHashSet<>(allNodeIds));
        ConformanceResult conformance = ConformanceChecker.check(tree, log);

        // Confidence: when grounding is active, derive from calibrated steps (geometric mean of
        // SUPPORTED confidences; 0.0 if any REFUTED). Fall back to fitness × precision otherwise.
        double confidence;
        if (!allGroundedSteps.isEmpty()) {
            confidence = aggregateGroundedConfidence(allGroundedSteps);
        } else {
            // Confidence is earned, not guessed: a flower model fits perfectly but is imprecise,
            // so its fitness × precision is low — which is exactly how much it should be trusted.
            confidence = Math.max(0.1, conformance.fitness() * conformance.precision());
        }

        StructuredEvidence modelEvidence = StructuredEvidence.builder()
                .type("STATISTICAL")
                .description("Inductive Miner process tree (LLM-free): " + tree)
                .score((double) log.size())
                .supportingNodeIds(distinctNodeIds)
                .build();
        StructuredEvidence conformanceEvidence = StructuredEvidence.builder()
                .type("STATISTICAL")
                .description(String.format("Conformance — fitness %.2f, precision %.2f, simplicity %.2f",
                        conformance.fitness(), conformance.precision(), conformance.simplicity()))
                .score(conformance.fScore())
                .supportingNodeIds(distinctNodeIds)
                .build();

        return ProcessSuggestion.builder()
                .name(processName != null ? processName : "Mined process")
                .description(String.format(
                        "Discovered by the Inductive Miner from %d case(s) over the knowledge graph "
                                + "(no LLM); fitness %.2f, precision %.2f: %s",
                        log.size(), conformance.fitness(), conformance.precision(), tree))
                .discoverySource("PROCESS_MINING")
                .confidence(confidence)
                .phases(phases)
                .sourceGraphNodeIds(distinctNodeIds)
                .evidence(List.of(
                        "Inductive Miner over the directly-follows graph (sound, block-structured, deterministic)",
                        "Process tree: " + tree))
                .structuredEvidence(List.of(modelEvidence, conformanceEvidence))
                .groundedSteps(allGroundedSteps)
                .build();
    }

    /**
     * Geometric mean of calibrated confidences for SUPPORTED steps.
     * Returns 0.0 if any step is REFUTED. Falls back to 0.1 when no steps are SUPPORTED.
     */
    private static double aggregateGroundedConfidence(List<GroundedElement<SuggestedStep>> steps) {
        if (steps == null || steps.isEmpty()) return 0.1;
        for (GroundedElement<SuggestedStep> ge : steps) {
            if (ge.isRefuted()) return 0.0;
        }
        double logSum = 0.0;
        int count = 0;
        for (GroundedElement<SuggestedStep> ge : steps) {
            if (ge.isVerified()) {
                double c = ge.calibratedConfidence();
                if (c > 0) {
                    logSum += Math.log(c);
                    count++;
                }
            }
        }
        if (count == 0) {
            // all UNKNOWN — cap at PlattCalibrator's unknown ceiling
            return 0.1;
        }
        return Math.max(0.1, Math.exp(logSum / count));
    }

    /** Activity leaves of a subtree in execution order (TAU/silent leaves skipped). */
    private static List<ProcessTreeNode> orderedActivities(ProcessTreeNode node) {
        List<ProcessTreeNode> out = new ArrayList<>();
        collectActivities(node, out);
        return out;
    }

    private static void collectActivities(ProcessTreeNode node, List<ProcessTreeNode> out) {
        if (node.operator() == Operator.ACTIVITY) {
            out.add(node);
            return;
        }
        for (ProcessTreeNode child : node.children()) {
            collectActivities(child, out);
        }
    }

    private static String describePhase(ProcessTreeNode node, int index) {
        String kind = switch (node.operator()) {
            case ACTIVITY -> node.activity();
            case SEQUENCE -> "Sequence";
            case XOR -> "Choice";
            case AND -> "Parallel";
            case LOOP -> "Loop";
            case TAU -> "Skip";
        };
        return "Phase " + index + ": " + kind;
    }

    private static String inferStepType(String label) {
        String l = label.toLowerCase();
        if (l.contains("approv") || l.contains("review") || l.contains("sign")) {
            return "APPROVE";
        }
        if (l.contains("manual") || l.contains("human")) {
            return "HUMAN";
        }
        return "AUTO";
    }
}
