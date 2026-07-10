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

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.process.discovery.mining.log.ActivityIntervals;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Surfaces CONFLICTING DESCRIPTIONS of the same process — two sources whose traces order the same
 * pair of steps in opposite directions — and proposes an explicitly-GUESSED reconciliation.
 *
 * <p>This is deliberately distinct from the machinery around it: temporal refutation handles a
 * MINORITY of noise votes (the majority silently wins); drift handles a change OVER TIME; this
 * handles two live, well-supported, disagreeing accounts. The verdict never picks a winner
 * silently — both sides surface with their source attribution, and the reconciliation is labeled
 * a guess with its basis, because choosing between conflicting departmental realities is the
 * operator's call, not the miner's.
 *
 * <p>The reconciliation basis is chosen by discriminating three situations:
 * <ul>
 *   <li><b>temporal separation</b> — one order's traces all precede the other's: this is DRIFT
 *       wearing a conflict's clothes; the guess is the recent order as current, the old one as
 *       historical.</li>
 *   <li><b>source split</b> — each source is internally consistent (≥ {@link #SOURCE_PURITY}) but
 *       sources disagree: parallel realities (departmental variants, SOP vs practice); the guess
 *       is the majority order as canonical with the minority as a named variant.</li>
 *   <li><b>interleaved</b> — the disagreement runs through the sources themselves: the steps may
 *       simply be order-independent; the guess is the majority order, weakly held.</li>
 * </ul>
 */
public final class ProcessConflictAnalyzer {

    /** Each direction needs at least this many trace votes before a conflict claim. */
    static final int MIN_DIRECTION_VOTES = 2;
    /** A source counts as internally consistent when this share of its votes agree. */
    static final double SOURCE_PURITY = 0.8;
    /** Traces whose events carry no source metadata group here. */
    static final String UNKNOWN_SOURCE = "(unknown source)";

    private ProcessConflictAnalyzer() {
    }

    /** One source's vote split on a conflicted pair. */
    public record SourceSplit(String source, long ordered, long reversed) {
    }

    /**
     * A well-supported two-way disagreement on the order of {@code from} vs {@code to}.
     *
     * @param ordered        traces putting {@code from} first
     * @param reversed       traces putting {@code to} first
     * @param bySource       per-source vote splits (largest sources first)
     * @param reconciliation the GUESSED reconciliation, human-readable, explicitly hedged
     * @param basis          which situation the guess rests on: {@code temporal-separation},
     *                       {@code source-split}, or {@code interleaved}
     * @param canonicalFromFirst the guessed canonical direction ({@code from} first?)
     * @param reconciledOpinion the reasoning library's own reconciliation: per-source Beta
     *                          opinions on the canonical direction, {@link Opinion#cumulativeFuse}d
     *                          across sources (⊕ — sources are independent evidence). Its
     *                          expectation is how strongly the guess is held; its uncertainty and
     *                          residual disbelief say honestly how contested it remains.
     */
    public record OrderingConflict(String from, String to, long ordered, long reversed,
                                   List<SourceSplit> bySource, String reconciliation,
                                   String basis, boolean canonicalFromFirst,
                                   Opinion reconciledOpinion) {

        /** Majority share of the guessed direction — how strongly the guess is held. */
        public double majorityShare() {
            long total = ordered + reversed;
            return total == 0 ? 0.0 : (double) Math.max(ordered, reversed) / total;
        }
    }

    /**
     * Detect conflicting orderings in the log.
     *
     * @param log           the (cluster's) event log
     * @param sourceByNode  graph node id → source label (crawl provenance); missing ids group as
     *                      {@link #UNKNOWN_SOURCE}
     * @param minorityShare the losing direction must hold at least this share of the votes —
     *                      below it, temporal refutation's silent-majority handling is the right
     *                      tool, not a conflict
     * @return conflicts ordered by total votes (most contested first)
     */
    public static List<OrderingConflict> orderingConflicts(EventLog log,
                                                           Map<String, String> sourceByNode,
                                                           double minorityShare) {
        List<OrderingConflict> out = new ArrayList<>();
        if (log == null || log.isEmpty()) {
            return out;
        }
        Map<String, Map<String, ActivityIntervals.Interval>> intervalsByCase =
                ActivityIntervals.ofLog(log.traces());
        Map<String, String> sourceByCase = new LinkedHashMap<>();
        Map<String, LocalDateTime> startByCase = new LinkedHashMap<>();
        for (Trace trace : log.traces()) {
            sourceByCase.put(trace.caseId(), dominantSource(trace, sourceByNode));
            LocalDateTime start = null;
            for (Event event : trace.ordered()) {
                if (event.timestamp() != null) {
                    start = event.timestamp();
                    break;
                }
            }
            startByCase.put(trace.caseId(), start);
        }

        List<String> activities = new ArrayList<>(log.activityNames());
        for (int i = 0; i < activities.size(); i++) {
            for (int j = i + 1; j < activities.size(); j++) {
                OrderingConflict conflict = conflictFor(activities.get(i), activities.get(j),
                        log, intervalsByCase, sourceByCase, startByCase, minorityShare);
                if (conflict != null) {
                    out.add(conflict);
                }
            }
        }
        out.sort((a, b) -> Long.compare(b.ordered() + b.reversed(), a.ordered() + a.reversed()));
        return out;
    }

    private static OrderingConflict conflictFor(String a, String b, EventLog log,
                                                Map<String, Map<String, ActivityIntervals.Interval>> intervalsByCase,
                                                Map<String, String> sourceByCase,
                                                Map<String, LocalDateTime> startByCase,
                                                double minorityShare) {
        List<String> orderedCases = new ArrayList<>();
        List<String> reversedCases = new ArrayList<>();
        for (Trace trace : log.traces()) {
            Map<String, ActivityIntervals.Interval> intervals =
                    intervalsByCase.getOrDefault(trace.caseId(), Map.of());
            ActivityIntervals.Interval ia = intervals.get(a);
            ActivityIntervals.Interval ib = intervals.get(b);
            if (ia == null || ib == null) {
                continue;
            }
            switch (ia.orderVs(ib)) {
                case ORDERED -> orderedCases.add(trace.caseId());
                case REVERSED -> reversedCases.add(trace.caseId());
                default -> { /* overlap/unknown: concurrency machinery owns those */ }
            }
        }
        long ordered = orderedCases.size();
        long reversed = reversedCases.size();
        long total = ordered + reversed;
        if (ordered < MIN_DIRECTION_VOTES || reversed < MIN_DIRECTION_VOTES
                || Math.min(ordered, reversed) < total * minorityShare) {
            return null;
        }

        // Per-source splits, largest first.
        Map<String, long[]> splits = new LinkedHashMap<>();
        orderedCases.forEach(c -> splits.computeIfAbsent(sourceByCase.get(c), k -> new long[2])[0]++);
        reversedCases.forEach(c -> splits.computeIfAbsent(sourceByCase.get(c), k -> new long[2])[1]++);
        List<SourceSplit> bySource = new ArrayList<>();
        splits.forEach((source, counts) -> bySource.add(new SourceSplit(source, counts[0], counts[1])));
        bySource.sort((x, y) -> Long.compare(y.ordered() + y.reversed(), x.ordered() + x.reversed()));

        boolean canonicalFromFirst = ordered >= reversed;
        String winner = canonicalFromFirst ? a + " → " + b : b + " → " + a;
        String loser = canonicalFromFirst ? b + " → " + a : a + " → " + b;

        String basis;
        String reconciliation;
        if (temporallySeparated(orderedCases, reversedCases, startByCase)) {
            // One order's traces all precede the other's — drift, not a live disagreement.
            boolean reversedIsRecent = latest(reversedCases, startByCase)
                    .isAfter(latest(orderedCases, startByCase));
            String current = reversedIsRecent ? b + " → " + a : a + " → " + b;
            String historical = reversedIsRecent ? a + " → " + b : b + " → " + a;
            basis = "temporal-separation";
            reconciliation = String.format(Locale.ROOT,
                    "GUESS: this is drift, not disagreement — treat '%s' as the current order and "
                            + "'%s' as historical (the two orders never interleave in time)",
                    current, historical);
            canonicalFromFirst = !reversedIsRecent;
        } else if (sourcesSplitCleanly(splits)) {
            basis = "source-split";
            reconciliation = String.format(Locale.ROOT,
                    "GUESS: source-specific variants — treat '%s' as canonical (majority %d vs %d) "
                            + "and '%s' as a named variant of the disagreeing source; confirm with "
                            + "the process owner",
                    winner, Math.max(ordered, reversed), Math.min(ordered, reversed), loser);
        } else {
            basis = "interleaved";
            reconciliation = String.format(Locale.ROOT,
                    "GUESS (weak): the disagreement runs through the sources themselves — the steps "
                            + "may be order-independent; '%s' is the majority order (%d vs %d) if one "
                            + "must be chosen",
                    winner, Math.max(ordered, reversed), Math.min(ordered, reversed));
        }
        // The library's OWN reconciliation of the FINAL guessed direction: each source's votes
        // become a Beta opinion, and independent sources fuse with ⊕ (cumulative fusion). The
        // fused opinion carries the honest residue: expectation for the guess, disbelief for the
        // live opposition, uncertainty for how thin the evidence is.
        Opinion reconciled = null;
        for (long[] counts : splits.values()) {
            long pro = canonicalFromFirst ? counts[0] : counts[1];
            long contra = canonicalFromFirst ? counts[1] : counts[0];
            Opinion sourceOpinion = Opinion.fromBetaEvidence(pro, contra);
            reconciled = reconciled == null ? sourceOpinion : reconciled.cumulativeFuse(sourceOpinion);
        }
        if (reconciled != null) {
            reconciliation += String.format(Locale.ROOT,
                    " — fused opinion across sources: E=%.2f, belief=%.2f, disbelief=%.2f, u=%.2f",
                    reconciled.expectation(), reconciled.belief(),
                    reconciled.disbelief(), reconciled.uncertainty());
        }
        return new OrderingConflict(a, b, ordered, reversed, bySource,
                reconciliation, basis, canonicalFromFirst, reconciled);
    }

    /** True when every vote of one direction precedes every vote of the other. */
    private static boolean temporallySeparated(List<String> orderedCases, List<String> reversedCases,
                                               Map<String, LocalDateTime> startByCase) {
        LocalDateTime latestOrdered = latest(orderedCases, startByCase);
        LocalDateTime earliestOrdered = earliest(orderedCases, startByCase);
        LocalDateTime latestReversed = latest(reversedCases, startByCase);
        LocalDateTime earliestReversed = earliest(reversedCases, startByCase);
        if (latestOrdered == null || latestReversed == null
                || earliestOrdered == null || earliestReversed == null) {
            return false;
        }
        return latestOrdered.isBefore(earliestReversed) || latestReversed.isBefore(earliestOrdered);
    }

    /** True when every source with enough votes is internally ≥{@link #SOURCE_PURITY} consistent. */
    private static boolean sourcesSplitCleanly(Map<String, long[]> splits) {
        int decisiveSources = 0;
        for (long[] counts : splits.values()) {
            long total = counts[0] + counts[1];
            if (total < MIN_DIRECTION_VOTES) {
                continue;
            }
            if ((double) Math.max(counts[0], counts[1]) / total < SOURCE_PURITY) {
                return false;
            }
            decisiveSources++;
        }
        return decisiveSources >= 2;
    }

    private static LocalDateTime latest(List<String> cases, Map<String, LocalDateTime> startByCase) {
        LocalDateTime latest = null;
        for (String caseId : cases) {
            LocalDateTime start = startByCase.get(caseId);
            if (start != null && (latest == null || start.isAfter(latest))) {
                latest = start;
            }
        }
        return latest;
    }

    private static LocalDateTime earliest(List<String> cases, Map<String, LocalDateTime> startByCase) {
        LocalDateTime earliest = null;
        for (String caseId : cases) {
            LocalDateTime start = startByCase.get(caseId);
            if (start != null && (earliest == null || start.isBefore(earliest))) {
                earliest = start;
            }
        }
        return earliest;
    }

    /** A trace's source = the most common source among its events' graph nodes. */
    static String dominantSource(Trace trace, Map<String, String> sourceByNode) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Event event : trace.ordered()) {
            String source = event.graphNodeId() != null
                    ? sourceByNode.getOrDefault(event.graphNodeId(), UNKNOWN_SOURCE)
                    : UNKNOWN_SOURCE;
            counts.merge(source, 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .max((x, y) -> {
                    int byCount = Long.compare(x.getValue(), y.getValue());
                    return byCount != 0 ? byCount : y.getKey().compareTo(x.getKey());
                })
                .map(Map.Entry::getKey)
                .orElse(UNKNOWN_SOURCE);
    }

    /** Human-readable one-liner for a conflict's two sides with source attribution. */
    public static String describe(OrderingConflict conflict) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Conflicting order: %s ↔ %s — '%s → %s' in %d case(s), "
                        + "'%s → %s' in %d case(s)",
                conflict.from(), conflict.to(),
                conflict.from(), conflict.to(), conflict.ordered(),
                conflict.to(), conflict.from(), conflict.reversed()));
        List<String> parts = new ArrayList<>();
        for (SourceSplit split : conflict.bySource()) {
            Set<String> sides = new LinkedHashSet<>();
            if (split.ordered() > 0) {
                sides.add(String.format(Locale.ROOT, "%s first ×%d", conflict.from(), split.ordered()));
            }
            if (split.reversed() > 0) {
                sides.add(String.format(Locale.ROOT, "%s first ×%d", conflict.to(), split.reversed()));
            }
            parts.add(split.source() + ": " + String.join(", ", sides));
        }
        sb.append(" [").append(String.join("; ", parts)).append(']');
        return sb.toString();
    }
}
