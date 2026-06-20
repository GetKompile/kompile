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

package ai.kompile.process.discovery.mining.conformance;

import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph.Arc;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import ai.kompile.process.discovery.mining.tree.ProcessTreeNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Footprint-based conformance: derives the directly-follows relation the {@link ProcessTree}
 * <em>permits</em> and compares it to the relation the {@link EventLog} actually exhibits.
 *
 * <p>This is a deterministic, interpretable approximation of alignment-based conformance that needs no
 * replay search: every step is a set operation over directly-follows pairs. Fitness asks "can the model
 * reproduce what we saw?"; precision asks "does the model allow only what we saw?". A flower model
 * scores fitness 1.0 but low precision — exactly the signal that tells a user not to trust it.
 */
public final class ConformanceChecker {

    private ConformanceChecker() {
    }

    public static ConformanceResult check(ProcessTree tree, EventLog log) {
        Footprint model = footprint(tree.root());
        Set<Arc> modelArcs = model.df;

        DirectlyFollowsGraph logDfg = DfgBuilder.build(log);
        Map<Arc, Long> logArcs = logDfg.arcs();

        long total = 0;
        long fit = 0;
        for (Map.Entry<Arc, Long> e : logArcs.entrySet()) {
            total += e.getValue();
            if (modelArcs.contains(e.getKey())) {
                fit += e.getValue();
            }
        }
        double fitness = (total == 0) ? 1.0 : (double) fit / total;

        long intersect = 0;
        for (Arc a : modelArcs) {
            if (logArcs.containsKey(a)) {
                intersect++;
            }
        }
        double precision = modelArcs.isEmpty() ? 1.0 : (double) intersect / modelArcs.size();

        int nodes = countNodes(tree.root());
        int activities = tree.activities().size();
        double simplicity = nodes == 0 ? 1.0 : (double) activities / nodes;

        boolean perfectFit = fitness >= 0.999999;
        return new ConformanceResult(fitness, precision, simplicity, modelArcs.size(), logArcs.size(), perfectFit);
    }

    // ── recursive footprint ──────────────────────────────────────────────────

    /** Behaviour summary of a subtree: where it can start/end, the directly-follows pairs it allows,
     *  and whether it can produce the empty trace (which controls how it concatenates). */
    private record Footprint(Set<String> start, Set<String> end, Set<Arc> df, boolean canBeEmpty) {
    }

    private static Footprint footprint(ProcessTreeNode node) {
        switch (node.operator()) {
            case ACTIVITY -> {
                String a = node.activity();
                return new Footprint(setOf(a), setOf(a), new LinkedHashSet<>(), false);
            }
            case TAU -> {
                return new Footprint(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), true);
            }
            case XOR -> {
                Set<String> start = new LinkedHashSet<>();
                Set<String> end = new LinkedHashSet<>();
                Set<Arc> df = new LinkedHashSet<>();
                boolean empty = false;
                for (ProcessTreeNode c : node.children()) {
                    Footprint f = footprint(c);
                    start.addAll(f.start);
                    end.addAll(f.end);
                    df.addAll(f.df);
                    empty |= f.canBeEmpty;
                }
                return new Footprint(start, end, df, empty);
            }
            case SEQUENCE -> {
                Footprint acc = null;
                for (ProcessTreeNode c : node.children()) {
                    Footprint f = footprint(c);
                    if (acc == null) {
                        acc = f;
                        continue;
                    }
                    Set<Arc> df = new LinkedHashSet<>(acc.df);
                    df.addAll(f.df);
                    crossArcs(df, acc.end, f.start);
                    Set<String> start = new LinkedHashSet<>(acc.start);
                    if (acc.canBeEmpty) {
                        start.addAll(f.start);
                    }
                    Set<String> end = new LinkedHashSet<>(f.end);
                    if (f.canBeEmpty) {
                        end.addAll(acc.end);
                    }
                    acc = new Footprint(start, end, df, acc.canBeEmpty && f.canBeEmpty);
                }
                return acc != null ? acc
                        : new Footprint(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), true);
            }
            case AND -> {
                Set<String> start = new LinkedHashSet<>();
                Set<String> end = new LinkedHashSet<>();
                Set<Arc> df = new LinkedHashSet<>();
                boolean empty = true;
                List<Set<String>> branchActivities = new java.util.ArrayList<>();
                for (ProcessTreeNode c : node.children()) {
                    Footprint f = footprint(c);
                    start.addAll(f.start);
                    end.addAll(f.end);
                    df.addAll(f.df);
                    empty &= f.canBeEmpty;
                    branchActivities.add(activitiesOf(c));
                }
                // Concurrency: any activity of one branch can be directly adjacent to any of another.
                for (int i = 0; i < branchActivities.size(); i++) {
                    for (int j = 0; j < branchActivities.size(); j++) {
                        if (i != j) {
                            crossArcs(df, branchActivities.get(i), branchActivities.get(j));
                        }
                    }
                }
                return new Footprint(start, end, df, empty);
            }
            case LOOP -> {
                List<ProcessTreeNode> children = node.children();
                Footprint body = footprint(children.get(0));
                Set<Arc> df = new LinkedHashSet<>(body.df);
                Set<String> start = new LinkedHashSet<>(body.start);
                Set<String> end = new LinkedHashSet<>(body.end);

                List<Footprint> redos = new java.util.ArrayList<>();
                for (int i = 1; i < children.size(); i++) {
                    Footprint redo = footprint(children.get(i));
                    redos.add(redo);
                    df.addAll(redo.df);
                    crossArcs(df, body.end, redo.start);   // body → redo
                    crossArcs(df, redo.end, body.start);   // redo → body
                    if (body.canBeEmpty) {
                        // an empty body lets redo entries/exits reach the loop boundary directly
                        start.addAll(redo.start);
                        end.addAll(redo.end);
                    }
                }
                if (body.canBeEmpty) {
                    // With an empty body, redo executions chain directly (this is the flower case).
                    for (Footprint ri : redos) {
                        for (Footprint rj : redos) {
                            crossArcs(df, ri.end, rj.start);
                        }
                    }
                }
                return new Footprint(start, end, df, body.canBeEmpty);
            }
            default -> {
                return new Footprint(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), true);
            }
        }
    }

    private static void crossArcs(Set<Arc> df, Set<String> from, Set<String> to) {
        for (String a : from) {
            for (String b : to) {
                df.add(new Arc(a, b));
            }
        }
    }

    private static Set<String> activitiesOf(ProcessTreeNode node) {
        Set<String> acc = new LinkedHashSet<>();
        collectActivities(node, acc);
        return acc;
    }

    private static void collectActivities(ProcessTreeNode node, Set<String> acc) {
        if (node.operator() == ProcessTreeNode.Operator.ACTIVITY) {
            acc.add(node.activity());
            return;
        }
        for (ProcessTreeNode c : node.children()) {
            collectActivities(c, acc);
        }
    }

    private static int countNodes(ProcessTreeNode node) {
        int n = 1;
        for (ProcessTreeNode c : node.children()) {
            n += countNodes(c);
        }
        return n;
    }

    private static Set<String> setOf(String s) {
        Set<String> set = new LinkedHashSet<>();
        set.add(s);
        return set;
    }
}
