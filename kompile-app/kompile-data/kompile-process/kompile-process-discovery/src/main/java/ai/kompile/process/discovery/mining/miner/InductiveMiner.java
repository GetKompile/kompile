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

package ai.kompile.process.discovery.mining.miner;

import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import ai.kompile.process.discovery.mining.tree.ProcessTreeNode;
import ai.kompile.process.discovery.mining.tree.ProcessTreeNode.Operator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Inductive Miner — discovers a sound, block-structured {@link ProcessTree} from an
 * {@link EventLog}, with no LLM and no randomness.
 *
 * <p>It recurses: on the directly-follows graph of the current sub-log it looks for a <em>cut</em> —
 * a way to split the activities under one of four operators — then splits the log accordingly and
 * recurses on each part. The cuts are tried in the canonical order:
 *
 * <ol>
 *   <li><b>exclusive choice (×)</b> — the activities fall into disconnected components;</li>
 *   <li><b>sequence (→)</b> — the activities form a strict reachability order of groups;</li>
 *   <li><b>parallel (∧)</b> — every pair of branches is fully interleaved and each branch starts and
 *       ends a trace;</li>
 *   <li><b>loop (↺)</b> — a body group holding all start/end activities, plus redo groups that only
 *       re-enter through start activities and leave through end activities.</li>
 * </ol>
 *
 * <p>When no cut exists the miner emits a <em>flower</em> model {@code ↺(τ, a1 … an)} — the most
 * permissive sound model — so the result is always sound even on noisy or unstructured logs.
 *
 * <p>The optional {@code noiseThreshold} (0 = classic IM; 0 &lt; t ≤ 1 = the "IMf" infrequent variant)
 * drops directly-follows arcs whose frequency is below {@code t ×} the strongest arc out of the same
 * activity before cuts are computed, which keeps occasional out-of-order events from collapsing the
 * model into a flower.
 */
public final class InductiveMiner implements ProcessMiner {

    private final double noiseThreshold;

    public InductiveMiner() {
        this(0.0);
    }

    public InductiveMiner(double noiseThreshold) {
        this.noiseThreshold = Math.max(0.0, Math.min(1.0, noiseThreshold));
    }

    @Override
    public String algorithmName() {
        return noiseThreshold > 0 ? "InductiveMiner(IMf, noise=" + noiseThreshold + ")" : "InductiveMiner";
    }

    @Override
    public ProcessTree mine(EventLog log) {
        List<List<String>> work = new ArrayList<>();
        for (Trace t : log.traces()) {
            work.add(t.activitySequence());
        }
        return new ProcessTree(mineNode(work));
    }

    // ──────────────────────────────────────────────────────────────────────── recursion

    private ProcessTreeNode mineNode(List<List<String>> log) {
        Set<String> activities = activitiesIn(log);
        if (activities.isEmpty()) {
            return ProcessTreeNode.tau();
        }
        if (activities.size() == 1) {
            return singleActivity(log, activities.iterator().next());
        }

        Dfg dfg = new Dfg(log, noiseThreshold);
        Cut cut = findCut(dfg);
        if (cut != null && cut.isValid()) {
            List<List<List<String>>> subLogs = split(log, cut);
            List<ProcessTreeNode> children = new ArrayList<>(subLogs.size());
            for (List<List<String>> sub : subLogs) {
                children.add(mineNode(sub));
            }
            return operatorNode(cut.operator(), children);
        }
        return flower(activities);
    }

