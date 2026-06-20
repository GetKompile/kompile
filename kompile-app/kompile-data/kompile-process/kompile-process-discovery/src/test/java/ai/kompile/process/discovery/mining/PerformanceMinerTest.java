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

import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.perf.PerformanceAnalysis;
import ai.kompile.process.discovery.mining.perf.PerformanceMiner;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PerformanceMiner}: verifies that arc durations are computed correctly,
 * null timestamps are skipped, and arcs are sorted by median descending (bottlenecks first).
 */
class PerformanceMinerTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2024, 1, 1, 0, 0, 0);

    /**
     * Three cases, each with three activities a→b→c.
     * a→b duration: 10 min (600 s) in each case.
     * b→c duration: 60 min (3600 s) in each case.
     * Expected: (b,c) arc median > (a,b) arc median.
     */
    @Test
    void slowerArcRankedFirst() {
        EventLog log = new EventLog(List.of(
                trace("case1",
                        Event.of("case1", "a", BASE,                   null),
                        Event.of("case1", "b", BASE.plusMinutes(10),   null),
                        Event.of("case1", "c", BASE.plusMinutes(70),   null)),
                trace("case2",
                        Event.of("case2", "a", BASE,                   null),
                        Event.of("case2", "b", BASE.plusMinutes(10),   null),
                        Event.of("case2", "c", BASE.plusMinutes(70),   null)),
                trace("case3",
                        Event.of("case3", "a", BASE,                   null),
                        Event.of("case3", "b", BASE.plusMinutes(10),   null),
                        Event.of("case3", "c", BASE.plusMinutes(70),   null))
        ));

        PerformanceAnalysis result = PerformanceMiner.analyze(log);

        assertFalse(result.arcs().isEmpty(), "should have arcs");

        // First arc (sorted by median desc) should be b→c (3600 s)
        PerformanceAnalysis.ArcPerformance first = result.arcs().get(0);
        assertEquals("b", first.from(), "slowest arc should start at b");
        assertEquals("c", first.to(),   "slowest arc should end at c");
        assertEquals(3600.0, first.medianSeconds(), 0.001, "b→c median should be 3600 s");
        assertEquals(3600.0, first.meanSeconds(),   0.001, "b→c mean should be 3600 s");
        assertEquals(3L, first.count(), "b→c observed in 3 cases");

        // Second arc should be a→b (600 s)
        PerformanceAnalysis.ArcPerformance second = result.arcs().get(1);
        assertEquals("a", second.from(), "faster arc should start at a");
        assertEquals("b", second.to(),   "faster arc should end at b");
        assertEquals(600.0, second.medianSeconds(), 0.001, "a→b median should be 600 s");

        // Verify descending order
        assertTrue(first.medianSeconds() > second.medianSeconds(), "arcs must be sorted by median desc");
    }

    /**
     * Pairs where either timestamp is null must be excluded from duration calculations.
     * The arc count should still reflect observed transitions, but durations should be 0
     * when no timed observations exist.
     * <p>
     * Note: {@link ai.kompile.process.discovery.mining.log.Trace#ordered()} sorts null timestamps first,
     * so events without a timestamp are placed before events with one. We construct each case so that
     * activity order is preserved under that stable sort (both events null, or the event we want first
     * has a null or earlier timestamp).
     */
    @Test
    void nullTimestampsAreSkipped() {
        // case1: both null → stable insertion order preserved → x before y, giving x→y arc
        // case2: both null → same order → x→y arc
        // case3: both null → same order → x→y arc
        // All 3 produce x→y with no timed pair → durations empty → count=3, mean=median=0
        EventLog log = new EventLog(List.of(
                trace("case1",
                        Event.of("case1", "x", null, null),
                        Event.of("case1", "y", null, null)),
                trace("case2",
                        Event.of("case2", "x", null, null),
                        Event.of("case2", "y", null, null)),
                trace("case3",
                        Event.of("case3", "x", null, null),
                        Event.of("case3", "y", null, null))
        ));

        PerformanceAnalysis result = PerformanceMiner.analyze(log);

        // There should be exactly one arc x→y
        assertEquals(1, result.arcs().size(), "should have exactly one arc x→y");
        PerformanceAnalysis.ArcPerformance arc = result.arcs().get(0);
        assertEquals("x", arc.from());
        assertEquals("y", arc.to());
        assertEquals(3L, arc.count(), "x→y observed 3 times");
        // All timestamps null → no timed pairs → durations=0
        assertEquals(0.0, arc.medianSeconds(), 0.001, "median should be 0 when no timed pairs");
        assertEquals(0.0, arc.meanSeconds(),   0.001, "mean should be 0 when no timed pairs");
    }

    /**
     * Mixed scenario: some pairs timed, some not. Only timed pairs contribute to durations.
     */
    @Test
    void mixedTimestamps() {
        // case1: a→b timed (10 min = 600 s); case2: a→b un-timed (null start)
        EventLog log = new EventLog(List.of(
                trace("case1",
                        Event.of("case1", "a", BASE,                   null),
                        Event.of("case1", "b", BASE.plusMinutes(10),   null)),
                trace("case2",
                        Event.of("case2", "a", null,                   null),
                        Event.of("case2", "b", BASE.plusMinutes(5),    null))
        ));

        PerformanceAnalysis result = PerformanceMiner.analyze(log);
        assertEquals(1, result.arcs().size());
        PerformanceAnalysis.ArcPerformance arc = result.arcs().get(0);
        assertEquals(2L, arc.count(), "arc observed in both cases");
        // Only case1 is timed → median = mean = 600
        assertEquals(600.0, arc.medianSeconds(), 0.001);
        assertEquals(600.0, arc.meanSeconds(),   0.001);
    }

    /**
     * Empty log → empty arcs.
     */
    @Test
    void emptyLog() {
        PerformanceAnalysis result = PerformanceMiner.analyze(new EventLog(List.of()));
        assertTrue(result.arcs().isEmpty(), "empty log → no arcs");
    }

    /**
     * Single-event traces produce no arcs.
     */
    @Test
    void singleEventTracesProduceNoArcs() {
        EventLog log = new EventLog(List.of(
                trace("c1", Event.of("c1", "a", BASE, null))
        ));
        PerformanceAnalysis result = PerformanceMiner.analyze(log);
        assertTrue(result.arcs().isEmpty(), "single-event trace → no arcs");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Trace trace(String caseId, Event... events) {
        return new Trace(caseId, List.of(events));
    }
}
