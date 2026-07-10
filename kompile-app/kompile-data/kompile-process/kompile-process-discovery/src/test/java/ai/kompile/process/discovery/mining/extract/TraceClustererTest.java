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

package ai.kompile.process.discovery.mining.extract;

import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trace clustering: unrelated workflows in one fact sheet must split into separate sub-logs
 * instead of being glued into one mega-process; same-process variants must stay together.
 */
class TraceClustererTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2024, 6, 1, 9, 0);

    private static Trace trace(String caseId, String... activities) {
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < activities.length; i++) {
            events.add(Event.of(caseId, activities[i], BASE.plusMinutes(i), caseId + "-n" + i));
        }
        return new Trace(caseId, events);
    }

    @Test
    void disjointWorkflows_splitIntoSeparateLogs_largestFirst() {
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            traces.add(trace("inv-" + i, "Invoice", "Approve", "Pay"));
        }
        for (int i = 0; i < 2; i++) {
            traces.add(trace("hire-" + i, "Interview", "Offer"));
        }
        List<EventLog> clusters = TraceClusterer.cluster(new EventLog(traces), 0.2, 6);

        assertEquals(2, clusters.size(), "two disjoint activity vocabularies = two processes");
        assertEquals(4, clusters.get(0).size(), "largest cluster first");
        assertEquals(2, clusters.get(1).size());
        assertTrue(clusters.get(0).activityNames().contains("Invoice"));
        assertTrue(clusters.get(1).activityNames().contains("Interview"));
    }

    @Test
    void overlappingVariants_ofOneProcess_stayTogether() {
        // Variants share most activities (Jaccard well above threshold) — one process.
        EventLog log = new EventLog(List.of(
                trace("c1", "Invoice", "Approve", "Pay"),
                trace("c2", "Invoice", "Approve", "Escalate", "Pay"),
                trace("c3", "Invoice", "Pay")));
        List<EventLog> clusters = TraceClusterer.cluster(log, 0.2, 6);
        assertEquals(1, clusters.size(), "overlapping variants must not split");
        assertSame(log, clusters.get(0), "single cluster returns the original log instance");
    }

    @Test
    void maxClusters_capsToLargest_neverSilently() {
        List<Trace> traces = new ArrayList<>();
        traces.add(trace("a1", "A1", "A2"));
        traces.add(trace("a2", "A1", "A2"));
        traces.add(trace("b1", "B1", "B2"));
        traces.add(trace("b2", "B1", "B2"));
        traces.add(trace("c1", "C1", "C2")); // smallest cluster — dropped at cap 2 (logged at WARN)
        List<EventLog> clusters = TraceClusterer.cluster(new EventLog(traces), 0.2, 2);
        assertEquals(2, clusters.size());
        assertEquals(2, clusters.get(0).size());
        assertEquals(2, clusters.get(1).size());
    }

    @Test
    void carrierOnlyVariants_neverBridgeClusters_andAreExcludedFromMining() {
        // The newsletter pathology: {Email Message} is a Jaccard subset of BOTH workflows and
        // would single-link chain them into one mega-cluster. Carrier activities are excluded
        // from signatures, and carriers-only traces from mining.
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            traces.add(trace("inv-" + i, "Email Message", "Invoice"));
        }
        for (int i = 0; i < 2; i++) {
            traces.add(trace("off-" + i, "Email Message", "Offer"));
        }
        traces.add(trace("newsletter", "Email Message"));

        List<EventLog> clusters = TraceClusterer.cluster(new EventLog(traces), 0.2, 6);

        assertEquals(2, clusters.size(), "the carriers-only trace must not bridge the workflows");
        assertEquals(3, clusters.get(0).size());
        assertEquals(2, clusters.get(1).size());
        assertTrue(clusters.stream().noneMatch(c -> c.traces().stream()
                        .anyMatch(t -> "newsletter".equals(t.caseId()))),
                "a trace with zero business activities has nothing to suggest");
    }

    @Test
    void scaffoldOnlyLog_fallsBackToRawClustering() {
        // An email-scaffold-only crawl (no extraction lane): everything is carriers. Mining must
        // degrade gracefully to the raw activity sets instead of yielding nothing.
        List<Trace> traces = new ArrayList<>(List.of(
                trace("m1", "Email Message"),
                trace("m2", "Email Message"),
                trace("m3", "Email Message", "Attachment")));
        List<EventLog> clusters = TraceClusterer.cluster(new EventLog(traces), 0.2, 6);
        assertEquals(1, clusters.size());
        assertEquals(3, clusters.get(0).size(), "scaffold-only logs keep all traces");
    }

    @Test
    void degenerateLogs_passThrough() {
        assertTrue(TraceClusterer.cluster(new EventLog(List.of()), 0.2, 6).isEmpty());
        EventLog single = new EventLog(List.of(trace("only", "A", "B")));
        assertEquals(List.of(single), TraceClusterer.cluster(single, 0.2, 6));
    }
}
