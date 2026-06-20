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
 */
public final class ProcessTreeToSuggestion {

    private ProcessTreeToSuggestion() {
    }

    public static ProcessSuggestion convert(ProcessTree tree, EventLog log, String processName) {
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
        int idx = 1;
        for (ProcessTreeNode phaseRoot : phaseRoots) {
            List<SuggestedStep> steps = new ArrayList<>();
            LocalDateTime phaseEarliest = null;
            LocalDateTime phaseLatest = null;
            for (ProcessTreeNode leaf : orderedActivities(phaseRoot)) {
                String label = leaf.activity();
                LocalDateTime when = labelToEarliest.get(label);
                steps.add(SuggestedStep.builder()
                        .name(label)
                        .stepType(inferStepType(label))
                        .description("Discovered activity \"" + label + "\"")
                        .graphNodeIds(new ArrayList<>(labelToNodeIds.getOrDefault(label, List.of())))
                        .occurredAt(when)
                        .build());
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
        // Confidence is earned, not guessed: a flower model fits perfectly but is imprecise, so its
        // fitness × precision is low — which is exactly how much it should be trusted.
        double confidence = Math.max(0.1, conformance.fitness() * conformance.precision());

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
                .build();
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