    /**
     * Base case for a sub-log over a single activity {@code a}. Handles empty traces (the activity is
     * optional) and repeats (it loops) exactly as the Inductive Miner specifies.
     */
    private ProcessTreeNode singleActivity(List<List<String>> log, String a) {
        boolean hasEmpty = false;
        boolean hasRepeat = false;
        for (List<String> t : log) {
            if (t.isEmpty()) {
                hasEmpty = true;
            } else if (t.size() > 1) {
                hasRepeat = true;
            }
        }
        ProcessTreeNode act = ProcessTreeNode.activity(a);
        if (!hasEmpty && !hasRepeat) {
            return act;                                                            // a
        }
        if (hasEmpty && !hasRepeat) {
            return ProcessTreeNode.xor(List.of(act, ProcessTreeNode.tau()));       // ×(a, τ)   — optional
        }
        if (!hasEmpty) {
            return ProcessTreeNode.loop(List.of(act, ProcessTreeNode.tau()));      // ↺(a, τ)   — one or more
        }
        return ProcessTreeNode.loop(List.of(ProcessTreeNode.tau(), act));          // ↺(τ, a)   — zero or more
    }

    private ProcessTreeNode operatorNode(Operator op, List<ProcessTreeNode> children) {
        return switch (op) {
            case SEQUENCE -> ProcessTreeNode.sequence(children);
            case XOR -> ProcessTreeNode.xor(children);
            case AND -> ProcessTreeNode.and(children);
            case LOOP -> ProcessTreeNode.loop(children);
            default -> throw new IllegalStateException("Unexpected cut operator: " + op);
        };
    }

    /** Flower model: {@code ↺(τ, a1, …, an)} — any activity, any number of times, in any order. */
    private ProcessTreeNode flower(Set<String> activities) {
        List<ProcessTreeNode> children = new ArrayList<>();
        children.add(ProcessTreeNode.tau());
        for (String a : activities) {
            children.add(ProcessTreeNode.activity(a));
        }
        return ProcessTreeNode.loop(children);
    }

    // ──────────────────────────────────────────────────────────────────────── cut detection

    private Cut findCut(Dfg dfg) {
        Cut c = xorCut(dfg);
        if (c != null) {
            return c;
        }
        c = sequenceCut(dfg);
        if (c != null) {
            return c;
        }
        c = parallelCut(dfg);
        if (c != null) {
            return c;
        }
        return loopCut(dfg);
    }

    /** Exclusive choice: ≥2 connected components in the undirected directly-follows graph. */
    private Cut xorCut(Dfg dfg) {
        UnionFind uf = new UnionFind(dfg.activities);
        for (String a : dfg.activities) {
            for (String b : dfg.successors(a)) {
                uf.union(a, b);
            }
        }
        List<Set<String>> components = uf.components();
        return components.size() >= 2 ? new Cut(Operator.XOR, components) : null;
    }

    /**
     * Sequence: group activities by the equivalence {@code a ~ b iff (a⇝b ∧ b⇝a) ∨ (a⇝̸b ∧ b⇝̸a)},
     * then order the groups so every earlier group can reach every later one but not vice versa.
     */
    private Cut sequenceCut(Dfg dfg) {
        Map<String, Set<String>> reach = transitiveClosure(dfg);
        List<String> acts = new ArrayList<>(dfg.activities);
        UnionFind uf = new UnionFind(dfg.activities);
        for (int i = 0; i < acts.size(); i++) {
            for (int j = i + 1; j < acts.size(); j++) {
                String a = acts.get(i);
                String b = acts.get(j);
                boolean ab = reach.get(a).contains(b);
                boolean ba = reach.get(b).contains(a);
                if ((ab && ba) || (!ab && !ba)) {
                    uf.union(a, b);
                }
            }
        }
        List<Set<String>> groups = uf.components();
        if (groups.size() < 2) {
            return null;
        }
        // Order groups by the strict reachability relation between them.
        groups.sort((g1, g2) -> {
            boolean r12 = groupReaches(g1, g2, reach);
            boolean r21 = groupReaches(g2, g1, reach);
            if (r12 && !r21) {
                return -1;
            }
            if (r21 && !r12) {
                return 1;
            }
            return 0;
        });
        // Validate that the sorted order is a genuine strict total order.
        for (int i = 0; i < groups.size(); i++) {
            for (int j = i + 1; j < groups.size(); j++) {
                if (!groupReaches(groups.get(i), groups.get(j), reach)
                        || groupReaches(groups.get(j), groups.get(i), reach)) {
                    return null;
                }
            }
        }
        return new Cut(Operator.SEQUENCE, groups);
    }

