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

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies interval construction (multi-event extent, end-attribute lift, point degeneration) and
 * the folded Allen classification precedence reasoning consumes.
 */
class ActivityIntervalsTest {

    private static final LocalDateTime T = LocalDateTime.of(2024, 6, 1, 9, 0);

    @Test
    void multiEventActivities_getRealExtent_andEndAttributesLift() {
        Trace trace = new Trace("c1", List.of(
                Event.of("c1", "Review", T, "n1"),
                Event.of("c1", "Review", T.plusHours(3), "n2"),          // second review event extends
                new Event("c1", "Deploy", T.plusHours(1), "n3",
                        Map.of("completedAt", T.plusHours(6).toString())), // end lifted from metadata
                Event.of("c1", "Sign Off", T.plusHours(8), "n4")));

        Map<String, ActivityIntervals.Interval> intervals = ActivityIntervals.of(trace);

        assertEquals(T, intervals.get("Review").start());
        assertEquals(T.plusHours(3), intervals.get("Review").end(), "latest event extends the interval");
        assertEquals(T.plusHours(6), intervals.get("Deploy").end(), "crawl-lifted end time extends further");
        assertEquals(intervals.get("Sign Off").start(), intervals.get("Sign Off").end(),
                "single events degenerate to points");
    }

    @Test
    void allenClassification_foldsToOrderedReversedOverlap() {
        ActivityIntervals.Interval a = new ActivityIntervals.Interval(T, T.plusHours(2));
        ActivityIntervals.Interval before = new ActivityIntervals.Interval(T.plusHours(3), T.plusHours(4));
        ActivityIntervals.Interval meets = new ActivityIntervals.Interval(T.plusHours(2), T.plusHours(5));
        ActivityIntervals.Interval during = new ActivityIntervals.Interval(T.plusMinutes(30), T.plusHours(1));
        ActivityIntervals.Interval overlapping = new ActivityIntervals.Interval(T.plusHours(1), T.plusHours(6));

        assertEquals(ActivityIntervals.Interval.Order.ORDERED, a.orderVs(before), "BEFORE → ordered");
        assertEquals(ActivityIntervals.Interval.Order.ORDERED, a.orderVs(meets), "MEETS → ordered");
        assertEquals(ActivityIntervals.Interval.Order.REVERSED, before.orderVs(a));
        assertEquals(ActivityIntervals.Interval.Order.OVERLAP, a.orderVs(during), "DURING → overlap");
        assertEquals(ActivityIntervals.Interval.Order.OVERLAP, a.orderVs(overlapping), "OVERLAPS → overlap");

        ActivityIntervals.Interval point = new ActivityIntervals.Interval(T, T);
        assertEquals(ActivityIntervals.Interval.Order.OVERLAP, point.orderVs(new ActivityIntervals.Interval(T, T)),
                "equal points are Allen EQUAL — simultaneous, never both-ordered");
        assertEquals(ActivityIntervals.Interval.Order.ORDERED,
                point.orderVs(new ActivityIntervals.Interval(T.plusMinutes(5), T.plusMinutes(5))),
                "distinct points reduce to the prior first-occurrence semantics");
    }
}
