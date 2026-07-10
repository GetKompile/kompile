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

package ai.kompile.process.discovery.mining.log;

import ai.kompile.knowledgegraph.domain.OccurredAtParser;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-trace activity INTERVALS — the data Allen-relation reasoning needs where single timestamps
 * can't go. An activity's interval within a case is:
 *
 * <ul>
 *   <li>{@code start} = the earliest dated event of that activity in the trace;</li>
 *   <li>{@code end} = the latest dated event of that activity — activities with several events
 *       per case (email threads: many messages of one type; loops) get REAL extent — further
 *       extended by any parseable end-time attribute the crawl lifted onto an event
 *       ({@code endAt}/{@code completedAt}/{@code closedAt}/…, parsed by the same
 *       {@link OccurredAtParser} the ingest lanes use).</li>
 * </ul>
 *
 * Single-event activities without end attributes degenerate to points, where the Allen algebra
 * collapses to before/equal/after — exactly the prior first-occurrence semantics, so richer data
 * upgrades the reasoning without changing the degenerate case.
 */
public final class ActivityIntervals {

    /** Event-attribute keys that carry an activity's end time (crawl-lifted metadata). */
    private static final Set<String> END_TIME_KEYS = Set.of(
            "endAt", "endedAt", "endDate", "endTime", "end_time",
            "completedAt", "closedAt", "finishedAt", "resolvedAt");

    private ActivityIntervals() {
    }

    /** A closed interval; {@code start ≤ end} by construction. Points have {@code start == end}. */
    public record Interval(LocalDateTime start, LocalDateTime end) {

        /** The four-way Allen classification precedence reasoning needs. */
        public enum Order { ORDERED, REVERSED, OVERLAP, UNKNOWN }

        /**
         * Allen relation of {@code this} vs {@code other}, folded to what precedence needs:
         * BEFORE/MEETS → ORDERED, their inverses → REVERSED, everything intersecting
         * (OVERLAPS/STARTS/DURING/FINISHES/EQUAL + inverses) → OVERLAP. Equal points are EQUAL,
         * not both-ordered.
         */
        public Order orderVs(Interval other) {
            if (other == null) {
                return Order.UNKNOWN;
            }
            boolean thisFirst = !end.isAfter(other.start());
            boolean otherFirst = !other.end().isAfter(start);
            if (thisFirst && otherFirst) {
                return Order.OVERLAP; // degenerate: equal points (Allen EQUAL)
            }
            if (thisFirst) {
                return Order.ORDERED;
            }
            if (otherFirst) {
                return Order.REVERSED;
            }
            return Order.OVERLAP;
        }
    }

    /**
     * The trace's activity intervals; activities with no dated events are absent.
     */
    public static Map<String, Interval> of(Trace trace) {
        Map<String, LocalDateTime> starts = new LinkedHashMap<>();
        Map<String, LocalDateTime> ends = new LinkedHashMap<>();
        for (Event event : trace.ordered()) {
            LocalDateTime at = event.timestamp();
            if (at == null) {
                continue;
            }
            starts.merge(event.activity(), at, (a, b) -> a.isBefore(b) ? a : b);
            ends.merge(event.activity(), at, (a, b) -> a.isAfter(b) ? a : b);
            LocalDateTime attributeEnd = endFromAttributes(event.attributes());
            if (attributeEnd != null) {
                ends.merge(event.activity(), attributeEnd, (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        Map<String, Interval> intervals = new LinkedHashMap<>();
        for (Map.Entry<String, LocalDateTime> entry : starts.entrySet()) {
            LocalDateTime end = ends.get(entry.getKey());
            intervals.put(entry.getKey(), new Interval(entry.getValue(),
                    end.isBefore(entry.getValue()) ? entry.getValue() : end));
        }
        return intervals;
    }

    private static LocalDateTime endFromAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        LocalDateTime latest = null;
        for (String key : END_TIME_KEYS) {
            Object value = attributes.get(key);
            if (value instanceof String s && !s.isBlank()) {
                LocalDateTime parsed = OccurredAtParser.parse(s);
                if (parsed != null && (latest == null || parsed.isAfter(latest))) {
                    latest = parsed;
                }
            }
        }
        return latest;
    }

    /** Convenience: intervals for every trace, keyed by case id. */
    public static Map<String, Map<String, Interval>> ofLog(List<Trace> traces) {
        Map<String, Map<String, Interval>> byCase = new LinkedHashMap<>();
        for (Trace trace : traces) {
            byCase.put(trace.caseId(), of(trace));
        }
        return byCase;
    }
}
