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
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies within-log change-point detection: locating WHEN a process changed, reporting
 * activity- and flow-level differences, and the conservative no-claim cases (stable logs,
 * too little evidence, frequency wobbles).
 */
class ChangePointDetectorTest {

    private static final LocalDateTime T = LocalDateTime.of(2025, 1, 6, 9, 0);

    private static Trace trace(String caseId, LocalDateTime start, String... activities) {
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < activities.length; i++) {
            events.add(Event.of(caseId, activities[i], start.plusMinutes(10L * i), caseId + "-n" + i));
        }
        return new Trace(caseId, events);
    }

    @Test
    void locatesTheChange_andReportsActivityAndFlowDifferences() {
        // 4 weeks of Submit→Review→Close, then the review step is REPLACED by a scan and the
        // change date is week 5.
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            traces.add(trace("old-" + i, T.plusWeeks(i), "Submit", "Review", "Close"));
        }
        LocalDateTime changeAt = T.plusWeeks(5);
        for (int i = 0; i < 4; i++) {
            traces.add(trace("new-" + i, changeAt.plusWeeks(i), "Submit", "Scan", "Close"));
        }

        Optional<ChangePointDetector.ChangePoint> detected =
                ChangePointDetector.detect(new EventLog(traces), 3);

        assertTrue(detected.isPresent(), "an obvious mid-log change must be found");
        ChangePointDetector.ChangePoint cp = detected.get();
        assertEquals(changeAt, cp.splitAt(), "the split lands on the first changed case");
        assertEquals(4, cp.beforeCases());
        assertEquals(4, cp.afterCases());
        assertTrue(cp.changes().stream().anyMatch(c -> c.equals("activity 'Scan' appears (0 → 4 cases)")),
                String.valueOf(cp.changes()));
        assertTrue(cp.changes().stream().anyMatch(c -> c.equals("activity 'Review' disappears (4 → 0 cases)")),
                String.valueOf(cp.changes()));
    }

    @Test
    void flowRewire_withSameActivities_reportsArcChanges() {
        // Same activity vocabulary throughout; the ORDER of B and C flips at week 4.
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            traces.add(trace("old-" + i, T.plusWeeks(i), "A", "B", "C"));
        }
        for (int i = 0; i < 3; i++) {
            traces.add(trace("new-" + i, T.plusWeeks(4 + i), "A", "C", "B"));
        }

        ChangePointDetector.ChangePoint cp =
                ChangePointDetector.detect(new EventLog(traces), 3).orElseThrow();

        assertTrue(cp.changes().stream().anyMatch(c -> c.equals("flow 'C → B' appears (0 → 3)")),
                String.valueOf(cp.changes()));
        assertTrue(cp.changes().stream().anyMatch(c -> c.equals("flow 'B → C' disappears (3 → 0)")),
                String.valueOf(cp.changes()));
    }

    @Test
    void stableOrThinLogs_makeNoClaim() {
        List<Trace> stable = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            stable.add(trace("c-" + i, T.plusWeeks(i), "Submit", "Review", "Close"));
        }
        assertTrue(ChangePointDetector.detect(new EventLog(stable), 3).isEmpty(),
                "an unchanged process has no change point");

        List<Trace> thin = List.of(
                trace("a", T, "Submit", "Review"),
                trace("b", T.plusWeeks(1), "Submit", "Scan"),
                trace("c", T.plusWeeks(2), "Submit", "Scan"));
        assertTrue(ChangePointDetector.detect(new EventLog(thin), 3).isEmpty(),
                "fewer than minWindowCases per side is not enough evidence for a claim");
    }
}