    /**
     * Parallel: components of the <em>complement</em> graph (connect a—b when they are <em>not</em>
     * mutually directly-following). Every branch must contain a start and an end activity, since all
     * branches of a concurrency block begin and finish together.
     */
    private Cut parallelCut(Dfg dfg) {
        List<String> acts = new ArrayList<>(dfg.activities);
        UnionFind uf = new UnionFind(dfg.activities);
        for (int i = 0; i < acts.size(); i++) {
            for (int j = i + 1; j < acts.size(); j++) {
                String a = acts.get(i);
                String b = acts.get(j);
                boolean both = dfg.follows(a, b) && dfg.follows(b, a);
                if (!both) {
                    uf.union(a, b);
                }
            }
        }
        List<Set<String>> components = uf.components();
        if (components.size() < 2) {
            return null;
        }
        for (Set<String> branch : components) {
            if (Collections.disjoint(branch, dfg.start) || Collections.disjoint(branch, dfg.end)) {
                return null;
            }
        }
        return new Cut(Operator.AND, components);
    }

    /**
     * Loop: the body holds all start and end activities; redo parts are the remaining components that
     * are only entered from an end activity and only leave to a start activity. Components that violate
     * those conditions are absorbed into the body, matching the standard construction.
     */
    private Cut loopCut(Dfg dfg) {
        if (dfg.start.isEmpty() || dfg.end.isEmpty()) {
            return null;
        }
        Set<String> body = new LinkedHashSet<>();
        body.addAll(dfg.start);
        body.addAll(dfg.end);
        Set<String> rest = new LinkedHashSet<>(dfg.activities);
        rest.removeAll(body);
        List<Set<String>> redo = connectedComponents(dfg, rest);

        boolean changed = true;
        while (changed) {
            changed = false;
            List<Set<String>> stillRedo = new ArrayList<>();
            for (Set<String> part : redo) {
                if (isValidRedo(part, body, dfg)) {
                    stillRedo.add(part);
                } else {
                    body.addAll(part);
                    changed = true;
                }
            }
            if (changed) {
                rest = new LinkedHashSet<>(dfg.activities);
                rest.removeAll(body);
                redo = connectedComponents(dfg, rest);
            } else {
                redo = stillRedo;
            }
        }
        if (redo.isEmpty()) {
            return null;
        }
        List<Set<String>> partitions = new ArrayList<>();
        partitions.add(body);
        partitions.addAll(redo);
        return new Cut(Operator.LOOP, partitions);
    }

    private boolean isValidRedo(Set<String> part, Set<String> body, Dfg dfg) {
        // Every edge from the body into the redo part must leave from an end activity.
        for (String a : body) {
            for (String b : dfg.successors(a)) {
                if (part.contains(b) && !dfg.end.contains(a)) {
                    return false;
                }
            }
        }
        // Every edge from the redo part back into the body must arrive at a start activity.
        for (String a : part) {
            for (String b : dfg.successors(a)) {
                if (body.contains(b) && !dfg.start.contains(b)) {
                    return false;
                }
            }
        }
        // The redo part must actually be reachable from an end activity and lead back to a start.
        boolean entered = false;
        for (String a : dfg.end) {
            for (String b : dfg.successors(a)) {
                if (part.contains(b)) {
                    entered = true;
                }
            }
        }
        boolean exits = false;
        for (String a : part) {
            for (String b : dfg.successors(a)) {
                if (dfg.start.contains(b)) {
                    exits = true;
                }
            }
        }
        return entered && exits;
    }

    // ──────────────────────────────────────────────────────────────────────── log splitting

