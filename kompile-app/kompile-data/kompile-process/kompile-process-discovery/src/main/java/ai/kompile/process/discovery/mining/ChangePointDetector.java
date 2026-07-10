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

import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Within-log change-POINT detection — the piece recency decay deliberately does not do. Decay
 * makes the verdicts reflect the process as it is NOW; this explains WHEN it stopped being what
 * it was: the log's dated traces are ordered by start time, every split with both windows at
 * least {@code minWindowCases} deep is scored by how many WELL-SUPPORTED structural differences
 * it separates (activities and directly-follows arcs present ≥{@link #MIN_SUPPORT}× on one side
 * and absent on the other), and the strongest split is reported with its differences.
 *
 * <p>Deliberately conservative: a difference must be completely absent on the other side —
 * frequency wobbles are noise, not drift. Deterministic (ties prefer the most balanced split,
 * then the earliest). Pure and DFG-level (no per-split Inductive Miner runs) — O(splits × events).
 */
public final class ChangePointDetector {

    /** A structural difference must appear at least this many times on its side of the split. */
    static final int MIN_SUPPORT = 2;
    /** Report at most this many difference lines (never silently — the count says how many more). */
    static final int MAX_REPORTED_CHANGES = 6;

    private ChangePointDetector() {
    }

    /**
     * The strongest change point found.
     *
     * @param splitAt     start time of the first trace AFTER the change
     * @param beforeCases dated traces before the split
     * @param afterCases  dated traces from the split on
     * @param changes     human-readable structural differences (capped at
     *                    {@link #MAX_REPORTED_CHANGES}; {@code totalChanges} carries the rest)
     * @param totalChanges every well-supported difference the split separates
     */
    public record ChangePoint(LocalDateTime splitAt, int beforeCases, int afterCases,
                              List<String> changes, int totalChanges) {
    }

    /**
     * Detect the strongest change point in the log's dated traces.
     *
     * @param log            the (cluster's) event log
     * @param minWindowCases min dated traces each side of a candidate split
     * @return the change point, or empty when no split separates any well-supported difference
     */
    public static Optional<ChangePoint> detect(EventLog log, int minWindowCases) {
        if (log == null || minWindowCases < 1) {
            return Optional.empty();
        }
        List<Trace> dated = new ArrayList<>();
        for (Trace trace : log.traces()) {
            if (startOf(trace) != null) {
                dated.add(trace);
            }
        }
        if (dated.size() < 2L * minWindowCases) {
            return Optional.empty();
        }
        dated.sort((a, b) -> startOf(a).compareTo(startOf(b)));

        ChangePoint best = null;
        for (int split = minWindowCases; split + minWindowCases <= dated.size(); split++) {
            List<Trace> before = dated.subList(0, split);
            List<Trace> after = dated.subList(split, dated.size());
            List<String> changes = structuralDifferences(before, after);
            if (changes.isEmpty()) {
                continue;
            }
            ChangePoint candidate = new ChangePoint(
                    startOf(after.get(0)), before.size(), after.size(),
                    changes.size() > MAX_REPORTED_CHANGES
                            ? List.copyOf(changes.subList(0, MAX_REPORTED_CHANGES)) : List.copyOf(changes),
                    changes.size());
            if (best == null || wins(candidate, best)) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean wins(ChangePoint candidate, ChangePoint best) {
        if (candidate.totalChanges() != best.totalChanges()) {
            return candidate.totalChanges() > best.totalChanges();
        }
        int candidateBalance = Math.min(candidate.beforeCases(), candidate.afterCases());
        int bestBalance = Math.min(best.beforeCases(), best.afterCases());
        if (candidateBalance != bestBalance) {
            return candidateBalance > bestBalance;
        }
        return candidate.splitAt().isBefore(best.splitAt());
    }

    /** Activities and DF arcs well-supported on exactly one side of the split. */
    private static List<String> structuralDifferences(List<Trace> before, List<Trace> after) {
        List<String> changes = new ArrayList<>();

        Map<String, Long> activityBefore = activityTraceSupport(before);
        Map<String, Long> activityAfter = activityTraceSupport(after);
        for (Map.Entry<String, Long> entry : activityAfter.entrySet()) {
            if (entry.getValue() >= MIN_SUPPORT && !activityBefore.containsKey(entry.getKey())) {
                changes.add(String.format("activity '%s' appears (0 → %d cases)",
                        entry.getKey(), entry.getValue()));
            }
        }
        for (Map.Entry<String, Long> entry : activityBefore.entrySet()) {
            if (entry.getValue() >= MIN_SUPPORT && !activityAfter.containsKey(entry.getKey())) {
                changes.add(String.format("activity '%s' disappears (%d → 0 cases)",
                        entry.getKey(), entry.getValue()));
            }
        }

        DirectlyFollowsGraph dfgBefore = DfgBuilder.build(new EventLog(before));
        DirectlyFollowsGraph dfgAfter = DfgBuilder.build(new EventLog(after));
        Map<DirectlyFollowsGraph.Arc, Long> arcsBefore = dfgBefore.arcs();
        Map<DirectlyFollowsGraph.Arc, Long> arcsAfter = dfgAfter.arcs();
        for (Map.Entry<DirectlyFollowsGraph.Arc, Long> entry : arcsAfter.entrySet()) {
            if (entry.getValue() >= MIN_SUPPORT && !arcsBefore.containsKey(entry.getKey())
                    && bothActivitiesOnBothSides(entry.getKey(), activityBefore, activityAfter)) {
                changes.add(String.format("flow '%s → %s' appears (0 → %d)",
                        entry.getKey().from(), entry.getKey().to(), entry.getValue()));
            }
        }
        for (Map.Entry<DirectlyFollowsGraph.Arc, Long> entry : arcsBefore.entrySet()) {
            if (entry.getValue() >= MIN_SUPPORT && !arcsAfter.containsKey(entry.getKey())
                    && bothActivitiesOnBothSides(entry.getKey(), activityBefore, activityAfter)) {
                changes.add(String.format("flow '%s → %s' disappears (%d → 0)",
                        entry.getKey().from(), entry.getKey().to(), entry.getValue()));
            }
        }
        return changes;
    }

    /**
     * An arc difference only counts when BOTH endpoints exist on both sides — otherwise it is the
     * same information as the activity-level appearance/disappearance already reported.
     */
    private static boolean bothActivitiesOnBothSides(DirectlyFollowsGraph.Arc arc,
                                                     Map<String, Long> activityBefore,
                                                     Map<String, Long> activityAfter) {
        return activityBefore.containsKey(arc.from()) && activityAfter.containsKey(arc.from())
                && activityBefore.containsKey(arc.to()) && activityAfter.containsKey(arc.to());
    }

    /** Activity → number of traces containing it. */
    private static Map<String, Long> activityTraceSupport(List<Trace> traces) {
        Map<String, Long> support = new LinkedHashMap<>();
        for (Trace trace : traces) {
            for (String activity : new LinkedHashSet<>(trace.activitySequence())) {
                support.merge(activity, 1L, Long::sum);
            }
        }
        return support;
    }

    /** First dated event's timestamp, or null for an undated trace. */
    private static LocalDateTime startOf(Trace trace) {
        for (Event event : trace.ordered()) {
            if (event.timestamp() != null) {
                return event.timestamp();
            }
        }
        return null;
    }
}
