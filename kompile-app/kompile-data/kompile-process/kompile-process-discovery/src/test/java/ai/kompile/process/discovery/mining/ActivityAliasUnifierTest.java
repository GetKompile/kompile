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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies embedding-based activity alias unification: near-identical vectors merge onto the
 * more frequent label, sub-threshold pairs never merge, unembedded labels never merge, and
 * activity-keyed maps remap consistently.
 */
class ActivityAliasUnifierTest {

    private static final LocalDateTime T = LocalDateTime.of(2024, 7, 1, 9, 0);

    private static EventLog log(String... traceActivities) {
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < traceActivities.length; i++) {
            List<Event> events = new ArrayList<>();
            int j = 0;
            for (String activity : traceActivities[i].split(",")) {
                events.add(Event.of("c" + i, activity.trim(), T.plusMinutes(i * 60L + j++ * 5L), "n" + i + j));
            }
            traces.add(new Trace("c" + i, events));
        }
        return new EventLog(traces);
    }

    @Test
    void nearIdenticalVectors_unifyOntoTheMoreFrequentLabel() {
        // "Invoice" appears in 3 traces, "Bill" in 1 — Bill must rewrite to Invoice.
        EventLog log = log("Submit, Invoice", "Submit, Invoice", "Submit, Invoice", "Submit, Bill");
        Map<String, double[]> vectors = Map.of(
                "Submit", new double[]{1, 0, 0},
                "Invoice", new double[]{0, 1, 0.01},
                "Bill", new double[]{0, 1, 0.0});

        ActivityAliasUnifier.Result result = ActivityAliasUnifier.unify(log, vectors, 0.95);

        assertEquals(1, result.merges().size());
        assertEquals("Bill", result.merges().get(0).alias());
        assertEquals("Invoice", result.merges().get(0).canonical());
        assertTrue(result.merges().get(0).cosine() >= 0.95);
        Set<String> activities = new LinkedHashSet<>(result.log().activityNames());
        assertFalse(activities.contains("Bill"), "the alias must be rewritten everywhere");
        assertTrue(activities.contains("Invoice") && activities.contains("Submit"));
        // Event provenance (case ids, node ids, timestamps) rides along untouched.
        assertEquals(log.size(), result.log().size());
    }

    @Test
    void subThresholdAndUnembeddedLabels_neverMerge() {
        EventLog log = log("Approve, Reject", "Approve, Escalate");
        Map<String, double[]> vectors = Map.of(
                "Approve", new double[]{1, 0},
                "Reject", new double[]{0.5, 0.5});    // cos ≈ 0.71 < 0.95; "Escalate" unembedded

        ActivityAliasUnifier.Result result = ActivityAliasUnifier.unify(log, vectors, 0.95);

        assertTrue(result.merges().isEmpty());
        assertSame(log, result.log(), "no merges ⇒ the original log, untouched");
    }

    @Test
    void remapKeys_followsMerges_andResolvesCollisions() {
        List<ActivityAliasUnifier.Merge> merges =
                List.of(new ActivityAliasUnifier.Merge("Bill", "Invoice", 0.97));
        Map<String, Integer> counts = Map.of("Bill", 2, "Invoice", 5, "Submit", 1);

        Map<String, Integer> remapped = ActivityAliasUnifier.remapKeys(counts, merges, Integer::max);

        assertEquals(2, remapped.size());
        assertEquals(5, remapped.get("Invoice"), "collision resolved by the supplied operator");
        assertEquals(1, remapped.get("Submit"));
    }
}