    private List<List<List<String>>> split(List<List<String>> log, Cut cut) {
        return switch (cut.operator()) {
            case XOR -> splitExclusive(log, cut.partitions());
            case SEQUENCE, AND -> splitProjection(log, cut.partitions());
            case LOOP -> splitLoop(log, cut.partitions());
            default -> throw new IllegalStateException("Unexpected cut operator: " + cut.operator());
        };
    }

    /** Each trace is routed whole to the branch that owns its first activity. */
    private List<List<List<String>>> splitExclusive(List<List<String>> log, List<Set<String>> parts) {
        Map<String, Integer> owner = ownerIndex(parts);
        List<List<List<String>>> subLogs = emptySubLogs(parts.size());
        for (List<String> trace : log) {
            if (trace.isEmpty()) {
                subLogs.get(0).add(new ArrayList<>());
                continue;
            }
            int idx = owner.getOrDefault(trace.get(0), 0);
            subLogs.get(idx).add(new ArrayList<>(trace));
        }
        return subLogs;
    }

    /** Sequence and parallel both split by projecting every trace onto each partition, preserving order. */
    private List<List<List<String>>> splitProjection(List<List<String>> log, List<Set<String>> parts) {
        List<List<List<String>>> subLogs = emptySubLogs(parts.size());
        for (List<String> trace : log) {
            for (int i = 0; i < parts.size(); i++) {
                Set<String> part = parts.get(i);
                List<String> projected = new ArrayList<>();
                for (String a : trace) {
                    if (part.contains(a)) {
                        projected.add(a);
                    }
                }
                subLogs.get(i).add(projected);
            }
        }
        return subLogs;
    }

    /** Cut each trace into maximal runs that stay within one partition; runs feed that partition's sub-log. */
    private List<List<List<String>>> splitLoop(List<List<String>> log, List<Set<String>> parts) {
        Map<String, Integer> owner = ownerIndex(parts);
        List<List<List<String>>> subLogs = emptySubLogs(parts.size());
        for (List<String> trace : log) {
            if (trace.isEmpty()) {
                subLogs.get(0).add(new ArrayList<>());
                continue;
            }
            int current = owner.getOrDefault(trace.get(0), 0);
            List<String> segment = new ArrayList<>();
            for (String a : trace) {
                int idx = owner.getOrDefault(a, 0);
                if (idx == current) {
                    segment.add(a);
                } else {
                    subLogs.get(current).add(segment);
                    segment = new ArrayList<>();
                    segment.add(a);
                    current = idx;
                }
            }
            subLogs.get(current).add(segment);
        }
        return subLogs;
    }

    // ──────────────────────────────────────────────────────────────────────── helpers

    private static Set<String> activitiesIn(List<List<String>> log) {
        Set<String> acts = new LinkedHashSet<>();
        for (List<String> t : log) {
            acts.addAll(t);
        }
        return acts;
    }

