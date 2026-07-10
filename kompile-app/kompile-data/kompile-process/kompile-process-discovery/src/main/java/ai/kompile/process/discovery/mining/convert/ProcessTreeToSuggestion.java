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
import ai.kompile.process.discovery.mining.entail.ProcessAtoms;
import ai.kompile.process.discovery.mining.entail.ProcessStateEntailment;
import ai.kompile.process.discovery.mining.log.ActivityIntervals;
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
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

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

    /** Default decision-stump separation accuracy when no managed config is in play. */
    public static final double DEFAULT_GUARD_MIN_ACCURACY = 0.9;

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
        return convertGrounded(tree, log, processName, kbGrounding, calibrator, factSheetId,
                DEFAULT_GUARD_MIN_ACCURACY);
    }

    /** Full conversion with the managed-config guard-mining accuracy floor. */
    public static ProcessSuggestion convertGrounded(
            ProcessTree tree,
            EventLog log,
            String processName,
            KbGroundingService kbGrounding,
            StrengthCalibrator calibrator,
            Long factSheetId,
            double guardMinAccuracy) {
        // Resolve calibrator — use a fresh PlattCalibrator when not provided
        StrengthCalibrator cal = (calibrator != null) ? calibrator : new PlattCalibrator();

        Map<String, List<String>> labelToNodeIds = new LinkedHashMap<>();
        Map<String, LocalDateTime> labelToEarliest = new LinkedHashMap<>();
        Map<String, Map<String, Object>> labelToAttributes = new LinkedHashMap<>();
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
                Map<String, Object> attrs = labelToAttributes.computeIfAbsent(e.activity(), k -> new LinkedHashMap<>());
                for (Map.Entry<String, Object> attr : e.attributes().entrySet()) {
                    attrs.putIfAbsent(attr.getKey(), attr.getValue());
                }
            }
        }

        ProcessTreeNode root = tree.root();
        List<ProcessTreeNode> phaseRoots =
                (root.operator() == Operator.SEQUENCE) ? root.children() : List.of(root);

        // Conformance is a whole-tree replay over the whole log — compute it ONCE and reuse it for
        // every step's raw score and the final evidence (it was previously re-run per step).
        ConformanceResult conformance = ConformanceChecker.check(tree, log);

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

                // KB grounding: verify activity("<label>") against the KB. The key comes from
                // ProcessAtoms so it matches what the discover path batch-asserts — the miner's
                // derived atoms are promoted into the KB before conversion, making the KB
                // authoritative over the miner's own output (grounded verdicts stop being UNKNOWN).
                if (kbGrounding != null && factSheetId != null) {
                    String atomKey = ProcessAtoms.activityAtom(label);
                    VerifyResult vr = kbGrounding.verify(factSheetId, atomKey);
                    // Raw score: fitness×precision at step level (the conformance is per-tree,
                    // not per-step, so we use the tree conformance as a proxy)
                    double rawScore = conformance.fitness() * conformance.precision();
                    // D3-A: blend soft-truth value from InferredFactStore into calibrated score
                    OptionalDouble softTruth = kbGrounding.latestValue(factSheetId, atomKey);
                    double blendedRaw = softTruth.isPresent()
                            ? 0.6 * rawScore + 0.4 * softTruth.getAsDouble()
                            : rawScore;
                    double calibrated = cal.calibrate(blendedRaw, StrengthCalibrator.SignalType.INDUCTIVE_MINER_FM, vr);
                    StrengthBand band = StrengthBand.fromScalar(calibrated);
                    GroundedElement<SuggestedStep> ge = new GroundedElement<>(
                            step, vr, calibrated, band, atomKey,
                            null, null, "INDUCTIVE_MINER");
                    allGroundedSteps.add(ge);
                    // Build per-step lineage tracing back to the KB basis
                    ProcessSuggestion.ProcessLineage stepLineage = ProcessSuggestion.ProcessLineage.builder()
                            .basisNodeIds(new ArrayList<>(labelToNodeIds.getOrDefault(label, List.of())))
                            .derivationMethod("INDUCTIVE_MINER")
                            .softTruthValue(softTruth.isPresent() ? softTruth.getAsDouble() : null)
                            .atomKey(atomKey)
                            .build();
                    step.setLineageRef(stepLineage);
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

        // Wire the tree's control-flow semantics into step dependencies (SEQUENCE/LOOP children
        // chain, XOR/AND branches stay independent) so the accepted ProcessDefinition keeps the
        // mined ordering — previously the operators survived only as phase names.
        Map<String, SuggestedStep> stepsByLabel = new LinkedHashMap<>();
        for (SuggestedPhase phase : phases) {
            for (SuggestedStep s : phase.getSteps()) {
                stepsByLabel.putIfAbsent(s.getName(), s);
            }
        }
        wireDependencies(root, stepsByLabel);

        // Decision + concurrency semantics from the tree's operators, grounded in the log:
        // XOR branches get observed case shares + default-TRUE SpEL routing stubs + mined
        // attribute guards; AND blocks get PARALLEL evidence with Allen-interval overlap and
        // cross-trace order-instability corroboration.
        List<StructuredEvidence> operatorEvidence = new ArrayList<>();
        annotateOperatorSemantics(root, log, stepsByLabel, operatorEvidence, guardMinAccuracy);
        annotatePolicySemantics(labelToAttributes, stepsByLabel, operatorEvidence);
        annotateStateSemantics(log, stepsByLabel, operatorEvidence);

        List<String> distinctNodeIds = new ArrayList<>(new LinkedHashSet<>(allNodeIds));

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

        List<StructuredEvidence> structuredEvidence = new ArrayList<>();
        structuredEvidence.add(modelEvidence);
        structuredEvidence.add(conformanceEvidence);
        structuredEvidence.addAll(operatorEvidence);

        return ProcessSuggestion.builder()
                .name(processName != null ? processName : "Mined process")
                .description(String.format(
                        "Discovered by the Inductive Miner from %d case(s) over the knowledge graph "
                                + "(no LLM); fitness %.2f, precision %.2f: %s",
                        log.size(), conformance.fitness(), conformance.precision(), tree))
                .discoverySource("PROCESS_MINING")
                .confidence(confidence)
                .rawConformanceScore(conformance.fitness() * conformance.precision())
                .phases(phases)
                .sourceGraphNodeIds(distinctNodeIds)
                .evidence(List.of(
                        "Inductive Miner over the directly-follows graph (sound, block-structured, deterministic)",
                        "Process tree: " + tree))
                .structuredEvidence(structuredEvidence)
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

    /** Entry/exit activity labels of a subtree — the seam dependency wiring chains across. */
    private record Boundary(List<String> entries, List<String> exits) {
        static final Boundary EMPTY = new Boundary(List.of(), List.of());
    }

    /**
     * Recursively wires {@code dependsOn} between steps according to the tree's operator semantics:
     * SEQUENCE (and the first pass of LOOP) chains each child's entry steps onto the previous
     * child's exit steps; XOR and AND children stay mutually independent (that is exactly what the
     * operators assert). Returns the subtree's entry/exit label sets.
     */
    private static Boundary wireDependencies(ProcessTreeNode node, Map<String, SuggestedStep> stepsByLabel) {
        switch (node.operator()) {
            case ACTIVITY -> {
                String label = node.activity();
                return stepsByLabel.containsKey(label)
                        ? new Boundary(List.of(label), List.of(label))
                        : Boundary.EMPTY;
            }
            case TAU -> {
                return Boundary.EMPTY;
            }
            case SEQUENCE, LOOP -> {
                List<String> entries = null;
                List<String> prevExits = List.of();
                for (ProcessTreeNode child : node.children()) {
                    Boundary b = wireDependencies(child, stepsByLabel);
                    if (b.entries().isEmpty() && b.exits().isEmpty()) {
                        continue;
                    }
                    for (String entry : b.entries()) {
                        SuggestedStep step = stepsByLabel.get(entry);
                        if (step == null) {
                            continue;
                        }
                        for (String dep : prevExits) {
                            if (!dep.equals(entry) && !step.getDependsOn().contains(dep)) {
                                step.getDependsOn().add(dep);
                            }
                        }
                    }
                    if (entries == null) {
                        entries = b.entries();
                    }
                    prevExits = b.exits();
                }
                return new Boundary(entries == null ? List.of() : entries, prevExits);
            }
            case XOR, AND -> {
                List<String> entries = new ArrayList<>();
                List<String> exits = new ArrayList<>();
                for (ProcessTreeNode child : node.children()) {
                    Boundary b = wireDependencies(child, stepsByLabel);
                    entries.addAll(b.entries());
                    exits.addAll(b.exits());
                }
                return new Boundary(entries, exits);
            }
        }
        return Boundary.EMPTY;
    }

    private static void annotatePolicySemantics(Map<String, Map<String, Object>> labelToAttributes,
                                                Map<String, SuggestedStep> stepsByLabel,
                                                List<StructuredEvidence> evidence) {
        int annotated = 0;
        List<String> annotatedLabels = new ArrayList<>();
        for (Map.Entry<String, SuggestedStep> entry : stepsByLabel.entrySet()) {
            Map<String, Object> attrs = labelToAttributes.getOrDefault(entry.getKey(), Map.of());
            if (attrs.isEmpty()) {
                continue;
            }
            SuggestedStep step = entry.getValue();
            boolean changed = false;
            changed |= addUnique(step.getControlIds(), extractStrings(attrs,
                    "controlId", "controlIds", "control", "controls",
                    "source.controlId", "source.controlIds", "source.controls",
                    "target.controlId", "target.controlIds", "target.controls",
                    "relation.controlId", "relation.controlIds", "relation.controls"));
            changed |= addUnique(step.getRequiredRoles(), extractStrings(attrs,
                    "requiredRole", "requiredRoles", "role", "roles", "owner", "owners", "gate",
                    "approver", "approvers", "approvalRole", "approvalRoles",
                    "source.requiredRole", "source.requiredRoles", "source.role", "source.roles", "source.owner",
                    "target.requiredRole", "target.requiredRoles", "target.role", "target.roles", "target.owner",
                    "relation.requiredRole", "relation.requiredRoles", "relation.role", "relation.roles", "relation.owner"));
            changed |= addUnique(step.getRequiredPermissions(), extractStrings(attrs,
                    "requiredPermission", "requiredPermissions", "permission", "permissions",
                    "source.requiredPermission", "source.requiredPermissions", "source.permission", "source.permissions",
                    "target.requiredPermission", "target.requiredPermissions", "target.permission", "target.permissions",
                    "relation.requiredPermission", "relation.requiredPermissions", "relation.permission", "relation.permissions"));

            Map<String, Object> policyMetadata = policyMetadata(attrs);
            if (!policyMetadata.isEmpty()) {
                step.getMetadata().putAll(policyMetadata);
                changed = true;
            }

            PolicyCondition condition = policyCondition(attrs);
            if (condition != null) {
                appendCondition(step, condition.spel(), condition.label());
                changed = true;
            } else {
                String label = policyLabel(attrs);
                if (hasText(label)) {
                    appendConditionLabel(step, label);
                    changed = true;
                }
            }

            if (changed) {
                annotated++;
                annotatedLabels.add(entry.getKey());
            }
        }
        if (annotated > 0) {
            evidence.add(StructuredEvidence.builder()
                    .type("POLICY")
                    .description("Graph policy attributes lifted onto " + annotated + " step(s): "
                            + String.join(", ", annotatedLabels))
                    .score((double) annotated)
                    .build());
        }
    }

    private static void annotateStateSemantics(EventLog log,
                                               Map<String, SuggestedStep> stepsByLabel,
                                               List<StructuredEvidence> evidence) {
        ProcessStateEntailment.Result result = ProcessStateEntailment.entail(log);
        if (result.isEmpty()) {
            return;
        }
        Map<String, List<ProcessStateEntailment.EntailedState>> byActivity = new LinkedHashMap<>();
        for (ProcessStateEntailment.EntailedState state : result.states()) {
            byActivity.computeIfAbsent(state.activity(), ignored -> new ArrayList<>()).add(state);
        }

        int annotated = 0;
        List<String> annotatedLabels = new ArrayList<>();
        for (Map.Entry<String, SuggestedStep> entry : stepsByLabel.entrySet()) {
            List<ProcessStateEntailment.EntailedState> states = byActivity.get(entry.getKey());
            if (states == null || states.isEmpty()) {
                continue;
            }
            SuggestedStep step = entry.getValue();
            Map<String, Object> metadata = stepMetadata(step);
            boolean changed = false;
            for (ProcessStateEntailment.EntailedState state : states) {
                changed |= addMetadataValue(metadata, "processStateTypes", state.stateType().name());
                changed |= addMetadataValue(metadata, "processStates", stateDetail(state));
                changed |= applyStateToStep(step, metadata, state);
            }
            if (changed) {
                annotated++;
                annotatedLabels.add(entry.getKey());
            }
        }
        if (annotated > 0) {
            evidence.add(StructuredEvidence.builder()
                    .type("STATE")
                    .description("Graph state entailment lifted onto " + annotated + " step(s): "
                            + String.join(", ", annotatedLabels))
                    .score((double) annotated)
                    .build());
        }
    }

    private static boolean applyStateToStep(SuggestedStep step,
                                            Map<String, Object> metadata,
                                            ProcessStateEntailment.EntailedState state) {
        boolean changed = false;
        String object = state.object();
        String value = state.value();
        String summary = stateSummary(state);
        switch (state.stateType()) {
            case CONTROLLED, VALIDATED, CONTROL_EFFECT -> {
                if (looksLikeControlObject(object)) {
                    changed |= addUnique(step.getControlIds(), List.of(object));
                }
                changed |= addMetadataValue(metadata, "controlEffects", summary);
            }
            case ROLE_REQUIRED -> {
                if (hasText(object)) {
                    changed |= addUnique(step.getRequiredRoles(), List.of(object));
                }
            }
            case APPROVAL_REQUIRED -> {
                if (hasText(object)) {
                    changed |= addUnique(step.getRequiredRoles(), List.of(object));
                    changed |= addMetadataValue(metadata, "approvalRequiredRoles", object);
                }
                changed |= setMetadata(metadata, "approvalRequired", true);
                changed |= promoteStepType(step, "APPROVE");
            }
            case APPROVED -> {
                changed |= setMetadata(metadata, "approved", true);
                if (hasText(object)) {
                    changed |= addMetadataValue(metadata, "approvedBy", object);
                }
            }
            case ESCALATION_REQUIRED -> {
                changed |= setMetadata(metadata, "escalationRequired", true);
                if (hasText(object)) {
                    changed |= addMetadataValue(metadata, "escalationTargets", object);
                }
                appendConditionLabel(step, "State: escalation required");
                changed = true;
            }
            case ROUTED -> changed |= addMetadataValue(metadata, "routingStates", summary);
            case THRESHOLD_GUARDED -> changed |= addMetadataValue(metadata, "thresholdGuards", summary);
            case SLA_GOVERNED -> changed |= addMetadataValue(metadata, "slaStates", summary);
            case SLA_BREACH -> {
                changed |= setMetadata(metadata, "slaBreached", true);
                changed |= addMetadataValue(metadata, "slaBreaches", summary);
                appendConditionLabel(step, "State: SLA breach");
                changed = true;
            }
            case REMEDIATION_REQUIRED -> {
                changed |= setMetadata(metadata, "remediationRequired", true);
                changed |= addMetadataValue(metadata, "remediationActions", summary);
            }
            case REMEDIATION_SEVERITY -> {
                if (hasText(object)) {
                    changed |= setMetadata(metadata, "remediationSeverity", object);
                }
            }
            case POLICY_ATTACHED -> changed |= addMetadataValue(metadata, "policies", summary);
            case BLOCKED -> {
                changed |= setMetadata(metadata, "processBlocked", true);
                changed |= addMetadataValue(metadata, "blockingStates", summary);
                appendConditionLabel(step, "State: blocked");
                changed = true;
            }
            case READY -> changed |= setMetadata(metadata, "processReady", true);
        }
        return changed;
    }

    private static boolean looksLikeControlObject(String object) {
        if (!hasText(object)) {
            return false;
        }
        String normalized = object.trim().toUpperCase(Locale.ROOT);
        if ("PROCESS-STATE".equals(normalized) || "POLICY".equals(normalized)
                || "VALIDATE".equals(normalized) || "VALIDATED".equals(normalized)
                || "VERIFY".equals(normalized) || "CHECK".equals(normalized)
                || "RECONCILE".equals(normalized)) {
            return false;
        }
        return true;
    }

    private static Map<String, Object> stepMetadata(SuggestedStep step) {
        if (step.getMetadata() == null) {
            step.setMetadata(new LinkedHashMap<>());
        }
        return step.getMetadata();
    }

    private static boolean promoteStepType(SuggestedStep step, String stepType) {
        if (!hasText(stepType) || stepType.equals(step.getStepType())) {
            return false;
        }
        step.setStepType(stepType);
        return true;
    }

    private static boolean setMetadata(Map<String, Object> metadata, String key, Object value) {
        Object existing = metadata.get(key);
        if (value == null ? existing == null : value.equals(existing)) {
            return false;
        }
        metadata.put(key, value);
        return true;
    }

    private static boolean addMetadataValue(Map<String, Object> metadata, String key, Object value) {
        if (value == null) {
            return false;
        }
        List<Object> values = new ArrayList<>();
        Object existing = metadata.get(key);
        if (existing instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                values.add(item);
            }
        } else if (existing != null) {
            values.add(existing);
        }
        if (values.contains(value)) {
            return false;
        }
        values.add(value);
        metadata.put(key, values);
        return true;
    }

    private static Map<String, Object> stateDetail(ProcessStateEntailment.EntailedState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", state.stateType().name());
        if (hasText(state.object())) {
            out.put("object", state.object());
        }
        if (hasText(state.value())) {
            out.put("value", state.value());
        }
        out.put("confidence", state.confidence());
        if (!state.supportingAtomKeys().isEmpty()) {
            out.put("supportingAtomKeys", state.supportingAtomKeys());
        }
        return out;
    }

    private static String stateSummary(ProcessStateEntailment.EntailedState state) {
        if (hasText(state.object()) && hasText(state.value())) {
            return state.object() + "=" + state.value();
        }
        if (hasText(state.object())) {
            return state.object();
        }
        if (hasText(state.value())) {
            return state.value();
        }
        return state.stateType().name().toLowerCase(Locale.ROOT);
    }

    private record PolicyCondition(String spel, String label) {
    }

    private static PolicyCondition policyCondition(Map<String, Object> attrs) {
        Object explicitExpression = firstValue(attrs,
                "conditionExpression", "policyExpression", "routingExpression",
                "relation.conditionExpression", "relation.policyExpression", "relation.routingExpression");
        if (explicitExpression instanceof String s && hasText(s)) {
            return new PolicyCondition(s.trim(), policyLabel(attrs));
        }

        Object thresholdValue = firstValue(attrs,
                "threshold", "confidenceThreshold", "amountThreshold", "varianceThreshold",
                "relation.threshold", "relation.confidenceThreshold", "relation.amountThreshold", "relation.varianceThreshold",
                "source.threshold", "source.confidenceThreshold", "target.threshold", "target.confidenceThreshold");
        Double threshold = asNumber(thresholdValue);
        if (threshold == null) {
            return null;
        }
        String field = text(firstValue(attrs, "thresholdField", "policyField", "relation.thresholdField"));
        if (!hasText(field)) {
            String thresholdKey = firstPresentKey(attrs,
                    "confidenceThreshold", "amountThreshold", "varianceThreshold",
                    "relation.confidenceThreshold", "relation.amountThreshold", "relation.varianceThreshold",
                    "source.confidenceThreshold", "target.confidenceThreshold");
            field = thresholdKey == null ? "confidence" : thresholdFieldName(thresholdKey);
        }
        String variable = spelKey(field);
        String operator = normalizeOperator(text(firstValue(attrs,
                "thresholdOperator", "policyOperator", "relation.thresholdOperator")));
        String number = trimNumber(threshold);
        String spel = String.format(Locale.ROOT,
                "#%s == null || !(#%s instanceof T(java.lang.Number)) || #%s %s %s",
                variable, variable, variable, operator, number);
        return new PolicyCondition(spel, "Policy: " + variable + " " + operator + " " + number);
    }

    private static String thresholdFieldName(String key) {
        String local = key;
        int dot = local.lastIndexOf('.');
        if (dot >= 0) {
            local = local.substring(dot + 1);
        }
        if (local.endsWith("Threshold") && local.length() > "Threshold".length()) {
            local = local.substring(0, local.length() - "Threshold".length());
        }
        return local;
    }

    private static String normalizeOperator(String raw) {
        if (!hasText(raw)) {
            return ">=";
        }
        return switch (raw.trim()) {
            case "<", "<=", ">", ">=", "==", "!=" -> raw.trim();
            case "lte", "LTE", "max", "MAX" -> "<=";
            case "lt", "LT" -> "<";
            case "gt", "GT" -> ">";
            case "eq", "EQ" -> "==";
            default -> ">=";
        };
    }

    private static String policyLabel(Map<String, Object> attrs) {
        List<String> parts = new ArrayList<>();
        addLabelPart(parts, "routing", firstValue(attrs, "routingPolicy", "route", "relation.routingPolicy"));
        addLabelPart(parts, "approval", firstValue(attrs, "approvalPolicy", "approval", "relation.approvalPolicy"));
        addLabelPart(parts, "action", firstValue(attrs, "actionType", "action", "relation.actionType", "relation.action"));
        return parts.isEmpty() ? null : "Policy: " + String.join("; ", parts);
    }

    private static void addLabelPart(List<String> parts, String label, Object value) {
        String text = text(value);
        if (hasText(text)) {
            parts.add(label + "=" + text);
        }
    }

    private static Map<String, Object> policyMetadata(Map<String, Object> attrs) {
        Map<String, Object> out = new LinkedHashMap<>();
        copyMetadata(out, attrs,
                "routingPolicy", "approvalPolicy", "actionType", "action", "threshold", "thresholdField",
                "thresholdOperator", "confidenceThreshold", "amountThreshold", "varianceThreshold", "gate",
                "owner", "approver", "goldPattern", "processPattern",
                "relation.routingPolicy", "relation.approvalPolicy", "relation.actionType", "relation.action",
                "relation.threshold", "relation.confidenceThreshold", "relation.gate", "relation.owner",
                "source.goldPattern", "target.goldPattern", "source.owner", "target.owner");
        return out;
    }

    private static void copyMetadata(Map<String, Object> out, Map<String, Object> attrs, String... keys) {
        for (String key : keys) {
            Object value = attrs.get(key);
            if (value != null) {
                String metadataKey = key.replace('.', '_');
                out.putIfAbsent(metadataKey, value);
            }
        }
    }

    private static List<String> extractStrings(Map<String, Object> attrs, String... keys) {
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            addStrings(out, attrs.get(key));
        }
        return out;
    }

    private static void addStrings(List<String> out, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addStrings(out, item);
            }
            return;
        }
        String raw = String.valueOf(value);
        for (String part : raw.split(",")) {
            String item = part.trim();
            if (!item.isBlank() && !out.contains(item)) {
                out.add(item);
            }
        }
    }

    private static boolean addUnique(List<String> target, List<String> values) {
        if (target == null || values == null || values.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (String value : values) {
            if (hasText(value) && !target.contains(value)) {
                target.add(value);
                changed = true;
            }
        }
        return changed;
    }

    private static void appendCondition(SuggestedStep step, String conditionExpression, String conditionLabel) {
        if (!hasText(conditionExpression)) {
            appendConditionLabel(step, conditionLabel);
            return;
        }
        if (!hasText(step.getConditionExpression())) {
            step.setConditionExpression(conditionExpression);
        } else if (!step.getConditionExpression().contains(conditionExpression)) {
            step.setConditionExpression("(" + step.getConditionExpression() + ") && (" + conditionExpression + ")");
        }
        appendConditionLabel(step, conditionLabel);
    }

    private static void appendConditionLabel(SuggestedStep step, String label) {
        if (!hasText(label)) {
            return;
        }
        if (!hasText(step.getConditionLabel())) {
            step.setConditionLabel(label);
        } else if (!step.getConditionLabel().contains(label)) {
            step.setConditionLabel(step.getConditionLabel() + "; " + label);
        }
    }

    private static Object firstValue(Map<String, Object> attrs, String... keys) {
        for (String key : keys) {
            Object value = attrs.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstPresentKey(Map<String, Object> attrs, String... keys) {
        for (String key : keys) {
            if (attrs.containsKey(key)) {
                return key;
            }
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Ground the tree's XOR/AND operators in the log — decision-mining lite:
     *
     * <ul>
     *   <li><b>XOR</b>: each branch's observed case share (traces touching the branch's activities
     *       ÷ traces touching any branch). Branch steps get a default-TRUE SpEL routing stub
     *       ({@code #take_<branch> != false} — a missing runData variable is null, so accepted
     *       processes behave exactly as before until an operator sets the flag) plus a
     *       {@code conditionLabel} carrying the share. One CHOICE evidence entry per XOR.
     *       Events carry no data attributes yet, so guard PREDICATES cannot be mined — shares and
     *       editable stubs are what the log honestly supports.</li>
     *   <li><b>AND</b>: cross-branch activity pairs get PARALLEL evidence with cross-trace order
     *       statistics (a-first / b-first / simultaneous by first occurrence) — with point
     *       timestamps, order instability across cases is the corroborating signal for real
     *       concurrency (interval overlap needs start+end times we don't have).</li>
     * </ul>
     *
     * Pre-order walk: an outer XOR conditions a step before any nested XOR can (first wins).
     */
    private static void annotateOperatorSemantics(ProcessTreeNode node, EventLog log,
                                                  Map<String, SuggestedStep> stepsByLabel,
                                                  List<StructuredEvidence> evidence,
                                                  double guardMinAccuracy) {
        if (node.operator() == Operator.XOR && node.children().size() > 1) {
            annotateChoice(node, log, stepsByLabel, evidence, guardMinAccuracy);
        } else if (node.operator() == Operator.AND && node.children().size() > 1) {
            annotateParallel(node, log, evidence);
        }
        for (ProcessTreeNode child : node.children()) {
            annotateOperatorSemantics(child, log, stepsByLabel, evidence, guardMinAccuracy);
        }
    }

    private static void annotateChoice(ProcessTreeNode node, EventLog log,
                                       Map<String, SuggestedStep> stepsByLabel,
                                       List<StructuredEvidence> evidence,
                                       double guardMinAccuracy) {
        // Branch → distinct activity labels; activities claimed by MORE than one branch are
        // ambiguous and never conditioned.
        List<LinkedHashSet<String>> branchActivities = new ArrayList<>();
        Map<String, Integer> claimCounts = new LinkedHashMap<>();
        for (ProcessTreeNode child : node.children()) {
            LinkedHashSet<String> labels = new LinkedHashSet<>();
            for (ProcessTreeNode leaf : orderedActivities(child)) {
                labels.add(leaf.activity());
            }
            branchActivities.add(labels);
            labels.forEach(l -> claimCounts.merge(l, 1, Integer::sum));
        }

        // Observed shares: which branch did each trace take?
        long[] branchCases = new long[branchActivities.size()];
        long totalCases = 0;
        for (Trace t : log.traces()) {
            Set<String> traceActivities = new LinkedHashSet<>(t.activitySequence());
            boolean touched = false;
            for (int i = 0; i < branchActivities.size(); i++) {
                if (branchActivities.get(i).stream().anyMatch(traceActivities::contains)) {
                    branchCases[i]++;
                    touched = true;
                }
            }
            if (touched) {
                totalCases++;
            }
        }
        if (totalCases == 0) {
            return;
        }

        // Decision mining over the crawl-lifted event attributes: per case, first value per key;
        // per branch, the best one-vs-rest decision stump. A guard only ships when it separates
        // the decision cases at ≥ guardMinAccuracy — and it composes NULL-SAFELY with the manual
        // take-flag (a case without the attribute always runs).
        Map<String, Map<String, Object>> caseAttributes = new LinkedHashMap<>();
        Map<String, Integer> caseBranch = new LinkedHashMap<>(); // caseId → sole branch index
        for (Trace t : log.traces()) {
            Set<String> traceActivities = new LinkedHashSet<>(t.activitySequence());
            int branch = -1;
            for (int i = 0; i < branchActivities.size(); i++) {
                if (branchActivities.get(i).stream().anyMatch(traceActivities::contains)) {
                    branch = branch == -1 ? i : -2; // -2 = ambiguous (touched several branches)
                }
            }
            if (branch < 0) {
                continue;
            }
            caseBranch.put(t.caseId(), branch);
            Map<String, Object> attributes = new LinkedHashMap<>();
            for (Event e : t.ordered()) {
                for (Map.Entry<String, Object> attr : e.attributes().entrySet()) {
                    attributes.putIfAbsent(attr.getKey(), attr.getValue());
                }
            }
            caseAttributes.put(t.caseId(), attributes);
        }

        List<String> branchSummaries = new ArrayList<>();
        for (int i = 0; i < branchActivities.size(); i++) {
            LinkedHashSet<String> labels = branchActivities.get(i);
            if (labels.isEmpty()) {
                continue;
            }
            String lead = labels.iterator().next();
            GuardStump guard = mineGuardStump(i, caseBranch, caseAttributes, guardMinAccuracy);
            branchSummaries.add(String.format("%s (%d/%d)%s", lead, branchCases[i], totalCases,
                    guard != null ? " when " + guard.display() : ""));
            String flag = "take_" + spelKey(lead);
            String conditionExpression = "#" + flag + " != false"
                    + (guard != null ? " && (" + guard.spel() + ")" : "");
            String conditionLabel = String.format("Choice: %s branch — observed in %d of %d cases",
                    lead, branchCases[i], totalCases)
                    + (guard != null ? String.format(Locale.ROOT,
                            "; mined guard: %s (separates %d of %d decision cases)",
                            guard.display(), guard.correct(), guard.evaluated()) : "");
            for (String label : labels) {
                SuggestedStep step = stepsByLabel.get(label);
                if (step == null || claimCounts.getOrDefault(label, 0) > 1
                        || step.getConditionExpression() != null) {
                    continue; // ambiguous membership or already conditioned by an outer choice
                }
                step.setConditionExpression(conditionExpression);
                step.setConditionLabel(conditionLabel);
            }
        }
        if (!branchSummaries.isEmpty()) {
            long maxCases = 0;
            for (long c : branchCases) {
                maxCases = Math.max(maxCases, c);
            }
            evidence.add(StructuredEvidence.builder()
                    .type("CHOICE")
                    .description("Choice between " + String.join(", ", branchSummaries)
                            + " — branch steps carry default-true routing stubs (#take_… != false)"
                            + " with null-safe mined guards where the attributes separate the cases")
                    .score((double) maxCases / totalCases)
                    .build());
        }
    }

    /** A mined single-attribute branch guard: the SpEL predicate plus its separation stats. */
    private record GuardStump(String spel, String display, int correct, int evaluated) {
    }

    /**
     * One-vs-rest decision stump for a branch over the decision cases' attributes. Numeric
     * attributes get the best midpoint threshold; categoricals the best equality test. A key
     * qualifies only when it is a valid SpEL identifier, present in at least half the decision
     * cases, and has ≥2 cases on each side; the winning stump must reach {@code minAccuracy}
     * over the cases where the attribute is present. The emitted SpEL is null- AND type-safe:
     * missing or non-comparable runData values always pass (the step runs).
     */
    private static GuardStump mineGuardStump(int branch, Map<String, Integer> caseBranch,
                                             Map<String, Map<String, Object>> caseAttributes,
                                             double minAccuracy) {
        int decisionCases = caseBranch.size();
        if (decisionCases < 4) {
            return null; // too few cases for a separation claim
        }
        // TreeMap: attribute iteration order decides ties between equally-separating stumps —
        // lexicographic keys keep re-mines deterministic (Event.attributes loses insertion order).
        Map<String, Map<String, Object>> byKey = new TreeMap<>(); // key → caseId → value
        for (Map.Entry<String, Map<String, Object>> entry : caseAttributes.entrySet()) {
            for (Map.Entry<String, Object> attr : entry.getValue().entrySet()) {
                if (attr.getKey().matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                    byKey.computeIfAbsent(attr.getKey(), k -> new LinkedHashMap<>())
                            .put(entry.getKey(), attr.getValue());
                }
            }
        }
        GuardStump best = null;
        for (Map.Entry<String, Map<String, Object>> keyEntry : byKey.entrySet()) {
            Map<String, Object> values = keyEntry.getValue();
            if (values.size() * 2 < decisionCases) {
                continue; // attribute too sparse to route on
            }
            long inBranch = values.keySet().stream().filter(c -> caseBranch.get(c) == branch).count();
            long outBranch = values.size() - inBranch;
            if (inBranch < 2 || outBranch < 2) {
                continue;
            }
            GuardStump candidate = bestStumpForKey(keyEntry.getKey(), values, caseBranch, branch);
            if (candidate != null
                    && candidate.correct() >= minAccuracy * candidate.evaluated()
                    && (best == null || candidate.correct() > best.correct())) {
                best = candidate;
            }
        }
        return best;
    }

    private static GuardStump bestStumpForKey(String key, Map<String, Object> values,
                                              Map<String, Integer> caseBranch, int branch) {
        // Numeric stump: best midpoint threshold when every present value parses as a number.
        List<double[]> numeric = new ArrayList<>(); // {value, inBranch?1:0}
        boolean allNumeric = true;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Double parsed = asNumber(e.getValue());
            if (parsed == null) {
                allNumeric = false;
                break;
            }
            numeric.add(new double[]{parsed, caseBranch.get(e.getKey()) == branch ? 1 : 0});
        }
        if (allNumeric && numeric.size() >= 4) {
            numeric.sort((x, y) -> Double.compare(x[0], y[0]));
            GuardStump best = null;
            for (int i = 0; i + 1 < numeric.size(); i++) {
                if (numeric.get(i)[0] == numeric.get(i + 1)[0]) {
                    continue;
                }
                double threshold = (numeric.get(i)[0] + numeric.get(i + 1)[0]) / 2.0;
                int belowIn = 0;
                int belowOut = 0;
                int aboveIn = 0;
                int aboveOut = 0;
                for (double[] v : numeric) {
                    if (v[0] <= threshold) {
                        if (v[1] > 0) belowIn++; else belowOut++;
                    } else {
                        if (v[1] > 0) aboveIn++; else aboveOut++;
                    }
                }
                boolean branchBelow = belowIn + aboveOut >= aboveIn + belowOut;
                int correct = branchBelow ? belowIn + aboveOut : aboveIn + belowOut;
                String op = branchBelow ? "<=" : ">=";
                String spel = String.format(Locale.ROOT,
                        "#%s == null || !(#%s instanceof T(java.lang.Number)) || #%s %s %s",
                        key, key, key, op, trimNumber(threshold));
                String display = String.format(Locale.ROOT, "%s %s %s",
                        key, branchBelow ? "≤" : "≥", trimNumber(threshold));
                if (best == null || correct > best.correct()) {
                    best = new GuardStump(spel, display, correct, numeric.size());
                }
            }
            return best;
        }
        // Categorical stump: best equality test over the distinct values (capped).
        Map<Object, long[]> byValue = new LinkedHashMap<>(); // value → {inBranch, outBranch}
        for (Map.Entry<String, Object> e : values.entrySet()) {
            long[] counts = byValue.computeIfAbsent(e.getValue(), v -> new long[2]);
            counts[caseBranch.get(e.getKey()) == branch ? 0 : 1]++;
        }
        if (byValue.size() < 2 || byValue.size() > 12) {
            return null; // constant (separates nothing) or id-like (separates everything vacuously)
        }
        long inTotal = values.keySet().stream().filter(c -> caseBranch.get(c) == branch).count();
        long outTotal = values.size() - inTotal;
        GuardStump best = null;
        for (Map.Entry<Object, long[]> e : byValue.entrySet()) {
            long correct = e.getValue()[0] + (outTotal - e.getValue()[1]);
            String literal = e.getKey() instanceof Boolean
                    ? e.getKey().toString()
                    : "'" + e.getKey().toString().replace("'", "''") + "'";
            String spel = String.format("#%s == null || #%s == %s", key, key, literal);
            String display = key + " = " + e.getKey();
            if (best == null || correct > best.correct()) {
                best = new GuardStump(spel, display, (int) correct, values.size());
            }
        }
        return best;
    }

    private static Double asNumber(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Render 5000.0 as "5000.0" but avoid scientific notation and trailing noise. */
    private static String trimNumber(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.format(Locale.ROOT, "%.1f", v);
        }
        return String.format(Locale.ROOT, "%s", v);
    }

    private static void annotateParallel(ProcessTreeNode node, EventLog log,
                                         List<StructuredEvidence> evidence) {
        List<List<String>> branches = new ArrayList<>();
        for (ProcessTreeNode child : node.children()) {
            List<String> labels = new ArrayList<>(new LinkedHashSet<>(
                    orderedActivities(child).stream().map(ProcessTreeNode::activity).toList()));
            if (!labels.isEmpty()) {
                branches.add(labels);
            }
        }
        Map<String, Map<String, ActivityIntervals.Interval>> intervalsByCase =
                ActivityIntervals.ofLog(log.traces());
        int pairBudget = 8; // evidence, not an index — a few representative pairs suffice
        for (int i = 0; i < branches.size() && pairBudget > 0; i++) {
            for (int j = i + 1; j < branches.size() && pairBudget > 0; j++) {
                for (String a : branches.get(i)) {
                    for (String b : branches.get(j)) {
                        if (pairBudget-- <= 0) {
                            break;
                        }
                        long aFirst = 0;
                        long bFirst = 0;
                        long overlaps = 0;
                        for (Trace t : log.traces()) {
                            Map<String, ActivityIntervals.Interval> intervals =
                                    intervalsByCase.getOrDefault(t.caseId(), Map.of());
                            ActivityIntervals.Interval ia = intervals.get(a);
                            ActivityIntervals.Interval ib = intervals.get(b);
                            if (ia == null || ib == null) {
                                continue;
                            }
                            switch (ia.orderVs(ib)) {
                                case ORDERED -> aFirst++;
                                case REVERSED -> bFirst++;
                                case OVERLAP -> overlaps++;
                                case UNKNOWN -> { /* abstain */ }
                            }
                        }
                        long votes = aFirst + bFirst;
                        // Concurrency corroboration: Allen interval overlap is direct evidence;
                        // order instability across cases (1 = perfectly interleaved) is the
                        // point-timestamp fallback signal. Take the stronger of the two.
                        double instability = votes == 0
                                ? (overlaps > 0 ? 1.0 : 0.0)
                                : 1.0 - (double) Math.abs(aFirst - bFirst) / votes;
                        double overlapShare = (votes + overlaps) == 0
                                ? 0.0 : (double) overlaps / (votes + overlaps);
                        evidence.add(StructuredEvidence.builder()
                                .type("PARALLEL")
                                .description(String.format(
                                        "Parallel: %s ∥ %s — %s first %d×, %s first %d×, intervals overlap %d× "
                                                + "across cases (overlap + order instability corroborate concurrency)",
                                        a, b, a, aFirst, b, bFirst, overlaps))
                                .score(Math.max(instability, overlapShare))
                                .build());
                    }
                }
            }
        }
    }

    /** Activity label → SpEL-safe variable token: lowercase, non-alphanumerics collapsed to '_'. */
    static String spelKey(String label) {
        String key = label == null ? "branch" : label.toLowerCase().replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return key.isBlank() ? "branch" : key;
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
