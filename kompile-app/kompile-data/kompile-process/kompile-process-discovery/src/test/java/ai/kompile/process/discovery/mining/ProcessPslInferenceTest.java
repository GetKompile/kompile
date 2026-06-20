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

import ai.kompile.process.discovery.mining.causal.ProcessPslInference;
import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the live PSL coupling actually runs on the project's HL-MRF engine and behaves sensibly:
 * clamping the first step as evidence lifts the downstream steps along strong directly-follows edges.
 */
class ProcessPslInferenceTest {

    private static DirectlyFollowsGraph dfg(String... traces) {
        List<Trace> ts = new ArrayList<>();
        int caseId = 0;
        for (String tr : traces) {
            String cid = "c" + (caseId++);
            List<Event> events = new ArrayList<>();
            for (String a : tr.trim().split("\\s+")) {
                events.add(Event.of(cid, a, null, null));
            }
            ts.add(new Trace(cid, events));
        }
        return DfgBuilder.build(new EventLog(ts));
    }

    @Test
    void evidencePropagatesAlongDirectlyFollows() {
        DirectlyFollowsGraph dfg = dfg("a b c", "a b c", "a b c", "a b c", "a b c");

        ProcessPslInference.Result r = ProcessPslInference.infer(dfg, List.of("a"));

        for (double v : r.activation().values()) {
            assertTrue(v >= 0.0 && v <= 1.0, () -> "soft-truth out of range: " + r.activation());
        }
        assertEquals(1.0, r.activation().get("a"), 1e-9);          // evidence is clamped active
        assertTrue(r.activation().get("b") > r.priors().get("b"),  // propagation lifts downstream
                () -> "b should be lifted above its prior: " + r.activation());
        assertTrue(r.activation().get("c") > r.priors().get("c"),
                () -> "c should be lifted above its prior: " + r.activation());
        assertTrue(r.groundRules() > 0, "the program must produce ground rules");
    }

    @Test
    void withoutEvidenceTheStartActivityStaysMostActive() {
        DirectlyFollowsGraph dfg = dfg("a b c", "a b c", "a b c");

        ProcessPslInference.Result r = ProcessPslInference.infer(dfg, null);

        for (double v : r.activation().values()) {
            assertTrue(v >= 0.0 && v <= 1.0);
        }
        // 'a' is the start activity (high prior); 'c' is terminal (low prior).
        assertTrue(r.activation().get("a") >= r.activation().get("c") - 1e-6, r.activation()::toString);
    }
}