    private static Map<String, Integer> ownerIndex(List<Set<String>> parts) {
        Map<String, Integer> owner = new LinkedHashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            for (String a : parts.get(i)) {
                owner.put(a, i);
            }
        }
        return owner;
    }

    private static List<List<List<String>>> emptySubLogs(int n) {
        List<List<List<String>>> subLogs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            subLogs.add(new ArrayList<>());
        }
        return subLogs;
    }

    private boolean groupReaches(Set<String> from, Set<String> to, Map<String, Set<String>> reach) {
        for (String a : from) {
            Set<String> r = reach.get(a);
            for (String b : to) {
                if (r.contains(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, Set<String>> transitiveClosure(Dfg dfg) {
        Map<String, Set<String>> reach = new LinkedHashMap<>();
        for (String a : dfg.activities) {
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> stack = new ArrayDeque<>(dfg.successors(a));
            while (!stack.isEmpty()) {
                String x = stack.pop();
                if (seen.add(x)) {
                    for (String y : dfg.successors(x)) {
                        if (!seen.contains(y)) {
                            stack.push(y);
                        }
                    }
                }
            }
            reach.put(a, seen);
        }
        return reach;
    }

    /** Connected components (undirected directly-follows) restricted to {@code subset}. */
    private List<Set<String>> connectedComponents(Dfg dfg, Set<String> subset) {
        UnionFind uf = new UnionFind(subset);
        for (String a : subset) {
            for (String b : dfg.successors(a)) {
                if (subset.contains(b)) {
                    uf.union(a, b);
                }
            }
            for (String b : dfg.predecessors(a)) {
                if (subset.contains(b)) {
                    uf.union(a, b);
                }
            }
        }
        return uf.components();
    }

    // ──────────────────────────────────────────────────────────────────────── inner types

    /**
     * A directly-follows view over one sub-log: directly-follows successor sets (optionally
     * frequency-filtered for IMf), plus start and end activity sets.
     */
    private static final class Dfg {
        final Set<String> activities = new LinkedHashSet<>();
        final Map<String, Set<String>> succ = new LinkedHashMap<>();
        final Set<String> start = new LinkedHashSet<>();
        final Set<String> end = new LinkedHashSet<>();

        Dfg(List<List<String>> log, double noiseThreshold) {
            Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
            for (List<String> t : log) {
                if (t.isEmpty()) {
                    continue;
                }
                activities.addAll(t);
                start.add(t.get(0));
                end.add(t.get(t.size() - 1));
                for (int i = 0; i + 1 < t.size(); i++) {
                    counts.computeIfAbsent(t.get(i), k -> new LinkedHashMap<>())
                            .merge(t.get(i + 1), 1L, Long::sum);
                }
            }
            for (String a : activities) {
                succ.put(a, new LinkedHashSet<>());
            }
            for (Map.Entry<String, Map<String, Long>> e : counts.entrySet()) {
                long max = 0;
                for (long c : e.getValue().values()) {
                    max = Math.max(max, c);
                }
                long minKeep = (noiseThreshold <= 0) ? 1 : Math.max(1, (long) Math.ceil(noiseThreshold * max));
                for (Map.Entry<String, Long> f : e.getValue().entrySet()) {
                    if (f.getValue() >= minKeep) {
                        succ.get(e.getKey()).add(f.getKey());
                    }
                }
            }
        }

        Set<String> successors(String a) {
            return succ.getOrDefault(a, Set.of());
        }

        Set<String> predecessors(String b) {
            Set<String> preds = new LinkedHashSet<>();
            for (Map.Entry<String, Set<String>> e : succ.entrySet()) {
                if (e.getValue().contains(b)) {
                    preds.add(e.getKey());
                }
            }
            return preds;
        }

        boolean follows(String a, String b) {
            return succ.getOrDefault(a, Set.of()).contains(b);
        }
    }

    /** Minimal union-find over a fixed activity set, returning components in first-seen order. */
    private static final class UnionFind {
        private final Map<String, String> parent = new LinkedHashMap<>();

        UnionFind(Set<String> elements) {
            for (String e : elements) {
                parent.put(e, e);
            }
        }

        String find(String x) {
            String root = x;
            while (!root.equals(parent.get(root))) {
                root = parent.get(root);
            }
            while (!x.equals(root)) {
                String next = parent.get(x);
                parent.put(x, root);
                x = next;
            }
            return root;
        }

        void union(String a, String b) {
            if (!parent.containsKey(a) || !parent.containsKey(b)) {
                return;
            }
            String ra = find(a);
            String rb = find(b);
            if (!ra.equals(rb)) {
                parent.put(ra, rb);
            }
        }

        List<Set<String>> components() {
            Map<String, Set<String>> byRoot = new LinkedHashMap<>();
            for (String e : parent.keySet()) {
                byRoot.computeIfAbsent(find(e), k -> new LinkedHashSet<>()).add(e);
            }
            return new ArrayList<>(byRoot.values());
        }
    }
}
